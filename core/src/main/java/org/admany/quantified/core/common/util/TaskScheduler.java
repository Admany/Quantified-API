package org.admany.quantified.core.common.util;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.gpu.GpuWorkloadRegistry;
import org.admany.quantified.core.common.async.gpu.OpenClBatchWorkload;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.admany.quantified.api.QuantifiedAPI;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class TaskScheduler {

    private static final Logger LOGGER = Logger.getLogger(TaskScheduler.class.getName());

    // Performance thresholds and heuristics
    private static final long MIN_GPU_DATA_SIZE = 1024 * 4; // 4KB minimum for GPU worth it
    private static final long OPTIMAL_GPU_DATA_SIZE = 1024 * 1024 * 50; // 50MB sweet spot
    private static final int MIN_PARALLEL_UNITS = 256; // Minimum parallel work units
    private static final double GPU_SPEEDUP_THRESHOLD = 1.05; // Need at least ~5% speedup to justify GPU
    private static final long GPU_AGGRESSIVE_MIN_BYTES = 1024; // 1KB minimum for aggressive GPU selection
    private static final double GPU_AGGRESSIVE_UTIL_LIMIT = 0.70; // Prefer GPU when usage is below this threshold
    private static final long GPU_BATCH_TARGET_NANOS = 2_000_000L; // ~2ms kernel target
    private static final double GPU_BATCH_EMA_ALPHA = 0.20d;

    // Performance tracking
    private static final AtomicLong totalTasksScheduled = new AtomicLong(0);
    private static final AtomicLong gpuTasksExecuted = new AtomicLong(0);
    private static final AtomicLong cpuTasksExecuted = new AtomicLong(0);

    private static TaskMetadata.GpuBatchWorkload gpuBatchWorkload = OpenClBatchWorkload.INSTANCE;

    private TaskScheduler() {}

    public enum TaskComplexity {
        SIMPLE,
        MODERATE,
        COMPLEX,
        MASSIVE
    }

    public enum TaskType {
        VECTOR_MATH,
        MATRIX_MATH,
        STATISTICAL,
        SIMULATION,
        SIGNAL_PROCESSING,
        SPATIAL_ANALYSIS,
        GENERAL
    }

    /**
     * Submit a compute task for optimal execution (CPU or GPU automatically selected).
     *
     * @param modId Mod identifier for tracking
     * @param taskName Human-readable task name
     * @param taskKey Unique task identifier for deduplication
     * @param cpuImplementation CPU fallback implementation
     * @param gpuTask Optional GPU-accelerated task (null if no GPU implementation)
     * @param dataSizeBytes Estimated data size in bytes
     * @param parallelUnits Estimated number of parallel work units
     * @param complexity Task complexity level
     * @param type Task type for scheduling hints
     * @param timeout Optional timeout
     * @return CompletableFuture with the result
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> submitComputeTask(
            String modId,
            String taskName,
            long taskKey,
            Supplier<T> cpuImplementation,
            Object gpuTask,
            long dataSizeBytes,
            int parallelUnits,
            TaskComplexity complexity,
            TaskType type,
            Duration timeout,
            boolean allowMainThreadRerouting) {

        OpenCLTask<T> effectiveGpuTask = null;
        if (gpuTask instanceof OpenCLTask) {
            effectiveGpuTask = (OpenCLTask<T>) gpuTask;
        } else if (gpuTask instanceof org.admany.quantified.api.opencl.QuantifiedOpenCL.ApiOpenClTask) {
            effectiveGpuTask = new org.admany.quantified.core.common.opencl.core.ApiOpenClTaskWrapper<T>(
                (org.admany.quantified.api.opencl.QuantifiedOpenCL.ApiOpenClTask<T>) gpuTask);
        }

        if (effectiveGpuTask instanceof org.admany.quantified.core.common.opencl.core.CacheableOpenCLTask<?> cacheableTask) {
            String cacheKey = cacheableTask.cacheKey();
            if (cacheKey != null && !cacheKey.isBlank()) {
                org.admany.quantified.core.common.opencl.cache.TieredGpuCache.CacheHit hit =
                    org.admany.quantified.core.common.opencl.core.OpenCLManager.cacheGet(modId, cacheKey);
                if (hit.present() && hit.data() != null) {
                    try {
                        T cached = ((org.admany.quantified.core.common.opencl.core.CacheableOpenCLTask<T>) cacheableTask)
                            .decodeResult(hit.data());
                        if (cached != null) {
                            return CompletableFuture.completedFuture(cached);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        TaskAnalysis analysis = analyzeTask(dataSizeBytes, parallelUnits, complexity, type);

        ExecutionPlatform platform = decidePlatform(analysis, effectiveGpuTask);

        LOGGER.fine(() -> String.format("Task '%s' scheduled for %s (data: %d bytes, parallel: %d, complexity: %s)",
            taskName, platform, dataSizeBytes, parallelUnits, complexity));

        boolean gpuSelected = platform == ExecutionPlatform.GPU && effectiveGpuTask != null;
        if (gpuSelected) {
            recordGpuTaskScheduled();
            GpuWorkloadRegistry.register(taskKey, effectiveGpuTask);
        } else {
            recordCpuTaskScheduled();
        }

        TaskMetadata metadata = buildMetadata(analysis, gpuSelected, modId, taskName, dataSizeBytes, parallelUnits, complexity, type);

        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] TaskScheduler: Routing task " + taskKey + " via AsyncManager (gpuSelected=" + gpuSelected + ")");
        }

        if (gpuSelected) {
            Supplier<CompletableFuture<T>> asyncSupplier = () -> resolveGpuOrCpu(taskKey, cpuImplementation);
            return AsyncManager.submitAsync(
                taskKey,
                PriorityTaskType.BUILDING,
                PriorityTaskType.BUILDING.defaultScore(),
                asyncSupplier,
                timeout,
                allowMainThreadRerouting,
                modId,
                metadata
            );
        }

        return AsyncManager.submitSync(
            taskKey,
            PriorityTaskType.BUILDING,
            PriorityTaskType.BUILDING.defaultScore(),
            cpuImplementation,
            timeout,
            allowMainThreadRerouting,
            modId,
            metadata
        );
    }

    public static <T> CompletableFuture<T> submitComputeTask(
            String modId,
            String taskName,
            long taskKey,
            Supplier<T> cpuImplementation,
            Object gpuTask,
            long dataSizeBytes,
            int parallelUnits) {

        return submitComputeTask(modId, taskName, taskKey, cpuImplementation, gpuTask,
            dataSizeBytes, parallelUnits, TaskComplexity.MODERATE, TaskType.GENERAL, null, true);
    }

    public static <T> CompletableFuture<T> submitCpuTask(
            String modId,
            String taskName,
            long taskKey,
            Supplier<T> implementation,
            Duration timeout) {

        recordCpuTaskScheduled();

        return AsyncManager.submitSync(
            taskKey,
            PriorityTaskType.BUILDING,
            PriorityTaskType.BUILDING.defaultScore(),
            implementation,
            timeout,
            true,
            modId
        );
    }

    public static <T> CompletableFuture<T> submitSpatialAnalysisTask(
            String modId,
            String taskName,
            long taskKey,
            Supplier<T> cpuImplementation,
            Object gpuTask,
            long dataSizeBytes,
            int parallelUnits) {

        return submitComputeTask(modId, taskName, taskKey, cpuImplementation, gpuTask,
            dataSizeBytes, parallelUnits, TaskComplexity.COMPLEX, TaskType.SPATIAL_ANALYSIS, null, true);
    }

    public static <T> CompletableFuture<T> submitMassiveDataTask(
            String modId,
            String taskName,
            long taskKey,
            Supplier<T> cpuImplementation,
            Object gpuTask,
            long dataSizeBytes,
            int parallelUnits) {

        return submitComputeTask(modId, taskName, taskKey, cpuImplementation, gpuTask,
            dataSizeBytes, parallelUnits, TaskComplexity.MASSIVE, TaskType.GENERAL, null, true);
    }

    private static TaskAnalysis analyzeTask(long dataSizeBytes, int parallelUnits,
            TaskComplexity complexity, TaskType type) {

        // Calculate parallelism efficiency
        double parallelismRatio = parallelUnits / (double) Math.max(1, dataSizeBytes / 1024);

        // Calculate data transfer cost (rough estimate)
        double transferCostMs = (dataSizeBytes / (1024.0 * 1024.0)) * 10; // ~10ms per MB

        // Calculate expected GPU speedup based on task type
        double expectedSpeedup = calculateExpectedSpeedup(complexity, type, dataSizeBytes, parallelUnits);

        // Consider data size efficiency
        boolean dataSizeEfficient = dataSizeBytes >= MIN_GPU_DATA_SIZE;
        boolean dataSizeOptimal = dataSizeBytes >= OPTIMAL_GPU_DATA_SIZE;

        return new TaskAnalysis(
            dataSizeBytes,
            parallelUnits,
            complexity,
            type,
            parallelismRatio,
            transferCostMs,
            expectedSpeedup,
            dataSizeEfficient,
            dataSizeOptimal
        );
    }

    private static TaskMetadata buildMetadata(TaskAnalysis analysis,
                                              boolean gpuSelected,
                                              String modId,
                                              String taskName,
                                              long dataSizeBytes,
                                              int parallelUnits,
                                              TaskComplexity complexity,
                                              TaskType type) {
        TaskMetadata.Builder builder = TaskMetadata.builder();
        double estimatedCost = Math.max(1.0, dataSizeBytes / 4096.0);
        builder.estimatedCost(estimatedCost);
        if (taskName != null && !taskName.isBlank()) {
            builder.affinityKey(taskName);
        } else {
            builder.affinityKey(modId + ":" + type.name());
        }
        int preferredBatch = Math.max(4, Math.min(64, Math.max(1, parallelUnits / 64)));
        if (gpuSelected) {
            preferredBatch = adjustGpuPreferredBatch(preferredBatch);
        }
        int preferred = Math.max(1, preferredBatch);
        builder.preferredBatchSize(preferred);
        builder.maximumBatchSize(Math.max(preferred, preferred * 2));
          if (gpuSelected) {
              builder.gpuPreferred(true);
              builder.batchable(true);
              builder.gpuWorkload(gpuBatchWorkload);
              if (analysis.dataSizeOptimal() || complexity == TaskComplexity.MASSIVE) {
                  builder.gpuRequired(true);
              }
          } else {
              builder.batchable(false);
          }
        return builder.build();
    }

    private static ExecutionPlatform decidePlatform(TaskAnalysis analysis, OpenCLTask<?> gpuTask) {
        if (gpuTask == null) {
            return ExecutionPlatform.CPU;
        }

        if (!OpenCLManager.isAvailable()) {
            return ExecutionPlatform.CPU;
        }

        boolean forceOpenCl = MultithreadingConfig.CONFIG != null
            && MultithreadingConfig.CONFIG.enableGpuAcceleration
            && MultithreadingConfig.CONFIG.openclForced;

        boolean capacityOk = canGpuAcceptTask(analysis, gpuTask);
        if (!capacityOk) {
            if (forceOpenCl) {
                LOGGER.fine(() -> "GPU capacity limit reached while OpenCL forcing enabled; routing task to CPU to avoid VRAM exhaustion.");
            }
            return ExecutionPlatform.CPU;
        }

        if (forceOpenCl) {
            return ExecutionPlatform.GPU;
        }

        if (shouldPreferGpu(analysis)) {
            return ExecutionPlatform.GPU;
        }

        if (!analysis.dataSizeEfficient) {
            return ExecutionPlatform.CPU;
        }

        if ((analysis.complexity == TaskComplexity.MODERATE || analysis.complexity == TaskComplexity.COMPLEX || analysis.complexity == TaskComplexity.MASSIVE) && analysis.expectedSpeedup >= GPU_SPEEDUP_THRESHOLD) {
            return ExecutionPlatform.GPU;
        }

        if (analysis.dataSizeOptimal && analysis.parallelUnits >= MIN_PARALLEL_UNITS) {
            return ExecutionPlatform.GPU;
        }

        if (analysis.parallelismRatio > 0.1 && analysis.expectedSpeedup >= GPU_SPEEDUP_THRESHOLD) {
            return ExecutionPlatform.GPU;
        }

        return ExecutionPlatform.CPU;
    }

    private static boolean shouldPreferGpu(TaskAnalysis analysis) {
        if (analysis == null) {
            return false;
        }
        if (analysis.dataSizeBytes() < GPU_AGGRESSIVE_MIN_BYTES) {
            return false;
        }
        if (analysis.expectedSpeedup() < 1.0d) {
            return false;
        }
        org.admany.quantified.core.common.opencl.gpu.GPUMonitor.GPUStatus status = OpenCLManager.getGPUStatus();
        if (status == null) {
            return false;
        }
        if (status.computeUtilization() > GPU_AGGRESSIVE_UTIL_LIMIT) {
            return false;
        }
        if (status.memoryUtilization() > GPU_AGGRESSIVE_UTIL_LIMIT) {
            return false;
        }
        return true;
    }

    private static boolean canGpuAcceptTask(TaskAnalysis analysis, OpenCLTask<?> gpuTask) {
        if (gpuTask == null) {
            return false;
        }

        if (!OpenCLManager.isAvailable()) {
            return false;
        }

        if (OpenCLManager.isInVramPressureCooldown()) {
            if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                LOGGER.fine("Skipping GPU execution due to recent VRAM saturation cooldown");
            }
            return false;
        }

        org.admany.quantified.core.common.opencl.gpu.GPUMonitor.GPUStatus status = OpenCLManager.getGPUStatus();
        if (status == null || status.totalVramBytes() <= 0) {
            return true;
        }

        boolean accepted = OpenCLManager.canAcceptTask(gpuTask);
        if (!accepted && LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            long estimatedVram = Math.max(0L, gpuTask.estimatedVramBytes());
            int estimatedCompute = Math.max(0, gpuTask.estimatedComputeUnits());
            long totalVram = status.totalVramBytes();
            long usedVram = status.usedVramBytes();
            double memoryUtil = status.memoryUtilization() * 100.0d;
            LOGGER.fine(String.format(
                "GPU capacity rejection (estimated VRAM=%d bytes, compute=%d units, dataSize=%d bytes, status=%d/%d bytes %.1f%%)",
                estimatedVram,
                estimatedCompute,
                analysis != null ? analysis.dataSizeBytes() : -1L,
                usedVram,
                totalVram,
                memoryUtil));
        }
        return accepted;
    }

    private static double calculateExpectedSpeedup(TaskComplexity complexity, TaskType type,
            long dataSizeBytes, int parallelUnits) {

        double baseSpeedup = 1.0;

        // Complexity multiplier
        switch (complexity) {
            case SIMPLE -> baseSpeedup = 0.8; // GPU overhead might not be worth it
            case MODERATE -> baseSpeedup = 2.0;
            case COMPLEX -> baseSpeedup = 5.0;
            case MASSIVE -> baseSpeedup = 10.0;
        }

        switch (type) {
            case VECTOR_MATH, MATRIX_MATH -> baseSpeedup *= 1.5; // Excellent for GPUs
            case SIMULATION -> baseSpeedup *= 1.3; // Good parallelism
            case SIGNAL_PROCESSING, SPATIAL_ANALYSIS -> baseSpeedup *= 1.4; // Data parallel
            case STATISTICAL -> baseSpeedup *= 1.1; // Moderate benefit
            case GENERAL -> {} // No adjustment
        }

        // Data size scaling (larger = better GPU utilization)
        double sizeMultiplier = Math.min(2.0, dataSizeBytes / (1024.0 * 1024.0)); // Max 2x for 1MB+
        baseSpeedup *= Math.max(0.5, sizeMultiplier);

        // Parallelism scaling
        double parallelMultiplier = Math.min(2.0, parallelUnits / 1000.0); // Max 2x for 1000+ units
        baseSpeedup *= Math.max(0.5, parallelMultiplier);

        return baseSpeedup;
    }

    public static SchedulingStats getStats() {
        long total = totalTasksScheduled.get();
        long gpu = gpuTasksExecuted.get();
        long cpu = cpuTasksExecuted.get();
        double gpuRatio = total > 0 ? (gpu / (double) total) : 0.0;

        return new SchedulingStats(total, gpu, cpu, gpuRatio);
    }

    /**
     * Submit a batch of compute tasks with smart grouping by resource hints.
     * Tasks are automatically grouped by CPU/GPU requirements and executed efficiently.
     *
     * @param modId Mod identifier for tracking
     * @param batchName Human-readable batch name
     * @param tasks List of tasks to submit
     * @param groupBy Function to determine resource grouping (e.g., ResourceHint.GPU)
     * @return CompletableFuture with list of results in submission order
     */
    public static <R> CompletableFuture<java.util.List<R>> submitBatch(
            String modId,
            String batchName,
            java.util.List<TaskBatchItem<R>> tasks,
            java.util.function.Function<TaskBatchItem<R>, ResourceHint> groupBy) {

        if (tasks == null || tasks.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.List.of());
        }

        java.util.Map<ResourceHint, java.util.List<IndexedTask<R>>> groupedTasks = new java.util.EnumMap<>(ResourceHint.class);
        for (int i = 0; i < tasks.size(); i++) {
            TaskBatchItem<R> task = tasks.get(i);
            ResourceHint hint = groupBy.apply(task);
            if (hint == null) {
                hint = ResourceHint.CPU;
            }
            groupedTasks.computeIfAbsent(hint, k -> new java.util.ArrayList<>()).add(new IndexedTask<>(i, task));
        }

        Object[] orderedResults = new Object[tasks.size()];
        java.util.List<CompletableFuture<Void>> groupFutures = new java.util.ArrayList<>(groupedTasks.size());

        for (java.util.Map.Entry<ResourceHint, java.util.List<IndexedTask<R>>> entry : groupedTasks.entrySet()) {
            ResourceHint hint = entry.getKey();
            java.util.List<IndexedTask<R>> group = entry.getValue();
            java.util.List<TaskBatchItem<R>> groupTasks = new java.util.ArrayList<>(group.size());
            for (IndexedTask<R> indexed : group) {
                groupTasks.add(indexed.task);
            }

            CompletableFuture<java.util.List<R>> groupFuture = submitTaskGroup(modId, batchName, groupTasks, hint);
            CompletableFuture<Void> fillFuture = groupFuture.thenAccept(results -> {
                for (int i = 0; i < results.size(); i++) {
                    orderedResults[group.get(i).index] = results.get(i);
                }
            });
            groupFutures.add(fillFuture);
        }

        return CompletableFuture.allOf(groupFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                java.util.List<R> results = new java.util.ArrayList<>(tasks.size());
                for (Object value : orderedResults) {
                    @SuppressWarnings("unchecked")
                    R cast = (R) value;
                    results.add(cast);
                }
                return results;
            });
    }

    private static <R> CompletableFuture<java.util.List<R>> submitTaskGroup(
            String modId,
            String batchName,
            java.util.List<TaskBatchItem<R>> tasks,
            ResourceHint hint) {

        java.util.List<CompletableFuture<R>> futures = new java.util.ArrayList<>();

        for (TaskBatchItem<R> task : tasks) {
            long taskKey = (modId.hashCode() * 31L + task.name().hashCode()) & 0x7FFFFFFFFFFFFFFFL;

            CompletableFuture<R> future;
            if (hint == ResourceHint.GPU && task.gpuTask() != null) {
                // GPU-optimized submission
                future = submitComputeTask(
                    modId,
                    task.name(),
                    taskKey,
                    task.cpuImplementation(),
                    task.gpuTask(),
                    task.dataSizeBytes(),
                    task.parallelUnits(),
                    TaskComplexity.COMPLEX,  // Use COMPLEX for GPU tasks to ensure better scheduling
                    TaskType.GENERAL,
                    null,
                    true // Default to allowing main thread rerouting for batch tasks
                );
            } else {
                // CPU submission
                future = submitCpuTask(modId, task.name(), taskKey, task.cpuImplementation(), null);
            }
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }

    public enum ResourceHint {
        CPU, GPU
    }

    private static final class IndexedTask<R> {
        private final int index;
        private final TaskBatchItem<R> task;

        private IndexedTask(int index, TaskBatchItem<R> task) {
            this.index = index;
            this.task = task;
        }
    }

    public static record TaskBatchItem<R>(
        String name,
        java.util.function.Supplier<R> cpuImplementation,
        Object gpuTask,
        long dataSizeBytes,
        int parallelUnits
    ) {

        public static <R> TaskBatchItem<R> of(
                String name,
                java.util.function.Supplier<R> cpuImplementation,
                Object gpuTask,
                long dataSizeBytes,
                int parallelUnits) {
            return new TaskBatchItem<>(name, cpuImplementation, gpuTask, dataSizeBytes, parallelUnits);
        }
    }

    private enum ExecutionPlatform {
        CPU, GPU
    }

    private record TaskAnalysis(
        long dataSizeBytes,
        int parallelUnits,
        TaskComplexity complexity,
        TaskType type,
        double parallelismRatio,
        double transferCostMs,
        double expectedSpeedup,
        boolean dataSizeEfficient,
        boolean dataSizeOptimal
    ) {}

    public record SchedulingStats(
        long totalTasks,
        long gpuTasks,
        long cpuTasks,
        double gpuUtilizationRatio
    ) {}

    public static void resetStats() {
        totalTasksScheduled.set(0);
        gpuTasksExecuted.set(0);
        cpuTasksExecuted.set(0);
    }

    public static void recordExternalCpuTask() {
        recordCpuTaskScheduled();
    }

    public static void recordExternalGpuTask() {
        recordGpuTaskScheduled();
    }

    public static void recordGpuKernelDuration(long durationNanos) {
        AdaptiveGpuBatchSizer.record(durationNanos);
    }

    private static void recordCpuTaskScheduled() {
        totalTasksScheduled.incrementAndGet();
        cpuTasksExecuted.incrementAndGet();
    }

    private static void recordGpuTaskScheduled() {
        totalTasksScheduled.incrementAndGet();
        gpuTasksExecuted.incrementAndGet();
    }

    private static <T> CompletableFuture<T> resolveGpuOrCpu(long taskKey, Supplier<T> cpuImplementation) {
        CompletableFuture<Object> gpuFuture = GpuWorkloadRegistry.result(taskKey);
        CompletableFuture<T> resolved;
        if (gpuFuture == null) {
            resolved = CompletableFuture.supplyAsync(cpuImplementation);
        } else {
            resolved = gpuFuture.handle((value, error) -> {
                if (error == null) {
                    @SuppressWarnings("unchecked")
                    T cast = (T) value;
                    return CompletableFuture.completedFuture(cast);
                }
                LOGGER.fine(() -> "GPU result unavailable for task " + taskKey + ": " + error.getMessage());
                return CompletableFuture.supplyAsync(cpuImplementation);
            }).thenCompose(result -> result);
        }
        return resolved.whenComplete((ignored, throwable) -> GpuWorkloadRegistry.cancel(taskKey));
    }

    static void setGpuWorkloadForTesting(TaskMetadata.GpuBatchWorkload workload) {
        gpuBatchWorkload = workload == null ? OpenClBatchWorkload.INSTANCE : workload;
    }

    private static int adjustGpuPreferredBatch(int base) {
        return AdaptiveGpuBatchSizer.adjust(base);
    }

    private static final class AdaptiveGpuBatchSizer {
        private static final java.util.concurrent.atomic.AtomicLong emaNanos = new java.util.concurrent.atomic.AtomicLong(0L);

        private static void record(long durationNanos) {
            if (durationNanos <= 0L) {
                return;
            }
            long current = emaNanos.get();
            long next = current == 0L
                ? durationNanos
                : (long) (current * (1.0d - GPU_BATCH_EMA_ALPHA) + durationNanos * GPU_BATCH_EMA_ALPHA);
            emaNanos.set(next);
        }

        private static int adjust(int base) {
            long avg = emaNanos.get();
            if (avg <= 0L) {
                return base;
            }
            double ratio = (double) GPU_BATCH_TARGET_NANOS / Math.max(1.0d, avg);
            double scaled = base * Math.max(0.5d, Math.min(4.0d, ratio));
            int rounded = (int) Math.round(scaled);
            return Math.max(2, Math.min(256, rounded));
        }
    }
}
