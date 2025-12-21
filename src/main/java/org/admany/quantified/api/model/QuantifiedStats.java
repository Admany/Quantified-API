package org.admany.quantified.api.model;

import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.interfaces.ModStatistics;
import org.admany.quantified.core.common.telemetry.TelemetryService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class QuantifiedStats {

    private QuantifiedStats() {
    }

    public static ModStats getModStats(String modId) {
        Objects.requireNonNull(modId, "modId");
        ModStatistics stats = QuantifiedAPI.getModStatistics(modId);
        return stats != null ? convertToLegacyStats(modId, stats) : null;
    }

    public static GlobalStats getGlobalStats() {
        Map<String, ModStats> perMod = new LinkedHashMap<>();
        long submitted = 0;
        long success = 0;
        long failed = 0;
        long hits = 0;
        long misses = 0;
        Map<String, ModStatistics> allStats = QuantifiedAPI.getAllModStatistics();
        for (Map.Entry<String, ModStatistics> entry : allStats.entrySet()) {
            ModStats stats = convertToLegacyStats(entry.getKey(), entry.getValue());
            perMod.put(stats.modId, stats);
            submitted += stats.tasksSubmitted;
            success += stats.tasksSucceeded;
            failed += stats.tasksFailed;
            hits += stats.cacheHits;
            misses += stats.cacheMisses;
        }
        TelemetryService.SchedulerSnapshot snapshot = TelemetryService.getLatest();
        double execRate = snapshot != null ? snapshot.execRate : 0.0d;
        return new GlobalStats(perMod, submitted, success, failed, hits, misses, execRate);
    }

    public static final class ModStats {
        public final String modId;
        public final String version;
        public final long tasksSubmitted;
        public final long tasksSucceeded;
        public final long tasksFailed;
        public final long cacheHits;
        public final long cacheMisses;
        public final long lastActivityEpochMs;

        public ModStats(String modId,
                 String version,
                 long tasksSubmitted,
                 long tasksSucceeded,
                 long tasksFailed,
                 long cacheHits,
                 long cacheMisses,
                 long lastActivityEpochMs) {
            this.modId = modId;
            this.version = version;
            this.tasksSubmitted = tasksSubmitted;
            this.tasksSucceeded = tasksSucceeded;
            this.tasksFailed = tasksFailed;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.lastActivityEpochMs = lastActivityEpochMs;
        }

        public long tasksInFlight() {
            return Math.max(0L, tasksSubmitted - (tasksSucceeded + tasksFailed));
        }

        public double cacheHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0L ? (double) cacheHits / total : 0.0d;
        }
    }

    public static final class GlobalStats {
        public final Map<String, ModStats> modStats;
        public final long totalTasksSubmitted;
        public final long totalTasksSucceeded;
        public final long totalTasksFailed;
        public final long totalCacheHits;
        public final long totalCacheMisses;
        public final double schedulerExecRate;

        GlobalStats(Map<String, ModStats> modStats,
                    long totalTasksSubmitted,
                    long totalTasksSucceeded,
                    long totalTasksFailed,
                    long totalCacheHits,
                    long totalCacheMisses,
                    double schedulerExecRate) {
            this.modStats = modStats;
            this.totalTasksSubmitted = totalTasksSubmitted;
            this.totalTasksSucceeded = totalTasksSucceeded;
            this.totalTasksFailed = totalTasksFailed;
            this.totalCacheHits = totalCacheHits;
            this.totalCacheMisses = totalCacheMisses;
            this.schedulerExecRate = schedulerExecRate;
        }

        public int registeredModCount() {
            return modStats.size();
        }

        public double aggregateCacheHitRate() {
            long total = totalCacheHits + totalCacheMisses;
            return total > 0L ? (double) totalCacheHits / total : 0.0d;
        }
    }

    private static ModStats convertToLegacyStats(String modId, ModStatistics stats) {
        return new ModStats(
            modId,
            stats.getModVersion(),
            stats.getTotalTasksSubmitted(),
            stats.getTasksCompleted(),
            stats.getTasksFailed(),
            (long) (stats.getCacheHitRate() * (stats.getCacheSize() + 1)),
            (long) ((1.0 - stats.getCacheHitRate()) * (stats.getCacheSize() + 1)),
            stats.getLastActivity().toEpochMilli()
        );
    }
}
