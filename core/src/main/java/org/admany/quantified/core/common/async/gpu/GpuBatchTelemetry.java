package org.admany.quantified.core.common.async.gpu;

import java.util.concurrent.atomic.LongAdder;


public final class GpuBatchTelemetry {

    private static final LongAdder batchesAttempted = new LongAdder();
    private static final LongAdder batchesSucceeded = new LongAdder();
    private static final LongAdder batchesFallenBack = new LongAdder();

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

    public static Snapshot snapshot() {
        return new Snapshot(
            batchesAttempted.sum(),
            batchesSucceeded.sum(),
            batchesFallenBack.sum()
        );
    }

    public record Snapshot(long attempted, long succeeded, long fallenBack) {
    }

    public static void reset() {
        batchesAttempted.reset();
        batchesSucceeded.reset();
        batchesFallenBack.reset();
    }
}
