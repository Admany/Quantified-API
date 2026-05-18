package org.admany.quantified.core.common.dev;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.web.DeveloperDashboardServer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DeveloperFeatures {

    private static final Logger LOGGER = Logger.getLogger(DeveloperFeatures.class.getName());

    private static final AtomicBoolean developerMode = new AtomicBoolean(false);
    private static final AtomicBoolean timelineEnabled = new AtomicBoolean(false);
    private static final AtomicBoolean replayEnabled = new AtomicBoolean(false);
    private static final AtomicBoolean stressTestEnabled = new AtomicBoolean(false);
    private static final AtomicBoolean modSpotlightEnabled = new AtomicBoolean(false);
    private static final AtomicBoolean dashboardEnabled = new AtomicBoolean(false);

    private DeveloperFeatures() {
    }

    public static void initialiseFromConfig() {
        LOGGER.info("DeveloperFeatures.initialiseFromConfig: developerDashboard=" + MultithreadingConfig.CONFIG.developerDashboard +
            ", developerMode=" + MultithreadingConfig.CONFIG.developerMode);
        setDeveloperMode(MultithreadingConfig.CONFIG.developerMode, false);
        setTimelineEnabled(MultithreadingConfig.CONFIG.developerTimeline, false);
        setReplayEnabled(MultithreadingConfig.CONFIG.developerReplay, false);
        setStressTestEnabled(MultithreadingConfig.CONFIG.developerStressTest, false);
        setModSpotlightEnabled(MultithreadingConfig.CONFIG.developerModSpotlight, false);
        setDashboardEnabled(MultithreadingConfig.CONFIG.developerDashboard, false);
        setStressTestProfile(parseProfile(MultithreadingConfig.CONFIG.developerStressProfile), false);
    }

    public static boolean isDeveloperModeEnabled() {
        return developerMode.get();
    }

    public static boolean isTimelineEnabled() {
        return developerMode.get() && timelineEnabled.get();
    }

    public static boolean isReplayEnabled() {
        return developerMode.get() && replayEnabled.get();
    }

    public static boolean isAutoHintsEnabled() {
        return developerMode.get();
    }

    public static boolean isStressTestEnabled() {
        return developerMode.get() && stressTestEnabled.get();
    }

    public static StressTestController.StressTestProfile getStressTestProfile() {
        return StressTestController.getProfile();
    }

    public static boolean isModSpotlightEnabled() {
        return developerMode.get() && modSpotlightEnabled.get();
    }

    public static boolean isDashboardEnabled() {
        return dashboardEnabled.get();
    }

    public static boolean isOverlaySamplingActive() {
        return dashboardEnabled.get()
            || (developerMode.get() && (timelineEnabled.get() || replayEnabled.get() || modSpotlightEnabled.get()));
    }

    public static void setDeveloperMode(boolean enabled, boolean persist) {
        boolean changed = developerMode.getAndSet(enabled) != enabled;
        if (!enabled) {
            // Turning developer mode off disables experimental paths, but the webpanel can stay up
            // because it is also used as a normal runtime monitor during world load.
            timelineEnabled.set(false);
            replayEnabled.set(false);
            stressTestEnabled.set(false);
            modSpotlightEnabled.set(false);
            DeveloperOverlayManager.enableOverlay(false);
            DeveloperOverlayManager.setTimelineEnabled(false);
            DeveloperOverlayManager.setReplayEnabled(false);
            DeveloperOverlayManager.setAutoHintsEnabled(false);
            DeveloperOverlayManager.setModSpotlightEnabled(false);
            StressTestController.setEnabled(false);
            if (changed) {
                LOGGER.info("Developer mode disabled; experimental telemetry sampling is offline.");
            }
        } else if (changed) {
            LOGGER.info("Developer mode enabled; experimental telemetry toggles now available.");
        }

        if (persist) {
            MultithreadingConfig.CONFIG.developerMode = enabled;
            MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
        }

        applyOverlayState();
        applyStressTestState();
        applyAnalyticsState();
        applyDashboardState();
    }

    public static void setTimelineEnabled(boolean enabled, boolean persist) {
        timelineEnabled.set(enabled);
        if (persist) {
            MultithreadingConfig.CONFIG.developerTimeline = enabled;
            MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
        }
        applyAnalyticsState();
        applyOverlayState();
    }

    public static void setReplayEnabled(boolean enabled, boolean persist) {
        replayEnabled.set(enabled);
        if (persist) {
            MultithreadingConfig.CONFIG.developerReplay = enabled;
            MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
        }
        applyAnalyticsState();
        applyOverlayState();
    }

    public static void setStressTestEnabled(boolean enabled, boolean persist) {
        stressTestEnabled.set(enabled);
        if (persist) {
            MultithreadingConfig.CONFIG.developerStressTest = enabled;
            MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
        }
        applyStressTestState();
    }

    public static void setModSpotlightEnabled(boolean enabled, boolean persist) {
        modSpotlightEnabled.set(enabled);
        if (persist) {
            MultithreadingConfig.CONFIG.developerModSpotlight = enabled;
            MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
        }
        applyAnalyticsState();
        applyOverlayState();
    }

    public static void setStressTestProfile(StressTestController.StressTestProfile profile, boolean persist) {
        StressTestController.setProfile(profile);
        if (persist) {
            MultithreadingConfig.CONFIG.developerStressProfile = profile.configKey();
            MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
        }
    }

    private static void applyOverlayState() {
        boolean active = isOverlaySamplingActive();
        DeveloperOverlayManager.enableOverlay(active);
    }

    private static void applyAnalyticsState() {
        DeveloperOverlayManager.setTimelineEnabled(isTimelineEnabled());
        DeveloperOverlayManager.setReplayEnabled(isReplayEnabled());
        DeveloperOverlayManager.setAutoHintsEnabled(isAutoHintsEnabled());
        DeveloperOverlayManager.setModSpotlightEnabled(isModSpotlightEnabled());
    }

    private static void applyStressTestState() {
        boolean active = isStressTestEnabled();
        StressTestController.setEnabled(active);
    }

    private static void applyDashboardState() {
        DeveloperDashboardServer.applyConfiguration(isDashboardEnabled(), MultithreadingConfig.CONFIG.developerDashboardPort,
            MultithreadingConfig.CONFIG.developerDashboardHost, MultithreadingConfig.CONFIG.developerDashboardHttps,
            MultithreadingConfig.CONFIG.developerDashboardAuth);
        applyOverlayState();
    }

    public static void setDashboardEnabled(boolean enabled, boolean persist) {
        dashboardEnabled.set(enabled);
        if (persist) {
            MultithreadingConfig.CONFIG.developerDashboard = enabled;
            MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
        }
        applyDashboardState();
    }

    public static java.util.concurrent.CompletableFuture<Boolean> runOpenCLProbe() {
        if (!isDeveloperModeEnabled()) {
            java.util.concurrent.CompletableFuture<Boolean> f = new java.util.concurrent.CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Developer mode is not enabled"));
            return f;
        }
        return org.admany.quantified.core.common.opencl.core.OpenCLManager.forceProbe();
    }

    public static java.util.concurrent.CompletableFuture<Boolean> runVulkanProbe() {
        if (!isDeveloperModeEnabled()) {
            java.util.concurrent.CompletableFuture<Boolean> f = new java.util.concurrent.CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Developer mode is not enabled"));
            return f;
        }
        if (!org.admany.quantified.core.common.gpu.backend.VulkanRuntime.hasBindings()) {
            java.util.concurrent.CompletableFuture<Boolean> f = new java.util.concurrent.CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("LWJGL Vulkan classes are not present in this runtime"));
            return f;
        }
        return org.admany.quantified.core.common.vulkan.core.VulkanManager.forceProbe();
    }

    private static StressTestController.StressTestProfile parseProfile(String raw) {
        try {
            return StressTestController.StressTestProfile.fromConfigKey(raw);
        } catch (IllegalArgumentException ex) {
            LOGGER.log(Level.WARNING, "Invalid developer stress profile {0}, defaulting to CPU_HEAVY", raw);
            return StressTestController.StressTestProfile.CPU_HEAVY;
        }
    }
}
