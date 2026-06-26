package org.admany.quantified.core.common.opencl.gpu;

import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.opencl.core.OpenCLRuntime;
import org.admany.quantified.core.common.opencl.gpu.memory.GPUMemoryManager;
import org.admany.quantified.core.common.opencl.gpu.probe.GpuTelemetryService;
import org.admany.quantified.core.common.opencl.gpu.probe.HardwareProbeService;
import org.admany.quantified.core.common.opencl.gpu.synthetic.SyntheticGPUMonitor;
import org.admany.quantified.core.common.opencl.gpu.task.GPUTaskTracker;
import org.admany.quantified.core.common.opencl.gpu.thermal.ThermalManager;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class GPUMonitor {

	private static final java.util.logging.Logger JUL_LOGGER = java.util.logging.Logger.getLogger(GPUMonitor.class.getName());
    private static final Logger LOG4J_LOGGER = LogManager.getLogger(GPUMonitor.class);
    private static final Handler BRIDGE = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) return;
            String msg = record.getMessage();
            Throwable thrown = record.getThrown();
            Level lvl = record.getLevel();
            if (lvl.intValue() >= Level.SEVERE.intValue()) {
                LOG4J_LOGGER.error(msg, thrown);
            } else if (lvl.intValue() >= Level.WARNING.intValue()) {
                LOG4J_LOGGER.warn(msg, thrown);
            } else if (lvl.intValue() >= Level.INFO.intValue()) {
                LOG4J_LOGGER.info(msg, thrown);
            } else {
                LOG4J_LOGGER.debug(msg, thrown);
            }
        }
        @Override public void flush() {}
        @Override public void close() {}
    };
    private static final java.util.logging.Logger LOGGER = JUL_LOGGER;
    static {
        JUL_LOGGER.setUseParentHandlers(false);
        for (Handler h : JUL_LOGGER.getHandlers()) {
            JUL_LOGGER.removeHandler(h);
        }
        JUL_LOGGER.addHandler(BRIDGE);
        JUL_LOGGER.setLevel(Level.ALL);
    }

    private static final long MONITORING_INTERVAL_MS = 1_000L;
    private static final double MEMORY_ACCEPTANCE_LIMIT = 0.95d;
    private static final double COMPUTE_ACCEPTANCE_LIMIT = 0.99d;
    private static final long MIN_SAFETY_BYTES = 64L * 1024L * 1024L;

	private final AtomicReference<GPUStatus> lastStatus = new AtomicReference<>();
	private final AtomicBoolean gpuStatusLogged = new AtomicBoolean();
    private volatile double lastLoggedTemp = Double.NaN;
    private volatile double lastLoggedUtil = Double.NaN;
    private volatile double lastLoggedSystemUsage = Double.NaN;
    private volatile long lastLoggedVramUsed = -1L;

	private volatile boolean monitoring;
	private Thread monitorThread;

    private volatile int totalComputeUnits = 64;
    private volatile String deviceName = "Unknown GPU";

	private GPUMonitor() {}

	private static final class Holder {
		static final GPUMonitor INSTANCE = new GPUMonitor();
	}

	public static GPUMonitor getInstance() {
		return Holder.INSTANCE;
	}

    public long estimatedActiveVramBytes() {
        return getInstance().taskTracker.estimatedActiveVramBytes();
    }

    public int activeComputeUnits() {
        return getInstance().taskTracker.getActiveComputeUnits();
    }

    public synchronized void configure(long detectedVramBytes, int detectedComputeUnits, String deviceName) {
        memoryManager.configureVram(detectedVramBytes);
        if (detectedComputeUnits > 0) {
            this.totalComputeUnits = detectedComputeUnits;
        }
        String systemName = HardwareProbeService.getGPUNameFromSystem();
        this.deviceName = !systemName.equals("Unknown GPU") ? systemName : (deviceName != null && !deviceName.isBlank() ? deviceName : "Unknown GPU");
        GpuTelemetryService.getInstance().setPreferredDeviceName(this.deviceName);
        syntheticMonitor.clearSyntheticState();
        taskTracker.clearTracking();
    }

	public TaskSample beginTask(long estimatedVramBytes, int estimatedComputeUnits) {
		return taskTracker.beginTask(estimatedVramBytes, estimatedComputeUnits);
	}

	public void endTask(TaskSample sample) {
		taskTracker.endTask(sample);
	}

	public void recordFallback() {
		taskTracker.recordFallback();
	}

	public void start() {
		if (monitoring) {
			return;
		}
		monitoring = true;
		monitorThread = new Thread(this::monitorLoop, "gpu-monitor");
		monitorThread.setDaemon(true);
		monitorThread.start();
		LOGGER.info("GPU monitoring synthesiser started");
	}

	public void stop() {
		monitoring = false;
		if (monitorThread != null) {
			monitorThread.interrupt();
			try {
				monitorThread.join(1_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			monitorThread = null;
		}
		taskTracker.clearTracking();
		lastStatus.set(null);
		LOGGER.info("GPU monitoring synthesiser stopped");
	}

	public synchronized void clearMemoryTracking() {
		taskTracker.clearTracking();
		syntheticMonitor.clearSyntheticState();
		memoryManager.resetBaseline();
		DeveloperOverlayManager.recordApiLog("[OpenCL] GPU memory tracking forcibly cleared");
	}

	public boolean canAcceptTask(long estimatedVramBytes, int estimatedComputeUnits) {
        GPUStatus status = lastStatus.get();
        if (status == null) {
            return true;
        }

        long totalBytes = Math.max(0L, status.totalVramBytes());
        long usedBytes = Math.max(0L, status.usedVramBytes());
        long availableVram = Math.max(0L, totalBytes - usedBytes);
        long safetyHeadroom = Math.max(MIN_SAFETY_BYTES, totalBytes / 12L);
        long adjustedEstimate = Math.max(estimatedVramBytes, MIN_SAFETY_BYTES / 2);
        long requiredHeadroom = Math.max(safetyHeadroom, adjustedEstimate / 2L);

        if (availableVram <= 0L) {
            return false;
        }

        double memoryUtil = status.memoryUtilization();
        if (availableVram < requiredHeadroom) {
            if (memoryUtil >= 0.995d) {
                return false;
            }
            if (availableVram + requiredHeadroom < adjustedEstimate) {
                return false;
            }
        }

        if (memoryUtil > MEMORY_ACCEPTANCE_LIMIT && availableVram < safetyHeadroom) {
            return false;
        }

        double remainingCompute = Math.max(0.0d, 1.0d - status.computeUtilization());
        double requestedCompute = estimatedComputeUnits / (double) Math.max(1, status.totalComputeUnits());
        if (remainingCompute < requestedCompute * 0.9d) {
            if (status.computeUtilization() >= 0.995d) {
                return false;
            }
        }

        if (status.temperatureC() > 95.0d) {
            return false;
        }

        if (status.computeUtilization() > COMPUTE_ACCEPTANCE_LIMIT && remainingCompute < 0.05d) {
            return false;
        }

        return true;
	}

	public GPUStatus getStatus() {
		GPUStatus status = lastStatus.get();
		if (status == null) {
            return new GPUStatus(
                Instant.now(),
                memoryManager.getTotalVramBytes(),
                0,
                totalComputeUnits,
                0.0,
                40.0d,
                deviceName,
                0.0d
            );
		}
		return status;
	}

    public boolean isThermallyLimited() {
        return thermalManager.isThermallyLimited();
    }

    public synchronized void refreshNow() {
        try {
            GPUStatus status = queryGPUStatus();
            lastStatus.set(status);
        } catch (Throwable t) {
            DeveloperOverlayManager.recordApiLog("[OpenCL] Synchronous GPU refresh failed: " + t.getMessage());
        }
    }

	public synchronized void updateDeviceName(String newName) {
		if (newName != null && !newName.isBlank()) {
			this.deviceName = newName;
		}
	}

	private void monitorLoop() {
		while (monitoring && !Thread.currentThread().isInterrupted()) {
			try {
				GPUStatus status = queryGPUStatus();
				lastStatus.set(status);
				Thread.sleep(MONITORING_INTERVAL_MS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception exception) {
				try {
					Thread.sleep(MONITORING_INTERVAL_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
	}

    private GPUStatus queryGPUStatus() {
        OpenCLRuntime.snapshot();

        GpuTelemetryService telemetryService = GpuTelemetryService.getInstance();
        GpuTelemetryService.TelemetrySample telemetrySample = telemetryService.latestSample();
        double telemetryTemp = telemetrySample != null ? telemetrySample.temperatureC() : Double.NaN;
        double resolvedTemp = Double.NaN;
        if (!Double.isNaN(telemetryTemp) && telemetryTemp > 0.0d) {
            resolvedTemp = telemetryTemp;
        }
        if (!Double.isNaN(resolvedTemp) && resolvedTemp > 0.0d) {
            thermalManager.updateThermalLimiter(resolvedTemp);
        }

        if (telemetrySample == null) {
            return syntheticMonitor.generateSyntheticStatus(totalComputeUnits, deviceName);
        }

        String reportedName = (telemetrySample.deviceName() != null && !telemetrySample.deviceName().isBlank())
            ? telemetrySample.deviceName()
            : deviceName;

        long totalMemoryBytes = telemetrySample.totalMemoryBytes() > 0L
            ? telemetrySample.totalMemoryBytes()
            : memoryManager.getConfiguredVramBytes();
        if (totalMemoryBytes > 0L) {
            long configured = memoryManager.getConfiguredVramBytes();
            long tolerance = Math.max(32L * 1024L * 1024L, configured > 0L ? configured / 10L : 0L);
            if (configured <= 0L || Math.abs(totalMemoryBytes - configured) > tolerance) {
                memoryManager.configureVram(totalMemoryBytes);
            }
        }

        long configuredTotal = memoryManager.getConfiguredVramBytes() > 0L
            ? memoryManager.getConfiguredVramBytes()
            : totalMemoryBytes;
        long usedBytes = telemetrySample.usedMemoryBytes();
        long activeVramBytes = Math.max(0L, taskTracker.getActiveVramBytes());
        if (usedBytes <= 0L && activeVramBytes > 0L) {
            // Telemetry can report 0 on some systems; fall back to task estimates.
            usedBytes = activeVramBytes;
        }
        if (configuredTotal > 0L) {
            usedBytes = Math.max(0L, Math.min(usedBytes, configuredTotal));
        } else {
            usedBytes = Math.max(0L, usedBytes);
        }

        memoryManager.updateBaseline(usedBytes);
        long apiUsageBytes = memoryManager.getAdjustedUsed(usedBytes);
        GPUMemoryManager.BudgetSnapshot budget = memoryManager.evaluate(usedBytes);
        long apiBudgetBytes = Math.max(1L, budget.apiBudgetBytes());
        long cappedUsage = Math.min(apiUsageBytes, apiBudgetBytes);

        double computeUtil = telemetrySample.computeUtilization();
        int activeCompute = Math.max(0, taskTracker.getActiveComputeUnits());
        if ((Double.isNaN(computeUtil) || computeUtil <= 0.0d) && activeCompute > 0) {
            computeUtil = Math.min(1.0d, activeCompute / (double) Math.max(1, totalComputeUnits));
        }
        if (Double.isNaN(computeUtil) || computeUtil < 0.0d) {
            computeUtil = Math.min(1.0d, Math.max(0.0d,
                taskTracker.getActiveComputeUnits() / (double) Math.max(1, totalComputeUnits)));
        }

        double finalTemperature = !Double.isNaN(resolvedTemp) ? resolvedTemp : 0.0d;

        GPUStatus status = new GPUStatus(
            Instant.now(),
            apiBudgetBytes,
            cappedUsage,
            totalComputeUnits,
            computeUtil,
            finalTemperature,
            reportedName,
            budget.systemUsageRatio()
        );

        boolean shouldLog = !gpuStatusLogged.get() && (Double.isNaN(lastLoggedTemp)
            || Math.abs(finalTemperature - lastLoggedTemp) >= 0.5
            || Double.isNaN(lastLoggedUtil)
            || Math.abs((computeUtil * 100.0) - lastLoggedUtil) >= 1.0
            || Math.abs(cappedUsage - lastLoggedVramUsed) >= 50L * 1024L * 1024L
            || Double.isNaN(lastLoggedSystemUsage)
            || Math.abs((budget.systemUsageRatio() * 100.0) - lastLoggedSystemUsage) >= 2.0);

        if (shouldLog) {
            DeveloperOverlayManager.recordApiLog(String.format(
                "[OpenCL] GPU - Temp: %.1fC | Util: %.0f%% | VRAM cache: %d/%d MB (system %.0f%% used)",
                finalTemperature,
                computeUtil * 100,
                Math.max(0L, cappedUsage) / (1024 * 1024),
                Math.max(1L, apiBudgetBytes) / (1024 * 1024),
                budget.systemUsageRatio() * 100.0));
            lastLoggedTemp = finalTemperature;
            lastLoggedUtil = computeUtil * 100.0;
            lastLoggedVramUsed = cappedUsage;
            lastLoggedSystemUsage = budget.systemUsageRatio() * 100.0;
            gpuStatusLogged.set(true);
        }
        return status;
    }

    private final GPUMemoryManager memoryManager = new GPUMemoryManager();
    private final GPUTaskTracker taskTracker = new GPUTaskTracker();
    private final ThermalManager thermalManager = new ThermalManager();
    private final SyntheticGPUMonitor syntheticMonitor = new SyntheticGPUMonitor(memoryManager, taskTracker, thermalManager);

	public record TaskSample(long estimatedVramBytes, int estimatedComputeUnits, long startedNanos) {}

    public record GPUStatus(
        Instant timestamp,
        long totalVramBytes,
        long usedVramBytes,
        int totalComputeUnits,
        double computeUtilization,
        double temperatureC,
        String deviceName,
        double systemUsageRatio
    ) {
        public double memoryUtilization() {
            return totalVramBytes == 0 ? 0.0d : (double) usedVramBytes / totalVramBytes;
        }
    }
}

