package org.admany.quantified.core.common.parallel;

import org.admany.quantified.api.model.ParallelTaskSpec;
import org.admany.quantified.api.parallel.ParallelSliceCachePolicy;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.parallel.cache.ParallelResultCache;
import org.admany.quantified.core.common.parallel.config.ParallelConfig;
import org.admany.quantified.core.common.parallel.executor.ParallelScheduler;
import org.admany.quantified.core.common.parallel.metrics.ParallelMetrics;
import org.admany.quantified.core.common.parallel.policy.ParallelFailurePolicy;
import org.admany.quantified.core.common.parallel.throttle.ParallelBackpressure;
import org.admany.quantified.core.common.parallel.throttle.ParallelModTracker;
import org.admany.quantified.core.common.telemetry.TaskKindTelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.locks.LockSupport;

public final class ParallelTaskManager {
    private static final int INLINE_SYNC_THRESHOLD = Math.max(256,
        Integer.getInteger("quantified.parallel.inlineThreshold", 8192));

    private ParallelTaskManager() {
    }

    public static <S, R, O> CompletableFuture<O> submit(ParallelTaskSpec<S, R, O> spec) {
        if (spec.hasDirectSliceExecutor()) {
            return submitUnifiedSync(spec);
        }
        return submitLegacy(spec);
    }

    private static <S, R, O> CompletableFuture<O> submitUnifiedSync(ParallelTaskSpec<S, R, O> spec) {
        List<S> slices = spec.sliceSupplier().get();
        if (slices.isEmpty()) {
            O value = spec.reducer().apply(List.of());
            return CompletableFuture.completedFuture(value);
        }
        if (!TaskKindTelemetry.isInternalBatchName(spec.taskName())) {
            TaskKindTelemetry.recordParallel(spec.modId(), spec.taskName());
        }
        ParallelMetrics.recordSubmission(spec.modId(), slices.size());
        int jobParallelism = Math.max(1, Math.min(spec.maxParallelism(), ParallelConfig.maxThreads()));
        int workerCount = Math.min(jobParallelism, slices.size());
        if (slices.size() <= INLINE_SYNC_THRESHOLD || workerCount <= 1) {
            return submitUnifiedInline(spec, slices);
        }
        Object[] results = new Object[slices.size()];
        AtomicBoolean failFast = new AtomicBoolean(false);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicBoolean resultCompleted = new AtomicBoolean(false);
        AtomicInteger cursor = new AtomicInteger(0);
        final Semaphore modSemaphore = ParallelModTracker.semaphore(spec.modId());
        CompletableFuture<O> result = new CompletableFuture<>();
        AtomicInteger activeWorkers = new AtomicInteger(workerCount);
        BatchSizing sizing = resolveBatchSizing(slices.size(), workerCount);
        Function<S, R> directExecutor = spec.directSliceExecutor();
        ParallelSliceCachePolicy<S, R> cachePolicy = spec.cachePolicy();
        Consumer<R> listener = spec.sliceListener();
        ParallelFailurePolicy failurePolicy = spec.failurePolicy();
        String affinityPrefix = taskAffinity(spec.taskName());

        Runnable tryFinish = () -> {
            if (activeWorkers.get() != 0 || !resultCompleted.compareAndSet(false, true)) {
                return;
            }
            if (failFast.get()) {
                Throwable failure = firstFailure.get();
                result.completeExceptionally(failure != null ? failure : new IllegalStateException("Parallel batch failed"));
                return;
            }
            try {
                List<R> collected = new ArrayList<>(results.length);
                for (Object entry : results) {
                    @SuppressWarnings("unchecked")
                    R cast = (R) entry;
                    collected.add(cast);
                }
                result.complete(spec.reducer().apply(collected));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };

        for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
            final int shard = workerIndex;
            TaskMetadata metadata = TaskMetadata.builder()
                .batchable(false)
                .affinityKey(affinityPrefix + "|w" + shard)
                .build();
            AsyncManager.submitDirect(
                spec.taskKey() ^ (0x9E3779B97F4A7C15L + workerIndex),
                PriorityTaskType.BUILDING,
                PriorityTaskType.BUILDING.defaultScore(),
                () -> {
                    runUnifiedWorker(spec, slices, results, cursor, modSemaphore, failFast, firstFailure, directExecutor,
                        cachePolicy, listener, failurePolicy, sizing.max());
                    if (activeWorkers.decrementAndGet() == 0) {
                        tryFinish.run();
                    }
                    return null;
                },
                true,
                spec.modId(),
                metadata
            );
        }

        return result;
    }

