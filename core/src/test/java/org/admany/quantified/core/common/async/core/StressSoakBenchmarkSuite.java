package org.admany.quantified.core.common.async.core;

import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "quantified.benchmarks", matches = "true")
class StressSoakBenchmarkSuite {

    @Test
    void burstStressBenchmark() throws Exception {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(6);
        try {
            AsyncManager.shutdown();
            AsyncManager.initialise(AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors()), executor);

            final int totalTasks = 4_000;
            List<CompletableFuture<Integer>> futures = new ArrayList<>(totalTasks);
            long start = System.nanoTime();
            for (int i = 0; i < totalTasks; i++) {
                final int value = i;
                futures.add(AsyncManager.submitSync(
                    1_000_000L + i,
                    PriorityTaskType.BUILDING,
                    0.6,
                    () -> value + 1,
                    Duration.ofSeconds(5),
                    "bench_burst_mod"
                ));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(40, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            PriorityScheduler.SchedulerSnapshot snapshot = AsyncManager.schedulerSnapshot();
            long throughputPerSec = (totalTasks * 1000L) / Math.max(1L, elapsedMs);

            assertTrue(elapsedMs < 30_000L, "Burst benchmark took too long: " + elapsedMs + "ms");
            assertTrue(snapshot.workerCrashes() == 0L, "Worker crashes detected: " + snapshot.workerCrashes());
            assertTrue(snapshot.dropped() < (totalTasks * 0.02), "Drop rate too high: " + snapshot.dropped());
            assertTrue(throughputPerSec > 300L, "Throughput too low: " + throughputPerSec + " tasks/s");
        } finally {
            AsyncManager.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void soakStabilityBenchmark() throws Exception {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
        try {
            AsyncManager.shutdown();
            AsyncManager.initialise(AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors()), executor);

            long endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(45L);
            long key = 2_000_000L;
            long submitted = 0L;
            while (System.nanoTime() < endAt) {
                List<CompletableFuture<Integer>> wave = new ArrayList<>(200);
                for (int i = 0; i < 200; i++) {
                    final int work = i;
                    wave.add(AsyncManager.submitSync(
                        key++,
                        PriorityTaskType.CACHE,
                        0.3,
                        () -> work * work,
                        Duration.ofSeconds(5),
                        "bench_soak_mod"
                    ));
                    submitted++;
                }
                CompletableFuture.allOf(wave.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
                Thread.sleep(150L);
            }

            PriorityScheduler.SchedulerSnapshot snapshot = AsyncManager.schedulerSnapshot();
            assertTrue(submitted > 0L);
            assertTrue(snapshot.workerCrashes() == 0L, "Crashes during soak: " + snapshot.workerCrashes());
            assertTrue(snapshot.dropped() < submitted * 0.03, "Drops during soak too high: " + snapshot.dropped());
        } finally {
            AsyncManager.shutdown();
            executor.shutdownNow();
        }
    }
}
