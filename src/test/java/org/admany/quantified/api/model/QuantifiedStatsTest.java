package org.admany.quantified.api.model;

import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.interfaces.ConnectedMod;
import org.admany.quantified.api.interfaces.ModConnectionListener;
import org.admany.quantified.api.interfaces.ModStatistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class QuantifiedStatsTest {

    @AfterEach
    void cleanup() {
        QuantifiedAPI.disconnect("tm");
    }

    @Test
    void modStatsReflectsConnectedMod() {
        QuantifiedAPI.register("tm", "Test Mod", "1.2");
        QuantifiedAPI.addConnectionListener(new ModConnectionListener() {
            @Override
            public ConnectedMod onModConnecting(String modId, String version, String displayName) {
                return new ConnectedMod() {
                    @Override public String getModId() { return "tm"; }
                    @Override public String getVersion() { return "1.2"; }
                    @Override public String getDisplayName() { return "Test Mod"; }
                    @Override public ModStatistics getStatistics() {
                        return new ModStatistics() {
                            @Override public String getModId() { return "tm"; }
                            @Override public String getModVersion() { return "1.2"; }
                            @Override public Instant getLastActivity() { return Instant.EPOCH; }
                            @Override public long getTotalTasksSubmitted() { return 10; }
                            @Override public long getTasksCompleted() { return 7; }
                            @Override public long getTasksFailed() { return 2; }
                            @Override public int getCurrentQueueDepth() { return 1; }
                            @Override public boolean isThrottled() { return false; }
                            @Override public double getThrottleFactor() { return 0.0; }
                            @Override public Duration getAverageTaskTime() { return Duration.ZERO; }
                            @Override public Duration getMaxTaskTime() { return Duration.ZERO; }
                            @Override public double getTasksPerSecond() { return 0.0; }
                            @Override public double getCacheHitRate() { return 0.5; }
                            @Override public long getCacheSize() { return 3; }
                            @Override public long getCacheMaxSize() { return 10; }
                            @Override public long getCacheEvictions() { return 0; }
                            @Override public long getCacheMemoryUsage() { return 0; }
                            @Override public long getPacketsSent() { return 0; }
                            @Override public long getPacketsReceived() { return 0; }
                            @Override public long getNetworkErrors() { return 0; }
                            @Override public long getNetworkBytesTransferred() { return 0; }
                            @Override public Duration getTotalGPUTime() { return Duration.ZERO; }
                            @Override public long getPeakVRAMUsage() { return 0; }
                            @Override public double getGPUUtilization() { return 0.0; }
                            @Override public double getCPUFallbackRate() { return 0.0; }
                        };
                    }
                    @Override public org.admany.quantified.api.builders.QuantifiedTaskBuilder task(String name) { return null; }
                    @Override public org.admany.quantified.api.builders.QuantifiedCacheBuilder cache(Enum<?> cacheType) { return null; }
                    @Override public org.admany.quantified.api.builders.QuantifiedHybridBuilder hybrid(String name) { return null; }
                    @Override public org.admany.quantified.api.builders.QuantifiedNetworkBuilder network(String channel) { return null; }
                    @Override public void disconnect() { /* no-op */ }
                };
            }

            @Override
            public void onModConnected(ConnectedMod connectedMod) { /* ignore */ }

            @Override
            public void onModDisconnected(ConnectedMod connectedMod) { /* ignore */ }
        });

        QuantifiedStats.ModStats stats = QuantifiedStats.getModStats("tm");
        assertThat(stats).isNotNull();
        assertThat(stats.modId).isEqualTo("tm");
        assertThat(stats.tasksSubmitted).isEqualTo(10);
        assertThat(stats.tasksSucceeded).isEqualTo(7);
        assertThat(stats.tasksFailed).isEqualTo(2);
        assertThat(stats.cacheHits).isPositive();
        assertThat(stats.cacheMisses).isPositive();

        QuantifiedStats.GlobalStats global = QuantifiedStats.getGlobalStats();
        Map<String, QuantifiedStats.ModStats> m = global.modStats;
        assertThat(m).containsKey("tm");
        assertThat(global.totalTasksSubmitted).isGreaterThanOrEqualTo(10);
    }
}