    private static <S, R, O> CompletableFuture<O> submitUnifiedInline(ParallelTaskSpec<S, R, O> spec, List<S> slices) {
        Object[] results = new Object[slices.size()];
        ParallelSliceCachePolicy<S, R> cachePolicy = spec.cachePolicy();
        Consumer<R> listener = spec.sliceListener();
        Function<S, R> directExecutor = spec.directSliceExecutor();

        int successes = 0;
        for (int index = 0; index < slices.size(); index++) {
            S slice = slices.get(index);
            try {
                R value = cachePolicy != null ? ParallelResultCache.tryLoad(spec, cachePolicy, slice) : null;
                if (value == null) {
                    value = directExecutor.apply(slice);
                    if (cachePolicy != null) {
                        ParallelResultCache.store(spec, cachePolicy, slice, value);
                    }
                }
                results[index] = value;
                if (listener != null) {
                    try {
                        listener.accept(value);
                    } catch (Throwable ignored) {
                    }
                }
                successes++;
            } catch (Throwable throwable) {
                if (spec.failurePolicy() == ParallelFailurePolicy.FAIL_FAST) {
                    CompletableFuture<O> failed = new CompletableFuture<>();
                    failed.completeExceptionally(throwable);
                    return failed;
                }
            }
        }
        ParallelMetrics.recordDispatch(spec.modId(), slices.size());
        ParallelMetrics.recordCompletion(spec.modId(), successes, Math.max(0, slices.size() - successes));
        try {
            List<R> collected = new ArrayList<>(results.length);
            for (Object entry : results) {
                @SuppressWarnings("unchecked")
                R cast = (R) entry;
                collected.add(cast);
            }
            return CompletableFuture.completedFuture(spec.reducer().apply(collected));
        } catch (Throwable throwable) {
            CompletableFuture<O> failed = new CompletableFuture<>();
            failed.completeExceptionally(throwable);
            return failed;
        }
    }

