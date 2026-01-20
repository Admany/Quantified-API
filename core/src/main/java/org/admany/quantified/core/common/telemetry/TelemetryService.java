package org.admany.quantified.core.common.telemetry;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.PriorityScheduler;
import org.admany.quantified.core.common.threading.pool.ThreadPoolStats;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class TelemetryService {

    private static final Logger LOGGER = Logger.getLogger(TelemetryService.class.getName());
    private static final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quantified-telemetry");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<SchedulerSnapshot> latest = new AtomicReference<>();
    private static final double ALPHA = 0.3; // EMA smoothing factor
    private static double emaExecRate = 0.0;
    private static long prevTs = 0;
    private static long prevExecuted = 0;

    private TelemetryService() {}

    public static void start() {
        sampler.scheduleAtFixedRate(TelemetryService::sample, 0, 1000, TimeUnit.MILLISECONDS);
        LOGGER.info("Telemetry service started");
    }

    public static void stop() {
        sampler.shutdown();
        LOGGER.info("Telemetry service stopped");
    }

    public static SchedulerSnapshot getLatest() {
        return latest.get();
    }

    private static void sample() {
        long now = System.currentTimeMillis();
        PriorityScheduler.SchedulerSnapshot schedulerSnap = AsyncManager.schedulerSnapshot();
        ThreadPoolStats threadStats = AsyncManager.threadPoolStats();

        long executed = schedulerSnap.executed();
        long dtMillis = Math.max(1, now - prevTs);
        double execRate = (executed - prevExecuted) / (dtMillis / 1000.0);
        emaExecRate = ALPHA * execRate + (1 - ALPHA) * emaExecRate;

        SchedulerSnapshot snap = new SchedulerSnapshot(
            now,
            schedulerSnap.submitted(),
            executed,
            emaExecRate,
            schedulerSnap.foregroundQueue() + schedulerSnap.backgroundQueue(),
            threadStats.desiredForegroundWorkers(),
            threadStats.desiredBackgroundWorkers()
        );
        latest.set(snap);

        prevTs = now;
        prevExecuted = executed;
    }

    public static final class SchedulerSnapshot {
        public final long timestamp;
        public final long submitted;
        public final long executed;
        public final double execRate;
        public final int queueSize;
        public final int foregroundWorkers;
        public final int backgroundWorkers;

        public SchedulerSnapshot(long timestamp, long submitted, long executed, double execRate,
                                int queueSize, int foregroundWorkers, int backgroundWorkers) {
            this.timestamp = timestamp;
            this.submitted = submitted;
            this.executed = executed;
            this.execRate = execRate;
            this.queueSize = queueSize;
            this.foregroundWorkers = foregroundWorkers;
            this.backgroundWorkers = backgroundWorkers;
        }
    }
}