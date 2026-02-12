package org.admany.quantified.core.common.async.core;

import java.util.concurrent.TimeUnit;

final class RuntimeAutoTuner {

    private static final long TUNE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long GUARDRAIL_LOCK_NANOS = TimeUnit.SECONDS.toNanos(20L);
    private static final long DEFAULT_STALE_BASE_NANOS = TimeUnit.MILLISECONDS.toNanos(3_000L);
    private static final long DEFAULT_STALE_OVERLOAD_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);
    private static final long DEFAULT_GPU_BATCH_TARGET_NANOS = 2_000_000L;
    private static final double DEFAULT_GPU_UTIL_LIMIT = 0.70d;

    private long nextTuneNanos = 0L;
    private long guardrailUntilNanos = 0L;
    private long lastDroppedCount = 0L;
    private long lastCrashCount = 0L;
    private int level = 0;
    private int lastLevel = 0;
    private int unstableCycles = 0;

    RuntimeTuning maybeTune(int foregroundQueue,
                            int backgroundQueue,
                            int queueBound,
                            long droppedCount,
                            long crashCount,
                            double systemLoad,
                            double systemLoadCap) {
        long now = System.nanoTime();
        if (now < nextTuneNanos) {
            return null;
        }
        nextTuneNanos = now + TUNE_INTERVAL_NANOS;

        long droppedDelta = Math.max(0L, droppedCount - lastDroppedCount);
        long crashDelta = Math.max(0L, crashCount - lastCrashCount);
        lastDroppedCount = droppedCount;
        lastCrashCount = crashCount;

        int totalQueue = Math.max(0, foregroundQueue + backgroundQueue);
        double pressure = queueBound <= 0 ? 0.0d : Math.min(1.0d, totalQueue / (double) queueBound);

        int change = 0;
        if (pressure > 0.82d && systemLoad < systemLoadCap * 0.98d) {
            change += 1;
        }
        if (pressure > 1.00d && systemLoad < systemLoadCap * 0.95d) {
            change += 1;
        }
        if (systemLoad > systemLoadCap || droppedDelta > 40L || crashDelta > 0L) {
            change -= 2;
        } else if (systemLoad > systemLoadCap * 0.92d || droppedDelta > 12L) {
            change -= 1;
        }

        boolean immediateGuardrail = crashDelta > 0L || droppedDelta > 120L || systemLoad > systemLoadCap * 1.08d;
        if (immediateGuardrail) {
            guardrailUntilNanos = now + GUARDRAIL_LOCK_NANOS;
            level = -3;
            lastLevel = level;
            unstableCycles = 0;
            return buildTuning(level);
        }

        level = Math.max(-3, Math.min(3, level + change));
        if (Math.abs(level - lastLevel) >= 2) {
            unstableCycles++;
        } else if (unstableCycles > 0) {
            unstableCycles--;
        }
        lastLevel = level;

        if (unstableCycles >= 3) {
            guardrailUntilNanos = now + GUARDRAIL_LOCK_NANOS;
            level = -2;
            unstableCycles = 0;
            lastLevel = level;
            return buildTuning(level);
        }

        if (now < guardrailUntilNanos) {
            if (level > -2) {
                level = -2;
            }
            return buildTuning(level);
        }

        return buildTuning(level);
    }

    private RuntimeTuning buildTuning(int aggressivenessLevel) {
        int lvl = Math.max(-3, Math.min(3, aggressivenessLevel));
        int shift = lvl + 3;

        int fgBatchAdditional = 8 + (shift * 2); // 8..20
        int bgBatchAdditional = 4 + shift;       // 4..10
        long fgTargetNanos = TimeUnit.MICROSECONDS.toNanos(700L + (shift * 80L));   // 700us..1180us
        long bgTargetNanos = TimeUnit.MICROSECONDS.toNanos(1300L + (shift * 120L)); // 1300us..2020us

        long staleBase = DEFAULT_STALE_BASE_NANOS - TimeUnit.MILLISECONDS.toNanos(lvl * 250L);
        long staleOverload = DEFAULT_STALE_OVERLOAD_NANOS - TimeUnit.MILLISECONDS.toNanos(lvl * 160L);

        double fgThrottlePenalty = clamp(0.22d + ((3 - shift) * 0.03d), 0.15d, 0.42d);
        double bgThrottlePenalty = clamp(0.36d + ((3 - shift) * 0.03d), 0.24d, 0.56d);
        double healthyBoost = clamp(1.03d + (shift * 0.03d), 0.95d, 1.24d);

        double gpuUtilLimit = clamp(DEFAULT_GPU_UTIL_LIMIT + ((shift - 3) * 0.03d), 0.56d, 0.84d);
        long gpuBatchTarget = clampLong(
            DEFAULT_GPU_BATCH_TARGET_NANOS + (shift - 3) * 250_000L,
            1_000_000L,
            4_000_000L
        );

        return new RuntimeTuning(
            fgTargetNanos,
            bgTargetNanos,
            fgBatchAdditional,
            bgBatchAdditional,
            clampLong(staleBase, TimeUnit.MILLISECONDS.toNanos(1_200L), TimeUnit.MILLISECONDS.toNanos(6_000L)),
            clampLong(staleOverload, TimeUnit.MILLISECONDS.toNanos(700L), TimeUnit.MILLISECONDS.toNanos(3_500L)),
            fgThrottlePenalty,
            bgThrottlePenalty,
            healthyBoost,
            gpuUtilLimit,
            gpuBatchTarget
        );
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static long clampLong(long value, long min, long max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    record RuntimeTuning(long foregroundTargetNanos,
                         long backgroundTargetNanos,
                         int foregroundMaxAdditional,
                         int backgroundMaxAdditional,
                         long staleBaseNanos,
                         long staleOverloadNanos,
                         double foregroundThrottlePenalty,
                         double backgroundThrottlePenalty,
                         double healthyLoadBoost,
                         double gpuUtilLimit,
                         long gpuBatchTargetNanos) {
    }

    boolean isGuardrailActive() {
        return System.nanoTime() < guardrailUntilNanos;
    }

    int currentLevelForTesting() {
        return level;
    }
}