    static <S, R, O> CompletableFuture<O> submitLegacy(ParallelTaskSpec<S, R, O> spec) {
        List<S> slices = spec.sliceSupplier().get();
        if (slices.isEmpty()) {
            O value = spec.reducer().apply(List.of());
            return CompletableFuture.completedFuture(value);
        }
        if (!TaskKindTelemetry.isInternalBatchName(spec.taskName())) {
            TaskKindTelemetry.recordParallel(spec.modId(), spec.taskName());
        }
        ParallelMetrics.recordSubmission(spec.modId(), slices.size());
        ExecutorService executor = ParallelScheduler.executor();
        int jobParallelism = Math.max(1, Math.min(spec.maxParallelism(), ParallelConfig.maxThreads()));
        int workerCount = Math.min(jobParallelism, slices.size());
        BatchSizing sizing = resolveBatchSizing(slices.size(), workerCount);
        AdaptiveBatchSizer adaptiveBatchSizer = new AdaptiveBatchSizer(
            sizing.initial(),
            sizing.min(),
            sizing.max(),
            sizing.targetNanos()
        );

        Object[] results = new Object[slices.size()];
        AtomicBoolean failFast = new AtomicBoolean(false);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicBoolean resultCompleted = new AtomicBoolean(false);

        final AtomicInteger cursor = new AtomicInteger(0);
        final Semaphore modSemaphore = ParallelModTracker.semaphore(spec.modId());

        final CompletableFuture<O> result = new CompletableFuture<>();
        final AtomicInteger remaining = new AtomicInteger(slices.size());

        Runnable tryFinish = () -> {
            if (remaining.get() != 0) {
                return;
            }
            if (!resultCompleted.compareAndSet(false, true)) {
                return;
            }
            if (failFast.get()) {
                Throwable failure = firstFailure.get();
                result.completeExceptionally(failure != null ? failure : new IllegalStateException("Parallel batch failed"));
                return;
            }
            try {
                List<R> collected = new ArrayList<>(results.length);
                for (Object entry : results) {
                    @SuppressWarnings("unchecked")
                    R cast = (R) entry;
                    collected.add(cast);
                }
                result.complete(spec.reducer().apply(collected));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };

        final ParallelSliceCachePolicy<S, R> cachePolicy = spec.cachePolicy();
        final Consumer<R> listener = spec.sliceListener();
        final ParallelFailurePolicy failurePolicy = spec.failurePolicy();

        class BatchPump implements Runnable {
            private final int[] indices = new int[sizing.max()];
            private final Object[] pending = new Object[sizing.max()];
            private final int minBackoffMs = Math.max(1, Integer.getInteger("quantified.parallel.backoffMs", 4));
            private final int maxBackoffMs = Math.max(minBackoffMs, Integer.getInteger("quantified.parallel.backoffMaxMs", 50));
            private int backoffMs = minBackoffMs;

            @Override
            public void run() {
                if (failFast.get()) {
                    return;
                }

                int currentBatchSize = adaptiveBatchSizer.size();
                int permittedBatchSize = resolvePermittedBatchSize(currentBatchSize, modSemaphore);
                if (permittedBatchSize <= 0) {
                    rescheduleWithBackoff();
                    return;
                }
                if (!tryAcquirePermits(modSemaphore, permittedBatchSize)) {
                    rescheduleWithBackoff();
                    return;
                }
                resetBackoff();

                int start = cursor.getAndAdd(permittedBatchSize);
                if (start >= slices.size()) {
                    releasePermits(modSemaphore, permittedBatchSize);
                    return;
                }
                int end = Math.min(slices.size(), start + permittedBatchSize);
                int acquiredPermits = end - start;
                if (acquiredPermits <= 0) {
                    releasePermits(modSemaphore, permittedBatchSize);
                    return;
                }
                if (acquiredPermits < permittedBatchSize) {
                    releasePermits(modSemaphore, permittedBatchSize - acquiredPermits);
                }

                if (failFast.get()) {
                    recordSkippedBatch(end - start);
                    releasePermits(modSemaphore, acquiredPermits);
                    return;
                }

                int pendingCount = 0;
                int cachedCount = 0;

                for (int i = start; i < end; i++) {
                    if (failFast.get()) {
                        recordSkippedBatch(end - i);
                        break;
                    }
                    S slice = slices.get(i);
                    if (cachePolicy != null) {
                        R cached = ParallelResultCache.tryLoad(spec, cachePolicy, slice);
                        if (cached != null) {
                            results[i] = cached;
                            if (listener != null) {
                                try {
                                    listener.accept(cached);
                                } catch (Throwable ignored) {
                                }
                            }
                            cachedCount++;
                            continue;
                        }
                    }
                    indices[pendingCount] = i;
                    pending[pendingCount] = slice;
                    pendingCount++;
                }

                if (acquiredPermits > pendingCount) {
                    releasePermits(modSemaphore, acquiredPermits - pendingCount);
                }

                final int cachedCountFinal = cachedCount;
                final int pendingCountFinal = pendingCount;

                if (pendingCountFinal == 0) {
                    if (cachedCountFinal > 0) {
                        ParallelMetrics.recordCompletion(spec.modId(), cachedCountFinal, 0);
                        if (remaining.addAndGet(-cachedCountFinal) == 0) {
                            tryFinish.run();
                        }
                    }
                    if (!failFast.get()) {
                        executor.execute(this);
                    }
                    return;
                }

                if (failFast.get()) {
                    recordBatchFailure(cachedCountFinal, pendingCountFinal, null);
                    return;
                }

                ParallelMetrics.recordDispatch(spec.modId(), pendingCountFinal);
                final long batchStartNanos = System.nanoTime();

                AtomicInteger batchRemaining = new AtomicInteger(pendingCountFinal);
                AtomicInteger batchSuccess = new AtomicInteger();
                AtomicInteger batchFailure = new AtomicInteger();

                for (int i = 0; i < pendingCountFinal; i++) {
                    if (failFast.get() && failurePolicy == ParallelFailurePolicy.FAIL_FAST) {
                        int skipped = pendingCountFinal - i;
                        for (int j = i; j < pendingCountFinal; j++) {
                            results[indices[j]] = null;
                        }
                        batchFailure.addAndGet(skipped);
                        if (batchRemaining.addAndGet(-skipped) == 0) {
                            finishBatch(cachedCountFinal, pendingCountFinal, batchSuccess, batchFailure, batchStartNanos);
                        }
                        break;
                    }

                    int index = indices[i];
                    @SuppressWarnings("unchecked")
                    S slice = (S) pending[i];

                    CompletableFuture<R> sliceFuture;
                    try {
                        sliceFuture = spec.sliceExecutor().apply(slice);
                        if (sliceFuture == null) {
                            throw new IllegalStateException("Slice executor returned null future");
                        }
                    } catch (Throwable throwable) {
                        results[index] = null;
                        batchFailure.incrementAndGet();
                        if (failurePolicy == ParallelFailurePolicy.FAIL_FAST) {
                            triggerFailFast(spec, throwable, failFast, firstFailure, resultCompleted, result, remaining, cursor, slices.size());
                        }
                        if (batchRemaining.decrementAndGet() == 0) {
                            finishBatch(cachedCountFinal, pendingCountFinal, batchSuccess, batchFailure, batchStartNanos);
                        }
                        continue;
                    }

                    sliceFuture.whenComplete((value, error) -> {
                        if (error != null) {
                            results[index] = null;
                            batchFailure.incrementAndGet();
                            if (failurePolicy == ParallelFailurePolicy.FAIL_FAST) {
                                triggerFailFast(spec, error, failFast, firstFailure, resultCompleted, result, remaining, cursor, slices.size());
                            }
                        } else {
                            results[index] = value;
                            batchSuccess.incrementAndGet();
                            if (cachePolicy != null) {
                                ParallelResultCache.store(spec, cachePolicy, slice, value);
                            }
                            if (listener != null) {
                                try {
                                    listener.accept(value);
                                } catch (Throwable ignored) {
                                }
                            }
                        }

                        if (batchRemaining.decrementAndGet() == 0) {
                            finishBatch(cachedCountFinal, pendingCountFinal, batchSuccess, batchFailure, batchStartNanos);
                        }
                    });
                }
            }

            private void finishBatch(int cachedCount,
                                     int pendingCount,
                                     AtomicInteger batchSuccess,
                                     AtomicInteger batchFailure,
                                     long batchStartNanos) {
                modSemaphore.release(pendingCount);
                ParallelBackpressure.release(pendingCount);

                int successes = cachedCount + batchSuccess.get();
                int failures = batchFailure.get();
                ParallelMetrics.recordCompletion(spec.modId(), successes, failures);
                adaptiveBatchSizer.update(System.nanoTime() - batchStartNanos);

                if (remaining.addAndGet(-(cachedCount + pendingCount)) == 0) {
                    tryFinish.run();
                }

                if (!failFast.get()) {
                    executor.execute(this);
                }
            }

            private void recordBatchFailure(int cachedCount, int pendingCount, Throwable error) {
                releasePermits(modSemaphore, pendingCount);
                for (int i = 0; i < pendingCount; i++) {
                    results[indices[i]] = null;
                }
                if (failurePolicy == ParallelFailurePolicy.FAIL_FAST && error != null) {
                    triggerFailFast(spec, error, failFast, firstFailure,
                        resultCompleted, result, remaining, cursor, slices.size());
                }
                ParallelMetrics.recordCompletion(spec.modId(), cachedCount, pendingCount);
                if (remaining.addAndGet(-(cachedCount + pendingCount)) == 0) {
                    tryFinish.run();
                }
            }

            private void recordSkippedBatch(int count) {
                if (count <= 0) {
                    return;
                }
                ParallelMetrics.recordCompletion(spec.modId(), 0, count);
                if (remaining.addAndGet(-count) == 0) {
                    tryFinish.run();
                }
            }

            private void rescheduleWithBackoff() {
                if (failFast.get()) {
                    return;
                }
                ParallelMetrics.recordRejection();
                int delay = backoffMs;
                backoffMs = Math.min(maxBackoffMs, backoffMs * 2);
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor).execute(this);
            }

            private void resetBackoff() {
                backoffMs = minBackoffMs;
            }
        }

        for (int i = 0; i < workerCount; i++) {
            executor.execute(new BatchPump());
        }

        return result;
    }

