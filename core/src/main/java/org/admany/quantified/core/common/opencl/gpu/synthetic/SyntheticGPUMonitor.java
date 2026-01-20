package org.admany.quantified.core.common.opencl.gpu.synthetic;

import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;
import org.admany.quantified.core.common.opencl.gpu.memory.GPUMemoryManager;
import org.admany.quantified.core.common.opencl.gpu.task.GPUTaskTracker;
import org.admany.quantified.core.common.opencl.gpu.thermal.ThermalManager;
import org.admany.quantified.core.common.telemetry.TelemetryService;

import java.time.Instant;

public final class SyntheticGPUMonitor {

    private static final double BASE_MEMORY_FOOTPRINT = 0.0d;
    private static final double BASE_TEMPERATURE = 40.0d;
    private static final double MAX_TEMPERATURE_C = 90.0d;

    private volatile double smoothedMemory = BASE_MEMORY_FOOTPRINT;
    private volatile double smoothedCompute = 0.0d;
    private volatile double smoothedTemperature = BASE_TEMPERATURE;

    private final GPUMemoryManager memoryManager;
    private final GPUTaskTracker taskTracker;
    private final ThermalManager thermalManager;

    public SyntheticGPUMonitor(GPUMemoryManager memoryManager, GPUTaskTracker taskTracker, ThermalManager thermalManager) {
        this.memoryManager = memoryManager;
        this.taskTracker = taskTracker;
        this.thermalManager = thermalManager;
    }

    public void clearSyntheticState() {
        smoothedMemory = BASE_MEMORY_FOOTPRINT;
        smoothedCompute = 0.0d;
        smoothedTemperature = BASE_TEMPERATURE;
    }

    public GPUMonitor.GPUStatus generateSyntheticStatus(int totalComputeUnits, String deviceName) {
        long nowMs = System.currentTimeMillis();

        int activeCompute = totalComputeUnits > 0
            ? (int) clamp(taskTracker.getActiveComputeUnits() / (double) totalComputeUnits, 0.0d, 1.2d)
            : 0;

        TelemetryService.SchedulerSnapshot scheduler = TelemetryService.getLatest();
        if (scheduler != null) {
            double queueFactor = clamp(scheduler.queueSize / 384.0d, 0.0d, 1.0d);
            double execFactor = clamp(scheduler.execRate / 180.0d, 0.0d, 1.0d);
            activeCompute = (int) clamp(activeCompute + queueFactor * 0.35d + execFactor * 0.25d, 0.0d, 1.2d);
        }
        smoothedCompute = smooth(smoothedCompute * 0.96d, activeCompute, 0.32d);

        long activeBytes = Math.max(0L, taskTracker.getActiveVramBytes());
        double activeMemory = memoryManager.getTotalVramBytes() > 0
            ? clamp(activeBytes / (double) memoryManager.getTotalVramBytes(), 0.0d, 0.95d)
            : 0.0d;
        double targetMemory = clamp(BASE_MEMORY_FOOTPRINT + activeMemory, BASE_MEMORY_FOOTPRINT, 0.97d);
        smoothedMemory = smooth(smoothedMemory, targetMemory, 0.28d);
        if (activeBytes == 0L) {
            smoothedMemory = clamp(smoothedMemory - 0.01d, BASE_MEMORY_FOOTPRINT, 0.97d);
        }

        int fallbackPulse = taskTracker.getFallbackHeat();
        double fallbackBoost = fallbackPulse * 1.6d;

        boolean recentlyActive = nowMs - taskTracker.getLastTaskActivityMs() < 3_500L;
        double tempTarget = clamp(
            BASE_TEMPERATURE + smoothedCompute * 32.0d + smoothedMemory * 18.0d + fallbackBoost,
            40.0d,
            MAX_TEMPERATURE_C
        );
        double alpha = recentlyActive ? 0.30d : 0.18d;
        smoothedTemperature = smooth(smoothedTemperature, tempTarget, alpha);
        if (!recentlyActive) {
            smoothedTemperature = clamp(smoothedTemperature - 0.18d, 38.0d, MAX_TEMPERATURE_C);
        }

        thermalManager.updateThermalLimiter(smoothedTemperature);

        long usedBytes = Math.min(memoryManager.getTotalVramBytes(), Math.max(0L, Math.round(smoothedMemory * memoryManager.getTotalVramBytes())));
        double computeUtil = clamp(smoothedCompute, 0.0d, 1.0d);
        double temperature = clamp(smoothedTemperature, 36.0d, MAX_TEMPERATURE_C);

        return new GPUMonitor.GPUStatus(
            Instant.now(),
            memoryManager.getTotalVramBytes(),
            usedBytes,
            totalComputeUnits,
            computeUtil,
            temperature,
            deviceName,
            clamp(smoothedMemory, 0.0d, 1.0d)
        );
    }

    private static double smooth(double current, double target, double alpha) {
        return current + ((target - current) * clamp(alpha, 0.0d, 1.0d));
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}