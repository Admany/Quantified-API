package org.admany.quantified.core.common.dev;

import org.admany.quantified.api.model.QuantifiedStats;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;
import org.admany.quantified.core.common.parallel.metrics.ParallelMetrics;
import org.admany.quantified.core.common.telemetry.TelemetryService;
import org.admany.quantified.core.common.threading.pool.ThreadPoolStats;
import org.admany.quantified.core.common.threading.scaling.SystemLoadMonitor;
import org.admany.quantified.core.common.opencl.gpu.probe.GpuTelemetryService;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DeveloperOverlayManager {

    private static final Logger LOGGER = Logger.getLogger(DeveloperOverlayManager.class.getName());

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "quantified-dev-overlay");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean overlayActive = new AtomicBoolean(false);
    private static final AtomicBoolean timelineActive = new AtomicBoolean(false);
    private static final AtomicBoolean replayActive = new AtomicBoolean(false);
    private static final AtomicBoolean autoHintsActive = new AtomicBoolean(true); // Always active
    private static final AtomicBoolean modSpotlightActive = new AtomicBoolean(false); // Disabled, card removed

    private static final AtomicReference<OverlaySnapshot> latestSnapshot =
        new AtomicReference<>(OverlaySnapshot.empty());
    private static final AtomicReference<List<AutoTuningHint>> latestHints =
        new AtomicReference<>(List.of());
    private static final AtomicReference<List<ModSpotlightEntry>> latestSpotlight =
        new AtomicReference<>(List.of());

    private static final Deque<TimelineEvent> timeline = new ArrayDeque<>();
    private static final Deque<String> apiLogs = new ArrayDeque<>();
    private static final Deque<OverlaySnapshot> replayBuffer = new ArrayDeque<>();
    private static final AtomicLong fallbackEventsTotal = new AtomicLong();
    private static final AtomicLong fallbackEventsRecent = new AtomicLong();
    private static final AtomicLong LAST_VALID_GPU_TEMP_BITS = new AtomicLong(Double.doubleToLongBits(Double.NaN));

    private static final int TIMELINE_CAPACITY = 180; // roughly three minutes at 1s cadence
    private static final int REPLAY_CAPACITY = 120;   // two minutes of snapshots
    private static final int API_LOG_CAPACITY = 16_384;  // number of recent API log lines to retain
    // Async writer for persisting logs
    private static final java.util.concurrent.ExecutorService LOG_WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "quantified-api-log-writer");
        t.setDaemon(true);
        return t;
    });
    private static final java.nio.file.Path API_LOG_PATH = java.nio.file.Paths.get(System.getProperty("user.dir"), "quantified-api.log");
    private static final long ALERT_COOLDOWN_MS = 7_500L;
    private static final long QUEUE_ALERT_THRESHOLD = 1024;
    private static final double GPU_ALERT_UTIL = 0.92;
    private static final double GPU_BUSY_UTIL = 0.97;
    private static final double GPU_WARM_TEMP = 82.0;
    private static final double GPU_ALERT_TEMP = 90.0;
    private static final long MOD_HEADROOM_TARGET = 64L;

    private static ScheduledFuture<?> samplingTask;
    private static volatile long lastQueueAlertMs = 0L;
    private static volatile GpuSafeguardState lastGpuSafeguardState = GpuSafeguardState.CLEAR;

    private DeveloperOverlayManager() {
    }

    /**
     * Enable or disable the live overlay sampler. When disabled all derived buffers are cleared.
     */
    public static synchronized void enableOverlay(boolean enable) {
        if (enable == overlayActive.get()) {
            return;
        }
        overlayActive.set(enable);
        if (enable) {
            if (samplingTask == null || samplingTask.isCancelled()) {
                samplingTask = EXECUTOR.scheduleAtFixedRate(DeveloperOverlayManager::sample, 0L, 1L, TimeUnit.SECONDS);
                LOGGER.info("Developer overlay sampler started");
            }
        } else {
            if (samplingTask != null) {
                samplingTask.cancel(false);
                samplingTask = null;
            }
            latestSnapshot.set(OverlaySnapshot.empty());
            latestHints.set(List.of());
            latestSpotlight.set(List.of());
            synchronized (timeline) {
                timeline.clear();
            }
            synchronized (replayBuffer) {
                replayBuffer.clear();
            }
            LOGGER.info("Developer overlay sampler stopped");
        }
    }

    public static void setTimelineEnabled(boolean enabled) {
        timelineActive.set(enabled);
    }

    public static void setReplayEnabled(boolean enabled) {
        replayActive.set(enabled);
        if (!enabled) {
            synchronized (replayBuffer) {
                replayBuffer.clear();
            }
        }
    }

    public static void setAutoHintsEnabled(boolean enabled) {
        autoHintsActive.set(enabled);
        if (!enabled) {
            latestHints.set(List.of());
        }
    }

    public static void setModSpotlightEnabled(boolean enabled) {
        modSpotlightActive.set(enabled);
        if (!enabled) {
            latestSpotlight.set(List.of());
        }
    }

    private static void sample() {
        if (!overlayActive.get()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            TelemetryService.SchedulerSnapshot schedulerSnapshot = TelemetryService.getLatest();
            ThreadPoolStats threadStats = AsyncManager.threadPoolStats();
            GPUMonitor.GPUStatus gpuStatus = OpenCLManager.getGPUStatus();

            int queueDepth = 0;
            int foregroundQueue = 0;
            int backgroundQueue = 0;
            int desiredFg = 0;
            int desiredBg = 0;
            double execRate = 0.0d;
            if (threadStats != null) {
                foregroundQueue = threadStats.foregroundQueue();
                backgroundQueue = threadStats.backgroundQueue();
                queueDepth = foregroundQueue + backgroundQueue;
                desiredFg = threadStats.desiredForegroundWorkers();
                desiredBg = threadStats.desiredBackgroundWorkers();
            }
            if (schedulerSnapshot != null) {
                execRate = schedulerSnapshot.execRate;
            }

            long parallelActive = 0L;
            try {
                ParallelMetrics.Snapshot parallelSnapshot = ParallelMetrics.snapshot();
                if (parallelSnapshot != null && parallelSnapshot.modActiveSlices() != null) {
                    parallelActive = parallelSnapshot.modActiveSlices().values().stream()
                        .mapToLong(Long::longValue)
                        .sum();
                }
            } catch (Throwable ignored) {
            }

            int parallelActiveInt = parallelActive >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) parallelActive;
            long combined = (long) queueDepth + parallelActive;
            int totalWork = combined >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) combined;
            if (DeveloperFeatures.isStressTestEnabled() && LOGGER.isLoggable(Level.FINE)) {
                String logMsg = "Stress test active: tasks=" + queueDepth + ", slices=" + parallelActiveInt
                    + ", fg=" + foregroundQueue + ", bg=" + backgroundQueue + ", execRate=" + execRate;
                LOGGER.fine(logMsg);
            }

            double gpuMemoryUtil = 0.0d;
            double gpuComputeUtil = 0.0d;
            double gpuTemperature = 0.0d;
            long gpuVramBudgetBytes = 0L;
            long gpuVramUsedBytes = 0L;
            double gpuSystemUsageRatio = 0.0d;
            boolean gpuStatusPresent = gpuStatus != null;
            if (gpuStatusPresent) {
                gpuMemoryUtil = gpuStatus.memoryUtilization();
                gpuComputeUtil = gpuStatus.computeUtilization();
                gpuTemperature = gpuStatus.temperatureC();
                gpuVramBudgetBytes = Math.max(0L, gpuStatus.totalVramBytes());
                gpuVramUsedBytes = Math.max(0L, gpuStatus.usedVramBytes());
                gpuSystemUsageRatio = Math.max(0.0d, gpuStatus.systemUsageRatio());
            }
            boolean gpuAvailable = gpuStatusPresent && OpenCLManager.isAvailable();
            // Fallbacks for temp and VRAM even if OpenCL not available
            String deviceName = gpuStatusPresent ? gpuStatus.deviceName() : "Unknown GPU";

            GpuTelemetryService.TelemetrySample telemetrySample = GpuTelemetryService.getInstance().latestSample();
            if (telemetrySample != null) {
                double telemetryTemp = telemetrySample.temperatureC();
                if (!Double.isNaN(telemetryTemp) && telemetryTemp > 0.0d) {
                    gpuTemperature = gpuTemperature > 0.0d ? Math.max(gpuTemperature, telemetryTemp) : telemetryTemp;
                }
                if ((deviceName == null || deviceName.isBlank()) && telemetrySample.deviceName() != null && !telemetrySample.deviceName().isBlank()) {
                    deviceName = telemetrySample.deviceName();
                }
                double telemetryUtil = telemetrySample.computeUtilization();
                if (gpuComputeUtil <= 0.0d && !Double.isNaN(telemetryUtil) && telemetryUtil > 0.0d) {
                    gpuComputeUtil = telemetryUtil;
                }
            }
            if (gpuVramBudgetBytes == 0L) {
                long totalVram = approximateSystemVram();
                if (totalVram > 0L) {
                    long estimatedBudget = Math.max(64L * 1024L * 1024L, totalVram / 4L);
                    gpuVramBudgetBytes = estimatedBudget;
                }
            }

            double lastValidTemp = Double.longBitsToDouble(LAST_VALID_GPU_TEMP_BITS.get());
            if (gpuTemperature > 0.0d) {
                LAST_VALID_GPU_TEMP_BITS.set(Double.doubleToLongBits(gpuTemperature));
            } else if (!Double.isNaN(lastValidTemp) && lastValidTemp > 0.0d) {
                gpuTemperature = lastValidTemp;
            } else {
                gpuTemperature = 0.0d;
            }

            double systemLoad = SystemLoadMonitor.currentSystemLoad();

            long totalFallbacks = fallbackEventsTotal.get();
            long recentFallbacks = fallbackEventsRecent.getAndSet(0L);
            long activeGpuTaskBytes = Math.max(0L, OpenCLManager.activeTaskVramBytes());
            int activeGpuComputeUnits = Math.max(0, OpenCLManager.activeTaskComputeUnits());
            boolean qapiGpuActive = activeGpuTaskBytes > 0L || activeGpuComputeUnits > 0 || recentFallbacks > 0L;

            if (timelineActive.get()) {
                maybeEmitAutomaticAlerts(now, totalWork, gpuMemoryUtil, gpuComputeUtil, gpuTemperature, qapiGpuActive);
            }

            List<TimelineEvent> timelineSnapshot = timelineActive.get() ? snapshotTimeline() : List.of();

            List<AutoTuningHint> hints = autoHintsActive.get()
                ? computeHints(totalWork, execRate, gpuMemoryUtil, gpuComputeUtil, gpuTemperature, recentFallbacks, desiredFg, desiredBg, systemLoad, gpuAvailable, qapiGpuActive)
                : List.of();
            latestHints.set(hints);

            List<ModSpotlightEntry> spotlight = modSpotlightActive.get()
                ? computeModSpotlight()
                : List.of();
            latestSpotlight.set(spotlight);

            OverlaySnapshot snapshot = new OverlaySnapshot(
                now,
                gpuAvailable,
                gpuMemoryUtil,
                gpuComputeUtil,
                gpuTemperature,
                gpuVramBudgetBytes,
                gpuVramUsedBytes,
                gpuSystemUsageRatio,
                deviceName,
                queueDepth,
                foregroundQueue,
                backgroundQueue,
                parallelActiveInt,
                totalWork,
                desiredFg,
                desiredBg,
                execRate,
                systemLoad,
                totalFallbacks,
                recentFallbacks,
                timelineSnapshot,
                hints,
                spotlight
            );
            latestSnapshot.set(snapshot);

            if (replayActive.get()) {
                appendReplayFrame(snapshot);
            }
        } catch (Throwable throwable) {
            LOGGER.log(Level.WARNING, "Developer overlay sampling failed", throwable);
        }
    }

    private static void maybeEmitAutomaticAlerts(long now,
                                                 int queueDepth,
                                                 double gpuMemoryUtil,
                                                 double gpuComputeUtil,
                                                 double gpuTemperature,
                                                 boolean qapiGpuActive) {
        if (queueDepth > QUEUE_ALERT_THRESHOLD && (now - lastQueueAlertMs) > ALERT_COOLDOWN_MS) {
            lastQueueAlertMs = now;
            recordTimelineEvent(new TimelineEvent(
                now,
                TimelineEventType.BACKPRESSURE,
                "Async queues exceeded " + QUEUE_ALERT_THRESHOLD,
                queueDepth,
                gpuMemoryUtil,
                gpuComputeUtil,
                gpuTemperature,
                null
            ));
        }
        if (!qapiGpuActive) {
            lastGpuSafeguardState = GpuSafeguardState.CLEAR;
            return;
        }
        GpuSafeguardState safeguardState = classifyGpuSafeguard(gpuMemoryUtil, gpuComputeUtil, gpuTemperature);
        GpuSafeguardState previousState = lastGpuSafeguardState;
        if (safeguardState == GpuSafeguardState.CLEAR) {
            lastGpuSafeguardState = GpuSafeguardState.CLEAR;
            return;
        }
        boolean stateChanged = safeguardState != previousState;
        boolean escalated = safeguardState.severity() > previousState.severity();
        if (stateChanged || escalated) {
            lastGpuSafeguardState = safeguardState;
            recordTimelineEvent(new TimelineEvent(
                now,
                TimelineEventType.GPU_SAFEGUARD,
                safeguardState.message(),
                queueDepth,
                gpuMemoryUtil,
                gpuComputeUtil,
                gpuTemperature,
                null
            ));
        }
    }

    private static GpuSafeguardState classifyGpuSafeguard(double gpuMemoryUtil,
                                                          double gpuComputeUtil,
                                                          double gpuTemperature) {
        if (gpuTemperature > GPU_ALERT_TEMP) {
            return GpuSafeguardState.THERMAL;
        }
        if (gpuMemoryUtil > GPU_ALERT_UTIL) {
            return GpuSafeguardState.VRAM_PRESSURE;
        }
        if (gpuComputeUtil > GPU_BUSY_UTIL && gpuTemperature >= GPU_WARM_TEMP) {
            return GpuSafeguardState.SATURATED;
        }
        return GpuSafeguardState.CLEAR;
    }

    private static List<TimelineEvent> snapshotTimeline() {
        synchronized (timeline) {
            if (timeline.isEmpty()) {
                return List.of();
            }
            return List.copyOf(timeline);
        }
    }

    private static void appendReplayFrame(OverlaySnapshot snapshot) {
        synchronized (replayBuffer) {
            if (replayBuffer.size() >= REPLAY_CAPACITY) {
                replayBuffer.pollFirst();
            }
            replayBuffer.addLast(snapshot);
        }
    }

    private static List<AutoTuningHint> computeHints(int queueDepth,
                                                     double execRate,
                                                     double gpuMemoryUtil,
                                                     double gpuComputeUtil,
                                                     double gpuTemperature,
                                                     long recentFallbacks,
                                                     int desiredFg,
                                                     int desiredBg,
                                                     double systemLoad,
                                                     boolean gpuAvailable,
                                                     boolean qapiGpuActive) {
        List<AutoTuningHint> hints = new ArrayList<>();
        if (queueDepth > 512) {
            hints.add(new AutoTuningHint(
                HintSeverity.WARNING,
                "Queue depth above 512 - increase worker threads or reduce submission volume."
            ));
        } else if (queueDepth > 256) {
            hints.add(new AutoTuningHint(
                HintSeverity.INFO,
                "Queue depth above 256 - investigate recent submission spikes or backlog sources."
            ));
        }

        if (execRate < 5.0 && queueDepth > 64) {
            hints.add(new AutoTuningHint(
                HintSeverity.WARNING,
                "Low execution rate despite queued work - workers may be saturated or blocked."
            ));
        }

        if (qapiGpuActive && (gpuMemoryUtil > 0.85 || gpuComputeUtil > 0.85 || gpuTemperature > GPU_ALERT_TEMP)) {
            hints.add(new AutoTuningHint(
                HintSeverity.WARNING,
                "GPU is under heavy load. Increased fallbacks to CPU are likely."
            ));
        }

        if (recentFallbacks > 0) {
            hints.add(new AutoTuningHint(
                HintSeverity.INFO,
                recentFallbacks + " GPU task(s) fell back to CPU in the last interval."
            ));
        }

        // Additional hints
        if (queueDepth == 0 && execRate > 0) {
            hints.add(new AutoTuningHint(
                HintSeverity.OK,
                "Queues are clear - system is processing tasks efficiently."
            ));
        }

        if (execRate > 50.0) {
            hints.add(new AutoTuningHint(
                HintSeverity.INFO,
                "High execution rate detected - async scheduler is highly active."
            ));
        }

        if (qapiGpuActive && gpuTemperature > 75.0 && gpuTemperature <= GPU_ALERT_TEMP && execRate == 0.0) {
            hints.add(new AutoTuningHint(
                HintSeverity.WARNING,
                "Tasks are queuing but not being processed - verify worker threads are active and not blocked by long operations or deadlocks."
            ));
        }

        if (systemLoad > 0.8) {
            hints.add(new AutoTuningHint(
                HintSeverity.WARNING,
                "System CPU load > 80%!"
            ));
        }

        if (desiredFg + desiredBg >= Runtime.getRuntime().availableProcessors()) {
            hints.add(new AutoTuningHint(
                HintSeverity.INFO,
                "Async workers are near the available CPU core count - profile and shorten CPU-heavy tasks or reduce worker concurrency."
            ));
        }

        // Suggest OpenCL acceleration for CPU-heavy workloads (but not when GPU test is active)
        boolean stressActive = DeveloperFeatures.isStressTestEnabled();
        StressTestController.StressTestProfile activeProfile = DeveloperFeatures.getStressTestProfile();
        boolean isGpuTestActive = stressActive && activeProfile == StressTestController.StressTestProfile.GPU_TEST;
        if (!isGpuTestActive && (stressActive || queueDepth > 100) && gpuAvailable && gpuComputeUtil < 0.5 && execRate < 20.0) {
            String reason = stressActive ? "Stress test active" : "High queue depth";
            hints.add(new AutoTuningHint(
                HintSeverity.INFO,
                reason + " with heavy CPU load - enable GPU (OpenCL) acceleration where supported to reduce CPU pressure."
            ));
        }

        if (hints.isEmpty()) {
            hints.add(new AutoTuningHint(HintSeverity.OK, "System stable - no tuning suggestions at the moment."));
        }
        return hints;
    }

    private static List<ModSpotlightEntry> computeModSpotlight() {
        try {
            QuantifiedStats.GlobalStats global = QuantifiedStats.getGlobalStats();
            if (global.modStats.isEmpty()) {
                return List.of();
            }
            return global.modStats.values().stream()
                .sorted((a, b) -> Long.compare(b.tasksInFlight(), a.tasksInFlight()))
                .limit(3)
                .map(mod -> new ModSpotlightEntry(
                    mod.modId,
                    mod.version,
                    mod.tasksInFlight(),
                    Math.max(0L, MOD_HEADROOM_TARGET - mod.tasksInFlight()),
                    mod.cacheHitRate()
                ))
                .toList();
        } catch (Throwable throwable) {
            LOGGER.log(Level.FINE, "Failed to compute mod spotlight", throwable);
            return List.of();
        }
    }

    /**
     * Record a timeline event that originates from other subsystems (e.g. GPU fallbacks).
     */
    public static void recordTimelineEvent(TimelineEvent event) {
        Objects.requireNonNull(event, "event");
        if (!overlayActive.get() || !timelineActive.get()) {
            return;
        }
        synchronized (timeline) {
            if (timeline.size() >= TIMELINE_CAPACITY) {
                timeline.pollFirst();
            }
            timeline.addLast(event);
        }
    }

    /**
     * Mark that a GPU fallback occurred so counters and timeline widgets stay accurate.
     */
    public static void recordFallbackEvent(String reason, String modId) {
        fallbackEventsTotal.incrementAndGet();
        fallbackEventsRecent.incrementAndGet();
        if (timelineActive.get()) {
            long now = System.currentTimeMillis();
            recordTimelineEvent(new TimelineEvent(
                now,
                TimelineEventType.GPU_FALLBACK,
                reason,
                0,
                0.0,
                0.0,
                0.0,
                modId
            ));
        }
    }

    /**
     * Record a short API log line for display in the web panel and persist to server log.
     */
    public static void recordApiLog(String line) {
        if (line == null) return;
        // Ensure every API log line is consistently prefixed with [Quantified]
        if (!line.startsWith("[Quantified]")) {
            line = "[Quantified] " + line;
        }
        synchronized (apiLogs) {
            if (apiLogs.size() >= API_LOG_CAPACITY) {
                apiLogs.pollFirst();
            }
            apiLogs.addLast(line);
        }
        // Also write to the mod logger so it appears in the Minecraft log files
        // Persist to disk asynchronously; errors are swallowed to avoid impacting server
        final String toWrite = line + System.lineSeparator();
        LOG_WRITER.submit(() -> {
            try {
                java.nio.file.Files.writeString(API_LOG_PATH, toWrite, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "Failed to persist API log line", t);
            }
        });
    }

    /**
     * Add a log line to the web panel display without logging to console or persisting to disk.
     */
    public static void addApiLog(String line) {
        if (line == null) return;
        if (!line.startsWith("[Quantified]")) {
            line = "[Quantified] " + line;
        }
        synchronized (apiLogs) {
            if (apiLogs.size() >= API_LOG_CAPACITY) {
                apiLogs.pollFirst();
            }
            apiLogs.addLast(line);
        }
    }

    public static OverlaySnapshot latestSnapshot() {
        return latestSnapshot.get();
    }

    public static List<TimelineEvent> timelineEvents() {
        if (!timelineActive.get()) {
            return List.of();
        }
        return snapshotTimeline();
    }

    public static List<String> apiLogLines() {
        synchronized (apiLogs) {
            if (apiLogs.isEmpty()) {
                return List.of();
            }
            return List.copyOf(apiLogs);
        }
    }

    public static List<OverlaySnapshot> replayFrames() {
        if (!replayActive.get()) {
            return List.of();
        }
        synchronized (replayBuffer) {
            if (replayBuffer.isEmpty()) {
                return List.of();
            }
            return List.copyOf(replayBuffer);
        }
    }

    public static List<AutoTuningHint> autoTuningHints() {
        return latestHints.get();
    }

    public static List<ModSpotlightEntry> modSpotlight() {
        return latestSpotlight.get();
    }

    public static long fallbackEventsTotal() {
        return fallbackEventsTotal.get();
    }

    // ----- immutable data carriers -----

    public record OverlaySnapshot(long timestamp,
                                  boolean gpuAvailable,
                                  double gpuMemoryUtil,
                                  double gpuComputeUtil,
                                  double gpuTemperature,
                                  long gpuVramBudgetBytes,
                                  long gpuVramUsedBytes,
                                  double gpuSystemUsageRatio,
                                  String deviceName,
                                  int queueDepth,
                                  int foregroundQueue,
                                  int backgroundQueue,
                                  int parallelActiveSlices,
                                  int totalWork,
                                  int desiredForegroundWorkers,
                                  int desiredBackgroundWorkers,
                                  double schedulerExecRate,
                                  double cpuSystemLoad,
                                  long fallbackTotal,
                                  long fallbackRecent,
                                  List<TimelineEvent> timeline,
                                  List<AutoTuningHint> hints,
                                  List<ModSpotlightEntry> spotlight) {
        public static OverlaySnapshot empty() {
            return new OverlaySnapshot(
                System.currentTimeMillis(),
                false,
                0.0d,
                0.0d,
                0.0d,
                0L,
                0L,
                0.0d,
                "Unknown GPU",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0.0d,
                0.0d,
                0L,
                0L,
                List.of(),
                List.of(),
                List.of()
            );
        }
    }

    public record TimelineEvent(long timestamp,
                                TimelineEventType type,
                                String message,
                                int queueDepth,
                                double gpuMemoryUtil,
                                double gpuComputeUtil,
                                double gpuTemperature,
                                String modId) {
    }

    public enum TimelineEventType {
        GPU_FALLBACK,
        GPU_SAFEGUARD,
        BACKPRESSURE,
        STRESS_TEST,
        CUSTOM
    }

    public enum HintSeverity {
        OK,
        INFO,
        WARNING
    }

    private enum GpuSafeguardState {
        CLEAR(0, ""),
        SATURATED(1, "GPU is heavily saturated and warming up"),
        VRAM_PRESSURE(2, "GPU VRAM pressure is high"),
        THERMAL(3, "GPU temperature exceeds 90C");

        private final int severity;
        private final String message;

        GpuSafeguardState(int severity, String message) {
            this.severity = severity;
            this.message = message;
        }

        public int severity() {
            return severity;
        }

        public String message() {
            return message;
        }
    }

    public record AutoTuningHint(HintSeverity severity, String message) {
    }

    public record ModSpotlightEntry(String modId,
                                    String version,
                                    long tasksInFlight,
                                    long estimatedHeadroom,
                                    double cacheHitRate) {
    }

    /**
     * Expose a safe, read-only view for external diagnostics APIs.
     */
    public static DeveloperDiagnosticsView diagnosticsView() {
        return new DeveloperDiagnosticsView(latestSnapshot.get(), autoTuningHints(), modSpotlight(), timelineEvents(), replayFrames());
    }

    public record DeveloperDiagnosticsView(OverlaySnapshot snapshot,
                                           List<AutoTuningHint> hints,
                                           List<ModSpotlightEntry> spotlight,
                                           List<TimelineEvent> timeline,
                                           List<OverlaySnapshot> replayFrames) {
    }

    private static long approximateSystemVram() {
        try {
            SystemInfo info = new SystemInfo();
            List<GraphicsCard> cards = info.getHardware().getGraphicsCards();
            if (cards != null) {
                for (GraphicsCard card : cards) {
                    if (card != null && card.getVRam() > 0L) {
                        return card.getVRam();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

}


