package org.admany.quantified.core.common.async.core;

import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.telemetry.TaskKindTelemetry;
import org.admany.quantified.core.common.threading.core.ThreadRole;
import org.admany.quantified.core.common.threading.health.ThreadHealthMonitor;
import org.admany.quantified.core.common.threading.pool.ThreadPoolErrorHandler;
import org.admany.quantified.core.common.threading.pool.ThreadPoolStats;
import org.admany.quantified.core.common.threading.scaling.DynamicThreadScaler;
import org.admany.quantified.core.common.threading.scaling.SystemLoadMonitor;
import org.admany.quantified.api.QuantifiedAPI;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PriorityScheduler {

    private static final Logger LOGGER = Logger.getLogger(PriorityScheduler.class.getName());
    private static final long DEFAULT_BACKGROUND_STALE_BASE_NANOS = TimeUnit.MILLISECONDS.toNanos(3_000L);
    private static final long DEFAULT_BACKGROUND_STALE_OVERLOAD_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);

    private final PriorityBlockingQueue<PriorityTask> foregroundQueue;
    private final BlockingQueue<PriorityTask> backgroundQueue;
    private final ConcurrentHashMap<Long, PriorityTask> coalesceMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> promotedKeys = new ConcurrentHashMap<>();

    private final ExecutorService foregroundPool;
    private final ExecutorService backgroundPool;
    private final ScheduledExecutorService housekeeper;

    private final Duration promotionDelay;
    private final int queueBound;
    private final int foregroundThreads;
    private final int backgroundThreads;
    private final ThreadPoolErrorHandler errorHandler;
    private final DynamicThreadScaler scaler;
    private final AutoBatchController autoBatchController = new AutoBatchController();
    private final RuntimeAutoTuner runtimeAutoTuner = new RuntimeAutoTuner();
    private final AtomicInteger desiredForegroundWorkers = new AtomicInteger();
    private final AtomicInteger desiredBackgroundWorkers = new AtomicInteger();
    private volatile long runtimeBackgroundStaleBaseNanos = DEFAULT_BACKGROUND_STALE_BASE_NANOS;
    private volatile long runtimeBackgroundStaleOverloadNanos = DEFAULT_BACKGROUND_STALE_OVERLOAD_NANOS;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicLong tasksSubmitted = new AtomicLong();
    private final AtomicLong tasksExecuted = new AtomicLong();
    private final AtomicLong foregroundExecuted = new AtomicLong();
    private final AtomicLong backgroundExecuted = new AtomicLong();
    private final AtomicInteger droppedTasks = new AtomicInteger();
    private final AtomicLong duplicatesSuppressed = new AtomicLong();
    private final AtomicLong workerCrashes = new AtomicLong();

    public PriorityScheduler(int foregroundThreads,
                             int backgroundThreads,
                             Duration promotionDelay,
                             int queueBound) {
        this(foregroundThreads, backgroundThreads, foregroundThreads, backgroundThreads, promotionDelay, queueBound,
            ThreadPoolErrorHandler.logging(LOGGER));
    }

    public PriorityScheduler(int foregroundThreads,
                             int backgroundThreads,
                             int maxForegroundThreads,
                             int maxBackgroundThreads,
                             Duration promotionDelay,
                             int queueBound,
                             ThreadPoolErrorHandler errorHandler) {
        this.foregroundQueue = new PriorityBlockingQueue<>();
        this.backgroundQueue = new LinkedBlockingQueue<>();
        this.foregroundThreads = Math.max(1, foregroundThreads);
        this.backgroundThreads = Math.max(1, backgroundThreads);
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.scaler = new DynamicThreadScaler(maxForegroundThreads, maxBackgroundThreads, SystemLoadMonitor.isSmtCapable());
        this.desiredForegroundWorkers.set(this.foregroundThreads);
        this.desiredBackgroundWorkers.set(this.backgroundThreads);
        this.foregroundPool = Executors.newFixedThreadPool(this.foregroundThreads, r -> {
            Thread t = new Thread(r, "quantified-fg");
            t.setDaemon(true);
            return t;
        });
        this.backgroundPool = Executors.newFixedThreadPool(this.backgroundThreads, r -> {
            Thread t = new Thread(r, "quantified-bg");
            t.setDaemon(true);
            return t;
        });
        this.housekeeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "quantified-hk");
            t.setDaemon(true);
            return t;
        });
        this.promotionDelay = Objects.requireNonNullElse(promotionDelay, Duration.ofMillis(250));
        this.queueBound = Math.max(256, queueBound);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < foregroundThreads; i++) {
            final int index = i;
            foregroundPool.submit(() -> foregroundLoop(index));
        }
        for (int i = 0; i < backgroundThreads; i++) {
            final int index = i;
            backgroundPool.submit(() -> backgroundLoop(index));
        }
        housekeeper.scheduleAtFixedRate(this::housekeeping, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        foregroundPool.shutdownNow();
        backgroundPool.shutdownNow();
        housekeeper.shutdownNow();
    }

    public void submit(PriorityTask task) {
        if (!running.get()) {
            LOGGER.log(Level.FINEST, "Scheduler not running, dropping task {0}", task);
            return;
        }
        tasksSubmitted.incrementAndGet();
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] PriorityScheduler: Submitting task " + task.taskKey() + " to queue");
        }
        PriorityTask stored = coalesceMap.merge(task.taskKey(), task, (oldTask, newTask) ->
            newTask.score() >= oldTask.score() ? newTask : oldTask);
        if (stored != task) {
            duplicatesSuppressed.incrementAndGet();
            return;
        }

        // Superseded any earlier task for this key; allow future promotion.
        promotedKeys.remove(task.taskKey());

        int queued = foregroundQueue.size() + backgroundQueue.size();
        int uniquePending = coalesceMap.size();
        boolean critical = task.type() == PriorityTaskType.FOREGROUND
            || (task.metadata() != null && task.metadata().gpuRequired())
            || task.score() >= 0.9;

        // Enforce a real bound under overload. Prefer to drop non-critical tasks.
        if ((queued >= queueBound || uniquePending > queueBound) && (!critical || queued >= (queueBound * 2))) {
            droppedTasks.incrementAndGet();
            coalesceMap.remove(task.taskKey(), task);
            return;
        }

        if (shouldRouteForeground(task)) {
            if (foregroundQueue.size() >= queueBound && !critical) {
                droppedTasks.incrementAndGet();
                coalesceMap.remove(task.taskKey(), task);
                return;
            }
            foregroundQueue.offer(task);
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Task " + task.taskKey() + " routed to foreground queue");
            }
        } else {
            if (backgroundQueue.size() >= queueBound && !critical) {
                droppedTasks.incrementAndGet();
                coalesceMap.remove(task.taskKey(), task);
                return;
            }
            backgroundQueue.offer(task);
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Task " + task.taskKey() + " routed to background queue");
            }
        }
    }

    private void foregroundLoop(int index) {
        ThreadHealthMonitor.register(Thread.currentThread(), ThreadRole.FOREGROUND_WORKER);
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] PriorityScheduler: Foreground worker " + index + " started");
        }
        while (running.get()) {
            try {
                ThreadHealthMonitor.heartbeat(ThreadRole.FOREGROUND_WORKER);
                if (index >= desiredForegroundWorkers.get()) {
                    TimeUnit.MILLISECONDS.sleep(50);
                    continue;
                }
                PriorityTask task = foregroundQueue.poll(250, TimeUnit.MILLISECONDS);
                if (task == null) {
                    if (SystemLoadMonitor.currentSystemLoad() >= SystemLoadMonitor.maxCpuLoad()) {
                        Thread.yield();
                        TimeUnit.MILLISECONDS.sleep(10);
                    }
                    continue;
                }
                if (QuantifiedAPI.isPrintDebugLogs()) {
                    LOGGER.fine("[DEBUG] PriorityScheduler: Foreground worker " + index + " picked up task " + task.taskKey());
                }
                PriorityTask current = coalesceMap.remove(task.taskKey());
                if (current == null || current != task) {
                    continue; // superseded
                }
                promotedKeys.remove(task.taskKey());
                int queueDepth = foregroundQueue.size() + backgroundQueue.size();
                if (shouldDropStale(current, true, queueDepth)) {
                    droppedTasks.incrementAndGet();
                    continue;
                }
                executeTask(current, foregroundExecuted, true);
                int additional = autoBatchController.recommendedAdditional(
                    true,
                    foregroundQueue.size(),
                    SystemLoadMonitor.currentSystemLoad(),
                    SystemLoadMonitor.maxCpuLoad()
                );
                executeBatchFromQueue(foregroundQueue, foregroundExecuted, additional, true);
            } catch (InterruptedException interrupted) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Foreground worker crash", t);
                workerCrashes.incrementAndGet();
                errorHandler.handle(ThreadRole.FOREGROUND_WORKER, t);
                ThreadHealthMonitor.recordCrash(ThreadRole.FOREGROUND_WORKER, t);
            }
        }
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] PriorityScheduler: Foreground worker " + index + " stopped");
        }
        ThreadHealthMonitor.unregister(Thread.currentThread());
    }

    private void backgroundLoop(int index) {
        ThreadHealthMonitor.register(Thread.currentThread(), ThreadRole.BACKGROUND_WORKER);
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] PriorityScheduler: Background worker " + index + " started");
        }
        while (running.get()) {
            try {
                ThreadHealthMonitor.heartbeat(ThreadRole.BACKGROUND_WORKER);
                if (index >= desiredBackgroundWorkers.get()) {
                    TimeUnit.MILLISECONDS.sleep(75);
                    continue;
                }
                PriorityTask task = backgroundQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) {
                    if (SystemLoadMonitor.currentSystemLoad() >= SystemLoadMonitor.maxCpuLoad()) {
                        Thread.yield();
                        TimeUnit.MILLISECONDS.sleep(20);
                    }
                    continue;
                }
                if (QuantifiedAPI.isPrintDebugLogs()) {
                    LOGGER.fine("[DEBUG] PriorityScheduler: Background worker " + index + " picked up task " + task.taskKey());
                }
                PriorityTask current = coalesceMap.remove(task.taskKey());
                if (current == null || current != task) {
                    continue;
                }
                promotedKeys.remove(task.taskKey());
                int queueDepth = foregroundQueue.size() + backgroundQueue.size();
                if (shouldDropStale(current, false, queueDepth)) {
                    droppedTasks.incrementAndGet();
                    continue;
                }
                executeTask(current, backgroundExecuted, false);
                int additional = autoBatchController.recommendedAdditional(
                    false,
                    backgroundQueue.size(),
                    SystemLoadMonitor.currentSystemLoad(),
                    SystemLoadMonitor.maxCpuLoad()
                );
                executeBatchFromQueue(backgroundQueue, backgroundExecuted, additional, false);
            } catch (InterruptedException interrupted) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Background worker crash", t);
                workerCrashes.incrementAndGet();
                errorHandler.handle(ThreadRole.BACKGROUND_WORKER, t);
                ThreadHealthMonitor.recordCrash(ThreadRole.BACKGROUND_WORKER, t);
            }
        }
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] PriorityScheduler: Background worker " + index + " stopped");
        }
        ThreadHealthMonitor.unregister(Thread.currentThread());
    }

    private void executeTask(PriorityTask task, AtomicLong counter, boolean foregroundWorker) {
        long startNanos = System.nanoTime();
        try {
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Executing task " + task.taskKey());
            }
            TaskMetadata metadata = task.metadata();
            if (metadata != null && !metadata.gpuPreferred() && !metadata.gpuRequired()) {
                String affinity = metadata.affinityKey();
                if (affinity != null && !affinity.isBlank() && !TaskKindTelemetry.isInternalBatchName(affinity)) {
                    TaskKindTelemetry.recordMultithreading(task.modId(), affinity);
                }
            }
            task.payload().run();
            counter.incrementAndGet();
            tasksExecuted.incrementAndGet();
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Task " + task.taskKey() + " completed successfully");
            }
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Scheduled task failure", t);
        } finally {
            long duration = Math.max(0L, System.nanoTime() - startNanos);
            autoBatchController.recordExecution(foregroundWorker, duration);
        }
    }

    private boolean shouldRouteForeground(PriorityTask task) {
        double score = task.score();
        boolean prefersForeground = score >= 0.5 || task.type() == PriorityTaskType.FOREGROUND;
        int fgSize = foregroundQueue.size();
        int bgSize = backgroundQueue.size();
        if (prefersForeground && bgSize < fgSize) {
            // BG is less loaded, route to BG instead
            return false;
        }
        if (!prefersForeground && fgSize < bgSize) {
            // FG is less loaded, route to FG instead
            return true;
        }
        return prefersForeground;
    }

    private void housekeeping() {
        ThreadHealthMonitor.heartbeat(ThreadRole.HOUSEKEEPER);
        long promoteAfter = promotionDelay.toNanos();
        long now = System.nanoTime();
        for (Map.Entry<Long, PriorityTask> entry : coalesceMap.entrySet()) {
            PriorityTask task = entry.getValue();
            if (task == null) {
                continue;
            }
            if (task.type() == PriorityTaskType.BACKGROUND) {
                // Aging: allow very old background tasks to receive a single foreground boost.
                if (now - task.enqueuedAtNanos() >= promoteAfter * 4L
                    && foregroundQueue.size() < Math.max(1, (int) (queueBound * 0.75d))
                    && promotedKeys.putIfAbsent(task.taskKey(), Boolean.TRUE) == null) {
                    task.adjustScore(0.05);
                    foregroundQueue.offer(task);
                }
                continue;
            }
            if (now - task.enqueuedAtNanos() >= promoteAfter) {
                // Prevent runaway queue growth: only promote a given key once until it executes.
                if (foregroundQueue.size() >= queueBound) {
                    continue;
                }
                if (promotedKeys.putIfAbsent(task.taskKey(), Boolean.TRUE) == null) {
                    task.adjustScore(0.1);
                    foregroundQueue.offer(task);
                }
            }
        }
        adjustScaling();
        applyRuntimeTuning();
    }

    private void executeBatchFromQueue(BlockingQueue<PriorityTask> queue, AtomicLong counter, int maxAdditional, boolean foregroundWorker) {
        if (maxAdditional <= 0) {
            return;
        }
        for (int i = 0; i < maxAdditional; i++) {
            PriorityTask next = queue.poll();
            if (next == null) {
                return;
            }
            PriorityTask current = coalesceMap.remove(next.taskKey());
            if (current == null || current != next) {
                continue;
            }
            promotedKeys.remove(next.taskKey());
            int queueDepth = foregroundQueue.size() + backgroundQueue.size();
            if (shouldDropStale(current, foregroundWorker, queueDepth)) {
                droppedTasks.incrementAndGet();
                continue;
            }
            executeTask(current, counter, foregroundWorker);
        }
    }

    private boolean shouldDropStale(PriorityTask task, boolean foregroundWorker, int totalQueueDepth) {
        if (task == null || foregroundWorker) {
            return false;
        }
        if (task.type() == PriorityTaskType.FOREGROUND) {
            return false;
        }
        TaskMetadata metadata = task.metadata();
        if (metadata != null && metadata.gpuRequired()) {
            return false;
        }
        if (totalQueueDepth < Math.max(24, queueBound / 3)) {
            return false;
        }
        long age = System.nanoTime() - task.enqueuedAtNanos();
        long staleThreshold = totalQueueDepth > queueBound
            ? runtimeBackgroundStaleOverloadNanos
            : runtimeBackgroundStaleBaseNanos;
        return age > staleThreshold;
    }

    private void applyRuntimeTuning() {
        RuntimeAutoTuner.RuntimeTuning tuning = runtimeAutoTuner.maybeTune(
            foregroundQueue.size(),
            backgroundQueue.size(),
            queueBound,
            droppedTasks.get(),
            workerCrashes.get(),
            SystemLoadMonitor.currentSystemLoad(),
            SystemLoadMonitor.maxCpuLoad()
        );
        if (tuning == null) {
            return;
        }

        autoBatchController.applyRuntimeTuning(
            tuning.foregroundTargetNanos(),
            tuning.backgroundTargetNanos(),
            tuning.foregroundMaxAdditional(),
            tuning.backgroundMaxAdditional()
        );
        runtimeBackgroundStaleBaseNanos = tuning.staleBaseNanos();
        runtimeBackgroundStaleOverloadNanos = tuning.staleOverloadNanos();
        scaler.applyRuntimeTuning(
            tuning.foregroundThrottlePenalty(),
            tuning.backgroundThrottlePenalty(),
            tuning.healthyLoadBoost()
        );
        org.admany.quantified.core.common.util.TaskScheduler.applyRuntimeTuning(
            tuning.gpuUtilLimit(),
            tuning.gpuBatchTargetNanos()
        );
    }

    private void adjustScaling() {
        double queuePressure = Math.min(1.0, (foregroundQueue.size() + backgroundQueue.size()) / (double) Math.max(1, queueBound));
        double systemLoad = SystemLoadMonitor.currentSystemLoad();
        DynamicThreadScaler.ScalingProfile profile = scaler.scale(foregroundQueue.size(), backgroundQueue.size(), queuePressure, systemLoad);
        desiredForegroundWorkers.set(profile.foregroundWorkers());
        desiredBackgroundWorkers.set(profile.backgroundWorkers());
    }

    public SchedulerSnapshot snapshot() {
        return new SchedulerSnapshot(
            tasksSubmitted.get(),
            tasksExecuted.get(),
            foregroundExecuted.get(),
            backgroundExecuted.get(),
            duplicatesSuppressed.get(),
            droppedTasks.get(),
            coalesceMap.size(),
            foregroundQueue.size(),
            backgroundQueue.size(),
            workerCrashes.get(),
            desiredForegroundWorkers.get(),
            desiredBackgroundWorkers.get());
    }

    public ThreadPoolStats stats() {
        SchedulerSnapshot snapshot = snapshot();
        return new ThreadPoolStats(
            java.time.Instant.now(),
            snapshot.submitted(),
            snapshot.executed(),
            snapshot.foreground(),
            snapshot.background(),
            snapshot.suppressedDuplicates(),
            snapshot.dropped(),
            snapshot.coalesced(),
            snapshot.foregroundQueue(),
            snapshot.backgroundQueue(),
            snapshot.workerCrashes(),
            snapshot.desiredForegroundWorkers(),
            snapshot.desiredBackgroundWorkers());
    }

    public record SchedulerSnapshot(long submitted,
                                    long executed,
                                    long foreground,
                                    long background,
                                    long suppressedDuplicates,
                                    long dropped,
                                    int coalesced,
                                    int foregroundQueue,
                                    int backgroundQueue,
                                    long workerCrashes,
                                    int desiredForegroundWorkers,
                                    int desiredBackgroundWorkers) {
    }
}
