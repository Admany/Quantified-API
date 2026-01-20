package org.admany.quantified.core.common.async.cpu;

import org.admany.quantified.api.model.ParallelTaskSpec;
import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.parallel.ParallelTaskManager;
import org.admany.quantified.core.common.parallel.config.ParallelConfig;
import org.admany.quantified.core.common.parallel.policy.ParallelFailurePolicy;
import org.admany.quantified.core.common.telemetry.TaskKindTelemetry;
import org.admany.quantified.core.common.async.core.PriorityScheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CpuTaskDispatcher {
    private static final long MAX_BATCH_AGE_NANOS = TimeUnit.MILLISECONDS.toNanos(6L);
    private static final long MIN_FLUSH_DELAY_MILLIS = 2L;
    private static final long MAX_FLUSH_DELAY_MILLIS = 12L;
    private static final long BURST_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(20L);
    private static final AtomicLong BATCH_SEQUENCE = new AtomicLong(Long.MIN_VALUE);

    private final PriorityScheduler scheduler;
    private final ScheduledExecutorService flushExecutor;
    private final ConcurrentHashMap<String, BatchBucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentArrivals = new ConcurrentHashMap<>();

    public CpuTaskDispatcher(PriorityScheduler scheduler, ScheduledExecutorService flushExecutor) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.flushExecutor = Objects.requireNonNull(flushExecutor, "flushExecutor");
    }

    public boolean trySchedule(PriorityTask task) {
        TaskMetadata metadata = task.metadata();
        if (!eligible(metadata) || flushExecutor.isShutdown()) {
            return false;
        }
        String key = bucketKey(task, metadata);
        BatchBucket existing = buckets.get(key);
        long now = System.nanoTime();
        Long lastSeen = recentArrivals.put(key, now);
        boolean burst = lastSeen != null && (now - lastSeen) <= BURST_WINDOW_NANOS;
        if (existing == null && !shouldBatchNow() && !burst) {
            return false;
        }
        BatchBucket bucket = buckets.computeIfAbsent(key, BatchBucket::new);
        boolean flushNow;
        synchronized (bucket) {
            bucket.type = task.type();
            bucket.modId = task.modId();
            bucket.metadata = TaskMetadata.merge(bucket.metadata, metadata);
            bucket.tasks.addLast(task);
            bucket.size++;
            if (bucket.size == 1) {
                bucket.firstEnqueueNanos = System.nanoTime();
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
            return false;
        }
        if (!metadata.batchable()) {
            return false;
        }
        if (metadata.gpuPreferred() || metadata.gpuRequired()) {
            return false;
        }
        return !metadata.affinityKey().isEmpty();
    }

    private boolean shouldBatchNow() {
        PriorityScheduler.SchedulerSnapshot snapshot = scheduler.snapshot();
        int queued = snapshot.foregroundQueue() + snapshot.backgroundQueue();
        int threshold = Math.max(64, resolveQueueThreshold());
        return queued >= threshold;
    }

    private int resolveQueueThreshold() {
        if (MultithreadingConfig.CONFIG == null) {
            return 256;
        }
        int configured = Math.max(1, MultithreadingConfig.CONFIG.taskQueueSize);
        return Math.max(128, (int) Math.ceil(configured * 0.6));
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
        TaskMetadata effectiveMetadata = finalMetadata == null ? TaskMetadata.DEFAULT : finalMetadata;
        String affinity = effectiveMetadata.affinityKey();
        TaskKindTelemetry.recordBatch(batchModId, affinity, batchTasks.size());
        String internalAffinity = TaskKindTelemetry.INTERNAL_CPU_BATCH_PREFIX + (affinity.isEmpty() ? "tasks" : affinity);
        TaskMetadata batchMetadata = effectiveMetadata.toBuilder()
            .batchable(false)
            .affinityKey(internalAffinity)
            .build();
        Runnable payload = () -> executeBatch(batchModId, batchTasks, batchMetadata, batchKey);
        PriorityTask batchTask = new PriorityTask(batchKey, type, score, payload,
            batchMetadata,
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

    private void executeBatch(String modId, List<PriorityTask> tasks, TaskMetadata metadata, long batchKey) {
        String affinity = metadata != null ? metadata.affinityKey() : "";
        String taskName = TaskKindTelemetry.INTERNAL_CPU_BATCH_PREFIX + (affinity.isEmpty() ? "tasks" : affinity);
        ParallelTaskSpec<PriorityTask, Void, Void> spec = new ParallelTaskSpec<>(
            modId,
            taskName,
            batchKey,
            () -> tasks,
            task -> {
                TaskKindTelemetry.recordParallel(task.modId(), describeTaskName(task));
                try {
                    task.payload().run();
                    return CompletableFuture.completedFuture(null);
                } catch (Throwable t) {
                    CompletableFuture<Void> failed = new CompletableFuture<>();
                    failed.completeExceptionally(t);
                    return failed;
                }
            },
            results -> null,
            null,
            ParallelFailurePolicy.BEST_EFFORT,
            Math.min(tasks.size(), ParallelConfig.maxThreads()),
            null
        );
        ParallelTaskManager.submit(spec).whenComplete((ignored, throwable) -> {
        });
    }

    private String describeTaskName(PriorityTask task) {
        TaskMetadata metadata = task.metadata();
        String name = metadata != null ? metadata.affinityKey() : "";
        if (name == null || name.isBlank()) {
            return "unknown-task";
        }
        if (TaskKindTelemetry.isInternalBatchName(name)) {
            return "batched-cpu";
        }
        return name;
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
