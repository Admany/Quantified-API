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
import org.admany.quantified.core.common.threading.core.MainThreadExecutor;
import org.admany.quantified.core.common.threading.core.ThreadRole;
import org.admany.quantified.core.common.threading.health.ThreadHealthMonitor;
import org.admany.quantified.core.common.threading.health.ThreadHealthSnapshot;
import org.admany.quantified.core.common.threading.pool.ThreadPoolStats;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AsyncManager {

    private static final Logger LOGGER = Logger.getLogger(AsyncManager.class.getName());

    private static final ConcurrentHashMap<Long, TaskEntry<?>> TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> LAST_REQUEST_NANOS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> FINALIZERS = new ConcurrentLinkedQueue<>();

    private static final long DEBOUNCE_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final int MAX_IN_FLIGHT = 4096;
    private static final int PRUNE_CHUNK = 128;

    private static final AsyncMetrics METRICS = new AsyncMetrics();
    private static final AdaptiveLoadController CONTROLLER = new AdaptiveLoadController();
    private static final ModPriorityManager MOD_MANAGER = new ModPriorityManager();

    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);

    private static PriorityScheduler scheduler;
    private static ScheduledExecutorService coalescer;
    private static GpuTaskDispatcher gpuDispatcher;
    private static CpuTaskDispatcher cpuDispatcher;

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
        gpuDispatcher = new GpuTaskDispatcher(scheduler, coalesceExecutor);
        cpuDispatcher = new CpuTaskDispatcher(scheduler, coalesceExecutor);
    }

    public static void shutdown() {
        if (!INITIALISED.compareAndSet(true, false)) return;

        if (scheduler != null) scheduler.stop();
        if (coalescer != null) coalescer.shutdownNow();
        if (gpuDispatcher != null) gpuDispatcher.shutdown();
        if (cpuDispatcher != null) cpuDispatcher.shutdown();

        scheduler = null;
        coalescer = null;
        gpuDispatcher = null;
        cpuDispatcher = null;

        TASKS.clear();
        LAST_REQUEST_NANOS.clear();
        FINALIZERS.clear();

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

        TaskSubmissionValidator.ValidatedSubmission<T> v =
            TaskSubmissionValidator.validate(key, type, score, computation, timeout, threadSafeOverride, METRICS, LOGGER);

        long now = System.nanoTime();
        Long prev = LAST_REQUEST_NANOS.put(key, now);

        if (prev != null && now - prev < DEBOUNCE_WINDOW_NANOS) {
            TaskEntry<T> existing = existingEntry(key);
            if (existing != null) {
                METRICS.incrementCoalesced();
                METRICS.incrementCacheHits();
                return existing.future;
            }
        }

        Holder<TaskEntry<T>> holder = new Holder<>();

        TASKS.compute(key, (k, existing) -> {
            if (existing != null) {
                holder.value = cast(existing);
                METRICS.incrementCoalesced();
                METRICS.incrementCacheHits();
                return existing;
            }

            TaskEntry<T> entry = new TaskEntry<>(
                v.computation(),
                v.threadSafe(),
                v.timeout(),
                modId,
                metadata
            );

            holder.value = entry;
            schedule(key, entry, v.type(), v.score());
            return entry;
        });

        pruneIfNecessary();
        return holder.value.future;
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

        TaskSubmissionValidator.ValidatedSubmission<T> v =
            TaskSubmissionValidator.validate(key, type, score, computation, timeout, METRICS, LOGGER);

        long now = System.nanoTime();
        Long prev = LAST_REQUEST_NANOS.put(key, now);

        if (prev != null && now - prev < DEBOUNCE_WINDOW_NANOS) {
            TaskEntry<T> existing = existingEntry(key);
            if (existing != null) {
                METRICS.incrementCoalesced();
                METRICS.incrementCacheHits();
                return existing.future;
            }
        }

        Holder<TaskEntry<T>> holder = new Holder<>();

        TASKS.compute(key, (k, existing) -> {
            if (existing != null) {
                holder.value = cast(existing);
                METRICS.incrementCoalesced();
                METRICS.incrementCacheHits();
                return existing;
            }

            TaskEntry<T> entry = new TaskEntry<>(
                v.computation(),
                v.threadSafe(),
                v.timeout(),
                modId,
                metadata
            );

            holder.value = entry;
            schedule(key, entry, v.type(), v.score());
            return entry;
        });

        pruneIfNecessary();
        return holder.value.future;
    }

    public static <T> CompletableFuture<T> submit(long key, PriorityTaskType type, double score, TaskComputation<T> computation, String modId) {
        return submit(key, type, score, computation, null, modId, TaskMetadata.DEFAULT);
    }

    private static <T> void schedule(long key, TaskEntry<T> entry, PriorityTaskType type, double score) {

        if (!MOD_MANAGER.canAcceptTask(entry.modId)) {
            entry.future.completeExceptionally(new RuntimeException("Mod limit exceeded: " + entry.modId));
            TASKS.remove(key);
            return;
        }

        long age = System.nanoTime() - entry.enqueueNanos;
        double modScore = MOD_MANAGER.computeModPriority(entry.modId, type, age);

        Runnable payload = () -> {
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

            entry.timeoutFuture = scheduleTimeout(key, entry, upstream);

            upstream.whenComplete((res, err) -> {
                cancelTimeout(entry.timeoutFuture);
                MOD_MANAGER.recordTaskComplete(entry.modId);

                if (!entry.timedOut) {
                    if (err != null) {
                        entry.future.completeExceptionally(err);
                    } else {
                        METRICS.recordSuccess(System.nanoTime() - start);
                        entry.future.complete(res);
                    }
                }

                TASKS.remove(key);
            });
        };

        PriorityTask task = new PriorityTask(key, type, modScore, payload, entry.metadata, entry.modId);

        if (gpuDispatcher != null && gpuDispatcher.trySchedule(task)) return;
        if (cpuDispatcher != null && cpuDispatcher.trySchedule(task)) return;

        enqueue(task);
    }

    private static void enqueue(PriorityTask task) {
        PriorityScheduler.SchedulerSnapshot s = scheduler.snapshot();

        AdaptiveLoadController.LoadSnapshot load = new AdaptiveLoadController.LoadSnapshot(
            (int) Math.max(0, s.submitted() - s.executed()),
            s.coalesced(),
            Math.max(32, s.foregroundQueue() + s.backgroundQueue()),
            Math.min(1.0, TASKS.size() / 2048.0)
        );

        long delay = CONTROLLER.evaluate(load).delayMillis();

        if (delay <= 0 || coalescer == null) {
            scheduler.submit(task);
        } else {
            coalescer.schedule(() -> {
                ThreadHealthMonitor.heartbeat(ThreadRole.COALESCER);
                scheduler.submit(task);
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static <T> ScheduledFuture<?> scheduleTimeout(long key, TaskEntry<T> entry, CompletableFuture<T> upstream) {
        if (entry.timeout == null || coalescer == null) return null;

        return coalescer.schedule(() -> {
            if (!entry.timedOut && TASKS.remove(key, entry)) {
                entry.timedOut = true;
                upstream.cancel(true);
                entry.future.completeExceptionally(new TimeoutException("Task " + key + " timed out"));
                METRICS.recordTimeout();
            }
        }, Math.max(1, entry.timeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    private static void cancelTimeout(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
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
        return (TaskEntry<T>) TASKS.get(key);
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
        volatile TaskMetadata metadata;
        volatile boolean timedOut;
        volatile ScheduledFuture<?> timeoutFuture;

        TaskEntry(TaskComputation<T> c, boolean ts, Duration to, String m, TaskMetadata md) {
            computation = c;
            threadSafe = ts;
            timeout = to;
            modId = m;
            metadata = md;
        }
    }

    private static final class Holder<T> {
        T value;
    }
}
