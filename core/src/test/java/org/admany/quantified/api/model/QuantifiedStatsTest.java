package org.admany.quantified.api.model;

import org.admany.quantified.api.CacheRequest;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

public class QuantifiedStatsTest {

    private static final String MOD_ID = "tm";

    private ScheduledExecutorService testExecutor;

    @BeforeEach
    void setUp() {
        testExecutor = Executors.newScheduledThreadPool(2);
        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors());
        AsyncManager.initialise(bootstrap, testExecutor);
    }

    @AfterEach
    void cleanup() {
        QuantifiedAPI.disconnect(MOD_ID);
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void modStatsReflectsConnectedMod() {
        QuantifiedAPI.register(MOD_ID, "Test Mod", "1.2");

        CacheRequest cache = QuantifiedAPI.cache(MOD_ID, "stats-" + System.nanoTime())
            .ttl(Duration.ofMinutes(1))
            .maxEntries(16)
            .memoryOnly();

        int taskResult = QuantifiedAPI.<Integer>compute(MOD_ID, "stats-task")
            .submit(() -> 7)
            .join();
        String first = cache.get("alpha", () -> "value-1");
        String second = cache.get("alpha", () -> "value-2");
        String miss = cache.get("beta", () -> "value-3");

        assertThat(taskResult).isEqualTo(7);
        assertThat(first).isEqualTo("value-1");
        assertThat(second).isEqualTo("value-1");
        assertThat(miss).isEqualTo("value-3");

        QuantifiedStats.ModStats stats = QuantifiedStats.getModStats(MOD_ID);
        assertThat(stats).isNotNull();
        assertThat(stats.modId).isEqualTo(MOD_ID);
        assertThat(stats.version).isEqualTo("1.2");
        assertThat(stats.tasksSubmitted).isGreaterThanOrEqualTo(1);
        assertThat(stats.tasksSucceeded).isGreaterThanOrEqualTo(1);
        assertThat(stats.tasksFailed).isEqualTo(0);
        assertThat(stats.cacheHits).isPositive();
        assertThat(stats.cacheMisses).isPositive();
        assertThat(stats.lastActivityEpochMs).isPositive();

        QuantifiedStats.GlobalStats global = QuantifiedStats.getGlobalStats();
        Map<String, QuantifiedStats.ModStats> modStats = global.modStats;
        assertThat(modStats).containsKey(MOD_ID);
        assertThat(global.totalTasksSubmitted).isGreaterThanOrEqualTo(stats.tasksSubmitted);
        assertThat(global.totalTasksSucceeded).isGreaterThanOrEqualTo(stats.tasksSucceeded);
        assertThat(global.totalCacheHits).isGreaterThanOrEqualTo(stats.cacheHits);
        assertThat(global.totalCacheMisses).isGreaterThanOrEqualTo(stats.cacheMisses);
    }
}
