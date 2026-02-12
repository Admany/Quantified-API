package org.admany.quantified.core.common.threading.scaling;

import java.util.concurrent.atomic.AtomicInteger;

public final class DynamicThreadScaler {

    private final int maxForeground;
    private final int maxBackground;
    private final AtomicInteger smoothedForeground = new AtomicInteger();
    private final AtomicInteger smoothedBackground = new AtomicInteger();
    private volatile double foregroundThrottlePenalty = 0.30d;
    private volatile double backgroundThrottlePenalty = 0.45d;
    private volatile double healthyLoadBoost = 1.10d;

    public DynamicThreadScaler(int maxForeground, int maxBackground, boolean smt) {
        this.maxForeground = Math.max(1, maxForeground);
        this.maxBackground = Math.max(1, maxBackground);
        smoothedForeground.set(this.maxForeground);
        smoothedBackground.set(this.maxBackground);
    }

    public ScalingProfile scale(int foregroundQueue,
                                int backgroundQueue,
                                double throttleLevel,
                                double systemLoad) {
        int minForeground = Math.max(1, maxForeground / 2);
        int minBackground = Math.max(1, maxBackground / 2);

        int desiredForeground = scaleWorkers(maxForeground, minForeground, foregroundQueue, throttleLevel, systemLoad, true);
        int desiredBackground = scaleWorkers(maxBackground, minBackground, backgroundQueue, throttleLevel, systemLoad, false);

        int smoothForeground = smooth(smoothedForeground, desiredForeground);
        int smoothBackground = smooth(smoothedBackground, desiredBackground);
        return new ScalingProfile(smoothForeground, smoothBackground);
    }

    private int smooth(AtomicInteger target, int desired) {
        return target.updateAndGet(previous -> (int) Math.round((previous * 3 + desired) / 4.0));
    }

    private int scaleWorkers(int max,
                             int min,
                             int queueDepth,
                             double throttleLevel,
                             double systemLoad,
                             boolean foreground) {
        double demand = queueDepth <= 0 ? 0.0 : Math.min(1.0, queueDepth / (double) (max * 2));
        double scaled = min + (max - min) * demand;

        // Auto-tune worker pressure from scheduler throttle signal.
        // High throttle means queues are being intentionally delayed under load.
        double throttle = Math.max(0.0, Math.min(1.0, throttleLevel));
        double throttlePenalty = foreground ? foregroundThrottlePenalty : backgroundThrottlePenalty;
        scaled *= (1.0 - (throttle * throttlePenalty));

        double cap = SystemLoadMonitor.maxCpuLoad();
        if (systemLoad > cap) {
            double reduction = Math.max(0.0, (systemLoad - cap) * 2.0);
            scaled *= (1.0 - reduction);
        } else if (systemLoad < cap * 0.75 && queueDepth > max) {
            // Under healthy CPU load with sustained queue pressure, allow a mild boost.
            scaled *= healthyLoadBoost;
        }

        int desired = (int) Math.round(Math.max(min, Math.min(max, scaled)));
        return desired;
    }

    public void applyRuntimeTuning(double foregroundThrottlePenalty,
                                   double backgroundThrottlePenalty,
                                   double healthyLoadBoost) {
        this.foregroundThrottlePenalty = clamp(foregroundThrottlePenalty, 0.10d, 0.60d);
        this.backgroundThrottlePenalty = clamp(backgroundThrottlePenalty, 0.15d, 0.75d);
        this.healthyLoadBoost = clamp(healthyLoadBoost, 0.90d, 1.35d);
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

    public record ScalingProfile(int foregroundWorkers, int backgroundWorkers) {
    }
}
