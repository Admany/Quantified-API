package org.admany.quantified.core.common.async.core;

import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
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

    private final PriorityBlockingQueue<PriorityTask> foregroundQueue;
    private final BlockingQueue<PriorityTask> backgroundQueue;
    private final ConcurrentHashMap<Long, PriorityTask> coalesceMap = new ConcurrentHashMap<>();

    private final ExecutorService foregroundPool;
    private final ExecutorService backgroundPool;
    private final ScheduledExecutorService housekeeper;

    private final Duration promotionDelay;
    private final int queueBound;
    private final int foregroundThreads;
    private final int backgroundThreads;
    private final ThreadPoolErrorHandler errorHandler;
    private final DynamicThreadScaler scaler;
    private final AtomicInteger desiredForegroundWorkers = new AtomicInteger();
    private final AtomicInteger desiredBackgroundWorkers = new AtomicInteger();

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
        if ((foregroundQueue.size() + backgroundQueue.size()) > queueBound) {
            droppedTasks.incrementAndGet();
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
        if (shouldRouteForeground(task)) {
            foregroundQueue.offer(task);
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Task " + task.taskKey() + " routed to foreground queue");
            }
        } else {
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
                double load = SystemLoadMonitor.currentSystemLoad();
                if (load > 0.8) {
                    Thread.yield();
                    TimeUnit.MILLISECONDS.sleep(10); 
                    continue;
                }
                PriorityTask task = foregroundQueue.poll(250, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                if (QuantifiedAPI.isPrintDebugLogs()) {
                    LOGGER.fine("[DEBUG] PriorityScheduler: Foreground worker " + index + " picked up task " + task.taskKey());
                }
                PriorityTask current = coalesceMap.remove(task.taskKey());
                if (current == null || current != task) {
                    continue; // superseded
                }
                executeTask(current, foregroundExecuted);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
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
                // Yield if system is contended (high load from other apps)
                double load = SystemLoadMonitor.currentSystemLoad();
                if (load > 0.8) {
                    Thread.yield(); // Allow other threads/apps to proceed
                    TimeUnit.MILLISECONDS.sleep(10); // Brief pause
                    continue;
                }
                PriorityTask task = backgroundQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                if (QuantifiedAPI.isPrintDebugLogs()) {
                    LOGGER.fine("[DEBUG] PriorityScheduler: Background worker " + index + " picked up task " + task.taskKey());
                }
                PriorityTask current = coalesceMap.remove(task.taskKey());
                if (current == null || current != task) {
                    continue;
                }
                executeTask(current, backgroundExecuted);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
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

    private void executeTask(PriorityTask task, AtomicLong counter) {
        try {
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Executing task " + task.taskKey());
            }
            task.payload().run();
            counter.incrementAndGet();
            tasksExecuted.incrementAndGet();
            if (QuantifiedAPI.isPrintDebugLogs()) {
                LOGGER.fine("[DEBUG] PriorityScheduler: Task " + task.taskKey() + " completed successfully");
            }
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Scheduled task failure", t);
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
                continue; // Don't promote background tasks to prevent imbalance
            }
            if (now - task.enqueuedAtNanos() >= promoteAfter) {
                task.adjustScore(0.1);
                foregroundQueue.offer(task);
            }
        }
        adjustScaling();
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