package org.admany.quantified.api;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.async.task.ModPriorityManager;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncIngressRuntimeComparisonBenchmarkTest {

    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    static void setUpAll() {
        testExecutor = Executors.newScheduledThreadPool(4);
        AsyncManager.initialise(AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors()), testExecutor);
        ModPriorityManager.setMaxTasksForMod("bench_async", 4096L);
    }

    @AfterAll
    static void tearDownAll() {
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void compareRandomAndStableDuplicateBurst() {
        requireBenchmarksEnabled();
        QuantifiedHandle handle = new QuantifiedHandle("bench_async", "1.0.0");

        BurstResult legacy = measureLegacyBurst();
        BurstResult modern = measureBurst(handle, "chunk:0,0");

        System.out.println("async.duplicateBurst.legacyMs=" + TimeUnit.NANOSECONDS.toMillis(legacy.nanos()));
        System.out.println("async.duplicateBurst.modernMs=" + TimeUnit.NANOSECONDS.toMillis(modern.nanos()));
        System.out.println("async.duplicateBurst.legacyExecutions=" + legacy.executions());
        System.out.println("async.duplicateBurst.modernExecutions=" + modern.executions());

        assertThat(legacy.executions()).isEqualTo(2048);
        assertThat(modern.executions()).isLessThan(legacy.executions());
        assertThat(modern.nanos()).isLessThanOrEqualTo(legacy.nanos());
    }

    private static BurstResult measureBurst(QuantifiedHandle handle, String affinity) {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        List<CompletableFuture<Integer>> futures = new ArrayList<>(2048);

        long start = System.nanoTime();
        for (int i = 0; i < 2048; i++) {
            futures.add(handle.submitRuntimeTask(
                "duplicate-burst",
                PriorityTaskType.BUILDING,
                false,
                true,
                null,
                affinity,
                () -> {
                    executions.incrementAndGet();
                    await(gate);
                    return 7;
                }
            ));
        }
        gate.countDown();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return new BurstResult(System.nanoTime() - start, executions.get());
    }

    private static BurstResult measureLegacyBurst() {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        List<CompletableFuture<Integer>> futures = new ArrayList<>(2048);

        long start = System.nanoTime();
        for (int i = 0; i < 2048; i++) {
            futures.add(AsyncManager.submitSync(
                ThreadLocalRandom.current().nextLong(),
                PriorityTaskType.BUILDING,
                PriorityTaskType.BUILDING.defaultScore(),
                () -> {
                    executions.incrementAndGet();
                    await(gate);
                    return 7;
                },
                "bench_async"
            ));
        }
        gate.countDown();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return new BurstResult(System.nanoTime() - start, executions.get());
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        }
    }

    private static void requireBenchmarksEnabled() {
        boolean enabled = Boolean.getBoolean("quantified.benchmarks")
            || "true".equalsIgnoreCase(System.getenv("QUANTIFIED_BENCHMARKS"));
        Assumptions.assumeTrue(enabled, "benchmark mode disabled");
    }

    private record BurstResult(long nanos, int executions) {
    }
}