    private static <S, R, O> void runUnifiedWorker(ParallelTaskSpec<S, R, O> spec,
                                                   List<S> slices,
                                                   Object[] results,
                                                   AtomicInteger cursor,
                                                   Semaphore modSemaphore,
                                                   AtomicBoolean failFast,
                                                   AtomicReference<Throwable> firstFailure,
                                                   Function<S, R> directExecutor,
                                                   ParallelSliceCachePolicy<S, R> cachePolicy,
                                                   Consumer<R> listener,
                                                   ParallelFailurePolicy failurePolicy,
                                                   int maxBatchSize) {
        int batchSize = Math.max(1, maxBatchSize);
        while (!failFast.get()) {
            int permits = acquirePermits(modSemaphore, batchSize);
            if (permits <= 0) {
                break;
            }
            int start = cursor.getAndAdd(permits);
            if (start >= slices.size()) {
                releasePermits(modSemaphore, permits);
                break;
            }
            int end = Math.min(slices.size(), start + permits);
            int actualPermits = end - start;
            if (actualPermits <= 0) {
                releasePermits(modSemaphore, permits);
                break;
            }
            if (actualPermits < permits) {
                releasePermits(modSemaphore, permits - actualPermits);
            }

            int successes = 0;
            int failures = 0;
            for (int index = start; index < end; index++) {
                if (failFast.get()) {
                    failures += end - index;
                    break;
                }
                S slice = slices.get(index);
                try {
                    R value = cachePolicy != null ? ParallelResultCache.tryLoad(spec, cachePolicy, slice) : null;
                    if (value == null) {
                        value = directExecutor.apply(slice);
                        if (cachePolicy != null) {
                            ParallelResultCache.store(spec, cachePolicy, slice, value);
                        }
                    }
                    results[index] = value;
                    if (listener != null) {
                        try {
                            listener.accept(value);
                        } catch (Throwable ignored) {
                        }
                    }
                    successes++;
                } catch (Throwable throwable) {
                    results[index] = null;
                    failures++;
                    if (failurePolicy == ParallelFailurePolicy.FAIL_FAST) {
                        firstFailure.compareAndSet(null, throwable);
                        failFast.set(true);
                    }
                }
            }
            ParallelMetrics.recordDispatch(spec.modId(), actualPermits);
            ParallelMetrics.recordCompletion(spec.modId(), successes, failures);
            modSemaphore.release(actualPermits);
            ParallelBackpressure.release(actualPermits);
        }
    }

