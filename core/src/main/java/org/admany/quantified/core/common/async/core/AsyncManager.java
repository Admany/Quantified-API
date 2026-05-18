package org.admany.quantified.core.common.async.core;

import org.admany.quantified.core.common.async.control.AdaptiveLoadController;
import org.admany.quantified.core.common.async.cpu.CpuTaskDispatcher;
import org.admany.quantified.core.common.async.gpu.GpuBatchTelemetry;
import org.admany.quantified.core.common.async.gpu.GpuTaskDispatcher;
import org.admany.quantified.core.common.async.gpu.GpuWorkloadRegistry;
import org.admany.quantified.core.common.async.metrics.AsyncMetrics;
import org.admany.quantified.core.common.async.task.ModPriorityManager;
import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskComputation;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.async.validation.TaskSubmissionValidator;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.threading.core.MainThreadExecutor;
import org.admany.quantified.core.common.threading.core.ThreadRole;
import org.admany.quantified.core.common.threading.health.ThreadHealthMonitor;
import org.admany.quantified.core.common.threading.health.ThreadHealthSnapshot;
import org.admany.quantified.core.common.threading.pool.ThreadPoolStats;
import org.admany.quantified.core.common.threading.scaling.SystemLoadMonitor;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AsyncManager {

    private static final Logger LOGGER = Logger.getLogger(AsyncManager.class.getName());

    private static final ConcurrentHashMap<Long, TaskEntry<?>> TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> FINALIZERS = new ConcurrentLinkedQueue<>();
    private static final DelayQueue<TimeoutRegistration> TIMEOUTS = new DelayQueue<>();

    private static final long DEBOUNCE_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long TIMEOUT_SWEEP_INTERVAL_MILLIS = Math.max(5L,
        Long.getLong("quantified.timeoutSweepMs", 10L));
    private static final int MAX_IN_FLIGHT = 4096;
    private static final int PRUNE_CHUNK = 128;

    private static final AsyncMetrics METRICS = new AsyncMetrics();
    private static final AdaptiveLoadController CONTROLLER = new AdaptiveLoadController();
    private static final ModPriorityManager MOD_MANAGER = new ModPriorityManager();

    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);
    private static final boolean FORCE_RENDER_REROUTE = Boolean.parseBoolean(System.getProperty("quantified.forceMainThreadForRender", "true"));
    private static final AtomicBoolean RENDER_REROUTE_LOGGED = new AtomicBoolean(false);

    private static PriorityScheduler scheduler;
    private static ScheduledExecutorService coalescer;
    private static GpuTaskDispatcher gpuDispatcher;
    private static CpuTaskDispatcher cpuDispatcher;
    private static ScheduledFuture<?> timeoutSweeper;

    private AsyncManager() {}

    public static void initialise(AsyncManagerBootstrap bootstrap, ScheduledExecutorService coalesceExecutor) {
        Objects.requireNonNull(bootstrap);
        Objects.requireNonNull(coalesceExecutor);
        if (!INITIALISED.compareAndSet(false, true)) return;

        scheduler = new PriorityScheduler(
            bootstrap.foregroundThreads(),
            bootstrap.backgroundThreads(),
            bootstrap.maxForegroundThreads(),
            bootstrap.maxBackgroundThreads(),
            bootstrap.promotionDelay(),
            bootstrap.queueBound(),
            bootstrap.errorHandler()
        );
        scheduler.start();

        coalescer = coalesceExecutor;
        timeoutSweeper = coalescer.scheduleAtFixedRate(
            AsyncManager::drainExpiredTimeouts,
            TIMEOUT_SWEEP_INTERVAL_MILLIS,
            TIMEOUT_SWEEP_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS
        );
        gpuDispatcher = new GpuTaskDispatcher(scheduler, coalesceExecutor);
        cpuDispatcher = new CpuTaskDispatcher(scheduler, coalesceExecutor);
    }

    public static void shutdown() {
        if (!INITIALISED.compareAndSet(true, false)) return;

        if (scheduler != null) scheduler.stop();
        if (timeoutSweeper != null) timeoutSweeper.cancel(false);
        if (coalescer != null) coalescer.shutdownNow();
        if (gpuDispatcher != null) gpuDispatcher.shutdown();
        if (cpuDispatcher != null) cpuDispatcher.shutdown();

        scheduler = null;
        coalescer = null;
        gpuDispatcher = null;
        cpuDispatcher = null;
        timeoutSweeper = null;

        TASKS.clear();
        FINALIZERS.clear();
        TIMEOUTS.clear();

        GpuWorkloadRegistry.clear();
        GpuBatchTelemetry.reset();
    }

    public static boolean isInitialised() {
        return INITIALISED.get();
    }

    public static AsyncMetrics.AsyncMetricsSnapshot metricsSnapshot() {
        return METRICS.snapshot(TASKS.size());
    }

    public static PriorityScheduler.SchedulerSnapshot schedulerSnapshot() {
        ensureInitialised();
        return scheduler.snapshot();
    }

    public static ThreadPoolStats threadPoolStats() {
        ensureInitialised();
        return scheduler.stats();
    }

    public static GpuBatchTelemetry.Snapshot gpuBatchSnapshot() {
        return GpuBatchTelemetry.snapshot();
    }

    public static ThreadHealthSnapshot threadHealthSnapshot() {
        return ThreadHealthMonitor.snapshot();
    }

    public static void escalateModPriority(String modId, String reason) {
        MOD_MANAGER.escalatePriority(modId, reason);
    }

    public static void enqueueFinalizer(Runnable runnable) {
        FINALIZERS.offer(Objects.requireNonNull(runnable));
    }

    public static void drainFinalizers() {
        ThreadHealthMonitor.heartbeat(ThreadRole.FINALIZER);
        Runnable r;
        while ((r = FINALIZERS.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Finalizer failed", t);
            }
        }
    }

    public static <T> CompletableFuture<T> submitSync(long key, PriorityTaskType type, double score, Supplier<T> supplier, String modId) {
        return submit(key, type, score, TaskComputation.sync(supplier), null, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitAsync(long key, PriorityTaskType type, double score, Supplier<CompletableFuture<T>> supplier, String modId) {
        return submit(key, type, score, TaskComputation.async(supplier), null, modId, TaskMetadata.DEFAULT);
    }

    // Convenience overload: include a timeout
    public static <T> CompletableFuture<T> submitSync(long key, PriorityTaskType type, double score, Supplier<T> supplier, Duration timeout, String modId) {
        return submit(key, type, score, TaskComputation.sync(supplier), timeout, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitAsync(long key, PriorityTaskType type, double score, Supplier<CompletableFuture<T>> supplier, Duration timeout, String modId) {
        return submit(key, type, score, TaskComputation.async(supplier), timeout, modId, TaskMetadata.DEFAULT);
    }

    // Overloads allowing explicit thread-safety override (with and without explicit TaskMetadata)
    public static <T> CompletableFuture<T> submitSync(long key, PriorityTaskType type, double score, Supplier<T> supplier, Duration timeout, boolean threadSafe, String modId) {
        return submit(key, type, score, TaskComputation.sync(supplier), timeout, threadSafe, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitAsync(long key, PriorityTaskType type, double score, Supplier<CompletableFuture<T>> supplier, Duration timeout, boolean threadSafe, String modId) {
        return submit(key, type, score, TaskComputation.async(supplier), timeout, threadSafe, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitSync(long key, PriorityTaskType type, double score, Supplier<T> supplier, Duration timeout, boolean threadSafe, String modId, TaskMetadata metadata) {
        return submit(key, type, score, TaskComputation.sync(supplier), timeout, threadSafe, modId, metadata);
    }

    public static <T> CompletableFuture<T> submitUniqueSync(long key,
                                                            PriorityTaskType type,
                                                            double score,
                                                            Supplier<T> supplier,
                                                            boolean threadSafe,
                                                            String modId,
                                                            TaskMetadata metadata) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(supplier, "supplier");
        ensureInitialised();
        METRICS.incrementRequests();
        boolean effectiveThreadSafe = threadSafe;
        if (shouldForceMainThread(effectiveThreadSafe)) {
            effectiveThreadSafe = false;
        }
        return submitDirect(key, type, score, supplier, effectiveThreadSafe, modId, metadata);
    }

    public static <T> CompletableFuture<T> submitAsync(long key, PriorityTaskType type, double score, Supplier<CompletableFuture<T>> supplier, Duration timeout, boolean threadSafe, String modId, TaskMetadata metadata) {
        return submit(key, type, score, TaskComputation.async(supplier), timeout, threadSafe, modId, metadata);
    }

    // New overload: allow explicit thread-safety override and metadata
    public static <T> CompletableFuture<T> submit(
        long key,
        PriorityTaskType type,
        double score,
        TaskComputation<T> computation,
        Duration timeout,
        boolean threadSafeOverride,
        String modId,
        TaskMetadata metadata
    ) {

        ensureInitialised();
        METRICS.incrementRequests();

        TaskEntry<T> active = existingEntry(key);
        if (active != null) {
            METRICS.incrementCoalesced();
            METRICS.incrementCacheHits();
            return active.future;
        }

        TaskSubmissionValidator.ValidatedSubmission<T> v =
            TaskSubmissionValidator.validate(key, type, score, computation, timeout, threadSafeOverride, METRICS, LOGGER);
        boolean threadSafe = v.threadSafe();
        if (shouldForceMainThread(threadSafe)) {
            threadSafe = false;
        }
        final boolean effectiveThreadSafe = threadSafe;
        TaskEntry<T> entry = new TaskEntry<>(
            v.computation(),
            effectiveThreadSafe,
            v.timeout(),
            modId,
            metadata
        );
        TaskEntry<?> existing = TASKS.putIfAbsent(key, entry);
        if (existing != null) {
            METRICS.incrementCoalesced();
            METRICS.incrementCacheHits();
            TaskEntry<T> existingEntry = cast(existing);
            return existingEntry.future;
        }
        if (!schedule(key, entry, v.type(), v.score())) {
            TASKS.remove(key, entry);
        }

        pruneIfNecessary();
        return entry.future;
    }

    // Existing submit remains for callers that rely on TaskMetadata to set safety via TaskSafetyRegistry
    public static <T> CompletableFuture<T> submit(
        long key,
        PriorityTaskType type,
        double score,
        TaskComputation<T> computation,
        Duration timeout,
        String modId,
        TaskMetadata metadata
    ) {

        ensureInitialised();
        METRICS.incrementRequests();

        TaskEntry<T> active = existingEntry(key);
        if (active != null) {
            METRICS.incrementCoalesced();
            METRICS.incrementCacheHits();
            return active.future;
        }

        TaskSubmissionValidator.ValidatedSubmission<T> v =
            TaskSubmissionValidator.validate(key, type, score, computation, timeout, METRICS, LOGGER);
        boolean threadSafe = v.threadSafe();
        if (shouldForceMainThread(threadSafe)) {
            threadSafe = false;
        }
        final boolean effectiveThreadSafe = threadSafe;
        TaskEntry<T> entry = new TaskEntry<>(
            v.computation(),
            effectiveThreadSafe,
            v.timeout(),
            modId,
            metadata
        );
        TaskEntry<?> existing = TASKS.putIfAbsent(key, entry);
        if (existing != null) {
            METRICS.incrementCoalesced();
            METRICS.incrementCacheHits();
            TaskEntry<T> existingEntry = cast(existing);
            return existingEntry.future;
        }
        if (!schedule(key, entry, v.type(), v.score())) {
            TASKS.remove(key, entry);
        }

        pruneIfNecessary();
        return entry.future;
    }

    public static <T> CompletableFuture<T> submit(long key, PriorityTaskType type, double score, TaskComputation<T> computation, String modId) {
        return submit(key, type, score, computation, null, modId, TaskMetadata.DEFAULT);
    }

    private static <T> boolean schedule(long key, TaskEntry<T> entry, PriorityTaskType type, double score) {

        if (!MOD_MANAGER.tryReserveTask(entry.modId)) {
            entry.future.completeExceptionally(new RuntimeException("Mod limit exceeded: " + entry.modId));
            return false;
        }

        long age = System.nanoTime() - entry.enqueueNanos;
        double modScore = MOD_MANAGER.computeModPriority(entry.modId, type, age);

        Runnable payload = () -> {
            if (entry.future.isDone()) {
                releaseReservation(entry.modId, entry.started.get(), entry.reservationReleased);
                return;
            }
            entry.started.set(true);
            MOD_MANAGER.recordTaskStart(entry.modId);
            long start = System.nanoTime();

            CompletableFuture<T> upstream;
            try {
                upstream = entry.threadSafe
                    ? entry.computation.execute()
                    : MainThreadExecutor.reroute(entry.computation);
            } catch (Throwable t) {
                entry.future.completeExceptionally(t);
                TASKS.remove(key);
                MOD_MANAGER.recordTaskComplete(entry.modId);
                return;
            }

            scheduleTimeout(key, entry, upstream);

            upstream.whenComplete((res, err) -> {
                cancelTimeout(entry.timeoutRegistration);
                releaseReservation(entry.modId, true, entry.reservationReleased);

                if (!entry.timedOut) {
                    if (err != null) {
                        entry.future.completeExceptionally(err);
                    } else {
                        METRICS.recordSuccess(System.nanoTime() - start);
                        entry.future.complete(res);
                    }
                }
                onEntryCompleted(key, entry);
            });
        };

        PriorityTask task = new PriorityTask(key, type, modScore, payload, entry.metadata, entry.modId,
            // Drop callback: ensure the future is always resolved so callers never hang.
            () -> {
                if (entry.future.completeExceptionally(
                        new java.util.concurrent.CancellationException("Task dropped by scheduler (queue overload or stale)"))) {
                    releaseReservation(entry.modId, entry.started.get(), entry.reservationReleased);
                    TASKS.remove(key);
                }
            });

        if (gpuDispatcher != null && gpuDispatcher.trySchedule(task)) return true;

        enqueue(task);
        return true;
    }

    private static void enqueue(PriorityTask task) {
        scheduler.submit(task);
    }

    public static <T> CompletableFuture<T> submitDirect(
        long key,
        PriorityTaskType type,
        double score,
        Supplier<T> supplier,
        boolean threadSafe,
        String modId,
        TaskMetadata metadata
    ) {
        return submitDirect(key, type, score, supplier, threadSafe, modId, metadata, null);
    }

    public static <T> CompletableFuture<T> submitDirect(
        long key,
        PriorityTaskType type,
        double score,
        Supplier<T> supplier,
        boolean threadSafe,
        String modId,
        TaskMetadata metadata,
        Function<Throwable, ? extends Throwable> errorMapper
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(supplier, "supplier");
        ensureInitialised();

        CompletableFuture<T> future = new CompletableFuture<>();
        String safeModId = Objects.requireNonNullElse(modId, "");
        TaskMetadata safeMetadata = metadata != null ? metadata : TaskMetadata.DEFAULT;
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean reservationReleased = new AtomicBoolean(false);

        if (!MOD_MANAGER.tryReserveTask(safeModId)) {
            future.completeExceptionally(new RuntimeException("Mod limit exceeded: " + safeModId));
            return future;
        }

        double modScore = MOD_MANAGER.computeModPriority(safeModId, type, 0L);
        Runnable payload = () -> {
            if (future.isDone()) {
                releaseReservation(safeModId, false, reservationReleased);
                return;
            }
            started.set(true);
            MOD_MANAGER.recordTaskStart(safeModId);
            long start = System.nanoTime();
            try {
                T value = threadSafe
                    ? supplier.get()
                    : MainThreadExecutor.reroute(() -> CompletableFuture.completedFuture(supplier.get())).join();
                METRICS.recordSuccess(System.nanoTime() - start);
                future.complete(value);
            } catch (Throwable throwable) {
                Throwable failure = mapFailure(errorMapper, throwable);
                future.completeExceptionally(failure);
            } finally {
                releaseReservation(safeModId, true, reservationReleased);
            }
        };

        PriorityTask task = new PriorityTask(
            key,
            type,
            Math.max(score, modScore),
            payload,
            safeMetadata,
            safeModId,
            () -> {
                if (!future.isDone()) {
                    future.completeExceptionally(new CancellationException("Internal runtime task dropped"));
                }
                releaseReservation(safeModId, started.get(), reservationReleased);
            }
        );
        scheduler.submit(task);
        return future;
    }

    private static <T> void scheduleTimeout(long key, TaskEntry<T> entry, CompletableFuture<T> upstream) {
        if (entry.timeout == null || coalescer == null) return;
        TimeoutRegistration registration = new TimeoutRegistration(
            key,
            entry,
            upstream,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, entry.timeout.toMillis()))
        );
        entry.timeoutRegistration = registration;
        TIMEOUTS.offer(registration);
    }

    private static void cancelTimeout(TimeoutRegistration registration) {
        if (registration != null) {
            registration.cancelled.set(true);
        }
    }

    private static Throwable mapFailure(Function<Throwable, ? extends Throwable> mapper, Throwable throwable) {
        if (mapper == null) {
            return throwable;
        }
        try {
            Throwable mapped = mapper.apply(throwable);
            return mapped != null ? mapped : throwable;
        } catch (Throwable ignored) {
            return throwable;
        }
    }

    private static void onEntryCompleted(long key, TaskEntry<?> entry) {
        if (!entry.coalescable || coalescer == null || entry.timedOut) {
            TASKS.remove(key, entry);
            return;
        }
        entry.completedAtNanos = System.nanoTime();
        coalescer.schedule(() -> TASKS.remove(key, entry),
            Math.max(1L, TimeUnit.NANOSECONDS.toMillis(DEBOUNCE_WINDOW_NANOS)),
            TimeUnit.MILLISECONDS);
    }

    private static void releaseReservation(String modId, boolean started) {
        if (started) {
            MOD_MANAGER.recordTaskComplete(modId);
        } else {
            MOD_MANAGER.releaseReservation(modId);
        }
    }

    private static void releaseReservation(String modId, boolean started, AtomicBoolean released) {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        releaseReservation(modId, started);
    }

    private static void drainExpiredTimeouts() {
        TimeoutRegistration registration;
        while ((registration = TIMEOUTS.poll()) != null) {
            if (registration.cancelled.get()) {
                continue;
            }
            registration.cancelled.set(true);
            if (!registration.entry.timedOut && TASKS.remove(registration.taskKey, registration.entry)) {
                registration.entry.timedOut = true;
                registration.upstream.cancel(true);
                registration.entry.future.completeExceptionally(
                    new TimeoutException("Task " + registration.taskKey + " timed out"));
                METRICS.recordTimeout();
                releaseReservation(registration.entry.modId,
                    registration.entry.started.get(),
                    registration.entry.reservationReleased);
            }
        }
    }

    private static boolean shouldForceMainThread(boolean threadSafe) {
        if (!threadSafe || !FORCE_RENDER_REROUTE) {
            return false;
        }
        if (MainThreadExecutor.executor().isEmpty()) {
            return false;
        }
        String name = Thread.currentThread().getName();
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("render thread") || lower.contains("client thread") || lower.contains("minecraft client")) {
            if (RENDER_REROUTE_LOGGED.compareAndSet(false, true)) {
                LOGGER.log(Level.INFO, "Forcing main-thread reroute for task submissions from {0}. Set quantified.forceMainThreadForRender=false to disable.", name);
            }
            return true;
        }
        return false;
    }

    private static void pruneIfNecessary() {
        if (TASKS.size() <= MAX_IN_FLIGHT) return;

        TASKS.entrySet().removeIf(e -> e.getValue().future.isDone());
        if (TASKS.size() <= MAX_IN_FLIGHT) return;

        int removed = 0;
        for (Long k : TASKS.keySet()) {
            TaskEntry<?> e = TASKS.get(k);
            if (e != null && e.future.isDone() && TASKS.remove(k, e)) {
                if (++removed >= PRUNE_CHUNK) break;
            }
        }
    }

    private static void ensureInitialised() {
        if (!INITIALISED.get()) throw new IllegalStateException("AsyncManager not initialised");
    }

    @SuppressWarnings("unchecked")
    private static <T> TaskEntry<T> existingEntry(long key) {
        TaskEntry<?> entry = TASKS.get(key);
        if (entry == null) {
            return null;
        }
        if (!entry.future.isDone()) {
            return (TaskEntry<T>) entry;
        }
        if (!entry.coalescable) {
            TASKS.remove(key, entry);
            return null;
        }
        long completedAt = entry.completedAtNanos;
        if (completedAt != 0L && (System.nanoTime() - completedAt) <= DEBOUNCE_WINDOW_NANOS) {
            return (TaskEntry<T>) entry;
        }
        TASKS.remove(key, entry);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> TaskEntry<T> cast(TaskEntry<?> e) {
        return (TaskEntry<T>) e;
    }

    private static final class TaskEntry<T> {
        final CompletableFuture<T> future = new CompletableFuture<>();
        final TaskComputation<T> computation;
        final boolean threadSafe;
        final Duration timeout;
        final String modId;
        final long enqueueNanos = System.nanoTime();
        final AtomicBoolean started = new AtomicBoolean(false);
        final AtomicBoolean reservationReleased = new AtomicBoolean(false);
        final boolean coalescable;
        volatile TaskMetadata metadata;
        volatile boolean timedOut;
        volatile long completedAtNanos;
        volatile TimeoutRegistration timeoutRegistration;

        TaskEntry(TaskComputation<T> c, boolean ts, Duration to, String m, TaskMetadata md) {
            computation = c;
            threadSafe = ts;
            timeout = to;
            modId = m;
            metadata = md;
            coalescable = md != null && !md.affinityKey().isBlank();
        }
    }
    private static final class TimeoutRegistration implements Delayed {
        final long taskKey;
        final TaskEntry<?> entry;
        final CompletableFuture<?> upstream;
        final long deadlineNanos;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        TimeoutRegistration(long taskKey, TaskEntry<?> entry, CompletableFuture<?> upstream, long deadlineNanos) {
            this.taskKey = taskKey;
            this.entry = entry;
            this.upstream = upstream;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return Long.compare(diff, 0L);
        }
    }
}
