package org.admany.quantified.core.common.util;

import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.GpuBackendType;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.gpu.GpuWorkloadRegistry;
import org.admany.quantified.core.common.async.gpu.OpenClBatchWorkload;
import org.admany.quantified.core.common.async.gpu.VulkanBatchWorkload;
import org.admany.quantified.core.common.async.gpu.VulkanIsolatedBatchWorkload;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.gpu.backend.VulkanExecutionSupport;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.opencl.core.OpenCLIsolatedExecutor;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.admany.quantified.core.common.vulkan.core.ApiVulkanTaskWrapper;
import org.admany.quantified.core.common.vulkan.core.VulkanIsolatedExecutor;
import org.admany.quantified.core.common.vulkan.core.VulkanManager;
import org.admany.quantified.core.common.vulkan.core.VulkanTask;
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
    private static final double DEFAULT_GPU_AGGRESSIVE_UTIL_LIMIT = 0.70; // Prefer GPU when usage is below this threshold
    private static final long DEFAULT_GPU_BATCH_TARGET_NANOS = 2_000_000L; // ~2ms kernel target
    private static final double GPU_BATCH_EMA_ALPHA = 0.20d;
    private static final double LATENCY_EMA_ALPHA = 0.15d;

    // Performance tracking
    private static final AtomicLong totalTasksScheduled = new AtomicLong(0);
    private static final AtomicLong gpuTasksExecuted = new AtomicLong(0);
    private static final AtomicLong cpuTasksExecuted = new AtomicLong(0);
    private static final AtomicLong emaCpuNanos = new AtomicLong(0L);
    private static final AtomicLong emaGpuNanos = new AtomicLong(0L);
    private static volatile double runtimeGpuAggressiveUtilLimit = DEFAULT_GPU_AGGRESSIVE_UTIL_LIMIT;
    private static volatile long runtimeGpuBatchTargetNanos = DEFAULT_GPU_BATCH_TARGET_NANOS;

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
        return submitComputeTask(modId, taskName, taskKey, cpuImplementation, gpuTask, dataSizeBytes, parallelUnits,
            complexity, type, timeout, allowMainThreadRerouting, GpuBackendPreference.AUTO);
    }

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
            boolean allowMainThreadRerouting,
            GpuBackendPreference backendPreference) {

        return submitComputeTask(modId, taskName, taskKey, cpuImplementation, gpuTask, dataSizeBytes,
            parallelUnits, complexity, type, timeout, allowMainThreadRerouting, backendPreference, true);
    }

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
            boolean allowMainThreadRerouting,
            GpuBackendPreference backendPreference,
            boolean threadSafe) {

        OpenCLTask<T> effectiveOpenClTask = null;
        VulkanTask<T> effectiveVulkanTask = null;
        org.admany.quantified.api.opencl.QuantifiedOpenCL.ApiOpenClTask<T> apiOpenClTask = null;
        org.admany.quantified.api.vulkan.QuantifiedVulkan.ApiVulkanTask<T> apiVulkanTask = null;
        if (gpuTask instanceof OpenCLTask) {
            effectiveOpenClTask = (OpenCLTask<T>) gpuTask;
        } else if (gpuTask instanceof org.admany.quantified.api.opencl.QuantifiedOpenCL.ApiOpenClTask) {
            apiOpenClTask = (org.admany.quantified.api.opencl.QuantifiedOpenCL.ApiOpenClTask<T>) gpuTask;
            effectiveOpenClTask = new org.admany.quantified.core.common.opencl.core.ApiOpenClTaskWrapper<>(
                apiOpenClTask);
        } else if (gpuTask instanceof VulkanTask) {
            effectiveVulkanTask = (VulkanTask<T>) gpuTask;
        } else if (gpuTask instanceof org.admany.quantified.api.vulkan.QuantifiedVulkan.ApiVulkanTask) {
            apiVulkanTask = (org.admany.quantified.api.vulkan.QuantifiedVulkan.ApiVulkanTask<T>) gpuTask;
            effectiveVulkanTask = new ApiVulkanTaskWrapper<>(apiVulkanTask);
        }

        if (effectiveOpenClTask instanceof org.admany.quantified.core.common.opencl.core.CacheableOpenCLTask<?> cacheableTask) {
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

        boolean isolatedOpenClEligible = apiOpenClTask != null
            && !OpenCLManager.isAvailable()
            && OpenCLManager.hasExecutableRuntime();
        boolean isolatedVulkanEligible = apiVulkanTask != null
            && !VulkanExecutionSupport.inProcessAvailable()
            && VulkanExecutionSupport.hasExecutableRuntime();

        TaskAnalysis analysis = analyzeTask(dataSizeBytes, parallelUnits, complexity, type);

        GpuBackendPreference effectivePreference = normalizeBackendPreference(modId, backendPreference);
        GpuBackendRouter.Selection backendSelection = GpuBackendRouter.selectBackend(
            modId,
            effectivePreference,
            effectiveOpenClTask != null,
            OpenCLManager.isAvailable() || isolatedOpenClEligible,
            effectiveVulkanTask != null,
            VulkanRuntime.isAvailable() || isolatedVulkanEligible
        );

        ExecutionPlatform platform = decidePlatform(
            analysis,
            effectiveOpenClTask,
            effectiveVulkanTask,
            backendSelection.backendType(),
            effectivePreference,
            isolatedOpenClEligible,
            isolatedVulkanEligible
        );

        LOGGER.fine(() -> String.format("Task '%s' scheduled for %s (data: %d bytes, parallel: %d, complexity: %s)",
            taskName, platform, dataSizeBytes, parallelUnits, complexity));

        boolean gpuSelected = platform == ExecutionPlatform.GPU
            && ((backendSelection.backendType() == GpuBackendType.OPENCL && effectiveOpenClTask != null)
            || (backendSelection.backendType() == GpuBackendType.VULKAN && effectiveVulkanTask != null));
        if (gpuSelected) {
            recordGpuTaskScheduled();
            if (backendSelection.backendType() == GpuBackendType.VULKAN) {
                GpuWorkloadRegistry.register(taskKey, effectiveVulkanTask);
            } else {
                GpuWorkloadRegistry.register(taskKey, effectiveOpenClTask);
            }
        } else {
            recordCpuTaskScheduled();
        }

        TaskMetadata metadata = buildMetadata(
            analysis,
            gpuSelected,
            backendSelection.backendType(),
            effectivePreference,
            modId,
            taskName,
            dataSizeBytes,
            parallelUnits,
            complexity,
            type,
            isolatedVulkanEligible
        );

        if (QuantifiedAPI.isPrintDebugLogs()) {
            LOGGER.fine("[DEBUG] TaskScheduler: Routing task " + taskKey + " via AsyncManager (gpuSelected=" + gpuSelected + ")");
        }

        if (gpuSelected) {
            // The dispatcher owns execution for both in-process and isolated
            // runtimes. The original isolated fast path executed the task in
            // this payload after VulkanIsolatedBatchWorkload had already run
            // it, doubling every isolated GPU dispatch.
            Supplier<CompletableFuture<T>> asyncSupplier = () -> resolveGpuOrCpu(taskKey, cpuImplementation);
            return AsyncManager.submitAsync(
                taskKey,
                PriorityTaskType.BUILDING,
                PriorityTaskType.BUILDING.defaultScore(),
                asyncSupplier,
                timeout,
                threadSafe,
                modId,
                metadata
            );
        }

        Supplier<T> measuredCpu = () -> executeMeasuredCpu(cpuImplementation);
        return AsyncManager.submitSync(
            taskKey,
            PriorityTaskType.BUILDING,
            PriorityTaskType.BUILDING.defaultScore(),
            measuredCpu,
            timeout,
            threadSafe,
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
                                              GpuBackendType backendType,
                                              GpuBackendPreference backendPreference,
                                              String modId,
                                              String taskName,
                                              long dataSizeBytes,
                                              int parallelUnits,
                                              TaskComplexity complexity,
                                              TaskType type,
                                              boolean isolatedVulkanEligible) {
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
              TaskMetadata.GpuBatchWorkload workload = resolveGpuBatchWorkload(backendType, isolatedVulkanEligible);
              builder.batchable(workload != null);
              if (workload != null) {
                  builder.gpuWorkload(workload);
              }
              if ((backendPreference != null && (backendPreference.requiresOpenCL() || backendPreference.requiresVulkan()))
                  || analysis.dataSizeOptimal()
                  || complexity == TaskComplexity.MASSIVE) {
                  builder.gpuRequired(true);
              }
          } else {
              builder.batchable(false);
          }
        return builder.build();
    }

    private static TaskMetadata.GpuBatchWorkload resolveGpuBatchWorkload(GpuBackendType backendType,
                                                                         boolean isolatedVulkanEligible) {
        if (backendType != GpuBackendType.VULKAN) {
            return gpuBatchWorkload;
        }
        if (VulkanExecutionSupport.inProcessAvailable()) {
            return VulkanBatchWorkload.INSTANCE;
        }
        return isolatedVulkanEligible ? VulkanIsolatedBatchWorkload.INSTANCE : null;
    }

    private static ExecutionPlatform decidePlatform(TaskAnalysis analysis,
                                                    OpenCLTask<?> openClTask,
                                                    VulkanTask<?> vulkanTask,
                                                    GpuBackendType backendType,
                                                    GpuBackendPreference backendPreference,
                                                    boolean isolatedOpenClEligible,
                                                    boolean isolatedVulkanEligible) {
        if (backendType == GpuBackendType.VULKAN) {
            return decideVulkanPlatform(analysis, vulkanTask, backendPreference, isolatedVulkanEligible);
        }
        if (backendType == GpuBackendType.OPENCL) {
            return decideOpenClPlatform(analysis, openClTask, backendPreference, isolatedOpenClEligible);
        }
        return ExecutionPlatform.CPU;
    }

    private static GpuBackendPreference normalizeBackendPreference(String modId,
                                                                  GpuBackendPreference backendPreference) {
        return GpuBackendRouter.resolvePreference(modId, backendPreference);
    }

    private static ExecutionPlatform decideOpenClPlatform(TaskAnalysis analysis,
                                                          OpenCLTask<?> gpuTask,
                                                          GpuBackendPreference backendPreference,
                                                          boolean isolatedOpenClEligible) {
        if (gpuTask == null || (!OpenCLManager.isAvailable() && !isolatedOpenClEligible)) {
            return ExecutionPlatform.CPU;
        }

        boolean forceOpenCl = MultithreadingConfig.CONFIG != null
            && MultithreadingConfig.CONFIG.enableGpuAcceleration
            && MultithreadingConfig.CONFIG.openclForced;

        boolean capacityOk = canGpuAcceptTask(analysis, gpuTask, isolatedOpenClEligible);
        if (!capacityOk) {
            if (forceOpenCl) {
                LOGGER.fine(() -> "GPU capacity limit reached while OpenCL forcing enabled; routing task to CPU to avoid VRAM exhaustion.");
            }
            return ExecutionPlatform.CPU;
        }

        if (backendPreference != null && backendPreference.requiresOpenCL()) {
            return ExecutionPlatform.GPU;
        }

        if (forceOpenCl || shouldPreferGpu(analysis)) {
            return ExecutionPlatform.GPU;
        }

        return decideGpuByAnalysis(analysis);
    }

    private static ExecutionPlatform decideVulkanPlatform(TaskAnalysis analysis,
                                                          VulkanTask<?> gpuTask,
                                                          GpuBackendPreference backendPreference,
                                                          boolean isolatedVulkanEligible) {
        if (gpuTask == null || (!VulkanRuntime.isAvailable() && !isolatedVulkanEligible)) {
            return ExecutionPlatform.CPU;
        }
        boolean required = backendPreference != null && backendPreference.requiresVulkan();
        if (!canGpuAcceptTask(analysis, gpuTask, isolatedVulkanEligible, required)) {
            return ExecutionPlatform.CPU;
        }
        if (required) {
            return ExecutionPlatform.GPU;
        }
        return decideGpuByAnalysis(analysis);
    }

    private static ExecutionPlatform decideGpuByAnalysis(TaskAnalysis analysis) {
        if (!analysis.dataSizeEfficient) {
            return ExecutionPlatform.CPU;
        }

        if ((analysis.complexity == TaskComplexity.MODERATE
            || analysis.complexity == TaskComplexity.COMPLEX
            || analysis.complexity == TaskComplexity.MASSIVE)
            && analysis.expectedSpeedup >= GPU_SPEEDUP_THRESHOLD) {
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
        double adaptiveUtilLimit = resolveAdaptiveGpuUtilLimit();
        if (status.computeUtilization() > adaptiveUtilLimit) {
            return false;
        }
        if (status.memoryUtilization() > adaptiveUtilLimit) {
            return false;
        }
        return true;
    }

    private static boolean canGpuAcceptTask(TaskAnalysis analysis,
                                            OpenCLTask<?> gpuTask,
                                            boolean isolatedOpenClEligible) {
        if (gpuTask == null) {
            return false;
        }

        if (!OpenCLManager.isAvailable()) {
            return isolatedOpenClEligible;
        }
        try {
            org.admany.quantified.core.common.async.core.PriorityScheduler.SchedulerSnapshot schedulerSnapshot = AsyncManager.schedulerSnapshot();
            int queueDepth = Math.max(0, schedulerSnapshot.foregroundQueue() + schedulerSnapshot.backgroundQueue());
            if (queueDepth > 0 && shouldTemporarilyPreferCpu(queueDepth)) {
                if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                    LOGGER.fine("Preferring CPU due to adaptive latency signal (queueDepth=" + queueDepth + ")");
                }
                return false;
            }
        } catch (Throwable ignored) {
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

    private static boolean canGpuAcceptTask(TaskAnalysis analysis,
                                            VulkanTask<?> gpuTask,
                                            boolean isolatedVulkanEligible,
                                            boolean required) {
        if (gpuTask == null) {
            return false;
        }
        if (!VulkanExecutionSupport.inProcessAvailable()) {
            return isolatedVulkanEligible;
        }
        try {
            org.admany.quantified.core.common.async.core.PriorityScheduler.SchedulerSnapshot schedulerSnapshot = AsyncManager.schedulerSnapshot();
            int queueDepth = Math.max(0, schedulerSnapshot.foregroundQueue() + schedulerSnapshot.backgroundQueue());
            // A REQUIRED backend is an explicit API contract. The adaptive
            // latency heuristic may reroute preferred/automatic work, but it
            // must not silently turn required Vulkan work into CPU work.
            // Runtime availability and VulkanManager.canAcceptTask still
            // enforce the hard safety/capacity boundaries below.
            if (shouldAdaptivelyRerouteVulkanToCpu(required, queueDepth)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return VulkanManager.canAcceptTask(gpuTask);
    }

    static boolean shouldAdaptivelyRerouteVulkanToCpu(boolean required, int queueDepth) {
        return !required && queueDepth > 0 && shouldTemporarilyPreferCpu(queueDepth);
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
        recordCpuTasksScheduled(1L);
    }

    public static void recordExternalGpuTask() {
        recordGpuTasksScheduled(1L);
    }

    public static void recordExternalCpuTasks(long count) {
        recordCpuTasksScheduled(count);
    }

    public static void recordExternalGpuTasks(long count) {
        recordGpuTasksScheduled(count);
    }

    public static void recordGpuKernelDuration(long durationNanos) {
        updateLatencyEma(emaGpuNanos, durationNanos, LATENCY_EMA_ALPHA);
        AdaptiveGpuBatchSizer.record(durationNanos);
    }

    private static void recordCpuTaskScheduled() {
        recordCpuTasksScheduled(1L);
    }

    private static void recordGpuTaskScheduled() {
        recordGpuTasksScheduled(1L);
    }

    private static void recordCpuTasksScheduled(long count) {
        if (count <= 0L) {
            return;
        }
        totalTasksScheduled.addAndGet(count);
        cpuTasksExecuted.addAndGet(count);
    }

    private static void recordGpuTasksScheduled(long count) {
        if (count <= 0L) {
            return;
        }
        totalTasksScheduled.addAndGet(count);
        gpuTasksExecuted.addAndGet(count);
    }

    private static <T> CompletableFuture<T> resolveGpuOrCpu(long taskKey, Supplier<T> cpuImplementation) {
        CompletableFuture<Object> gpuFuture = GpuWorkloadRegistry.result(taskKey);
        if (gpuFuture == null) {
            try {
                return CompletableFuture.completedFuture(executeMeasuredCpu(cpuImplementation));
            } finally {
                GpuWorkloadRegistry.cancel(taskKey);
            }
        }
        CompletableFuture<T> resolved = new CompletableFuture<>();
        gpuFuture.whenComplete((value, error) -> {
            try {
                if (error == null) {
                    @SuppressWarnings("unchecked")
                    T cast = (T) value;
                    resolved.complete(cast);
                    return;
                }
                LOGGER.fine(() -> "GPU result unavailable for task " + taskKey + ": " + error.getMessage());
                resolved.complete(executeMeasuredCpu(cpuImplementation));
            } catch (Throwable fallbackError) {
                resolved.completeExceptionally(fallbackError);
            } finally {
                GpuWorkloadRegistry.cancel(taskKey);
            }
        });
        return resolved;
    }

    private static <T> T executeMeasuredCpu(Supplier<T> supplier) {
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            long duration = Math.max(0L, System.nanoTime() - start);
            updateLatencyEma(emaCpuNanos, duration, LATENCY_EMA_ALPHA);
        }
    }

    private static <T> CompletableFuture<T> executeIsolatedOpenCl(
        org.admany.quantified.api.opencl.QuantifiedOpenCL.ApiOpenClTask<T> apiTask,
        Supplier<T> cpuImplementation
    ) {
        try {
            T result = OpenCLIsolatedExecutor.executeApiTask(apiTask);
            return CompletableFuture.completedFuture(result);
        } catch (Throwable ignored) {
            try {
                return CompletableFuture.completedFuture(executeMeasuredCpu(cpuImplementation));
            } catch (Throwable cpuThrowable) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(cpuThrowable);
                return failed;
            }
        }
    }

    private static <T> CompletableFuture<T> executeIsolatedVulkan(
        org.admany.quantified.api.vulkan.QuantifiedVulkan.ApiVulkanTask<T> apiTask,
        Supplier<T> cpuImplementation
    ) {
        try {
            T result = VulkanIsolatedExecutor.executeApiTask(apiTask);
            return CompletableFuture.completedFuture(result);
        } catch (Throwable ignored) {
            try {
                return CompletableFuture.completedFuture(executeMeasuredCpu(cpuImplementation));
            } catch (Throwable cpuThrowable) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(cpuThrowable);
                return failed;
            }
        }
    }

    private static void updateLatencyEma(AtomicLong target, long sampleNanos, double alpha) {
        if (sampleNanos <= 0L) {
            return;
        }
        target.updateAndGet(current -> current == 0L
            ? sampleNanos
            : (long) (current * (1.0d - alpha) + sampleNanos * alpha));
    }

    private static boolean shouldTemporarilyPreferCpu(int queueDepth) {
        long cpuAvg = emaCpuNanos.get();
        long gpuAvg = emaGpuNanos.get();
        if (cpuAvg <= 0L || gpuAvg <= 0L) {
            return false;
        }
        if (queueDepth < 32) {
            return false;
        }
        return gpuAvg > (long) (cpuAvg * 1.10d);
    }

    private static double resolveAdaptiveGpuUtilLimit() {
        long cpuAvg = emaCpuNanos.get();
        long gpuAvg = emaGpuNanos.get();
        double baseline = runtimeGpuAggressiveUtilLimit;
        if (cpuAvg <= 0L || gpuAvg <= 0L) {
            return baseline;
        }
        if (gpuAvg <= cpuAvg) {
            return Math.min(0.85d, baseline + 0.10d);
        }
        return Math.max(0.55d, baseline - 0.08d);
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
            double ratio = (double) runtimeGpuBatchTargetNanos / Math.max(1.0d, avg);
            double scaled = base * Math.max(0.5d, Math.min(4.0d, ratio));
            int rounded = (int) Math.round(scaled);
            return Math.max(2, Math.min(256, rounded));
        }
    }

    public static void applyRuntimeTuning(double gpuAggressiveUtilLimit, long gpuBatchTargetNanos) {
        double clampedUtilLimit = Math.max(0.50d, Math.min(0.90d, gpuAggressiveUtilLimit));
        long clampedBatchTarget = Math.max(1_000_000L, Math.min(5_000_000L, gpuBatchTargetNanos));
        runtimeGpuAggressiveUtilLimit = clampedUtilLimit;
        runtimeGpuBatchTargetNanos = clampedBatchTarget;
    }
}
