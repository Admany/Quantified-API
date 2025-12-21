package org.admany.quantified.core.common.async.core;

import org.admany.quantified.core.common.async.control.AdaptiveLoadController;
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
import org.admany.quantified.api.QuantifiedAPI;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private AsyncManager() {
    }

    public static void initialise(AsyncManagerBootstrap bootstrap, ScheduledExecutorService coalesceExecutor) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(coalesceExecutor, "coalesceExecutor");
        if (!INITIALISED.compareAndSet(false, true)) {
            return;
        }
        scheduler = new PriorityScheduler(
            bootstrap.foregroundThreads(),
            bootstrap.backgroundThreads(),
            bootstrap.maxForegroundThreads(),
            bootstrap.maxBackgroundThreads(),
            bootstrap.promotionDelay(),
            bootstrap.queueBound(),
            bootstrap.errorHandler());
        scheduler.start();
        coalescer = coalesceExecutor;
        gpuDispatcher = new GpuTaskDispatcher(scheduler, coalesceExecutor);
        LOGGER.info(() -> "AsyncManager initialised with fg=" + bootstrap.foregroundThreads()
            + ", bg=" + bootstrap.backgroundThreads()
            + ", maxFg=" + bootstrap.maxForegroundThreads()
            + ", maxBg=" + bootstrap.maxBackgroundThreads()
            + ", queueBound=" + bootstrap.queueBound());
    }

    public static void shutdown() {
        if (!INITIALISED.get()) {
            return;
        }
        if (scheduler != null) {
            scheduler.stop();
        }
        if (coalescer != null) {
            coalescer.shutdownNow();
        }
        if (gpuDispatcher != null) {
            gpuDispatcher.shutdown();
            gpuDispatcher = null;
        }
        GpuWorkloadRegistry.clear();
        GpuBatchTelemetry.reset();
        TASKS.clear();
        LAST_REQUEST_NANOS.clear();
        FINALIZERS.clear();
        INITIALISED.set(false);
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
        FINALIZERS.offer(Objects.requireNonNull(runnable, "runnable"));
    }

    public static void drainFinalizers() {
        ThreadHealthMonitor.heartbeat(ThreadRole.FINALIZER);
        Runnable next;
        while ((next = FINALIZERS.poll()) != null) {
            try {
                next.run();
            } catch (Throwable throwable) {
                LOGGER.log(Level.WARNING, "Finalizer failed", throwable);
            }
        }
    }

    public static <T> CompletableFuture<T> submitSync(long taskKey,
                                                      PriorityTaskType type,
                                                      double initialScore,
                                                      Supplier<T> supplier,
                                                      String modId) {
        return submitSync(taskKey, type, initialScore, supplier, null, true, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitSync(long taskKey,
                                                      PriorityTaskType type,
                                                      double initialScore,
                                                      Supplier<T> supplier,
                                                      String modId,
                                                      TaskMetadata metadata) {
        return submitSync(taskKey, type, initialScore, supplier, null, true, modId, metadata);
    }

    public static <T> CompletableFuture<T> submitSync(long taskKey,
                                                      PriorityTaskType type,
                                                      double initialScore,
                                                      Supplier<T> supplier,
                                                      Duration timeout,
                                                      String modId) {
        return submitSync(taskKey, type, initialScore, supplier, timeout, true, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitSync(long taskKey,
                                                      PriorityTaskType type,
                                                      double initialScore,
                                                      Supplier<T> supplier,
                                                      Duration timeout,
                                                      String modId,
                                                      TaskMetadata metadata) {
        return submitSync(taskKey, type, initialScore, supplier, timeout, true, modId, metadata);
    }

    public static <T> CompletableFuture<T> submitSync(long taskKey,
                                                      PriorityTaskType type,
                                                      double initialScore,
                                                      Supplier<T> supplier,
                                                      Duration timeout,
                                                      boolean threadSafe,
                                                      String modId) {
        return submitSync(taskKey, type, initialScore, supplier, timeout, threadSafe, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitSync(long taskKey,
                                                      PriorityTaskType type,
                                                      double initialScore,
                                                      Supplier<T> supplier,
                                                      Duration timeout,
                                                      boolean threadSafe,
                                                      String modId,
                                                      TaskMetadata metadata) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(metadata, "metadata");
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] AsyncManager: submitSync called for task " + taskKey + " with modId " + modId);
        }
        TaskComputation<T> computation = TaskComputation.sync(supplier).withThreadSafety(threadSafe);
        return submit(taskKey, type, initialScore, computation, timeout, modId, metadata);
    }

    public static <T> CompletableFuture<T> submitAsync(long taskKey,
                                                       PriorityTaskType type,
                                                       double initialScore,
                                                       Supplier<CompletableFuture<T>> supplier,
                                                       String modId) {
        return submitAsync(taskKey, type, initialScore, supplier, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitAsync(long taskKey,
                                                       PriorityTaskType type,
                                                       double initialScore,
                                                       Supplier<CompletableFuture<T>> supplier,
                                                       String modId,
                                                       TaskMetadata metadata) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(metadata, "metadata");
        return submit(taskKey, type, initialScore, TaskComputation.async(supplier), null, modId, metadata);
    }

    public static <T> CompletableFuture<T> submitAsync(long taskKey,
                                                       PriorityTaskType type,
                                                       double initialScore,
                                                       Supplier<CompletableFuture<T>> supplier,
                                                       Duration timeout,
                                                       String modId) {
        return submitAsync(taskKey, type, initialScore, supplier, timeout, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submitAsync(long taskKey,
                                                       PriorityTaskType type,
                                                       double initialScore,
                                                       Supplier<CompletableFuture<T>> supplier,
                                                       Duration timeout,
                                                       String modId,
                                                       TaskMetadata metadata) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(metadata, "metadata");
        return submit(taskKey, type, initialScore, TaskComputation.async(supplier), timeout, modId, metadata);
    }

    public static <T> CompletableFuture<T> submit(long taskKey,
                                                  PriorityTaskType type,
                                                  double initialScore,
                                                  TaskComputation<T> computation,
                                                  String modId) {
        Objects.requireNonNull(computation, "computation");
        return submit(taskKey, type, initialScore, computation, null, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submit(long taskKey,
                                                  PriorityTaskType type,
                                                  double initialScore,
                                                  TaskComputation<T> computation,
                                                  String modId,
                                                  TaskMetadata metadata) {
        Objects.requireNonNull(computation, "computation");
        return submit(taskKey, type, initialScore, computation, null, modId, metadata);
    }

    public static <T> CompletableFuture<T> submit(long taskKey,
                                                  PriorityTaskType type,
                                                  double initialScore,
                                                  TaskComputation<T> computation,
                                                  Duration timeout,
                                                  String modId) {
        return submit(taskKey, type, initialScore, computation, timeout, modId, TaskMetadata.DEFAULT);
    }

    public static <T> CompletableFuture<T> submit(long taskKey,
                                                  PriorityTaskType type,
                                                  double initialScore,
                                                  TaskComputation<T> computation,
                                                  Duration timeout,
                                                  String modId,
                                                  TaskMetadata metadata) {
        Objects.requireNonNull(computation, "computation");
        Objects.requireNonNull(metadata, "metadata");
        return submitInternal(taskKey, type, initialScore, computation, timeout, modId, metadata);
    }

    private static <T> CompletableFuture<T> submitInternal(long taskKey,
                                                           PriorityTaskType type,
                                                           double initialScore,
                                                           TaskComputation<T> computation,
                                                           Duration timeout,
                                                           String modId,
                                                           TaskMetadata metadata) {
        ensureInitialised();
        Objects.requireNonNull(metadata, "metadata");
        METRICS.incrementRequests();
        TaskSubmissionValidator.ValidatedSubmission<T> validated;
        try {
            validated = TaskSubmissionValidator.validate(taskKey, type, initialScore, computation, timeout, METRICS, LOGGER);
        } catch (RuntimeException runtimeException) {
            METRICS.recordRejectedSubmission();
            throw runtimeException;
        }

        PriorityTaskType resolvedType = validated.type();
        double resolvedScore = validated.score();
        TaskComputation<T> resolvedComputation = validated.computation();
        boolean threadSafe = validated.threadSafe();
        Duration resolvedTimeout = validated.timeout();

        long now = System.nanoTime();
        Long previous = LAST_REQUEST_NANOS.put(taskKey, now);
        if (previous != null && (now - previous) < DEBOUNCE_WINDOW_NANOS) {
            TaskEntry<T> cached = existingEntry(taskKey);
            if (cached != null) {
                METRICS.incrementCoalesced();
                METRICS.incrementCacheHits();
                return cached.future;
            }
        }

        Holder<TaskEntry<T>> holder = new Holder<>();
        TASKS.compute(taskKey, (key, existing) -> {
            if (existing != null) {
                TaskEntry<T> existingEntry = cast(existing);
                existingEntry.metadata = TaskMetadata.merge(existingEntry.metadata, metadata);
                holder.value = existingEntry;
                METRICS.incrementCoalesced();
                METRICS.incrementCacheHits();
                if (existingEntry.type != resolvedType) {
                    LOGGER.log(Level.FINE, "Task {0} reused existing entry with type {1} while new submission requested {2}", new Object[]{taskKey, existingEntry.type, resolvedType});
                }
                return existing;
            }
            TaskEntry<T> entry = new TaskEntry<>(resolvedComputation, resolvedType, threadSafe, resolvedTimeout, modId, metadata);
            holder.value = entry;
            schedule(taskKey, entry, resolvedType, resolvedScore);
            return entry;
        });

        TaskEntry<T> entry = holder.value;
        if (entry == null) {
            throw new IllegalStateException("Failed to acquire task entry for key " + taskKey);
        }
        pruneIfNecessary();
        return entry.future;
    }

    private static <T> void schedule(long taskKey,
                                     TaskEntry<T> entry,
                                     PriorityTaskType type,
                                     double score) {
        METRICS.incrementTasksCreated();

        // Check if mod can accept more tasks
        if (!MOD_MANAGER.canAcceptTask(entry.modId)) {
            METRICS.recordRejectedSubmission();
            entry.future.completeExceptionally(new RuntimeException("Mod " + entry.modId + " has exceeded task limit"));
            return;
        }

        // Compute mod-aware priority
        long taskAge = System.nanoTime() - System.nanoTime(); // Would need actual enqueue time
        double modAwareScore = MOD_MANAGER.computeModPriority(entry.modId, type, taskAge);

        Runnable payload = () -> {
            MOD_MANAGER.recordTaskStart(entry.modId);
            long start = System.nanoTime();
            CompletableFuture<T> future;
            try {
                if (!entry.threadSafe) {
                    if (!type.requiresThreadSafe()) {
                        METRICS.recordThreadSafetyViolation();
                    }
                    LOGGER.log(Level.WARNING, "Task {0} is flagged non-thread-safe; rerouting to main thread", taskKey);
                    future = MainThreadExecutor.reroute(entry.computation);
                } else {
                    future = entry.computation.execute();
                }
            } catch (Throwable throwable) {
                MOD_MANAGER.recordTaskComplete(entry.modId);
                METRICS.recordFailure();
                entry.future.completeExceptionally(throwable);
                TASKS.remove(taskKey);
                LOGGER.log(Level.SEVERE, "Async supplier threw", throwable);
                return;
            }
            if (future == null) {
                MOD_MANAGER.recordTaskComplete(entry.modId);
                METRICS.recordFailure();
                NullPointerException exception = new NullPointerException("Task " + taskKey + " returned null future");
                entry.future.completeExceptionally(exception);
                TASKS.remove(taskKey);
                LOGGER.log(Level.SEVERE, "Async supplier returned null future", exception);
                return;
            }
            entry.timeoutFuture = scheduleTimeout(taskKey, entry, future);
            future.whenComplete((result, error) -> {
                try {
                    MOD_MANAGER.recordTaskComplete(entry.modId);
                    cancelTimeout(entry.timeoutFuture);
                    if (entry.timedOut) {
                        return;
                    }
                    if (error != null) {
                        if (error instanceof CancellationException) {
                            METRICS.recordCancellation();
                        } else {
                            METRICS.recordFailure();
                        }
                        entry.future.completeExceptionally(error);
                    } else {
                        long duration = System.nanoTime() - start;
                        METRICS.recordSuccess(duration);
                        entry.future.complete(result);
                    }
                } finally {
                    TASKS.remove(taskKey);
                }
            });
        };
        TaskMetadata metadataSnapshot = entry.metadata;
        TaskMetadata effectiveMetadata = metadataSnapshot == null ? TaskMetadata.DEFAULT : metadataSnapshot;
        PriorityTask task = new PriorityTask(taskKey, type, modAwareScore, payload, effectiveMetadata, entry.modId);
        if (gpuDispatcher != null && gpuDispatcher.trySchedule(task)) {
            return;
        }
        enqueue(task);
    }

    private static <T> ScheduledFuture<?> scheduleTimeout(long taskKey,
                                                          TaskEntry<T> entry,
                                                          CompletableFuture<T> upstream) {
        if (entry.timeout == null) {
            return null;
        }
        if (coalescer == null) {
            LOGGER.log(Level.WARNING, "Timeout requested for task {0} but no coalescer is available", taskKey);
            return null;
        }
        long delayMillis = Math.max(1L, entry.timeout.toMillis());
        return coalescer.schedule(() -> {
            if (entry.timedOut) {
                return;
            }
            entry.timedOut = true;
            TimeoutException timeoutException = new TimeoutException("Task " + taskKey + " exceeded timeout of " + entry.timeout);
            boolean completed = entry.future.completeExceptionally(timeoutException);
            METRICS.recordTimeout();
            if (!upstream.isDone()) {
                upstream.cancel(true);
            }
            TASKS.remove(taskKey);
            if (completed) {
                LOGGER.log(Level.WARNING, "Task {0} timed out after {1}", new Object[]{taskKey, entry.timeout});
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private static void cancelTimeout(ScheduledFuture<?> timeoutFuture) {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
    }

    private static void enqueue(PriorityTask task) {
        PriorityScheduler.SchedulerSnapshot snapshot = scheduler.snapshot();
        AdaptiveLoadController.LoadSnapshot loadSnapshot = new AdaptiveLoadController.LoadSnapshot(
            pendingTasks(snapshot),
            snapshot.coalesced(),
            queueCapacityHint(snapshot),
            inferredCpuLoad());
        long delay = CONTROLLER.evaluate(loadSnapshot).delayMillis();
        if (delay <= 0L || coalescer == null) {
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] AsyncManager: Submitting task " + task.taskKey() + " to scheduler immediately");
            }
            scheduler.submit(task);
            return;
        }
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] AsyncManager: Delaying task " + task.taskKey() + " submission by " + delay + "ms");
        }
        coalescer.schedule(() -> {
            ThreadHealthMonitor.heartbeat(ThreadRole.COALESCER);
            scheduler.submit(task);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static int pendingTasks(PriorityScheduler.SchedulerSnapshot snapshot) {
        long outstanding = snapshot.submitted() - snapshot.executed();
        if (outstanding < 0) {
            outstanding = 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, outstanding);
    }

    private static int queueCapacityHint(PriorityScheduler.SchedulerSnapshot snapshot) {
        int queues = snapshot.foregroundQueue() + snapshot.backgroundQueue();
        return Math.max(queues, 32);
    }

    private static double inferredCpuLoad() {
        return Math.min(1.0, TASKS.size() / 2048.0);
    }

    private static void pruneIfNecessary() {
        if (TASKS.size() <= MAX_IN_FLIGHT) {
            return;
        }
        TASKS.entrySet().removeIf(entry -> entry.getValue().future.isDone());
        if (TASKS.size() <= MAX_IN_FLIGHT) {
            return;
        }
        int removed = 0;
        for (Long key : TASKS.keySet()) {
            if (TASKS.remove(key) != null) {
                removed++;
            }
            if (removed >= PRUNE_CHUNK || TASKS.size() <= MAX_IN_FLIGHT) {
                break;
            }
        }
    }

    private static void ensureInitialised() {
        if (!INITIALISED.get()) {
            throw new IllegalStateException("AsyncManager accessed before initialisation");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> TaskEntry<T> existingEntry(long taskKey) {
        return (TaskEntry<T>) TASKS.get(taskKey);
    }

    @SuppressWarnings("unchecked")
    private static <T> TaskEntry<T> cast(TaskEntry<?> entry) {
        return (TaskEntry<T>) entry;
    }

    private static final class TaskEntry<T> {
        final CompletableFuture<T> future = new CompletableFuture<>();
        final TaskComputation<T> computation;
        final PriorityTaskType type;
        final boolean threadSafe;
        final Duration timeout;
        final String modId;
        volatile TaskMetadata metadata;
        volatile boolean timedOut;
        volatile ScheduledFuture<?> timeoutFuture;

        TaskEntry(TaskComputation<T> computation,
                  PriorityTaskType type,
                  boolean threadSafe,
                  Duration timeout,
                  String modId,
                  TaskMetadata metadata) {
            this.computation = Objects.requireNonNull(computation, "computation");
            this.type = Objects.requireNonNull(type, "type");
            this.threadSafe = threadSafe;
            this.timeout = timeout;
            this.modId = Objects.requireNonNull(modId, "modId");
            this.metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }

    private static final class Holder<T> {
        T value;
    }
}
