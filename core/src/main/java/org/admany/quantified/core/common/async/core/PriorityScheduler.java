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
import java.util.concurrent.ConcurrentLinkedDeque;
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
    private static final int AFFINITY_STEAL_THRESHOLD = Math.max(4,
        Integer.getInteger("quantified.scheduler.affinityStealThreshold", 8));
    private static final long FOREGROUND_IDLE_POLL_MILLIS = Math.max(10L,
        Long.getLong("quantified.scheduler.foregroundIdlePollMs", 50L));
    private static final long BACKGROUND_IDLE_POLL_MILLIS = Math.max(20L,
        Long.getLong("quantified.scheduler.backgroundIdlePollMs", 100L));

    private final PriorityBlockingQueue<PriorityTask> foregroundQueue;
    private final BlockingQueue<PriorityTask> backgroundQueue;
    private final AffinityLane[] foregroundAffinityLanes;
    private final AffinityLane[] backgroundAffinityLanes;
    private final ConcurrentHashMap<Long, PriorityTask> coalesceMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> promotedKeys = new ConcurrentHashMap<>();

    private final ExecutorService foregroundPool;
    private final ExecutorService backgroundPool;
    private final ScheduledExecutorService housekeeper;

    private final Duration promotionDelay;
    private final int queueBound;
    private final int initialForegroundWorkers;
    private final int initialBackgroundWorkers;
    private final int maxForegroundWorkers;
    private final int maxBackgroundWorkers;
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
    private final AtomicInteger foregroundAffinityQueued = new AtomicInteger();
    private final AtomicInteger backgroundAffinityQueued = new AtomicInteger();
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
                             int queueBound) {
        this(foregroundThreads, backgroundThreads, maxForegroundThreads, maxBackgroundThreads, promotionDelay, queueBound,
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
        this.initialForegroundWorkers = Math.max(1, foregroundThreads);
        this.initialBackgroundWorkers = Math.max(1, backgroundThreads);
        this.maxForegroundWorkers = Math.max(this.initialForegroundWorkers, maxForegroundThreads);
        this.maxBackgroundWorkers = Math.max(this.initialBackgroundWorkers, maxBackgroundThreads);
        this.foregroundAffinityLanes = createAffinityLanes(this.maxForegroundWorkers);
        this.backgroundAffinityLanes = createAffinityLanes(this.maxBackgroundWorkers);
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.scaler = new DynamicThreadScaler(this.maxForegroundWorkers, this.maxBackgroundWorkers, SystemLoadMonitor.isSmtCapable());
        this.desiredForegroundWorkers.set(this.initialForegroundWorkers);
        this.desiredBackgroundWorkers.set(this.initialBackgroundWorkers);
        AtomicInteger fgThreadCounter = new AtomicInteger(0);
        AtomicInteger bgThreadCounter = new AtomicInteger(0);
        this.foregroundPool = Executors.newFixedThreadPool(this.maxForegroundWorkers, r -> {
            Thread t = new Thread(r, "quantified-fg-" + fgThreadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.backgroundPool = Executors.newFixedThreadPool(this.maxBackgroundWorkers, r -> {
            Thread t = new Thread(r, "quantified-bg-" + bgThreadCounter.incrementAndGet());
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
        for (int i = 0; i < maxForegroundWorkers; i++) {
            final int index = i;
            foregroundPool.submit(() -> foregroundLoop(index));
        }
        for (int i = 0; i < maxBackgroundWorkers; i++) {
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

        int queued = totalForegroundQueued() + totalBackgroundQueued();
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
            if (totalForegroundQueued() >= queueBound && !critical) {
                droppedTasks.incrementAndGet();
                coalesceMap.remove(task.taskKey(), task);
                return;
            }
            if (!routeToAffinityLane(task, true)) {
                foregroundQueue.offer(task);
            }
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Task " + task.taskKey() + " routed to foreground queue");
            }
        } else {
            if (totalBackgroundQueued() >= queueBound && !critical) {
                droppedTasks.incrementAndGet();
                coalesceMap.remove(task.taskKey(), task);
                return;
            }
            if (!routeToAffinityLane(task, false)) {
                backgroundQueue.offer(task);
            }
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
                PriorityTask task = pollForegroundTask(index);
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
                int queueDepth = totalForegroundQueued() + totalBackgroundQueued();
                if (shouldDropStale(current, true, queueDepth)) {
                    droppedTasks.incrementAndGet();
                    continue;
                }
                executeTask(current, foregroundExecuted, true);
                int additional = autoBatchController.recommendedAdditional(
                    true,
                    totalForegroundQueued(),
                    SystemLoadMonitor.currentSystemLoad(),
                    SystemLoadMonitor.maxCpuLoad()
                );
                int consumed = executeBatchFromAffinityLane(foregroundAffinityLanes[index], foregroundAffinityQueued, foregroundExecuted, additional, true);
                executeBatchFromQueue(foregroundQueue, foregroundExecuted, Math.max(0, additional - consumed), true);
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
                PriorityTask task = pollBackgroundTask(index);
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
                int queueDepth = totalForegroundQueued() + totalBackgroundQueued();
                if (shouldDropStale(current, false, queueDepth)) {
                    droppedTasks.incrementAndGet();
                    continue;
                }
                executeTask(current, backgroundExecuted, false);
                int additional = autoBatchController.recommendedAdditional(
                    false,
                    totalBackgroundQueued(),
                    SystemLoadMonitor.currentSystemLoad(),
                    SystemLoadMonitor.maxCpuLoad()
                );
                int consumed = executeBatchFromAffinityLane(backgroundAffinityLanes[index], backgroundAffinityQueued, backgroundExecuted, additional, false);
                executeBatchFromQueue(backgroundQueue, backgroundExecuted, Math.max(0, additional - consumed), false);
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
        TaskMetadata metadata = task.metadata();
        if (metadata != null) {
            String affinity = metadata.affinityKey();
            if (affinity != null && !affinity.isBlank()) {
                return prefersForeground;
            }
        }
        int fgSize = totalForegroundQueued();
        int bgSize = totalBackgroundQueued();
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
                    && totalForegroundQueued() < Math.max(1, (int) (queueBound * 0.75d))
                    && promotedKeys.putIfAbsent(task.taskKey(), Boolean.TRUE) == null) {
                    task.adjustScore(0.05);
                    if (!routeToAffinityLane(task, true)) {
                        foregroundQueue.offer(task);
                    }
                }
                continue;
            }
            if (now - task.enqueuedAtNanos() >= promoteAfter) {
                // Prevent runaway queue growth: only promote a given key once until it executes.
                if (totalForegroundQueued() >= queueBound) {
                    continue;
                }
                if (promotedKeys.putIfAbsent(task.taskKey(), Boolean.TRUE) == null) {
                    task.adjustScore(0.1);
                    if (!routeToAffinityLane(task, true)) {
                        foregroundQueue.offer(task);
                    }
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
            int queueDepth = totalForegroundQueued() + totalBackgroundQueued();
            if (shouldDropStale(current, foregroundWorker, queueDepth)) {
                droppedTasks.incrementAndGet();
                continue;
            }
            executeTask(current, counter, foregroundWorker);
        }
    }

    private int executeBatchFromAffinityLane(AffinityLane lane,
                                             AtomicInteger laneQueuedCounter,
                                             AtomicLong counter,
                                             int maxAdditional,
                                             boolean foregroundWorker) {
        if (maxAdditional <= 0 || lane == null) {
            return 0;
        }
        int executed = 0;
        while (executed < maxAdditional) {
            PriorityTask next = pollAffinityLane(lane, laneQueuedCounter);
            if (next == null) {
                break;
            }
            PriorityTask current = coalesceMap.remove(next.taskKey());
            if (current == null || current != next) {
                continue;
            }
            promotedKeys.remove(next.taskKey());
            int queueDepth = totalForegroundQueued() + totalBackgroundQueued();
            if (shouldDropStale(current, foregroundWorker, queueDepth)) {
                droppedTasks.incrementAndGet();
                continue;
            }
            executeTask(current, counter, foregroundWorker);
            executed++;
        }
        return executed;
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
            totalForegroundQueued(),
            totalBackgroundQueued(),
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
        int foregroundQueued = totalForegroundQueued();
        int backgroundQueued = totalBackgroundQueued();
        double queuePressure = Math.min(1.0, (foregroundQueued + backgroundQueued) / (double) Math.max(1, queueBound));
        double systemLoad = SystemLoadMonitor.currentSystemLoad();
        DynamicThreadScaler.ScalingProfile profile = scaler.scale(foregroundQueued, backgroundQueued, queuePressure, systemLoad);
        desiredForegroundWorkers.set(profile.foregroundWorkers());
        desiredBackgroundWorkers.set(profile.backgroundWorkers());
    }

    public SchedulerSnapshot snapshot() {
        int foregroundQueued = totalForegroundQueued();
        int backgroundQueued = totalBackgroundQueued();
        return new SchedulerSnapshot(
            tasksSubmitted.get(),
            tasksExecuted.get(),
            foregroundExecuted.get(),
            backgroundExecuted.get(),
            duplicatesSuppressed.get(),
            droppedTasks.get(),
            coalesceMap.size(),
            foregroundQueued,
            backgroundQueued,
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

    private PriorityTask pollForegroundTask(int workerIndex) throws InterruptedException {
        PriorityTask task = pollAffinityLane(foregroundAffinityLanes[workerIndex], foregroundAffinityQueued);
        if (task != null) {
            return task;
        }
        task = foregroundQueue.poll();
        if (task != null) {
            return task;
        }
        task = tryStealAffinityTask(
            foregroundAffinityLanes,
            workerIndex,
            Math.max(1, desiredForegroundWorkers.get()),
            foregroundAffinityQueued
        );
        if (task != null) {
            return task;
        }
        task = foregroundQueue.poll(FOREGROUND_IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
        if (task != null) {
            return task;
        }
        return pollAffinityLane(foregroundAffinityLanes[workerIndex], foregroundAffinityQueued);
    }

    private PriorityTask pollBackgroundTask(int workerIndex) throws InterruptedException {
        PriorityTask task = pollAffinityLane(backgroundAffinityLanes[workerIndex], backgroundAffinityQueued);
        if (task != null) {
            return task;
        }
        task = backgroundQueue.poll();
        if (task != null) {
            return task;
        }
        task = tryStealAffinityTask(
            backgroundAffinityLanes,
            workerIndex,
            Math.max(1, desiredBackgroundWorkers.get()),
            backgroundAffinityQueued
        );
        if (task != null) {
            return task;
        }
        task = backgroundQueue.poll(BACKGROUND_IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
        if (task != null) {
            return task;
        }
        return pollAffinityLane(backgroundAffinityLanes[workerIndex], backgroundAffinityQueued);
    }

    private boolean routeToAffinityLane(PriorityTask task, boolean foreground) {
        TaskMetadata metadata = task.metadata();
        if (metadata == null) {
            return false;
        }
        String affinity = metadata.affinityKey();
        if (affinity == null || affinity.isBlank()) {
            return false;
        }
        AffinityLane[] lanes = foreground ? foregroundAffinityLanes : backgroundAffinityLanes;
        if (lanes.length == 0) {
            return false;
        }
        int laneIndex = affinityLaneIndex(task.modId(), affinity, lanes.length);
        AffinityLane lane = lanes[laneIndex];
        lane.deque.offerLast(task);
        lane.size.incrementAndGet();
        if (foreground) {
            foregroundAffinityQueued.incrementAndGet();
        } else {
            backgroundAffinityQueued.incrementAndGet();
        }
        return true;
    }

    private PriorityTask pollAffinityLane(AffinityLane lane, AtomicInteger queuedCounter) {
        if (lane == null) {
            return null;
        }
        PriorityTask task = lane.deque.pollFirst();
        if (task != null) {
            lane.size.updateAndGet(current -> current > 0 ? current - 1 : 0);
            queuedCounter.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }
        return task;
    }

    private PriorityTask tryStealAffinityTask(AffinityLane[] lanes,
                                              int workerIndex,
                                              int activeWorkers,
                                              AtomicInteger queuedCounter) {
        if (lanes == null || lanes.length <= 1) {
            return null;
        }
        int activeLaneCount = Math.max(1, Math.min(activeWorkers, lanes.length));
        for (int offset = 1; offset < lanes.length; offset++) {
            int targetIndex = (workerIndex + offset) % lanes.length;
            AffinityLane lane = lanes[targetIndex];
            boolean inactiveLane = targetIndex >= activeLaneCount;
            if (lane == null || (!inactiveLane && lane.size.get() < AFFINITY_STEAL_THRESHOLD)) {
                continue;
            }
            PriorityTask stolen = lane.deque.pollLast();
            if (stolen != null) {
                lane.size.updateAndGet(current -> current > 0 ? current - 1 : 0);
                queuedCounter.updateAndGet(current -> current > 0 ? current - 1 : 0);
                return stolen;
            }
        }
        return null;
    }

    private int totalForegroundQueued() {
        return foregroundQueue.size() + foregroundAffinityQueued.get();
    }

    private int totalBackgroundQueued() {
        return backgroundQueue.size() + backgroundAffinityQueued.get();
    }

    private static int affinityLaneIndex(String modId, String affinity, int laneCount) {
        int hash = 31 * Objects.requireNonNullElse(modId, "").hashCode() + affinity.hashCode();
        return Math.floorMod(hash, laneCount);
    }

    private static AffinityLane[] createAffinityLanes(int count) {
        int safeCount = Math.max(1, count);
        AffinityLane[] lanes = new AffinityLane[safeCount];
        for (int i = 0; i < safeCount; i++) {
            lanes[i] = new AffinityLane();
        }
        return lanes;
    }

    private static final class AffinityLane {
        private final ConcurrentLinkedDeque<PriorityTask> deque = new ConcurrentLinkedDeque<>();
        private final AtomicInteger size = new AtomicInteger(0);
    }
}
