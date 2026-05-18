package org.admany.quantified.core.common.async.task;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class ModPriorityManager {

    private static final double FAIRNESS_WEIGHT = 0.3;
    private static final double AGE_WEIGHT = 0.3;

    private static final long MOD_RESET_INTERVAL_NANOS = 60_000_000_000L; 
    private static final long DEFAULT_MAX_TASKS = Long.getLong("quantified.maxTasksPerMod", 1024L);
    private static final ConcurrentHashMap<String, Long> MAX_TASKS_BY_MOD = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ModStats> modStats = new ConcurrentHashMap<>();
    private final LongAdder totalTasks = new LongAdder();
    private volatile long lastResetNanos = System.nanoTime();

    public double computeModPriority(String modId, PriorityTaskType baseType, long taskAgeNanos) {
        ModStats stats = modStats.computeIfAbsent(modId, id -> new ModStats());

        long now = System.nanoTime();
        if (now - lastResetNanos > MOD_RESET_INTERVAL_NANOS) {
            resetAllStats();
            lastResetNanos = now;
        }

        double baseScore = baseType.defaultScore();
        double fairnessMultiplier = computeFairnessMultiplier(stats);
        double activityPenalty = computeActivityPenalty(stats);
        double ageBonus = computeAgeBonus(taskAgeNanos);
        double boost = stats.priorityBoost;

        double finalScore = (baseScore * fairnessMultiplier * (1.0 - activityPenalty) + ageBonus) * (1.0 + boost);

        return Math.max(0.0, Math.min(10.0, finalScore));
    }

    public boolean canAcceptTask(String modId) {
        return getInFlight(modId) < resolveMaxTasks(modId);
    }

    public void recordTaskStart(String modId) {
        modStats.computeIfAbsent(modId, id -> new ModStats()).activeTasks.incrementAndGet();
        totalTasks.increment();
    }

    public void recordTaskComplete(String modId) {
        ModStats stats = modStats.get(modId);
        if (stats != null) {
            stats.activeTasks.updateAndGet(current -> current > 0 ? current - 1 : 0);
            stats.inFlightTasks.updateAndGet(current -> current > 0 ? current - 1 : 0);
            stats.completedTasks.increment();
        }
        totalTasks.decrement();
    }

    public void releaseReservation(String modId) {
        ModStats stats = modStats.get(modId);
        if (stats != null) {
            stats.inFlightTasks.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }
    }

    private double computeFairnessMultiplier(ModStats stats) {
        long total = totalTasks.sum();
        if (total == 0) return 1.0;

        double modShare = (double) stats.activeTasks.get() / total;
        double targetShare = 1.0 / modStats.size();

        if (modShare < targetShare) {
            return 1.0 + (FAIRNESS_WEIGHT * (targetShare - modShare) / targetShare);
        } else {
            return Math.max(0.5, 1.0 - (FAIRNESS_WEIGHT * (modShare - targetShare) / targetShare));
        }
    }

    private double computeActivityPenalty(ModStats stats) {
        return 0.0;
    }

    private double computeAgeBonus(long taskAgeNanos) {
        double ageSeconds = taskAgeNanos / 1_000_000_000.0;
        return Math.min(AGE_WEIGHT, ageSeconds / 60.0 * AGE_WEIGHT);
    }

    private void resetAllStats() {
        for (ModStats stats : modStats.values()) {
            stats.completedTasks.reset();
        }
    }

    public void escalatePriority(String modId, String reason) {
        ModStats stats = modStats.computeIfAbsent(modId, id -> new ModStats());
        stats.priorityBoost = Math.min(2.0, stats.priorityBoost + 0.5);
    }

    public double getPriorityBoost(String modId) {
        ModStats stats = modStats.get(modId);
        return stats != null ? stats.priorityBoost : 0.0;
    }

    public static void setMaxTasksForMod(String modId, long maxTasks) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        if (maxTasks <= 0) {
            MAX_TASKS_BY_MOD.remove(modId);
            return;
        }
        MAX_TASKS_BY_MOD.put(modId, maxTasks);
    }

    public boolean tryReserveTask(String modId) {
        ModStats stats = modStats.computeIfAbsent(modId, id -> new ModStats());
        long limit = resolveMaxTasks(modId);
        long inFlight = stats.inFlightTasks.incrementAndGet();
        if (inFlight > limit) {
            stats.inFlightTasks.decrementAndGet();
            return false;
        }
        return true;
    }

    private static long resolveMaxTasks(String modId) {
        if (modId == null || modId.isBlank()) {
            return DEFAULT_MAX_TASKS;
        }
        return MAX_TASKS_BY_MOD.getOrDefault(modId, DEFAULT_MAX_TASKS);
    }

    private long getInFlight(String modId) {
        ModStats stats = modStats.get(modId);
        if (stats == null) {
            return 0L;
        }
        return stats.inFlightTasks.get();
    }

    private static final class ModStats {
        final AtomicLong activeTasks = new AtomicLong();
        final AtomicLong inFlightTasks = new AtomicLong();
        final LongAdder completedTasks = new LongAdder();
        volatile double priorityBoost = 0.0;
        ModStats() {}
    }

    public record ModPrioritySnapshot(
        long activeTasks,
        long completedTasks,
        double fairnessMultiplier,
        double activityPenalty
    ) {}
}
