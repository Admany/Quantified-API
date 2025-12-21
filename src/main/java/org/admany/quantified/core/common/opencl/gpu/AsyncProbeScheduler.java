package org.admany.quantified.core.common.opencl.gpu;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;

public class AsyncProbeScheduler {
    private static final Logger LOGGER = Logger.getLogger(AsyncProbeScheduler.class.getName());
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Quantified-OpenCL-Probe-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private static final int MAX_ATTEMPTS = 6;
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(5);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(20);

    private static final AtomicInteger remainingAttempts = new AtomicInteger(0);
    private static final AtomicInteger attemptCounter = new AtomicInteger(0);
    private static final AtomicBoolean rendererTriggered = new AtomicBoolean(false);

    private static volatile boolean scheduled = false;
    private static volatile boolean succeeded = false;

    public static synchronized void scheduleBackgroundProbe() {
        if (scheduled) {
            LOGGER.fine("OpenCL background probe already scheduled");
            return;
        }
        scheduled = true;
        succeeded = false;
        attemptCounter.set(0);
        remainingAttempts.set(MAX_ATTEMPTS);
        rendererTriggered.set(false);
        scheduleProbe(INITIAL_DELAY, "initial");
    }

    public static void triggerRendererProbe(String renderer) {
        triggerProbe("renderer:" + (renderer != null ? renderer : "unknown"));
    }

    public static void triggerProbe(String reason) {
        if (succeeded) {
            return;
        }
        if (!scheduled) {
            scheduleBackgroundProbe();
        }
        LOGGER.info("Triggering OpenCL probe due to: " + reason);
        if (reason.startsWith("opengl-ready") || reason.startsWith("renderer:")) {
            runProbeSynchronously(reason);
        } else {
            scheduleProbe(Duration.ZERO, reason);
        }
    }

    public static synchronized void reset() {
        scheduled = false;
        succeeded = false;
        attemptCounter.set(0);
        remainingAttempts.set(0);
        rendererTriggered.set(false);
    }

    private static void scheduleProbe(Duration delay, String trigger) {
        if (succeeded) {
            return;
        }
        long delayMs = Math.max(0, delay.toMillis());
        int current;
        do {
            current = remainingAttempts.get();
            if (current <= 0) {
                LOGGER.warning("OpenCL probe attempts exhausted; giving up after " + MAX_ATTEMPTS + " tries");
                return;
            }
        } while (!remainingAttempts.compareAndSet(current, current - 1));

        int attemptNo = attemptCounter.incrementAndGet();
        LOGGER.info("Scheduling OpenCL probe attempt #" + attemptNo + " (" + trigger + ") in " + delayMs + " ms");

        SCHEDULER.schedule(() -> runProbe(trigger, attemptNo), delayMs, TimeUnit.MILLISECONDS);
    }

    private static void runProbe(String trigger, int attemptNo) {
        if (succeeded) {
            return;
        }
        try {
            LOGGER.info("Running OpenCL probe attempt #" + attemptNo + " (" + trigger + ")");
            OpenCLManager.forceProbe().whenComplete((ok, err) -> handleProbeResult(trigger, attemptNo, ok, err));
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "OpenCL probe execution failure on attempt #" + attemptNo, t);
            scheduleRetry("execution-exception");
        }
    }

    private static void runProbeSynchronously(String trigger) {
        if (succeeded) {
            return;
        }
        int current;
        do {
            current = remainingAttempts.get();
            if (current <= 0) {
                LOGGER.warning("OpenCL probe attempts exhausted; giving up after " + MAX_ATTEMPTS + " tries");
                return;
            }
        } while (!remainingAttempts.compareAndSet(current, current - 1));

        int attemptNo = attemptCounter.incrementAndGet();
        LOGGER.info("Running OpenCL probe attempt #" + attemptNo + " synchronously (" + trigger + ")");

        try {
            Boolean result = OpenCLManager.forceProbeSynchronous(); // Run synchronously
            handleProbeResult(trigger, attemptNo, result, null);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "OpenCL probe execution failure on attempt #" + attemptNo, t);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Probe attempt " + attemptNo + " failed (" + trigger + ") — " + t.getMessage());
            scheduleRetry("execution-exception");
        }
    }

    private static void handleProbeResult(String trigger, int attemptNo, Boolean ok, Throwable err) {
        if (succeeded) {
            return;
        }
        if (err != null) {
            LOGGER.log(Level.WARNING, "OpenCL probe attempt #" + attemptNo + " (" + trigger + ") failed", err);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Probe attempt " + attemptNo + " failed (" + trigger + ") — " + err.getMessage());
            scheduleRetry("exception");
            return;
        }
        if (Boolean.TRUE.equals(ok)) {
            succeeded = true;
            LOGGER.info("OpenCL probe succeeded on attempt #" + attemptNo + " (" + trigger + ")");
            DeveloperOverlayManager.recordApiLog("[OpenCL] Acceleration ready - attempt " + attemptNo + " (trigger: " + trigger + ")");
        } else {
            DeveloperOverlayManager.recordApiLog("[OpenCL] Probe unavailable - attempt " + attemptNo + " (" + trigger + ") - GPU acceleration disabled");
        }
    }

    private static void scheduleRetry(String reason) {
        if (succeeded) {
            return;
        }
        scheduleProbe(RETRY_DELAY, "retry:" + reason);
    }
}
