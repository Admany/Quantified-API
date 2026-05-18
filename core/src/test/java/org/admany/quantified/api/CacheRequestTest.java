package org.admany.quantified.api;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CacheRequestTest {

    private static final String MOD_ID = "cache_test";
    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    static void setUpAll() {
        testExecutor = Executors.newScheduledThreadPool(2);
        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors());
        AsyncManager.initialise(bootstrap, testExecutor);
        QuantifiedAPI.register(MOD_ID, "Cache Test", "1.0.0");
    }

    @AfterAll
    static void tearDownAll() {
        QuantifiedAPI.disconnect(MOD_ID);
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void prefetchAndRefreshAsyncPopulateAndReplaceEntries() {
        AtomicInteger loads = new AtomicInteger();

        CacheRequest request = QuantifiedAPI.cache(MOD_ID, "planner-" + System.nanoTime())
            .ttl(Duration.ofMinutes(5))
            .maxEntries(128)
            .persistent()
            .compressed()
            .refreshOnAccess();

        String first = request.prefetch("chunk:0:0", () -> "value-" + loads.incrementAndGet()).join();
        String cached = request.get("chunk:0:0", () -> "should-not-run");
        String refreshed = request.refreshAsync("chunk:0:0", () -> "value-" + loads.incrementAndGet()).join();

        assertThat(first).isEqualTo("value-1");
        assertThat(cached).isEqualTo("value-1");
        assertThat(refreshed).isEqualTo("value-2");
        assertThat(request.get("chunk:0:0", () -> "should-not-run-2")).isEqualTo("value-2");
        assertThat(loads.get()).isEqualTo(2);
        assertThat(request.contains("chunk:0:0")).isTrue();
    }
}
