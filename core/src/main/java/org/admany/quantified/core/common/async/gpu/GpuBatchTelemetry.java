package org.admany.quantified.core.common.async.gpu;

import java.util.concurrent.atomic.LongAdder;


public final class GpuBatchTelemetry {

    private static final LongAdder batchesAttempted = new LongAdder();
    private static final LongAdder batchesSucceeded = new LongAdder();
    private static final LongAdder batchesFallenBack = new LongAdder();
    private static final LongAdder gpuTasksCompleted = new LongAdder();
    private static final LongAdder rejectedNoMetadata = new LongAdder();
    private static final LongAdder rejectedNotBatchable = new LongAdder();
    private static final LongAdder rejectedNotGpuMarked = new LongAdder();
    private static final LongAdder rejectedThermal = new LongAdder();
    private static final LongAdder fallbackNoWorkload = new LongAdder();
    private static final LongAdder fallbackDispatcherUnavailable = new LongAdder();
    private static final LongAdder fallbackExecutionFailure = new LongAdder();
    private static final LongAdder directFailureCooldown = new LongAdder();
    private static final LongAdder directThrottleRejected = new LongAdder();
    private static final LongAdder directCapacityRejected = new LongAdder();
    private static final LongAdder directGpuSucceeded = new LongAdder();
    private static final LongAdder directGpuFailed = new LongAdder();
    private static final LongAdder directVramCooldown = new LongAdder();

    private GpuBatchTelemetry() {
    }

    static void recordAttempt() {
        batchesAttempted.increment();
    }

    static void recordSuccess() {
        batchesSucceeded.increment();
    }

    static void recordFallback() {
        batchesFallenBack.increment();
    }

    static void recordGpuTasksCompleted(int count) {
        if (count > 0) {
            gpuTasksCompleted.add(count);
        }
    }

    static void recordRejectedNoMetadata() {
        rejectedNoMetadata.increment();
    }

    static void recordRejectedNotBatchable() {
        rejectedNotBatchable.increment();
    }

    static void recordRejectedNotGpuMarked() {
        rejectedNotGpuMarked.increment();
    }

    static void recordRejectedThermal() {
        rejectedThermal.increment();
    }

    static void recordFallbackNoWorkload() {
        fallbackNoWorkload.increment();
    }

    static void recordFallbackDispatcherUnavailable() {
        fallbackDispatcherUnavailable.increment();
    }

    static void recordFallbackExecutionFailure() {
        fallbackExecutionFailure.increment();
    }

    public static void recordDirectFailureCooldown() {
        directFailureCooldown.increment();
    }

    public static void recordDirectThrottleRejected() {
        directThrottleRejected.increment();
    }

    public static void recordDirectCapacityRejected() {
        directCapacityRejected.increment();
    }

    public static void recordDirectGpuSucceeded() {
        directGpuSucceeded.increment();
    }

    public static void recordDirectGpuFailed() {
        directGpuFailed.increment();
    }

    public static void recordDirectVramCooldown() {
        directVramCooldown.increment();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            batchesAttempted.sum(),
            batchesSucceeded.sum(),
            batchesFallenBack.sum(),
            gpuTasksCompleted.sum(),
            rejectedNoMetadata.sum(),
            rejectedNotBatchable.sum(),
            rejectedNotGpuMarked.sum(),
            rejectedThermal.sum(),
            fallbackNoWorkload.sum(),
            fallbackDispatcherUnavailable.sum(),
            fallbackExecutionFailure.sum(),
            directFailureCooldown.sum(),
            directThrottleRejected.sum(),
            directCapacityRejected.sum(),
            directGpuSucceeded.sum(),
            directGpuFailed.sum(),
            directVramCooldown.sum()
        );
    }

    public record Snapshot(long attempted,
                           long succeeded,
                           long fallenBack,
                           long gpuTasksCompleted,
                           long rejectedNoMetadata,
                           long rejectedNotBatchable,
                           long rejectedNotGpuMarked,
                           long rejectedThermal,
                           long fallbackNoWorkload,
                           long fallbackDispatcherUnavailable,
                           long fallbackExecutionFailure,
                           long directFailureCooldown,
                           long directThrottleRejected,
                           long directCapacityRejected,
                           long directGpuSucceeded,
                           long directGpuFailed,
                           long directVramCooldown) {
    }

    public static void reset() {
        batchesAttempted.reset();
        batchesSucceeded.reset();
        batchesFallenBack.reset();
        gpuTasksCompleted.reset();
        rejectedNoMetadata.reset();
        rejectedNotBatchable.reset();
        rejectedNotGpuMarked.reset();
        rejectedThermal.reset();
        fallbackNoWorkload.reset();
        fallbackDispatcherUnavailable.reset();
        fallbackExecutionFailure.reset();
        directFailureCooldown.reset();
        directThrottleRejected.reset();
        directCapacityRejected.reset();
        directGpuSucceeded.reset();
        directGpuFailed.reset();
        directVramCooldown.reset();
    }
}
