package org.admany.quantified.core.common.async.gpu;

import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.vulkan.core.VulkanManager;
import org.admany.quantified.core.common.vulkan.core.VulkanTask;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class VulkanBatchWorkload implements TaskMetadata.GpuBatchWorkload {

    private static final Logger LOGGER = Logger.getLogger(VulkanBatchWorkload.class.getName());
    private static final AtomicLong BATCH_KEY_SEQUENCE = new AtomicLong();

    public static final VulkanBatchWorkload INSTANCE = new VulkanBatchWorkload();

    private VulkanBatchWorkload() {
    }

    @Override
    public CompletableFuture<Void> submit(String modId, List<PriorityTask> tasks, TaskMetadata metadata) {
        Objects.requireNonNull(tasks, "tasks");
        List<VulkanTask<?>> gpuTasks = GpuWorkloadRegistry.peekVulkan(tasks);
        if (gpuTasks.isEmpty()) {
            return null;
        }
        VulkanTask<Void> batchTask = CompositeVulkanBatchTask.create(modId, gpuTasks, BATCH_KEY_SEQUENCE.incrementAndGet());
        if (batchTask == null || !VulkanManager.canAcceptTask(batchTask)) {
            return null;
        }
        gpuTasks = GpuWorkloadRegistry.claimVulkan(tasks);
        if (gpuTasks.isEmpty()) {
            return null;
        }
        batchTask = CompositeVulkanBatchTask.create(modId, gpuTasks, BATCH_KEY_SEQUENCE.incrementAndGet());
        if (batchTask == null) {
            return null;
        }
        return VulkanManager.executeOnGpu(batchTask)
            .exceptionally(throwable -> {
                LOGGER.warning("Composite Vulkan batch failed for mod " + modId + ": " + throwable.getMessage());
                return null;
            });
    }

    private static final class CompositeVulkanBatchTask extends VulkanTask<Void> {
        private final List<VulkanTask<?>> tasks;
        private final long estimatedVram;
        private final int estimatedUnits;

        private CompositeVulkanBatchTask(Builder builder, List<VulkanTask<?>> tasks) {
            super(builder);
            this.tasks = tasks;
            this.estimatedVram = tasks.stream().mapToLong(VulkanTask::estimatedVramBytes).sum();
            this.estimatedUnits = tasks.stream().mapToInt(VulkanTask::estimatedComputeUnits).sum();
        }

        static VulkanTask<Void> create(String modId, List<VulkanTask<?>> tasks, long sequence) {
            if (tasks.isEmpty()) {
                return null;
            }
            String name = "Vulkan-Batch-" + sequence;
            long key = (modId.hashCode() * 31L + sequence) & Long.MAX_VALUE;
            return new Builder(modId, name, key, tasks).build();
        }

        @Override
        public long estimatedVramBytes() {
            return estimatedVram;
        }

        @Override
        public int estimatedComputeUnits() {
            return estimatedUnits;
        }

        @Override
        public Void executeOnGPU(org.admany.quantified.core.common.vulkan.core.VulkanContext context) {
            RuntimeException firstFailure = null;
            for (VulkanTask<?> task : tasks) {
                try {
                    Object result = task.executeOnGPU(context);
                    GpuWorkloadRegistry.complete(task.taskKey(), result);
                } catch (Throwable throwable) {
                    GpuWorkloadRegistry.completeExceptionally(task.taskKey(), throwable);
                    if (firstFailure == null) {
                        firstFailure = new RuntimeException("Vulkan subtask failed: " + task.name(), throwable);
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            return null;
        }

        private static final class Builder extends VulkanTask.Builder<Void> {
            private final List<VulkanTask<?>> tasks;

            private Builder(String modId, String name, long taskKey, List<VulkanTask<?>> tasks) {
                super(modId, name, taskKey, () -> {
                    throw new IllegalStateException("Composite Vulkan batch routed to CPU unexpectedly");
                });
                this.tasks = tasks;
            }

            @Override
            public VulkanTask<Void> build() {
                return new CompositeVulkanBatchTask(this, tasks);
            }
        }
    }
}
