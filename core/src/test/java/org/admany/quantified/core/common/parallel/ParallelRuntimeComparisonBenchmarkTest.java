package org.admany.quantified.core.common.parallel;

import org.admany.quantified.api.model.ParallelTaskSpec;
import org.admany.quantified.api.parallel.ParallelCompute;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.parallel.policy.ParallelFailurePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelRuntimeComparisonBenchmarkTest {

    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    static void setUpAll() {
        testExecutor = Executors.newScheduledThreadPool(4);
        AsyncManager.initialise(AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors()), testExecutor);
    }

    @AfterAll
    static void tearDownAll() {
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void compareLegacyAndUnifiedParallelMicroWork() {
        requireBenchmarksEnabled();
        ParallelTaskSpec<Integer, Integer, Integer> legacy = legacySpec(2_048, 8);
        ParallelCompute.Builder<Integer, Integer, List<Integer>> builder = modernBuilder(2_048, 8);

        long legacyNanos = measureNanos(() -> ParallelTaskManager.submitLegacy(legacy).join());
        long modernNanos = measureNanos(() -> builder.submit().join());

        System.out.println("parallel.micro.legacyMs=" + TimeUnit.NANOSECONDS.toMillis(legacyNanos));
        System.out.println("parallel.micro.modernMs=" + TimeUnit.NANOSECONDS.toMillis(modernNanos));

        assertThat(ParallelTaskManager.submitLegacy(legacy).join()).isEqualTo(sum(builder.submit().join()));
        assertThat(modernNanos).isLessThanOrEqualTo((long) (legacyNanos * 1.10d));
    }

    @Test
    void compareLegacyAndUnifiedParallelMediumWork() {
        requireBenchmarksEnabled();
        ParallelTaskSpec<Integer, Integer, Integer> legacy = legacySpec(8_192, 32);
        ParallelCompute.Builder<Integer, Integer, List<Integer>> builder = modernBuilder(8_192, 32);

        long legacyNanos = measureNanos(() -> ParallelTaskManager.submitLegacy(legacy).join());
        long modernNanos = measureNanos(() -> builder.submit().join());

        System.out.println("parallel.medium.legacyMs=" + TimeUnit.NANOSECONDS.toMillis(legacyNanos));
        System.out.println("parallel.medium.modernMs=" + TimeUnit.NANOSECONDS.toMillis(modernNanos));

        assertThat(ParallelTaskManager.submitLegacy(legacy).join()).isEqualTo(sum(builder.submit().join()));
        assertThat(modernNanos).isLessThanOrEqualTo((long) (legacyNanos * 1.10d));
    }

    private static ParallelTaskSpec<Integer, Integer, Integer> legacySpec(int count, int mathRounds) {
        return new ParallelTaskSpec<>(
            "bench_parallel",
            "legacy_parallel",
            91_000L + count + mathRounds,
            () -> range(count),
            value -> CompletableFuture.completedFuture(expensiveMath(value, mathRounds)),
            results -> results.stream().mapToInt(Integer::intValue).sum(),
            null,
            ParallelFailurePolicy.FAIL_FAST,
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            null
        );
    }

    private static ParallelCompute.Builder<Integer, Integer, List<Integer>> modernBuilder(int count, int mathRounds) {
        return ParallelCompute.<Integer, Integer>builder("bench_parallel", "modern_parallel", 95_000L + count + mathRounds)
            .slices(() -> range(count))
            .sliceExecutor(value -> expensiveMath(value, mathRounds))
            .failurePolicy(ParallelFailurePolicy.FAIL_FAST)
            .maxParallelism(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    private static List<Integer> range(int count) {
        ArrayList<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(i);
        }
        return values;
    }

    private static int expensiveMath(int seed, int rounds) {
        int value = seed;
        for (int i = 0; i < rounds; i++) {
            value = Integer.rotateLeft(value * 31 + 17, 3) ^ (i * 13);
        }
        return value;
    }

    private static long measureNanos(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static int sum(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).sum();
    }

    private static void requireBenchmarksEnabled() {
        boolean enabled = Boolean.getBoolean("quantified.benchmarks")
            || "true".equalsIgnoreCase(System.getenv("QUANTIFIED_BENCHMARKS"));
        Assumptions.assumeTrue(enabled, "benchmark mode disabled");
    }
}