    private static int acquirePermits(Semaphore modSemaphore, int desired) {
        int attempts = 0;
        int target = Math.max(1, desired);
        while (attempts++ < 32) {
            int allowed = resolvePermittedBatchSize(target, modSemaphore);
            if (allowed > 0 && tryAcquirePermits(modSemaphore, allowed)) {
                return allowed;
            }
            ParallelMetrics.recordRejection();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(Math.min(8, attempts)));
        }
        return 0;
    }

    private static String taskAffinity(String taskName) {
        if (taskName == null || taskName.isBlank()) {
            return "parallel|default";
        }
        return "parallel|" + taskName.trim().toLowerCase().replace(' ', '_');
    }

    private static int resolveBatchSize(int sliceCount, int workers) {
        if (sliceCount <= 0 || workers <= 0) {
            return 1;
        }
        int min = Math.max(1, Integer.getInteger("quantified.parallel.batchMin", 8));
        int max = Math.max(min, Integer.getInteger("quantified.parallel.batchMax", 256));
        int base = Math.max(1, sliceCount / workers);
        return Math.min(sliceCount, Math.min(max, Math.max(min, base)));
    }

    private static BatchSizing resolveBatchSizing(int sliceCount, int workers) {
        int min = Math.max(1, Integer.getInteger("quantified.parallel.batchMin", 8));
        int max = Math.max(min, Integer.getInteger("quantified.parallel.batchMax", 256));
        long targetMs = Math.max(1L, Long.getLong("quantified.parallel.batchTargetMs", 4L));
        int initial = resolveBatchSize(sliceCount, workers);
        return new BatchSizing(initial, min, max, targetMs * 1_000_000L);
    }

    private record BatchSizing(int initial, int min, int max, long targetNanos) {
    }

    private static int resolvePermittedBatchSize(int desired, Semaphore modSemaphore) {
        if (desired <= 0) {
            return 0;
        }
        int modAvailable = Math.max(0, modSemaphore.availablePermits());
        int globalAvailable = Math.max(0, ParallelBackpressure.availablePermits());
        int allowed = Math.min(desired, Math.min(modAvailable, globalAvailable));
        return Math.max(0, allowed);
    }

    private static boolean tryAcquirePermits(Semaphore modSemaphore, int permits) {
        if (permits <= 0) {
            return false;
        }
        if (!ParallelBackpressure.tryAcquire(permits)) {
            return false;
        }
        if (!modSemaphore.tryAcquire(permits)) {
            ParallelBackpressure.release(permits);
            return false;
        }
        return true;
    }

    private static void releasePermits(Semaphore modSemaphore, int permits) {
        if (permits <= 0) {
            return;
        }
        modSemaphore.release(permits);
        ParallelBackpressure.release(permits);
    }

    private static final class AdaptiveBatchSizer {
        private static final double SHRINK_FACTOR = 0.70;
        private static final double GROW_FACTOR = 1.20;

        private final int min;
        private final int max;
        private final long targetNanos;
        private final AtomicInteger current;

        private AdaptiveBatchSizer(int initial, int min, int max, long targetNanos) {
            this.min = min;
            this.max = Math.max(min, max);
            this.targetNanos = Math.max(1L, targetNanos);
            this.current = new AtomicInteger(Math.max(min, Math.min(this.max, initial)));
        }

        private int size() {
            return current.get();
        }

        private void update(long elapsedNanos) {
            if (elapsedNanos <= 0) {
                return;
            }
            int prev = current.get();
            int next = prev;
            if (elapsedNanos > targetNanos && prev > min) {
                next = Math.max(min, (int) Math.floor(prev * SHRINK_FACTOR));
            } else if (elapsedNanos < (targetNanos / 2) && prev < max) {
                next = Math.min(max, (int) Math.ceil(prev * GROW_FACTOR));
            }
            if (next == prev) {
                return;
            }
            current.compareAndSet(prev, next);
        }
    }

    private static <O> void triggerFailFast(ParallelTaskSpec<?, ?, O> spec,
                                            Throwable error,
                                            AtomicBoolean failFast,
                                            AtomicReference<Throwable> firstFailure,
                                            AtomicBoolean resultCompleted,
                                            CompletableFuture<O> result,
                                            AtomicInteger remaining,
                                            AtomicInteger cursor,
                                            int totalSlices) {
        if (!failFast.compareAndSet(false, true)) {
            return;
        }
        firstFailure.compareAndSet(null, error);
        int claimed = Math.min(totalSlices, cursor.get());
        int unassigned = totalSlices - claimed;
        if (unassigned > 0) {
            ParallelMetrics.recordCompletion(spec.modId(), 0, unassigned);
            remaining.addAndGet(-unassigned);
        }
        if (resultCompleted.compareAndSet(false, true)) {
            result.completeExceptionally(error);
        }
    }

}
