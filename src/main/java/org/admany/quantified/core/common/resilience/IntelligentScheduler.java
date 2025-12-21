package org.admany.quantified.core.common.resilience;

import org.admany.quantified.core.common.util.TaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class IntelligentScheduler {
    private static final Logger LOGGER = Logger.getLogger(IntelligentScheduler.class.getName());

    private static final IntelligentScheduler INSTANCE = new IntelligentScheduler();

    // Mod execution tracking
    private final Map<String, ModExecutionStats> modStats = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<ScheduledTask<?>> taskQueue = new PriorityBlockingQueue<>(100, new TaskComparator());

    // Fairness controls
    private final AtomicLong totalTasksScheduled = new AtomicLong(0);

    private IntelligentScheduler() {
        // Start background fairness monitor
        startFairnessMonitor();
    }

    public static IntelligentScheduler getInstance() {
        return INSTANCE;
    }

    public <T> CompletableFuture<T> submitTask(String modId, String taskName, Supplier<T> task,
                                             TaskPriority priority, ResourceRequirements requirements) {

        ScheduledTask<T> scheduledTask = new ScheduledTask<>(
            modId, taskName, task, priority, requirements, totalTasksScheduled.incrementAndGet()
        );

        modStats.computeIfAbsent(modId, ModExecutionStats::new)
                .recordTaskSubmitted(priority);
        if (!taskQueue.offer(scheduledTask)) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("Task queue is full"));
            return failed;
        }

        return processNextTask();
    }

    private <T> CompletableFuture<T> processNextTask() {
        @SuppressWarnings("unchecked")
        ScheduledTask<T> polledTask = (ScheduledTask<T>) taskQueue.poll();
        if (polledTask == null) {
            ErrorHandler.QuantifiedException exception = ErrorHandler.getInstance().createException(
                ErrorHandler.ErrorType.SCHEDULER_OVERLOADED,
                "Task queue processing",
                Map.of("queueSize", taskQueue.size())
            );
            throw exception;
        }

        String modId = polledTask.modId;

        if (shouldThrottleMod(modId)) {
            ScheduledTask<T> throttledTask = new ScheduledTask<>(
                polledTask.modId, polledTask.taskName, polledTask.task,
                TaskPriority.LOW, polledTask.requirements, polledTask.sequenceNumber
            );
            taskQueue.offer(throttledTask);
            return processNextTask();
        }

        if (!isSystemHealthy()) {
            if (polledTask.priority != TaskPriority.CRITICAL) {
                taskQueue.offer(polledTask); // Re-queue
                CompletableFuture<T> deferred = new CompletableFuture<>();
                final ScheduledTask<T> taskToRetry = polledTask;
                CompletableFuture.delayedExecutor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        submitTask(taskToRetry.modId, taskToRetry.taskName, taskToRetry.task,
                                 taskToRetry.priority, taskToRetry.requirements)
                            .whenComplete((result, error) -> {
                                if (error != null) {
                                    deferred.completeExceptionally(error);
                                } else {
                                    deferred.complete(result);
                                }
                            });
                    });
                return deferred;
            }
        }

        final ScheduledTask<T> taskToExecute = polledTask;
        ModExecutionStats stats = modStats.get(modId);
        stats.recordTaskStarted();

        try {
            CompletableFuture<T> result = TaskScheduler.submitCpuTask(
                modId, taskToExecute.taskName, taskToExecute.sequenceNumber, taskToExecute.task, Duration.ofSeconds(30)
            );

            return result.whenComplete((res, error) -> {
                if (error != null) {
                    stats.recordTaskFailed();
                } else {
                    stats.recordTaskCompleted();
                }
            });

        } catch (Exception e) {
            stats.recordTaskFailed();
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private boolean shouldThrottleMod(String modId) {
        ModExecutionStats stats = modStats.get(modId);
        if (stats == null) return false;

        double averageTasksPerMod = (double) totalTasksScheduled.get() / modStats.size();
        double modTaskRatio = stats.getTotalTasks() / averageTasksPerMod;

        return modTaskRatio > 2.0 && stats.hasActiveTasks();
    }

    private boolean isSystemHealthy() {
        return AutoHealthChecker.getInstance().isHealthy();
    }

    private void startFairnessMonitor() {
        CompletableFuture.delayedExecutor(10000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute(() -> {
                try {
                    adjustFairness();
                } finally {
                    startFairnessMonitor(); 
                }
            });
    }

    private void adjustFairness() {
        List<Map.Entry<String, ModExecutionStats>> sortedMods = modStats.entrySet().stream()
            .sorted(Comparator.comparingDouble(e -> e.getValue().getFairnessScore()))
            .collect(Collectors.toList());

        if (!sortedMods.isEmpty()) {
            LOGGER.fine("Fairness adjustment - Mod execution ratios: " +
                sortedMods.stream()
                    .map(e -> e.getKey() + "=" + String.format("%.2f", e.getValue().getFairnessScore()))
                    .collect(Collectors.joining(", ")));
        }
    }

    public SchedulingReport getSchedulingReport() {
        Map<String, ModStats> modReports = modStats.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> new ModStats(
                    e.getValue().getTotalTasks(),
                    e.getValue().getActiveTasks(),
                    e.getValue().getFailedTasks(),
                    e.getValue().getFairnessScore()
                )
            ));

        var healthReport = AutoHealthChecker.getInstance().getHealthReport();

        return new SchedulingReport(
            totalTasksScheduled.get(),
            taskQueue.size(),
            modReports,
            healthReport.status(),
            healthReport.lastCheck(),
            healthReport.timeSinceLastCheck()
        );
    }

    public enum TaskPriority {
        CRITICAL(4), HIGH(3), NORMAL(2), LOW(1);

        private final int value;
        TaskPriority(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    public record ResourceRequirements(
        long estimatedMemoryBytes,
        int estimatedCpuUnits,
        boolean requiresGpu
    ) {}

    private static class ScheduledTask<T> {
        final String modId;
        final String taskName;
        final Supplier<T> task;
        final TaskPriority priority;
        final ResourceRequirements requirements;
        final long sequenceNumber;

        ScheduledTask(String modId, String taskName, Supplier<T> task,
                     TaskPriority priority, ResourceRequirements requirements, long sequenceNumber) {
            this.modId = modId;
            this.taskName = taskName;
            this.task = task;
            this.priority = priority;
            this.requirements = requirements;
            this.sequenceNumber = sequenceNumber;
        }
    }

    private static class TaskComparator implements Comparator<ScheduledTask<?>> {
        @Override
        public int compare(ScheduledTask<?> t1, ScheduledTask<?> t2) {
            int priorityCompare = Integer.compare(t2.priority.getValue(), t1.priority.getValue());
            if (priorityCompare != 0) return priorityCompare;

            ModExecutionStats stats1 = INSTANCE.modStats.get(t1.modId);
            ModExecutionStats stats2 = INSTANCE.modStats.get(t2.modId);

            if (stats1 != null && stats2 != null) {
                double fairness1 = stats1.getFairnessScore();
                double fairness2 = stats2.getFairnessScore();
                int fairnessCompare = Double.compare(fairness1, fairness2);
                if (fairnessCompare != 0) return fairnessCompare;
            }

            return Long.compare(t1.sequenceNumber, t2.sequenceNumber);
        }
    }

    private static class ModExecutionStats {
        private final AtomicLong totalTasks = new AtomicLong(0);
        private final AtomicLong activeTasks = new AtomicLong(0);
        private final AtomicLong failedTasks = new AtomicLong(0);
        private volatile Instant lastExecution = Instant.now();

        ModExecutionStats(String modId) {
        }

        void recordTaskSubmitted(TaskPriority priority) {
            totalTasks.incrementAndGet();
        }

        void recordTaskStarted() {
            activeTasks.incrementAndGet();
            lastExecution = Instant.now();
        }

        void recordTaskCompleted() {
            activeTasks.decrementAndGet();
        }

        void recordTaskFailed() {
            activeTasks.decrementAndGet();
            failedTasks.incrementAndGet();
        }

        long getTotalTasks() { return totalTasks.get(); }
        long getActiveTasks() { return activeTasks.get(); }
        long getFailedTasks() { return failedTasks.get(); }

        boolean hasActiveTasks() { return activeTasks.get() > 0; }

        double getFairnessScore() {
            long total = totalTasks.get();
            if (total == 0) return 1.0;

            double failureRate = (double) failedTasks.get() / total;
            double recencyBonus = Math.max(0, 1.0 - (Duration.between(lastExecution, Instant.now()).toMillis() / 60000.0)); // 1 minute window

            return 1.0 / (total + recencyBonus - failureRate);
        }
    }

    public record SchedulingReport(
        long totalTasksScheduled,
        int queueSize,
        Map<String, ModStats> modStats,
        AutoHealthChecker.HealthStatus healthStatus,
        Instant lastHealthCheck,
        Duration timeSinceLastHealthCheck
    ) {}

    public record ModStats(
        long totalTasks,
        long activeTasks,
        long failedTasks,
        double fairnessScore
    ) {}

    public void submitTask(String taskName, Runnable task, String modId, TaskPriority priority) {
        submitTask(modId, taskName, () -> {
            task.run();
            return null;
        }, priority, new ResourceRequirements(1024 * 1024, 1, false)); // Default requirements
    }

    public void submitTask(String taskName, Runnable task, String modId) {
        submitTask(taskName, task, modId, TaskPriority.NORMAL);
    }

    public java.util.Map<String, Object> getSchedulerStats() {
        SchedulingReport report = getSchedulingReport();
        return java.util.Map.of(
            "totalTasksScheduled", report.totalTasksScheduled(),
            "queueSize", report.queueSize(),
            "healthStatus", report.healthStatus().toString(),
            "modCount", report.modStats().size()
        );
    }

    public boolean isHealthy() {
        return isSystemHealthy();
    }
}