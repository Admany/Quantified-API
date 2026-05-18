package org.admany.quantified.api;

import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.QuantifiedCompute;
import org.admany.quantified.api.model.QuantifiedTask;
import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ComputeRequest<T> {
    private final String modId;
    private final String taskName;
    private long taskKey;
    private boolean explicitKey;
    private ExecutionPriority priority = ExecutionPriority.AUTO;
    private boolean threadSafe = true;
    private Duration timeout;
    private String affinityKey;
    private Supplier<T> work;
    private Supplier<T> cpuFallback;
    private QuantifiedOpenCL.Workload<T> openclWorkload;
    private QuantifiedVulkan.Workload<T> vulkanWorkload;
    private long dataSizeBytes = 4 * 1024L;
    private int parallelUnits = 256;
    private QuantifiedCompute.Complexity complexity = QuantifiedCompute.Complexity.MODERATE;
    private QuantifiedCompute.WorkloadKind kind = QuantifiedCompute.WorkloadKind.GENERAL;
    private boolean allowMainThreadRerouting = true;
    private GpuBackendPreference backendPreference = GpuBackendPreference.AUTO;

    ComputeRequest(String modId, String taskName) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.taskName = Objects.requireNonNull(taskName, "taskName");
        this.taskKey = StableTaskKeys.of("compute", modId, taskName);
    }

    public ComputeRequest<T> key(long taskKey) {
        this.taskKey = taskKey;
        this.explicitKey = true;
        return this;
    }

    public ComputeRequest<T> key(String taskKey) {
        this.taskKey = StableTaskKeys.named("compute", modId, taskName, taskKey);
        this.explicitKey = true;
        return this;
    }

    public ComputeRequest<T> priority(ExecutionPriority priority) {
        this.priority = Objects.requireNonNull(priority, "priority");
        return this;
    }

    public ComputeRequest<T> foreground() {
        return priority(ExecutionPriority.FOREGROUND);
    }

    public ComputeRequest<T> background() {
        return priority(ExecutionPriority.BACKGROUND);
    }

    public ComputeRequest<T> critical() {
        return priority(ExecutionPriority.CRITICAL);
    }

    public ComputeRequest<T> timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public ComputeRequest<T> affinity(String affinityKey) {
        this.affinityKey = affinityKey;
        if (!explicitKey && affinityKey != null && !affinityKey.isBlank()) {
            this.taskKey = StableTaskKeys.of("compute", modId, taskName, affinityKey);
        }
        return this;
    }

    public ComputeRequest<T> threadSafe() {
        this.threadSafe = true;
        return this;
    }

    public ComputeRequest<T> notThreadSafe() {
        this.threadSafe = false;
        return this;
    }

    public ComputeRequest<T> dataSizeBytes(long dataSizeBytes) {
        if (dataSizeBytes > 0L) {
            this.dataSizeBytes = dataSizeBytes;
        }
        return this;
    }

    public ComputeRequest<T> parallelUnits(int parallelUnits) {
        if (parallelUnits > 0) {
            this.parallelUnits = parallelUnits;
        }
        return this;
    }

    public ComputeRequest<T> complexity(QuantifiedCompute.Complexity complexity) {
        this.complexity = Objects.requireNonNull(complexity, "complexity");
        return this;
    }

    public ComputeRequest<T> kind(QuantifiedCompute.WorkloadKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
        return this;
    }

    public ComputeRequest<T> allowMainThreadRerouting(boolean allow) {
        this.allowMainThreadRerouting = allow;
        return this;
    }

    public ComputeRequest<T> preferGpu() {
        this.backendPreference = GpuBackendPreference.AUTO;
        return this;
    }

    public ComputeRequest<T> preferVulkan() {
        this.backendPreference = GpuBackendPreference.VULKAN_PREFERRED;
        return this;
    }

    public ComputeRequest<T> preferOpenCL() {
        this.backendPreference = GpuBackendPreference.OPENCL_PREFERRED;
        return this;
    }

    public ComputeRequest<T> requireVulkan() {
        this.backendPreference = GpuBackendPreference.VULKAN_REQUIRED;
        return this;
    }

    public ComputeRequest<T> requireOpenCL() {
        this.backendPreference = GpuBackendPreference.OPENCL_REQUIRED;
        return this;
    }

    public ComputeRequest<T> cpuOnly() {
        this.backendPreference = GpuBackendPreference.CPU_ONLY;
        return this;
    }

    public ComputeRequest<T> work(Supplier<T> work) {
        this.work = Objects.requireNonNull(work, "work");
        if (this.cpuFallback == null) {
            this.cpuFallback = work;
        }
        return this;
    }

    public ComputeRequest<T> fallback(Supplier<T> fallback) {
        this.cpuFallback = Objects.requireNonNull(fallback, "fallback");
        return this;
    }

    public ComputeRequest<T> openclWorkload(QuantifiedOpenCL.Workload<T> workload) {
        this.openclWorkload = Objects.requireNonNull(workload, "workload");
        return this;
    }

    public ComputeRequest<T> vulkanWorkload(QuantifiedVulkan.Workload<T> workload) {
        this.vulkanWorkload = Objects.requireNonNull(workload, "workload");
        return this;
    }

    public CompletableFuture<T> submit(Supplier<T> work) {
        return work(work).submit();
    }

    public CompletableFuture<T> submit() {
        if (openclWorkload == null && vulkanWorkload == null) {
            Supplier<T> supplier = Objects.requireNonNull(work, "work");
            QuantifiedTask.Builder<T> builder = QuantifiedTask.builder(modId, taskName, supplier)
                .threadSafe(threadSafe)
                .timeout(timeout);
            if (!priority.isAuto()) {
                builder.priority(priority.toTaskType());
            } else {
                builder.priorityAuto();
            }
            if (affinityKey != null && !affinityKey.isBlank()) {
                builder.batchKey(affinityKey);
            }
            return QuantifiedAPI.submitTaskInternal(builder.build());
        }

        Supplier<T> fallback = cpuFallback != null ? cpuFallback : work;
        Objects.requireNonNull(fallback, "cpuFallback");
        QuantifiedCompute.Builder<T> builder = QuantifiedCompute.<T>builder(modId, taskName, taskKey)
            .cpuFallback(fallback)
            .dataSizeBytes(dataSizeBytes)
            .parallelUnits(parallelUnits)
            .complexity(complexity)
            .kind(kind)
            .timeout(timeout)
            .allowMainThreadRerouting(allowMainThreadRerouting)
            .backendPreference(backendPreference);
        if (openclWorkload != null) {
            builder.openclWorkload(openclWorkload);
        }
        if (vulkanWorkload != null) {
            builder.vulkanWorkload(vulkanWorkload);
        }
        return builder.submit();
    }
}
