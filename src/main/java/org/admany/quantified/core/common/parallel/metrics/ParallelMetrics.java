package org.admany.quantified.core.common.parallel.metrics;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class ParallelMetrics {
    private static final LongAdder SUBMISSIONS = new LongAdder();
    private static final LongAdder SLICES_DISPATCHED = new LongAdder();
    private static final LongAdder SLICES_COMPLETED = new LongAdder();
    private static final LongAdder SLICES_FAILED = new LongAdder();
    private static final LongAdder QUEUE_REJECTIONS = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> MOD_ACTIVE = new ConcurrentHashMap<>();
    private static final LongAdder CACHE_HITS = new LongAdder();
    private static final LongAdder CACHE_MISSES = new LongAdder();

    private ParallelMetrics() {
    }

    public static void recordSubmission(String modId, int slices) {
        SUBMISSIONS.increment();
        if (slices > 0) {
            MOD_ACTIVE.computeIfAbsent(modId, ignored -> new LongAdder()).add(slices);
        }
    }

    public static void recordDispatch(String modId) {
        SLICES_DISPATCHED.increment();
    }

    public static void recordCompletion(String modId, boolean success) {
        if (success) {
            SLICES_COMPLETED.increment();
        } else {
            SLICES_FAILED.increment();
        }
        LongAdder adder = MOD_ACTIVE.get(modId);
        if (adder != null) {
            adder.decrement();
        }
    }

    public static void recordRejection() {
        QUEUE_REJECTIONS.increment();
    }

    public static void recordCacheHit() {
        CACHE_HITS.increment();
    }

    public static void recordCacheMiss() {
        CACHE_MISSES.increment();
    }

    public static Snapshot snapshot() {
        Map<String, Long> perMod = new ConcurrentHashMap<>();
        for (Map.Entry<String, LongAdder> entry : MOD_ACTIVE.entrySet()) {
            perMod.put(entry.getKey(), entry.getValue().sum());
        }
        return new Snapshot(
            Instant.now(),
            SUBMISSIONS.sum(),
            SLICES_DISPATCHED.sum(),
            SLICES_COMPLETED.sum(),
            SLICES_FAILED.sum(),
            QUEUE_REJECTIONS.sum(),
            CACHE_HITS.sum(),
            CACHE_MISSES.sum(),
            perMod
        );
    }

    public record Snapshot(Instant timestamp,
                           long submissions,
                           long dispatched,
                           long completed,
                           long failed,
                           long rejections,
                           long cacheHits,
                           long cacheMisses,
                           Map<String, Long> modActiveSlices) {
    }
}
