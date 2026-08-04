package org.admany.quantified.api;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.async.task.ModPriorityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QuantifiedHandleCacheSingleFlightTest {

    private ScheduledExecutorService executor;

    @BeforeEach
    void setUp() {
        AsyncManager.shutdown();
        executor = Executors.newScheduledThreadPool(4);
        AsyncManager.initialise(AsyncManagerBootstrap.defaults(4), executor);
        ModPriorityManager.setMaxTasksForMod("cache_single_flight", 512L);
    }

    @AfterEach
    void tearDown() {
        AsyncManager.shutdown();
        executor.shutdownNow();
    }

    @Test
    void joinsConcurrentAsyncMissesForTheSameCacheKey() throws Exception {
        QuantifiedHandle handle = new QuantifiedHandle("cache_single_flight", "1.0.0");
        AtomicInteger loaderRuns = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();

        for (int index = 0; index < 64; index++) {
            futures.add(handle.cacheGetAsync(
                "plans",
                "same-key",
                () -> {
                    loaderRuns.incrementAndGet();
                    loaderEntered.countDown();
                    try {
                        assertThat(releaseLoader.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(interrupted);
                    }
                    return 42;
                },
                Duration.ofMinutes(1),
                128,
                false,
                false,
                false
            ));
        }

        assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        releaseLoader.countDown();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        assertThat(loaderRuns).hasValue(1);
        assertThat(futures).allSatisfy(future -> assertThat(future.join()).isEqualTo(42));
    }
}
