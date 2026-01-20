package org.admany.quantified.core.common.threading.scaling;

import java.util.concurrent.atomic.AtomicInteger;

public final class DynamicThreadScaler {

    private final int maxForeground;
    private final int maxBackground;
    private final AtomicInteger smoothedForeground = new AtomicInteger();
    private final AtomicInteger smoothedBackground = new AtomicInteger();

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

        double cap = SystemLoadMonitor.maxCpuLoad();
        if (systemLoad > cap) {
            double reduction = (systemLoad - cap) * 2;
            scaled *= (1.0 - reduction);
        }

        int desired = (int) Math.round(Math.max(min, Math.min(max, scaled)));
        return desired;
    }
    public record ScalingProfile(int foregroundWorkers, int backgroundWorkers) {
    }
}
