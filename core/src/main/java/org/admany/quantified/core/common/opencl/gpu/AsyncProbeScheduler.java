package org.admany.quantified.core.common.opencl.gpu;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;

public class AsyncProbeScheduler {
    private static final Logger LOGGER = Logger.getLogger(AsyncProbeScheduler.class.getName());
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        return LwjglRuntimeTuning.newDaemonThread(r, "Quantified-OpenCL-Probe-Scheduler",
            LwjglRuntimeTuning.gpuThreadStackSizeKb());
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
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            markDisabled();
            return;
        }
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
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            markDisabled();
            reset();
            return;
        }
        if (succeeded) {
            return;
        }
        String triggerReason = reason != null ? reason : "unknown";
        if (!scheduled) {
            scheduleBackgroundProbe();
        }
        LOGGER.info("Triggering OpenCL probe due to: " + triggerReason);
        if ((triggerReason.startsWith("opengl-ready") || triggerReason.startsWith("renderer:"))
            && !rendererTriggered.compareAndSet(false, true)) {
            return;
        }
        scheduleProbe(Duration.ZERO, triggerReason);
    }

    public static synchronized void reset() {
        scheduled = false;
        succeeded = false;
        attemptCounter.set(0);
        remainingAttempts.set(0);
        rendererTriggered.set(false);
    }

    private static void scheduleProbe(Duration delay, String trigger) {
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            markDisabled();
            return;
        }
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
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            markDisabled();
            return;
        }
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

    private static void handleProbeResult(String trigger, int attemptNo, Boolean ok, Throwable err) {
        if (succeeded) {
            return;
        }
        if (err != null) {
            LOGGER.log(Level.WARNING, "OpenCL probe attempt #" + attemptNo + " (" + trigger + ") failed", err);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Probe attempt " + attemptNo + " failed (" + trigger + ") - " + err.getMessage());
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

    private static void markDisabled() {
        LOGGER.info("Skipping OpenCL probe because enableGpuAcceleration=false");
        DeveloperOverlayManager.recordApiLog("[OpenCL] Probe skipped - GPU acceleration disabled in config");
    }

    private static void scheduleRetry(String reason) {
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            markDisabled();
            return;
        }
        if (succeeded) {
            return;
        }
        scheduleProbe(RETRY_DELAY, "retry:" + reason);
    }
}
