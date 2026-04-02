package org.admany.quantified.core.common.async.gpu;

import org.admany.quantified.core.common.async.core.PriorityScheduler;
import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;


public final class GpuTaskDispatcher {

    private static final Logger LOGGER = Logger.getLogger(GpuTaskDispatcher.class.getName());
    private static final long MAX_FLUSH_DELAY_MILLIS = 5L;
    private static final long MIN_FLUSH_DELAY_MILLIS = 1L;
    private static final long MAX_BATCH_AGE_NANOS = TimeUnit.MILLISECONDS.toNanos(5L);
    private static final AtomicLong BATCH_SEQUENCE = new AtomicLong(Long.MIN_VALUE);

    private final PriorityScheduler scheduler;
    private final ScheduledExecutorService flushExecutor;
    private final ConcurrentHashMap<String, BatchBucket> buckets = new ConcurrentHashMap<>();

    public GpuTaskDispatcher(PriorityScheduler scheduler, ScheduledExecutorService flushExecutor) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.flushExecutor = Objects.requireNonNull(flushExecutor, "flushExecutor");
    }

    public boolean trySchedule(PriorityTask task) {
        TaskMetadata metadata = task.metadata();
        if (!eligible(metadata) || flushExecutor.isShutdown()) {
            return false;
        }
        // Check GPU temperature before scheduling GPU tasks
        GPUMonitor.GPUStatus gpuStatus = OpenCLManager.getGPUStatus();
        if (gpuStatus != null && gpuStatus.temperatureC() > 90.0) {
            GpuBatchTelemetry.recordRejectedThermal();
            return false;
        }
        String key = bucketKey(task, metadata);
        BatchBucket bucket = buckets.computeIfAbsent(key, BatchBucket::new);
        boolean flushNow;
        synchronized (bucket) {
            bucket.type = task.type();
            bucket.modId = task.modId();
            bucket.metadata = TaskMetadata.merge(bucket.metadata, metadata);
            bucket.tasks.addLast(task);
            bucket.size++;
            long now = System.nanoTime();
            if (bucket.size == 1) {
                bucket.firstEnqueueNanos = now;
            }
            flushNow = shouldFlush(bucket);
            if (!flushNow) {
                scheduleFlush(bucket);
            }
        }
        if (flushNow) {
            flush(bucket);
        }
        return true;
    }

    public void shutdown() {
        List<BatchBucket> snapshot = new ArrayList<>(buckets.values());
        for (BatchBucket bucket : snapshot) {
            flush(bucket);
        }
    }

    private boolean eligible(TaskMetadata metadata) {
        if (metadata == null) {
            GpuBatchTelemetry.recordRejectedNoMetadata();
            return false;
        }
        if (!metadata.batchable()) {
            GpuBatchTelemetry.recordRejectedNotBatchable();
            return false;
        }
        if (!metadata.gpuPreferred() && !metadata.gpuRequired()) {
            GpuBatchTelemetry.recordRejectedNotGpuMarked();
            return false;
        }
        if (metadata.gpuWorkload().isEmpty()) {
            GpuBatchTelemetry.recordFallbackNoWorkload();
            return false;
        }
        return true;
    }

    private String bucketKey(PriorityTask task, TaskMetadata metadata) {
        String affinity = metadata.affinityKey().isEmpty() ? "__default" : metadata.affinityKey();
        return task.modId() + '|' + task.type().name() + '|' + affinity;
    }

    private boolean shouldFlush(BatchBucket bucket) {
        TaskMetadata metadata = bucket.metadata;
        if (metadata == null) {
            return true;
        }
        if (!metadata.batchable()) {
            return true;
        }
        if (metadata.gpuRequired()) {
            return true;
        }
        if (bucket.size >= metadata.maximumBatchSize()) {
            return true;
        }
        if (bucket.size >= metadata.preferredBatchSize()) {
            return true;
        }
        long age = bucket.firstEnqueueNanos == 0L ? 0L : System.nanoTime() - bucket.firstEnqueueNanos;
        return age >= MAX_BATCH_AGE_NANOS;
    }

    private void scheduleFlush(BatchBucket bucket) {
        if (bucket.flushFuture != null) {
            return;
        }
        long delay = computeDelayMillis(bucket.metadata);
        bucket.flushFuture = flushExecutor.schedule(() -> flush(bucket), delay, TimeUnit.MILLISECONDS);
    }

    private long computeDelayMillis(TaskMetadata metadata) {
        if (metadata == null) {
            return MIN_FLUSH_DELAY_MILLIS;
        }
        if (metadata.gpuRequired()) {
            return 0L;
        }
        double cost = Math.max(0.5, metadata.estimatedCost());
        long delay = (long) Math.ceil(cost);
        if (delay < MIN_FLUSH_DELAY_MILLIS) {
            delay = MIN_FLUSH_DELAY_MILLIS;
        }
        if (delay > MAX_FLUSH_DELAY_MILLIS) {
            delay = MAX_FLUSH_DELAY_MILLIS;
        }
        return delay;
    }

    private void flush(BatchBucket bucket) {
        List<PriorityTask> drained;
        TaskMetadata metadataSnapshot;
        PriorityTaskType type;
        String modId;
        synchronized (bucket) {
            if (bucket.tasks.isEmpty()) {
                cancelFlush(bucket);
                buckets.remove(bucket.key, bucket);
                return;
            }
            drained = new ArrayList<>(bucket.tasks);
            bucket.tasks.clear();
            metadataSnapshot = bucket.metadata;
            type = bucket.type;
            modId = bucket.modId;
            bucket.size = 0;
            bucket.metadata = TaskMetadata.DEFAULT;
            bucket.firstEnqueueNanos = 0L;
            cancelFlush(bucket);
        }
        buckets.remove(bucket.key, bucket);
        if (drained.isEmpty()) {
            return;
        }
        TaskMetadata combinedMetadata = metadataSnapshot;
        for (PriorityTask task : drained) {
            combinedMetadata = TaskMetadata.merge(combinedMetadata, task.metadata());
        }
        TaskMetadata finalMetadata = combinedMetadata;
        double score = drained.stream().mapToDouble(PriorityTask::score).max().orElse(0.0);
        long batchKey = BATCH_SEQUENCE.getAndIncrement();
        List<PriorityTask> batchTasks = List.copyOf(drained);
        String batchModId = modId;
        Runnable payload = () -> executeBatch(batchModId, batchTasks, finalMetadata);
        PriorityTask batchTask = new PriorityTask(batchKey, type, score, payload,
            finalMetadata == null ? TaskMetadata.DEFAULT : finalMetadata,
            modId);
        scheduler.submit(batchTask);
    }

    private void cancelFlush(BatchBucket bucket) {
        ScheduledFuture<?> future = bucket.flushFuture;
        if (future != null) {
            future.cancel(false);
            bucket.flushFuture = null;
        }
    }

    private void executeBatch(String modId, List<PriorityTask> tasks, TaskMetadata metadata) {
        if (metadata != null && metadata.gpuWorkload().isPresent()) {
            if (dispatchGpuBatch(modId, tasks, metadata)) {
                return;
            }
            GpuBatchTelemetry.recordFallbackDispatcherUnavailable();
            LOGGER.fine(() -> "GPU batch dispatcher unavailable; routing work back to CPU for mod " + modId);
        } else {
            GpuBatchTelemetry.recordFallbackNoWorkload();
        }
        runTasksInline(tasks);
    }

    private boolean dispatchGpuBatch(String modId, List<PriorityTask> tasks, TaskMetadata metadata) {
        if (flushExecutor.isShutdown()) {
            return false;
        }
        List<PriorityTask> snapshot = List.copyOf(tasks);
        flushExecutor.execute(() -> runGpuBatch(modId, snapshot, metadata));
        return true;
    }

    private void runGpuBatch(String modId, List<PriorityTask> tasks, TaskMetadata metadata) {
        boolean gpuExecuted = false;
        GpuBatchTelemetry.recordAttempt();
        gpuExecuted = tryExecuteGpuWorkload(modId, tasks, metadata);
        if (gpuExecuted) {
            GpuBatchTelemetry.recordSuccess();
            GpuBatchTelemetry.recordGpuTasksCompleted(tasks.size());
            // The GPU workload completes registry futures, but AsyncManager still needs
            // the original task payload to bridge those results back to caller futures.
            runTasksInline(tasks);
            LOGGER.fine(() -> "GPU batch completed for mod " + modId + " with " + tasks.size() + " tasks");
        } else {
            GpuBatchTelemetry.recordFallback();
            GpuBatchTelemetry.recordFallbackExecutionFailure();
            if (metadata.gpuRequired()) {
                LOGGER.warning("GPU-required batch falling back to CPU due to unavailable workload or runtime");
            }
            failGpuTasks(tasks);
            rerouteTasksToCpu(tasks);
        }
    }

    private void failGpuTasks(List<PriorityTask> tasks) {
        for (PriorityTask task : tasks) {
            GpuWorkloadRegistry.completeExceptionally(task.taskKey(), new IllegalStateException("GPU batch not executed"));
        }
    }

    private void rerouteTasksToCpu(List<PriorityTask> tasks) {
        for (PriorityTask task : tasks) {
            PriorityTask rerouted = forceCpuTask(task);
            scheduler.submit(rerouted);
        }
    }

    private PriorityTask forceCpuTask(PriorityTask task) {
        TaskMetadata metadata = task.metadata();
        TaskMetadata.Builder builder = metadata != null ? metadata.toBuilder() : TaskMetadata.builder();
        builder.batchable(false);
        builder.gpuPreferred(false);
        builder.gpuRequired(false);
        builder.gpuWorkload(null);
        TaskMetadata cpuMetadata = builder.build();
        return new PriorityTask(task.taskKey(), task.type(), task.score(), task.payload(), cpuMetadata, task.modId());
    }

    private void runTasksInline(List<PriorityTask> tasks) {
        for (PriorityTask task : tasks) {
            try {
                task.payload().run();
            } catch (Throwable throwable) {
                LOGGER.log(Level.SEVERE, "Batched task failed", throwable);
            }
        }
    }

    private boolean tryExecuteGpuWorkload(String modId, List<PriorityTask> tasks, TaskMetadata metadata) {
        Optional<TaskMetadata.GpuBatchWorkload> workloadOpt = metadata.gpuWorkload();
        if (workloadOpt.isEmpty()) {
            return false;
        }
        TaskMetadata.GpuBatchWorkload workload = workloadOpt.get();
        try {
            CompletableFuture<Void> gpuFuture = workload.submit(modId, tasks, metadata);
            if (gpuFuture == null) {
                return false;
            }
            gpuFuture.join();
            return true;
        } catch (Throwable throwable) {
            LOGGER.log(Level.WARNING, "GPU batch execution failed; reverting to CPU", throwable);
            return false;
        }
    }

    private static final class BatchBucket {
        final String key;
        final ArrayDeque<PriorityTask> tasks = new ArrayDeque<>();
        TaskMetadata metadata = TaskMetadata.DEFAULT;
        PriorityTaskType type = PriorityTaskType.BACKGROUND;
        String modId = "";
        int size;
        long firstEnqueueNanos;
        ScheduledFuture<?> flushFuture;

        BatchBucket(String key) {
            this.key = key;
        }
    }
}
