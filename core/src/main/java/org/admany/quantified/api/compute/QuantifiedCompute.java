package org.admany.quantified.api.compute;

import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.admany.quantified.core.common.util.TaskScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class QuantifiedCompute {

    private QuantifiedCompute() {
    }

    public enum Complexity {
        SIMPLE,
        MODERATE,
        COMPLEX,
        MASSIVE
    }

    public enum WorkloadKind {
        GENERAL,
        VECTOR_MATH,
        MATRIX_MATH,
        STATISTICAL,
        SIMULATION,
        SIGNAL_PROCESSING,
        SPATIAL_ANALYSIS
    }

    public static <T> Builder<T> builder(String modId, String taskName, long taskKey) {
        return new Builder<>(modId, taskName, taskKey);
    }

    public static final class Builder<T> {
        private final String modId;
        private final String taskName;
        private final long taskKey;
        private Supplier<T> cpuFallback;
        private QuantifiedOpenCL.Workload<T> openclWorkload;
        private QuantifiedVulkan.Workload<T> vulkanWorkload;
        private long dataSizeBytes = 4 * 1024L;
        private int parallelUnits = 256;
        private Complexity complexity = Complexity.MODERATE;
        private WorkloadKind kind = WorkloadKind.GENERAL;
        private Duration timeout;
        private boolean threadSafe = true;
        private boolean allowMainThreadRerouting = true;
        private GpuBackendPreference backendPreference = GpuBackendPreference.AUTO;

        private Builder(String modId, String taskName, long taskKey) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.taskName = Objects.requireNonNull(taskName, "taskName");
            this.taskKey = taskKey;
        }

        public Builder<T> cpuFallback(Supplier<T> cpuFallback) {
            this.cpuFallback = Objects.requireNonNull(cpuFallback, "cpuFallback");
            return this;
        }

        public Builder<T> openclWorkload(QuantifiedOpenCL.Workload<T> openclWorkload) {
            this.openclWorkload = Objects.requireNonNull(openclWorkload, "openclWorkload");
            return this;
        }

        public Builder<T> vulkanWorkload(QuantifiedVulkan.Workload<T> vulkanWorkload) {
            this.vulkanWorkload = Objects.requireNonNull(vulkanWorkload, "vulkanWorkload");
            return this;
        }

        public Builder<T> dataSizeBytes(long dataSizeBytes) {
            if (dataSizeBytes > 0L) {
                this.dataSizeBytes = dataSizeBytes;
            }
            return this;
        }

        public Builder<T> parallelUnits(int parallelUnits) {
            if (parallelUnits > 0) {
                this.parallelUnits = parallelUnits;
            }
            return this;
        }

        public Builder<T> complexity(Complexity complexity) {
            this.complexity = Objects.requireNonNull(complexity, "complexity");
            return this;
        }

        public Builder<T> kind(WorkloadKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
            return this;
        }

        public Builder<T> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder<T> threadSafe() {
            return threadSafe(true);
        }

        public Builder<T> notThreadSafe() {
            return threadSafe(false);
        }

        public Builder<T> threadSafe(boolean threadSafe) {
            this.threadSafe = threadSafe;
            return this;
        }

        public Builder<T> allowMainThreadRerouting(boolean allowMainThreadRerouting) {
            this.allowMainThreadRerouting = allowMainThreadRerouting;
            return this;
        }

        public Builder<T> backendPreference(GpuBackendPreference backendPreference) {
            this.backendPreference = Objects.requireNonNullElse(backendPreference, GpuBackendPreference.AUTO);
            return this;
        }

        public Builder<T> preferVulkan() {
            return backendPreference(GpuBackendPreference.VULKAN_PREFERRED);
        }

        public Builder<T> preferOpenCL() {
            return backendPreference(GpuBackendPreference.OPENCL_PREFERRED);
        }

        public Builder<T> requireVulkan() {
            return backendPreference(GpuBackendPreference.VULKAN_REQUIRED);
        }

        public Builder<T> requireOpenCL() {
            return backendPreference(GpuBackendPreference.OPENCL_REQUIRED);
        }

        public Builder<T> cpuOnly() {
            return backendPreference(GpuBackendPreference.CPU_ONLY);
        }

        public CompletableFuture<T> submit() {
            validate();
            Object gpuTask = null;
            if (vulkanWorkload != null) {
                gpuTask = QuantifiedVulkan.<T>builder(modId, taskName, taskKey)
                    .cpuFallback(cpuFallback)
                    .workload(vulkanWorkload)
                    .dataSizeBytes(dataSizeBytes)
                    .parallelUnits(parallelUnits)
                    .complexity(mapVulkanComplexity(complexity))
                    .kind(mapVulkanKind(kind))
                    .timeout(timeout)
                    .allowMainThreadRerouting(allowMainThreadRerouting)
                    .buildTask();
            } else if (openclWorkload != null) {
                gpuTask = QuantifiedOpenCL.<T>builder(modId, taskName, taskKey)
                    .cpuFallback(cpuFallback)
                    .workload(openclWorkload)
                    .dataSizeBytes(dataSizeBytes)
                    .parallelUnits(parallelUnits)
                    .complexity(mapComplexity(complexity))
                    .kind(mapKind(kind))
                    .timeout(timeout)
                    .allowMainThreadRerouting(allowMainThreadRerouting)
                    .buildTask();
            }
            return TaskScheduler.submitComputeTask(
                modId,
                taskName,
                taskKey,
                cpuFallback,
                gpuTask,
                dataSizeBytes,
                parallelUnits,
                mapSchedulerComplexity(complexity),
                mapSchedulerKind(kind),
                timeout,
                allowMainThreadRerouting,
                backendPreference,
                threadSafe
            );
        }

        private void validate() {
            Objects.requireNonNull(cpuFallback, "cpuFallback");
            if (openclWorkload != null && vulkanWorkload != null) {
                throw new IllegalStateException("QuantifiedCompute.Builder only accepts one GPU workload at a time");
            }
        }
    }

    private static QuantifiedOpenCL.Complexity mapComplexity(Complexity complexity) {
        return switch (complexity) {
            case SIMPLE -> QuantifiedOpenCL.Complexity.SIMPLE;
            case MODERATE -> QuantifiedOpenCL.Complexity.MODERATE;
            case COMPLEX -> QuantifiedOpenCL.Complexity.COMPLEX;
            case MASSIVE -> QuantifiedOpenCL.Complexity.MASSIVE;
        };
    }

    private static QuantifiedOpenCL.WorkloadKind mapKind(WorkloadKind kind) {
        return switch (kind) {
            case GENERAL -> QuantifiedOpenCL.WorkloadKind.GENERAL;
            case VECTOR_MATH -> QuantifiedOpenCL.WorkloadKind.VECTOR_MATH;
            case MATRIX_MATH -> QuantifiedOpenCL.WorkloadKind.MATRIX_MATH;
            case STATISTICAL -> QuantifiedOpenCL.WorkloadKind.STATISTICAL;
            case SIMULATION -> QuantifiedOpenCL.WorkloadKind.SIMULATION;
            case SIGNAL_PROCESSING -> QuantifiedOpenCL.WorkloadKind.SIGNAL_PROCESSING;
            case SPATIAL_ANALYSIS -> QuantifiedOpenCL.WorkloadKind.SPATIAL_ANALYSIS;
        };
    }

    private static TaskScheduler.TaskComplexity mapSchedulerComplexity(Complexity complexity) {
        return switch (complexity) {
            case SIMPLE -> TaskScheduler.TaskComplexity.SIMPLE;
            case MODERATE -> TaskScheduler.TaskComplexity.MODERATE;
            case COMPLEX -> TaskScheduler.TaskComplexity.COMPLEX;
            case MASSIVE -> TaskScheduler.TaskComplexity.MASSIVE;
        };
    }

    private static TaskScheduler.TaskType mapSchedulerKind(WorkloadKind kind) {
        return switch (kind) {
            case GENERAL -> TaskScheduler.TaskType.GENERAL;
            case VECTOR_MATH -> TaskScheduler.TaskType.VECTOR_MATH;
            case MATRIX_MATH -> TaskScheduler.TaskType.MATRIX_MATH;
            case STATISTICAL -> TaskScheduler.TaskType.STATISTICAL;
            case SIMULATION -> TaskScheduler.TaskType.SIMULATION;
            case SIGNAL_PROCESSING -> TaskScheduler.TaskType.SIGNAL_PROCESSING;
            case SPATIAL_ANALYSIS -> TaskScheduler.TaskType.SPATIAL_ANALYSIS;
        };
    }

    private static QuantifiedVulkan.Complexity mapVulkanComplexity(Complexity complexity) {
        return switch (complexity) {
            case SIMPLE -> QuantifiedVulkan.Complexity.SIMPLE;
            case MODERATE -> QuantifiedVulkan.Complexity.MODERATE;
            case COMPLEX -> QuantifiedVulkan.Complexity.COMPLEX;
            case MASSIVE -> QuantifiedVulkan.Complexity.MASSIVE;
        };
    }

    private static QuantifiedVulkan.WorkloadKind mapVulkanKind(WorkloadKind kind) {
        return switch (kind) {
            case GENERAL -> QuantifiedVulkan.WorkloadKind.GENERAL;
            case VECTOR_MATH -> QuantifiedVulkan.WorkloadKind.VECTOR_MATH;
            case MATRIX_MATH -> QuantifiedVulkan.WorkloadKind.MATRIX_MATH;
            case STATISTICAL -> QuantifiedVulkan.WorkloadKind.STATISTICAL;
            case SIMULATION -> QuantifiedVulkan.WorkloadKind.SIMULATION;
            case SIGNAL_PROCESSING -> QuantifiedVulkan.WorkloadKind.SIGNAL_PROCESSING;
            case SPATIAL_ANALYSIS -> QuantifiedVulkan.WorkloadKind.SPATIAL_ANALYSIS;
        };
    }
}
