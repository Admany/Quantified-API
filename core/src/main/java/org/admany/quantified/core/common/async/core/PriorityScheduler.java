package org.admany.quantified.core.common.async.core;

import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.telemetry.TaskKindTelemetry;
import org.admany.quantified.core.common.threading.core.ThreadRole;
import org.admany.quantified.core.common.threading.core.WorkerClassLoaderContext;
import org.admany.quantified.core.common.threading.health.ThreadHealthMonitor;
import org.admany.quantified.core.common.threading.pool.ThreadPoolErrorHandler;
import org.admany.quantified.core.common.threading.pool.ThreadPoolStats;
import org.admany.quantified.core.common.threading.scaling.DynamicThreadScaler;
import org.admany.quantified.core.common.threading.scaling.SystemLoadMonitor;
import org.admany.quantified.api.QuantifiedAPI;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PriorityScheduler {

    private static final Logger LOGGER = Logger.getLogger(PriorityScheduler.class.getName());
    private static final long DEFAULT_BACKGROUND_STALE_BASE_NANOS = TimeUnit.MILLISECONDS.toNanos(3_000L);
    private static final long DEFAULT_BACKGROUND_STALE_OVERLOAD_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);
    private static final int AFFINITY_STEAL_THRESHOLD = Math.max(4,
        Integer.getInteger("quantified.scheduler.affinityStealThreshold", 8));
    private static final long FOREGROUND_IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.max(10L,
        Long.getLong("quantified.scheduler.foregroundIdlePollMs", 50L)));
    private static final long BACKGROUND_IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.max(20L,
        Long.getLong("quantified.scheduler.backgroundIdlePollMs", 100L)));
    private final BucketedQueue foregroundBucket;
    private final BucketedQueue backgroundBucket;
    private final AffinityLane[] foregroundAffinityLanes;
    private final AffinityLane[] backgroundAffinityLanes;
    private final ConcurrentHashMap<Long, PriorityTask> coalesceMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> promotedKeys = new ConcurrentHashMap<>();
    private final DelayQueue<PromotionCandidate> promotionQueue = new DelayQueue<>();

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
    private final AtomicBoolean[] foregroundWorkerActive;
    private final AtomicBoolean[] backgroundWorkerActive;
    @SuppressWarnings("unchecked")
    private final AtomicReference<Thread>[] foregroundWorkerThreads;
    @SuppressWarnings("unchecked")
    private final AtomicReference<Thread>[] backgroundWorkerThreads;
    private volatile long runtimeBackgroundStaleBaseNanos = DEFAULT_BACKGROUND_STALE_BASE_NANOS;
    private volatile long runtimeBackgroundStaleOverloadNanos = DEFAULT_BACKGROUND_STALE_OVERLOAD_NANOS;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicLong tasksSubmitted = new AtomicLong();
    private final AtomicLong tasksExecuted = new AtomicLong();
    private final AtomicLong foregroundExecuted = new AtomicLong();
    private final AtomicLong backgroundExecuted = new AtomicLong();
    private final AtomicInteger droppedTasks = new AtomicInteger();
    private final LongAdder foregroundAffinityQueued = new LongAdder();
    private final LongAdder backgroundAffinityQueued = new LongAdder();
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

    @SuppressWarnings("unchecked")
    public PriorityScheduler(int foregroundThreads,
                             int backgroundThreads,
                             int maxForegroundThreads,
                             int maxBackgroundThreads,
                             Duration promotionDelay,
                             int queueBound,
                             ThreadPoolErrorHandler errorHandler) {
        this.foregroundBucket = new BucketedQueue();
        this.backgroundBucket = new BucketedQueue();
        this.initialForegroundWorkers = Math.max(1, foregroundThreads);
        this.initialBackgroundWorkers = Math.max(1, backgroundThreads);
        this.maxForegroundWorkers = Math.max(this.initialForegroundWorkers, maxForegroundThreads);
        this.maxBackgroundWorkers = Math.max(this.initialBackgroundWorkers, maxBackgroundThreads);
        this.foregroundAffinityLanes = createAffinityLanes(this.maxForegroundWorkers);
        this.backgroundAffinityLanes = createAffinityLanes(this.maxBackgroundWorkers);
        this.foregroundWorkerActive = createWorkerSlots(this.maxForegroundWorkers);
        this.backgroundWorkerActive = createWorkerSlots(this.maxBackgroundWorkers);
        this.foregroundWorkerThreads = createWorkerThreadSlots(this.maxForegroundWorkers);
        this.backgroundWorkerThreads = createWorkerThreadSlots(this.maxBackgroundWorkers);
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.scaler = new DynamicThreadScaler(this.maxForegroundWorkers, this.maxBackgroundWorkers, SystemLoadMonitor.isSmtCapable());
        this.desiredForegroundWorkers.set(resolveStartupWorkers(
            "quantified.scheduler.startupForegroundWorkers",
            this.initialForegroundWorkers,
            this.maxForegroundWorkers
        ));
        this.desiredBackgroundWorkers.set(resolveStartupWorkers(
            "quantified.scheduler.startupBackgroundWorkers",
            this.initialBackgroundWorkers,
            this.maxBackgroundWorkers
        ));
        AtomicInteger fgThreadCounter = new AtomicInteger(0);
        AtomicInteger bgThreadCounter = new AtomicInteger(0);
        this.foregroundPool = new ThreadPoolExecutor(0, this.maxForegroundWorkers,
            15L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            WorkerClassLoaderContext.wrap(r -> {
                Thread t = new Thread(r, "quantified-fg-" + fgThreadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }));
        this.backgroundPool = new ThreadPoolExecutor(0, this.maxBackgroundWorkers,
            20L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            WorkerClassLoaderContext.wrap(r -> {
                Thread t = new Thread(r, "quantified-bg-" + bgThreadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }));
        this.housekeeper = Executors.newSingleThreadScheduledExecutor(
            WorkerClassLoaderContext.wrap(r -> {
                Thread t = new Thread(r, "quantified-hk");
                t.setDaemon(true);
                return t;
            }));
        this.promotionDelay = Objects.requireNonNullElse(promotionDelay, Duration.ofMillis(250));
        this.queueBound = Math.max(256, queueBound);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ensureWorkerCapacity();
        housekeeper.scheduleAtFixedRate(this::housekeeping, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        foregroundPool.shutdownNow();
        backgroundPool.shutdownNow();
        housekeeper.shutdownNow();
        wakeAllWorkers();
    }

    public void submit(PriorityTask task) {
        if (!running.get()) {
            LOGGER.log(Level.FINEST, "Scheduler not running, dropping task {0}", task);
            task.notifyDrop();
            return;
        }
        tasksSubmitted.incrementAndGet();
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] PriorityScheduler: Submitting task " + task.taskKey() + " to queue");
        }
        PriorityTask stored = coalesceMap.merge(task.taskKey(), task, (oldTask, newTask) ->
            newTask.score() >= oldTask.score() ? newTask : oldTask);
        if (stored != task) {
            task.markSuperseded();
            duplicatesSuppressed.incrementAndGet();
            task.notifyDrop();
            return;
        }

        int queued = totalForegroundQueued() + totalBackgroundQueued();
        int uniquePending = coalesceMap.size();
        boolean critical = task.type() == PriorityTaskType.FOREGROUND
            || (task.metadata() != null && task.metadata().gpuRequired())
            || task.score() >= 0.9;
        boolean routedForeground = shouldRouteForeground(task);

        if (shouldTrackPromotion(task, critical, routedForeground)) {
            promotedKeys.remove(task.taskKey());
            long promotionDelayNanos = promotionDelay.toNanos();
            promotionQueue.offer(new PromotionCandidate(
                task.taskKey(),
                System.nanoTime() + promotionDelayNanos * 4L
            ));
        } else {
            promotedKeys.remove(task.taskKey());
        }

        if ((queued >= queueBound || uniquePending > queueBound) && (!critical || queued >= (queueBound * 2))) {
            droppedTasks.incrementAndGet();
            coalesceMap.remove(task.taskKey(), task);
            task.notifyDrop();
            return;
        }

        if (routedForeground) {
            if (totalForegroundQueued() >= queueBound && !critical) {
                droppedTasks.incrementAndGet();
                coalesceMap.remove(task.taskKey(), task);
                task.notifyDrop();
                return;
            }
            if (!routeToAffinityLane(task, true)) {
                foregroundBucket.offer(task);
            }
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Task " + task.taskKey() + " routed to foreground queue");
            }
        } else {
            if (totalBackgroundQueued() >= queueBound && !critical) {
                droppedTasks.incrementAndGet();
                coalesceMap.remove(task.taskKey(), task);
                task.notifyDrop();
                return;
            }
            if (!routeToAffinityLane(task, false)) {
                backgroundBucket.offer(task);
            }
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Task " + task.taskKey() + " routed to background queue");
            }
        }
        wakeOneWorker(routedForeground);
        ensureWorkerCapacity();
    }

    private void foregroundLoop(int index) {
        foregroundWorkerThreads[index].set(Thread.currentThread());
        ThreadHealthMonitor.register(Thread.currentThread(), ThreadRole.FOREGROUND_WORKER);
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] PriorityScheduler: Foreground worker " + index + " started");
        }
        try {
            while (running.get()) {
                try {
                    ThreadHealthMonitor.heartbeat(ThreadRole.FOREGROUND_WORKER);
                    if (index >= desiredForegroundWorkers.get()) {
                        if (shouldRetireForegroundWorker(index)) {
                            break;
                        }
                        LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                        continue;
                    }
                    PriorityTask task = pollForegroundTask(index);
                    if (task == null) {
                        if (shouldRetireForegroundWorker(index)) {
                            break;
                        }
                        if (SystemLoadMonitor.currentSystemLoad() >= SystemLoadMonitor.maxCpuLoad()) {
                            Thread.yield();
                        }
                        LockSupport.parkNanos(this, FOREGROUND_IDLE_PARK_NANOS);
                        continue;
                    }
                    if (QuantifiedAPI.isPrintDebugLogs()) {
                        LOGGER.fine("[DEBUG] PriorityScheduler: Foreground worker " + index + " picked up task " + task.taskKey());
                    }
                    if (task.isSuperseded()) {
                        continue;
                    }
                    PriorityTask current = coalesceMap.remove(task.taskKey());
                    if (current == null || current != task) {
                        continue; // superseded
                    }
                    promotedKeys.remove(task.taskKey());
                    int queueDepth = totalForegroundQueued() + totalBackgroundQueued();
                    if (shouldDropStale(current, true, queueDepth)) {
                        droppedTasks.incrementAndGet();
                        current.notifyDrop();
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
                    executeBatchFromBucket(foregroundBucket, foregroundExecuted, Math.max(0, additional - consumed), true);
                } catch (Throwable t) {
                    if (t instanceof InterruptedException) {
                        if (!running.get()) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    LOGGER.log(Level.WARNING, "Foreground worker crash", t);
                    workerCrashes.incrementAndGet();
                    errorHandler.handle(ThreadRole.FOREGROUND_WORKER, t);
                    ThreadHealthMonitor.recordCrash(ThreadRole.FOREGROUND_WORKER, t);
                }
            }
        } finally {
            foregroundWorkerThreads[index].set(null);
            foregroundWorkerActive[index].set(false);
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Foreground worker " + index + " stopped");
            }
            ThreadHealthMonitor.unregister(Thread.currentThread());
            if (running.get() && totalForegroundQueued() > 0) {
                ensureWorkerCapacity();
            }
        }
    }

    private void backgroundLoop(int index) {
        backgroundWorkerThreads[index].set(Thread.currentThread());
        ThreadHealthMonitor.register(Thread.currentThread(), ThreadRole.BACKGROUND_WORKER);
        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] PriorityScheduler: Background worker " + index + " started");
        }
        try {
            while (running.get()) {
                try {
                    ThreadHealthMonitor.heartbeat(ThreadRole.BACKGROUND_WORKER);
                    if (index >= desiredBackgroundWorkers.get()) {
                        if (shouldRetireBackgroundWorker(index)) {
                            break;
                        }
                        LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(75));
                        continue;
                    }
                    PriorityTask task = pollBackgroundTask(index);
                    if (task == null) {
                        if (shouldRetireBackgroundWorker(index)) {
                            break;
                        }
                        if (SystemLoadMonitor.currentSystemLoad() >= SystemLoadMonitor.maxCpuLoad()) {
                            Thread.yield();
                        }
                        LockSupport.parkNanos(this, BACKGROUND_IDLE_PARK_NANOS);
                        continue;
                    }
                    if (QuantifiedAPI.isPrintDebugLogs()) {
                        LOGGER.fine("[DEBUG] PriorityScheduler: Background worker " + index + " picked up task " + task.taskKey());
                    }
                    if (task.isSuperseded()) {
                        continue;
                    }
                    PriorityTask current = coalesceMap.remove(task.taskKey());
                    if (current == null || current != task) {
                        continue;
                    }
                    promotedKeys.remove(task.taskKey());
                    int queueDepth = totalForegroundQueued() + totalBackgroundQueued();
                    if (shouldDropStale(current, false, queueDepth)) {
                        droppedTasks.incrementAndGet();
                        current.notifyDrop();
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
                    executeBatchFromBucket(backgroundBucket, backgroundExecuted, Math.max(0, additional - consumed), false);
                } catch (Throwable t) {
                    if (t instanceof InterruptedException) {
                        if (!running.get()) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    LOGGER.log(Level.WARNING, "Background worker crash", t);
                    workerCrashes.incrementAndGet();
                    errorHandler.handle(ThreadRole.BACKGROUND_WORKER, t);
                    ThreadHealthMonitor.recordCrash(ThreadRole.BACKGROUND_WORKER, t);
                }
            }
        } finally {
            backgroundWorkerThreads[index].set(null);
            backgroundWorkerActive[index].set(false);
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Background worker " + index + " stopped");
            }
            ThreadHealthMonitor.unregister(Thread.currentThread());
            if (running.get() && totalBackgroundQueued() > 0) {
                ensureWorkerCapacity();
            }
        }
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

    private void ensureWorkerCapacity() {
        if (!running.get()) {
            return;
        }
        ensureWorkers(
            foregroundWorkerActive,
            desiredForegroundWorkers.get(),
            foregroundPool,
            this::foregroundLoop,
            "foreground"
        );
        ensureWorkers(
            backgroundWorkerActive,
            desiredBackgroundWorkers.get(),
            backgroundPool,
            this::backgroundLoop,
            "background"
        );
    }

    private void ensureWorkers(AtomicBoolean[] slots,
                               int desired,
                               ExecutorService pool,
                               WorkerLoop workerLoop,
                               String role) {
        int target = Math.max(1, Math.min(desired, slots.length));
        for (int i = 0; i < target; i++) {
            AtomicBoolean active = slots[i];
            if (!active.compareAndSet(false, true)) {
                continue;
            }
            final int index = i;
            try {
                pool.submit(() -> workerLoop.run(index));
            } catch (RuntimeException e) {
                active.set(false);
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "Failed to start " + role + " worker " + index, e);
                    workerCrashes.incrementAndGet();
                }
            }
        }
    }

    private boolean shouldRetireForegroundWorker(int index) {
        return index >= desiredForegroundWorkers.get() && totalForegroundQueued() == 0;
    }

    private boolean shouldRetireBackgroundWorker(int index) {
        return index >= desiredBackgroundWorkers.get() && totalBackgroundQueued() == 0;
    }

    private void housekeeping() {
        ThreadHealthMonitor.heartbeat(ThreadRole.HOUSEKEEPER);
        PromotionCandidate candidate;
        while ((candidate = promotionQueue.poll()) != null) {
            long key = candidate.taskKey;
            PriorityTask task = coalesceMap.get(key);
            if (task == null || promotedKeys.containsKey(key)) {
                continue;
            }
            if (task.isSuperseded()) {
                continue;
            }
            if (task.type() == PriorityTaskType.BACKGROUND
                && totalForegroundQueued() < Math.max(1, (int) (queueBound * 0.75d))
                && promotedKeys.putIfAbsent(key, Boolean.TRUE) == null) {
                task.adjustScore(0.05);
                if (!routeToAffinityLane(task, true)) {
                    foregroundBucket.offer(task);
                }
                wakeOneWorker(true);
            }
        }
        adjustScaling();
        ensureWorkerCapacity();
        applyRuntimeTuning();
    }

    private void executeBatchFromBucket(BucketedQueue bucket, AtomicLong counter, int maxAdditional, boolean foregroundWorker) {
        if (maxAdditional <= 0) {
            return;
        }
        for (int i = 0; i < maxAdditional; i++) {
            PriorityTask next = bucket.poll();
            if (next == null) {
                return;
            }
            if (next.isSuperseded()) {
                continue;
            }
            PriorityTask current = coalesceMap.remove(next.taskKey());
            if (current == null || current != next) {
                continue;
            }
            promotedKeys.remove(next.taskKey());
            int queueDepth = totalForegroundQueued() + totalBackgroundQueued();
            if (shouldDropStale(current, foregroundWorker, queueDepth)) {
                droppedTasks.incrementAndGet();
                current.notifyDrop();
                continue;
            }
            executeTask(current, counter, foregroundWorker);
        }
    }

    private int executeBatchFromAffinityLane(AffinityLane lane,
                                             LongAdder laneQueuedCounter,
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
            if (next.isSuperseded()) {
                continue;
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

    private PriorityTask pollForegroundTask(int workerIndex) {
        PriorityTask task = pollAffinityLane(foregroundAffinityLanes[workerIndex], foregroundAffinityQueued);
        if (task != null) {
            return task;
        }
        task = foregroundBucket.poll();
        if (task != null) {
            return task;
        }
        return tryStealAffinityTask(
            foregroundAffinityLanes,
            workerIndex,
            Math.max(1, desiredForegroundWorkers.get()),
            foregroundAffinityQueued
        );
    }

    private PriorityTask pollBackgroundTask(int workerIndex) {
        PriorityTask task = pollAffinityLane(backgroundAffinityLanes[workerIndex], backgroundAffinityQueued);
        if (task != null) {
            return task;
        }
        task = backgroundBucket.poll();
        if (task != null) {
            return task;
        }
        return tryStealAffinityTask(
            backgroundAffinityLanes,
            workerIndex,
            Math.max(1, desiredBackgroundWorkers.get()),
            backgroundAffinityQueued
        );
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
            foregroundAffinityQueued.increment();
        } else {
            backgroundAffinityQueued.increment();
        }
        return true;
    }

    private boolean shouldTrackPromotion(PriorityTask task, boolean critical, boolean routedForeground) {
        if (task == null || critical || routedForeground || task.type() != PriorityTaskType.BACKGROUND) {
            return false;
        }
        TaskMetadata metadata = task.metadata();
        return metadata == null || metadata.affinityKey().isBlank();
    }

    private PriorityTask pollAffinityLane(AffinityLane lane, LongAdder queuedCounter) {
        if (lane == null) {
            return null;
        }
        PriorityTask task = lane.deque.pollFirst();
        if (task != null) {
            lane.size.updateAndGet(current -> current > 0 ? current - 1 : 0);
            queuedCounter.decrement();
        }
        return task;
    }

    private PriorityTask tryStealAffinityTask(AffinityLane[] lanes,
                                              int workerIndex,
                                              int activeWorkers,
                                              LongAdder queuedCounter) {
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
                queuedCounter.decrement();
                return stolen;
            }
        }
        return null;
    }

    private void wakeOneWorker(boolean foreground) {
        AtomicReference<Thread>[] threads = foreground ? foregroundWorkerThreads : backgroundWorkerThreads;
        for (AtomicReference<Thread> ref : threads) {
            Thread t = ref.get();
            if (t != null) {
                LockSupport.unpark(t);
                return;
            }
        }
        ensureWorkerCapacity();
    }

    private void wakeAllWorkers() {
        for (AtomicReference<Thread> ref : foregroundWorkerThreads) {
            Thread t = ref.get();
            if (t != null) LockSupport.unpark(t);
        }
        for (AtomicReference<Thread> ref : backgroundWorkerThreads) {
            Thread t = ref.get();
            if (t != null) LockSupport.unpark(t);
        }
    }

    private int totalForegroundQueued() {
        return foregroundBucket.size() + (int) Math.max(0L, foregroundAffinityQueued.sum());
    }

    private int totalBackgroundQueued() {
        return backgroundBucket.size() + (int) Math.max(0L, backgroundAffinityQueued.sum());
    }

    private static int resolveStartupWorkers(String propertyName, int initialWorkers, int maxWorkers) {
        int startupWorkers = Integer.getInteger(propertyName, 1);
        return Math.max(1, Math.min(maxWorkers, startupWorkers));
    }

    private static int murmur3Mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    private static int affinityLaneIndex(String modId, String affinity, int laneCount) {
        int hash = murmur3Mix(31 * Objects.requireNonNullElse(modId, "").hashCode() + affinity.hashCode());
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

    private static AtomicBoolean[] createWorkerSlots(int count) {
        int safeCount = Math.max(1, count);
        AtomicBoolean[] slots = new AtomicBoolean[safeCount];
        for (int i = 0; i < safeCount; i++) {
            slots[i] = new AtomicBoolean(false);
        }
        return slots;
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Thread>[] createWorkerThreadSlots(int count) {
        int safeCount = Math.max(1, count);
        AtomicReference<Thread>[] slots = new AtomicReference[safeCount];
        for (int i = 0; i < safeCount; i++) {
            slots[i] = new AtomicReference<>(null);
        }
        return slots;
    }

    @FunctionalInterface
    private interface WorkerLoop {
        void run(int index);
    }

    private static final class AffinityLane {
        private final ConcurrentLinkedDeque<PriorityTask> deque = new ConcurrentLinkedDeque<>();
        private final AtomicInteger size = new AtomicInteger(0);
    }

    private static final class BucketedQueue {
        private final ConcurrentLinkedQueue<PriorityTask> critical = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<PriorityTask> high = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<PriorityTask> normal = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();

        void offer(PriorityTask task) {
            if (isCritical(task)) {
                critical.offer(task);
            } else if (task.score() >= 0.5) {
                high.offer(task);
            } else {
                normal.offer(task);
            }
            size.incrementAndGet();
        }

        PriorityTask poll() {
            PriorityTask task = critical.poll();
            if (task != null) { size.decrementAndGet(); return task; }
            task = high.poll();
            if (task != null) { size.decrementAndGet(); return task; }
            task = normal.poll();
            if (task != null) { size.decrementAndGet(); return task; }
            return null;
        }

        int size() {
            return Math.max(0, size.get());
        }

        private static boolean isCritical(PriorityTask task) {
            return task.type() == PriorityTaskType.FOREGROUND
                || (task.metadata() != null && task.metadata().gpuRequired())
                || task.score() >= 0.9;
        }
    }

    private static final class PromotionCandidate implements Delayed {
        final long taskKey;
        private final long promoteAtNanos;

        PromotionCandidate(long taskKey, long promoteAtNanos) {
            this.taskKey = taskKey;
            this.promoteAtNanos = promoteAtNanos;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(promoteAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other instanceof PromotionCandidate pc) {
                return Long.compare(this.promoteAtNanos, pc.promoteAtNanos);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
