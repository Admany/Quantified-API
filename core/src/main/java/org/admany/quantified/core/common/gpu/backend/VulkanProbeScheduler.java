package org.admany.quantified.core.common.gpu.backend;

import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.vulkan.core.VulkanManager;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VulkanProbeScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanProbeScheduler.class);
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        return LwjglRuntimeTuning.newDaemonThread(r, "Quantified-Vulkan-Probe-Scheduler",
            LwjglRuntimeTuning.gpuThreadStackSizeKb());
    });

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_DELAY = Duration.ZERO;
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private static final AtomicInteger remainingAttempts = new AtomicInteger(0);
    private static final AtomicInteger attemptCounter = new AtomicInteger(0);
    private static final AtomicBoolean rendererTriggered = new AtomicBoolean(false);
    private static final AtomicBoolean worldTriggered = new AtomicBoolean(false);
    private static final AtomicBoolean inlineProbeRunning = new AtomicBoolean(false);

    private static volatile boolean scheduled = false;
    private static volatile boolean succeeded = false;
    private static volatile boolean unavailableLogged = false;

    private VulkanProbeScheduler() {
    }

    public static synchronized void scheduleBackgroundProbe() {
        if (!VulkanRuntime.hasBindings()) {
            logBindingsUnavailable();
            return;
        }
        if (scheduled) {
            LOGGER.debug("Vulkan background probe already scheduled");
            return;
        }
        scheduled = true;
        succeeded = false;
        attemptCounter.set(0);
        remainingAttempts.set(MAX_ATTEMPTS);
        rendererTriggered.set(false);
        worldTriggered.set(false);
        VulkanManager.notePending("Background probe pending");
        String armedMessage = "Vulkan probe scheduler armed (maxAttempts=" + MAX_ATTEMPTS
            + ", initialDelayMs=" + INITIAL_DELAY.toMillis()
            + ", retryDelayMs=" + RETRY_DELAY.toMillis() + ")";
        LOGGER.info(armedMessage);
        DeveloperOverlayManager.recordApiLog("[Vulkan] " + armedMessage);
        scheduleProbe(INITIAL_DELAY, "initial");
    }

    public static void triggerRendererProbe(String renderer) {
        String suffix = renderer != null && !renderer.isBlank() ? renderer : "unknown";
        if (!rendererTriggered.compareAndSet(false, true)) {
            return;
        }
        triggerInlineProbe("renderer:" + suffix);
    }

    public static void triggerWorldProbe(String worldId) {
        String suffix = worldId != null && !worldId.isBlank() ? worldId : "unknown";
        if (!worldTriggered.compareAndSet(false, true)) {
            return;
        }
        triggerInlineProbe("world:" + suffix);
    }

    public static void triggerInlineProbe(String reason) {
        if (!VulkanRuntime.hasBindings()) {
            logBindingsUnavailable();
            return;
        }
        if (succeeded) {
            return;
        }
        String triggerReason = reason != null ? reason : "unknown";
        if (!scheduled) {
            scheduleBackgroundProbe();
        }
        if (!inlineProbeRunning.compareAndSet(false, true)) {
            LOGGER.debug("Skipping inline Vulkan probe; one is already running");
            return;
        }
        // Do NOT call forceProbeSynchronous() on the calling thread — vkCreateInstance in some
        // drivers (especially NVIDIA) consumes several MB of stack, which overflows the render
        // thread's default ~1 MB stack. Dispatch to PROBE_EXECUTOR which has a large stack
        // (gpuThreadStackSizeKb = 64 MB by default) and handle the result asynchronously.
        LOGGER.info("Triggering Vulkan probe inline due to: " + triggerReason);
        DeveloperOverlayManager.recordApiLog("[Vulkan] Probe running inline (" + triggerReason + ")");
        VulkanManager.notePending("Inline probe (" + triggerReason + ")");
        VulkanManager.forceProbe().whenComplete((ok, err) -> {
            try {
                if (err != null) {
                    LOGGER.warn("Inline Vulkan probe failed (" + triggerReason + ")", err);
                    DeveloperOverlayManager.recordApiLog("[Vulkan] Inline probe failed (" + triggerReason + ") - " + err.getMessage());
                } else if (Boolean.TRUE.equals(ok)) {
                    succeeded = true;
                    String deviceName = VulkanManager.deviceName();
                    LOGGER.info("Inline Vulkan probe succeeded for " + deviceName + " (" + triggerReason + ")");
                    DeveloperOverlayManager.recordApiLog("[Vulkan] Probe succeeded - " + deviceName + " (inline trigger: " + triggerReason + ")");
                } else {
                    String detail = VulkanManager.runtimeStatus().failureReason();
                    DeveloperOverlayManager.recordApiLog("[Vulkan] Inline probe unavailable (" + triggerReason + ") - "
                        + (detail != null && !detail.isBlank() ? detail : "GPU acceleration disabled"));
                }
            } finally {
                inlineProbeRunning.set(false);
            }
        });
    }

    public static void triggerProbe(String reason) {
        if (!VulkanRuntime.hasBindings()) {
            logBindingsUnavailable();
            return;
        }
        if (succeeded) {
            return;
        }
        String triggerReason = reason != null ? reason : "unknown";
        if (!scheduled) {
            scheduleBackgroundProbe();
        }
        LOGGER.info("Triggering Vulkan probe due to: " + triggerReason);
        VulkanManager.notePending("Probe queued (" + triggerReason + ")");
        scheduleProbe(Duration.ZERO, triggerReason);
    }

    public static synchronized void reset() {
        scheduled = false;
        succeeded = false;
        unavailableLogged = false;
        attemptCounter.set(0);
        remainingAttempts.set(0);
        rendererTriggered.set(false);
        worldTriggered.set(false);
    }

    private static void scheduleProbe(Duration delay, String trigger) {
        if (succeeded) {
            return;
        }
        if (VulkanManager.isProbeRunning()) {
            LOGGER.debug("Skipping queued Vulkan probe because one is already running");
            return;
        }
        long delayMs = Math.max(0L, delay.toMillis());
        int current;
        do {
            current = remainingAttempts.get();
            if (current <= 0) {
                String message = "Vulkan probe attempts exhausted; giving up after " + MAX_ATTEMPTS + " tries";
                LOGGER.warn(message);
                DeveloperOverlayManager.recordApiLog("[Vulkan] " + message);
                return;
            }
        } while (!remainingAttempts.compareAndSet(current, current - 1));

        int attemptNo = attemptCounter.incrementAndGet();
        LOGGER.info("Scheduling Vulkan probe attempt #" + attemptNo + " (" + trigger + ") in " + delayMs + " ms");
        DeveloperOverlayManager.recordApiLog("[Vulkan] Probe scheduled - attempt " + attemptNo + " (" + trigger + ") in " + delayMs + " ms");
        SCHEDULER.schedule(() -> runProbe(trigger, attemptNo), delayMs, TimeUnit.MILLISECONDS);
    }

    private static void runProbe(String trigger, int attemptNo) {
        if (succeeded) {
            return;
        }
        LOGGER.info("Running Vulkan probe attempt #" + attemptNo + " (" + trigger + ")");
        // Delegate to an ephemeral large-stack thread via forceProbe() so the SCHEDULER
        // thread is never the one calling vkCreateInstance.
        VulkanManager.forceProbe().whenComplete((ok, err) ->
            handleProbeResult(trigger, attemptNo, ok != null && ok, err));
    }

    private static void handleProbeResult(String trigger, int attemptNo, boolean ok, Throwable err) {
        if (succeeded) {
            return;
        }
        if (err != null) {
            LOGGER.warn("Vulkan probe attempt #" + attemptNo + " (" + trigger + ") failed", err);
            DeveloperOverlayManager.recordApiLog("[Vulkan] Probe attempt " + attemptNo + " failed (" + trigger + ") - " + err.getMessage());
            scheduleRetry("exception");
            return;
        }
        if (ok) {
            succeeded = true;
            String deviceName = VulkanManager.deviceName();
            LOGGER.info("Vulkan probe succeeded on attempt #" + attemptNo + " (" + trigger + ") for " + deviceName);
            DeveloperOverlayManager.recordApiLog("[Vulkan] Probe succeeded - " + deviceName + " (attempt " + attemptNo + ", trigger: " + trigger + ")");
            return;
        }
        String reason = VulkanManager.runtimeStatus().failureReason();
        String detail = reason != null && !reason.isBlank() ? reason : "GPU acceleration disabled";
        DeveloperOverlayManager.recordApiLog("[Vulkan] Probe unavailable - attempt " + attemptNo + " (" + trigger + ") - " + detail);
        scheduleRetry("unavailable");
    }

    private static void scheduleRetry(String reason) {
        if (succeeded) {
            return;
        }
        scheduleProbe(RETRY_DELAY, "retry:" + reason);
    }

    private static void logBindingsUnavailable() {
        if (unavailableLogged) {
            return;
        }
        unavailableLogged = true;
        String message = "Vulkan backend unavailable - LWJGL Vulkan classes are not present in this runtime";
        LOGGER.info(message);
        DeveloperOverlayManager.recordApiLog("[Vulkan] " + message);
    }
}
