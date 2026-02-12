package org.admany.quantified.core.common.async.core;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class AutoBatchController {

    private static final double EMA_ALPHA = 0.15d;
    private static final long DEFAULT_FG_TARGET_NANOS = TimeUnit.MICROSECONDS.toNanos(900L);
    private static final long DEFAULT_BG_TARGET_NANOS = TimeUnit.MICROSECONDS.toNanos(1600L);
    private static final int DEFAULT_MAX_FOREGROUND_ADDITIONAL = 14;
    private static final int DEFAULT_MAX_BACKGROUND_ADDITIONAL = 8;

    private final AtomicLong fgEmaNanos = new AtomicLong(0L);
    private final AtomicLong bgEmaNanos = new AtomicLong(0L);
    private volatile long fgTargetNanos = DEFAULT_FG_TARGET_NANOS;
    private volatile long bgTargetNanos = DEFAULT_BG_TARGET_NANOS;
    private volatile int fgMaxAdditional = DEFAULT_MAX_FOREGROUND_ADDITIONAL;
    private volatile int bgMaxAdditional = DEFAULT_MAX_BACKGROUND_ADDITIONAL;

    void recordExecution(boolean foreground, long durationNanos) {
        if (durationNanos <= 0L) {
            return;
        }
        AtomicLong target = foreground ? fgEmaNanos : bgEmaNanos;
        target.updateAndGet(current -> current == 0L
            ? durationNanos
            : (long) (current * (1.0d - EMA_ALPHA) + durationNanos * EMA_ALPHA));
    }

    int recommendedAdditional(boolean foreground, int queueDepth, double systemLoad, double systemLoadCap) {
        if (queueDepth <= 1) {
            return 0;
        }

        int maxAdditional = foreground ? fgMaxAdditional : bgMaxAdditional;
        double normalizedPressure = Math.min(1.0d, queueDepth / (double) (foreground ? 96 : 128));
        int pressureTarget = (int) Math.round(normalizedPressure * maxAdditional);
        if (pressureTarget <= 0) {
            pressureTarget = 1;
        }

        long ema = (foreground ? fgEmaNanos : bgEmaNanos).get();
        long targetNanos = foreground ? fgTargetNanos : bgTargetNanos;
        double latencyFactor = ema <= 0L
            ? 1.0d
            : Math.max(0.45d, Math.min(2.0d, targetNanos / (double) Math.max(1L, ema)));

        double loadFactor = systemLoad >= systemLoadCap
            ? Math.max(0.35d, 1.0d - ((systemLoad - systemLoadCap) * 1.8d))
            : 1.0d;

        int suggested = (int) Math.round(pressureTarget * latencyFactor * loadFactor);
        suggested = Math.max(1, Math.min(maxAdditional, suggested));
        return Math.min(suggested, queueDepth - 1);
    }

    void applyRuntimeTuning(long foregroundTargetNanos,
                            long backgroundTargetNanos,
                            int foregroundMaxAdditional,
                            int backgroundMaxAdditional) {
        fgTargetNanos = Math.max(TimeUnit.MICROSECONDS.toNanos(300L), foregroundTargetNanos);
        bgTargetNanos = Math.max(TimeUnit.MICROSECONDS.toNanos(500L), backgroundTargetNanos);
        fgMaxAdditional = Math.max(2, Math.min(24, foregroundMaxAdditional));
        bgMaxAdditional = Math.max(1, Math.min(16, backgroundMaxAdditional));
    }
}
