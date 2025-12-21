package org.admany.quantified.core.common.opencl.gpu.memory;

import java.util.concurrent.atomic.AtomicLong;

public final class GPUMemoryManager {

    private static final double BASE_BUDGET_FRACTION = 0.25d;
    private static final double PRESSURE_THRESHOLD = 0.75d;
    private static final double BUFFER_FRACTION = 0.05d;
    private static final double MIN_BUDGET_FRACTION = 0.05d;
    private static final long MIN_ABSOLUTE_BUDGET_BYTES = 64L * 1024L * 1024L;
    private static final long FALLBACK_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;

    private final AtomicLong baselineUsedVramBytes = new AtomicLong(-1L);
    private volatile long detectedTotalVramBytes = FALLBACK_TOTAL_BYTES;
    private volatile long apiBudgetBytes = computeBaseBudget(FALLBACK_TOTAL_BYTES);

    public long getConfiguredVramBytes() {
        return detectedTotalVramBytes;
    }

    public long getTotalVramBytes() {
        return apiBudgetBytes;
    }

    public void configureVram(long detectedVramBytes) {
        if (detectedVramBytes <= 0L) {
            detectedVramBytes = detectedTotalVramBytes > 0L ? detectedTotalVramBytes : FALLBACK_TOTAL_BYTES;
        }
        detectedTotalVramBytes = detectedVramBytes;
        apiBudgetBytes = computeBaseBudget(detectedVramBytes);
        baselineUsedVramBytes.set(-1L);
    }

    public BudgetSnapshot evaluate(long observedUsedBytes) {
        long gpuTotal = detectedTotalVramBytes > 0L ? detectedTotalVramBytes : FALLBACK_TOTAL_BYTES;
        long clampedUsed = Math.max(0L, Math.min(observedUsedBytes, gpuTotal));
        double systemUsageRatio = gpuTotal > 0L ? (double) clampedUsed / gpuTotal : 0.0d;

        double targetFraction = BASE_BUDGET_FRACTION;
        if (systemUsageRatio > PRESSURE_THRESHOLD) {
            double availableFraction = Math.max(0.0d, 1.0d - systemUsageRatio);
            double downscaled = Math.max(MIN_BUDGET_FRACTION, availableFraction - BUFFER_FRACTION);
            targetFraction = Math.min(BASE_BUDGET_FRACTION, downscaled);
        }
        targetFraction = Math.max(MIN_BUDGET_FRACTION, Math.min(BASE_BUDGET_FRACTION, targetFraction));

        long minBudget = computeMinimumBudget(gpuTotal);
        long baseBudget = computeBaseBudget(gpuTotal);
        long targetBudget = Math.max(minBudget, (long) Math.floor(gpuTotal * targetFraction));
        apiBudgetBytes = Math.max(minBudget, Math.min(baseBudget, targetBudget));

        return new BudgetSnapshot(gpuTotal, apiBudgetBytes, systemUsageRatio);
    }

    public void updateBaseline(long usedBytes) {
        long baseline = baselineUsedVramBytes.get();
        if (baseline < 0L || usedBytes < baseline) {
            long newBaseline = Math.min(usedBytes, detectedTotalVramBytes);
            baselineUsedVramBytes.set(newBaseline);
        }
    }

    public long getAdjustedUsed(long usedBytes) {
        long baseline = baselineUsedVramBytes.get();
        if (baseline < 0L) {
            long newBaseline = Math.min(usedBytes, detectedTotalVramBytes);
            baselineUsedVramBytes.set(newBaseline);
            baseline = newBaseline;
        }

        long adjustedUsed = usedBytes - baseline;
        if (adjustedUsed < 0L) {
            adjustedUsed = 0L;
        }
        return adjustedUsed;
    }

    public void resetBaseline() {
        baselineUsedVramBytes.set(-1L);
    }

    private static long computeBaseBudget(long totalBytes) {
        if (totalBytes <= 0L) {
            return Math.max(MIN_ABSOLUTE_BUDGET_BYTES, (long) Math.floor(FALLBACK_TOTAL_BYTES * BASE_BUDGET_FRACTION));
        }
        long budget = (long) Math.floor(totalBytes * BASE_BUDGET_FRACTION);
        long minBudget = computeMinimumBudget(totalBytes);
        return Math.max(minBudget, Math.min(totalBytes, budget));
    }

    private static long computeMinimumBudget(long totalBytes) {
        long fractionBudget = (long) Math.floor(totalBytes * MIN_BUDGET_FRACTION);
        return Math.max(MIN_ABSOLUTE_BUDGET_BYTES, fractionBudget);
    }

    public record BudgetSnapshot(long gpuTotalBytes, long apiBudgetBytes, double systemUsageRatio) {}
}