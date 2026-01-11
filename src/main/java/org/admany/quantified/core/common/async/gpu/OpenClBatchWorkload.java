package org.admany.quantified.core.common.async.gpu;

import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;


public final class OpenClBatchWorkload implements TaskMetadata.GpuBatchWorkload {

    private static final Logger LOGGER = Logger.getLogger(OpenClBatchWorkload.class.getName());
    private static final AtomicLong BATCH_KEY_SEQUENCE = new AtomicLong();

    public static final OpenClBatchWorkload INSTANCE = new OpenClBatchWorkload();

    private OpenClBatchWorkload() {
    }

    @Override
    public CompletableFuture<Void> submit(String modId, List<PriorityTask> tasks, TaskMetadata metadata) {
        Objects.requireNonNull(tasks, "tasks");
        if (!OpenCLManager.isAvailable()) {
            return null;
        }
        List<OpenCLTask<?>> gpuTasks = GpuWorkloadRegistry.collect(tasks);
        if (gpuTasks.isEmpty()) {
            return null;
        }
        OpenCLTask<Void> batchTask = CompositeOpenClBatchTask.create(modId, gpuTasks, metadata, BATCH_KEY_SEQUENCE.incrementAndGet());
        if (batchTask == null) {
            return null;
        }
        if (OpenCLManager.isInVramPressureCooldown() || !OpenCLManager.canAcceptTask(batchTask)) {
            return null;
        }
        return OpenCLManager.executeOnGpu(batchTask)
            .exceptionally(throwable -> {
                LOGGER.warning("Composite GPU batch failed for mod " + modId + ": " + throwable.getMessage());
                return null;
            });
    }

    private static final class CompositeOpenClBatchTask extends OpenCLTask<Void> {
        private final List<OpenCLTask<?>> tasks;
        private final long estimatedVram;
        private final int estimatedUnits;

        private CompositeOpenClBatchTask(Builder builder, List<OpenCLTask<?>> tasks) {
            super(builder);
            this.tasks = tasks;
            this.estimatedVram = tasks.stream().mapToLong(OpenCLTask::estimatedVramBytes).sum();
            this.estimatedUnits = tasks.stream().mapToInt(OpenCLTask::estimatedComputeUnits).sum();
        }

        static OpenCLTask<Void> create(String modId,
                                       List<OpenCLTask<?>> tasks,
                                       TaskMetadata metadata,
                                       long sequence) {
            if (tasks.isEmpty()) {
                return null;
            }
            String name = "GPU-Batch-" + sequence;
            long key = (modId.hashCode() * 31L + sequence) & Long.MAX_VALUE;
            Builder builder = new Builder(modId, name, key, tasks, metadata);
            return builder.build();
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
        public Void executeOnGPU(org.admany.quantified.core.common.opencl.core.OpenCLContext context) {
            RuntimeException firstFailure = null;
            for (OpenCLTask<?> task : tasks) {
                try {
                    Object cached = org.admany.quantified.core.common.opencl.task.OpenCLTaskManager.tryLoadCached(task);
                    if (cached != null) {
                        GpuWorkloadRegistry.complete(task.taskKey(), cached);
                        continue;
                    }
                    long startNanos = System.nanoTime();
                    Object result = task.executeOnGPU(context);
                    org.admany.quantified.core.common.util.TaskScheduler.recordGpuKernelDuration(System.nanoTime() - startNanos);
                    @SuppressWarnings("unchecked")
                    OpenCLTask<Object> typedTask = (OpenCLTask<Object>) task;
                    org.admany.quantified.core.common.opencl.task.OpenCLTaskManager.recordCachedResult(typedTask, result);
                    GpuWorkloadRegistry.complete(task.taskKey(), result);
                } catch (Throwable throwable) {
                    GpuWorkloadRegistry.completeExceptionally(task.taskKey(), throwable);
                    if (firstFailure == null) {
                        firstFailure = new RuntimeException("GPU subtask failed: " + task.name(), throwable);
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            return null;
        }

        private static final class Builder extends OpenCLTask.Builder<Void> {
            private final List<OpenCLTask<?>> tasks;

            private Builder(String modId,
                            String name,
                            long taskKey,
                            List<OpenCLTask<?>> tasks,
                            TaskMetadata metadata) {
                super(modId, name, taskKey, () -> {
                    throw new IllegalStateException("Composite GPU batch routed to CPU fallback unexpectedly");
                });
                this.tasks = tasks;
            }

            @Override
            public OpenCLTask<Void> build() {
                return new CompositeOpenClBatchTask(this, tasks);
            }
        }
    }
}
