package org.admany.quantified.core.common.parallel;

import org.admany.quantified.api.model.ParallelTaskSpec;
import org.admany.quantified.api.parallel.ParallelSliceCachePolicy;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

public final class ParallelTaskManager {
    private ParallelTaskManager() {
    }

    public static <S, R, O> CompletableFuture<O> submit(ParallelTaskSpec<S, R, O> spec) {
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
        int pumpCount = Math.min(jobParallelism, slices.size());

        List<CompletableFuture<R>> orchestrated = new ArrayList<>(slices.size());
        AtomicReferenceArray<R> results = new AtomicReferenceArray<>(slices.size());
        AtomicBoolean failFast = new AtomicBoolean(false);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicBoolean resultCompleted = new AtomicBoolean(false);

        for (int i = 0; i < slices.size(); i++) {
            orchestrated.add(new CompletableFuture<>());
        }

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
                List<R> collected = new ArrayList<>(results.length());
                for (int i = 0; i < results.length(); i++) {
                    collected.add(results.get(i));
                }
                result.complete(spec.reducer().apply(collected));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };

        Runnable pump = new Runnable() {
            @Override
            public void run() {
                // Fast path: consume cached slices without additional executor hops.
                while (true) {
                    if (failFast.get()) {
                        ParallelMetrics.recordCompletion(spec.modId(), false);
                        return;
                    }
                    int index = cursor.getAndIncrement();
                    if (index >= slices.size()) {
                        return;
                    }

                    CompletableFuture<R> targetFuture = orchestrated.get(index);
                    if (targetFuture.isDone()) {
                        continue;
                    }

                    S slice = slices.get(index);

                    ParallelSliceCachePolicy<S, R> cachePolicy = spec.cachePolicy();
                    if (cachePolicy != null) {
                        R cached = ParallelResultCache.tryLoad(spec, cachePolicy, slice);
                        if (cached != null) {
                            results.set(index, cached);
                            Consumer<R> listener = spec.sliceListener();
                            if (listener != null) {
                                try {
                                    listener.accept(cached);
                                } catch (Throwable ignored) {
                                }
                            }
                            ParallelMetrics.recordCompletion(spec.modId(), true);
                            targetFuture.complete(cached);

                            if (remaining.decrementAndGet() == 0) {
                                tryFinish.run();
                            }
                            continue;
                        }
                    }

                    // Not cached: acquire backpressure + mod permits, then start the slice.
                    try {
                        acquireManaged(ParallelBackpressure.class, 1);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        ParallelMetrics.recordCompletion(spec.modId(), false);
                        targetFuture.completeExceptionally(interrupted);

                        if (remaining.decrementAndGet() == 0) {
                            tryFinish.run();
                        }
                        return;
                    }

                    try {
                        acquireManaged(modSemaphore);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        ParallelMetrics.recordCompletion(spec.modId(), false);
                        ParallelBackpressure.release(1);
                        targetFuture.completeExceptionally(interrupted);

                        if (remaining.decrementAndGet() == 0) {
                            tryFinish.run();
                        }
                        return;
                    }

                    ParallelMetrics.recordDispatch(spec.modId());
                    CompletableFuture<R> sliceFuture;
                    try {
                        sliceFuture = spec.sliceExecutor().apply(slice);
                        if (sliceFuture == null) {
                            throw new IllegalStateException("Slice executor returned null future");
                        }
                    } catch (Throwable throwable) {
                        modSemaphore.release();
                        ParallelBackpressure.release(1);

                        if (spec.failurePolicy() == ParallelFailurePolicy.BEST_EFFORT) {
                            ParallelMetrics.recordCompletion(spec.modId(), false);
                            firstFailure.compareAndSet(null, throwable);
                            results.set(index, null);
                            targetFuture.complete(null);
                            if (remaining.decrementAndGet() == 0) {
                                tryFinish.run();
                            }
                            executor.execute(this);
                            return;
                        }

                        handleSliceFailure(spec, targetFuture, throwable, failFast, firstFailure, orchestrated);

                        if (remaining.decrementAndGet() == 0) {
                            tryFinish.run();
                        }
                        return;
                    }

                    sliceFuture.whenComplete((result, error) -> {
                        modSemaphore.release();
                        ParallelBackpressure.release(1);
                        if (error != null) {
                            if (spec.failurePolicy() == ParallelFailurePolicy.BEST_EFFORT) {
                                ParallelMetrics.recordCompletion(spec.modId(), false);
                                firstFailure.compareAndSet(null, error);
                                results.set(index, null);
                                targetFuture.complete(null);
                            } else {
                                handleSliceFailure(spec, targetFuture, error, failFast, firstFailure, orchestrated);
                            }
                        } else {
                            ParallelMetrics.recordCompletion(spec.modId(), true);
                            results.set(index, result);
                            ParallelSliceCachePolicy<S, R> policy = spec.cachePolicy();
                            if (policy != null) {
                                ParallelResultCache.store(spec, policy, slice, result);
                            }
                            Consumer<R> listener = spec.sliceListener();
                            if (listener != null) {
                                try {
                                    listener.accept(result);
                                } catch (Throwable ignored) {
                                }
                            }
                            targetFuture.complete(result);
                        }

                        if (remaining.decrementAndGet() == 0) {
                            tryFinish.run();
                            return;
                        }

                        // Kick the pump again (on the parallel executor) to start the next slice.
                        if (!failFast.get()) {
                            executor.execute(this);
                        }
                    });
                    return;
                }
            }
        };

        for (int i = 0; i < pumpCount; i++) {
            executor.execute(pump);
        }

        return result;
    }

    private static void acquireManaged(Semaphore semaphore) throws InterruptedException {
        if (semaphore.tryAcquire()) {
            return;
        }
        if (Thread.currentThread() instanceof ForkJoinWorkerThread) {
            ForkJoinPool.managedBlock(new SemaphoreBlocker(semaphore));
        } else {
            semaphore.acquire();
        }
    }

    private static void acquireManaged(Class<?> ignored, int permits) throws InterruptedException {
        if (ParallelBackpressure.tryAcquire(permits)) {
            return;
        }
        if (Thread.currentThread() instanceof ForkJoinWorkerThread) {
            ForkJoinPool.managedBlock(new PermitBlocker(permits));
        } else {
            ParallelBackpressure.acquire(permits);
        }
    }

    private static final class SemaphoreBlocker implements ForkJoinPool.ManagedBlocker {
        private final Semaphore semaphore;

        private SemaphoreBlocker(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public boolean block() throws InterruptedException {
            semaphore.acquire();
            return true;
        }

        @Override
        public boolean isReleasable() {
            return semaphore.tryAcquire();
        }
    }

    private static final class PermitBlocker implements ForkJoinPool.ManagedBlocker {
        private final int permits;

        private PermitBlocker(int permits) {
            this.permits = Math.max(1, permits);
        }

        @Override
        public boolean block() throws InterruptedException {
            ParallelBackpressure.acquire(permits);
            return true;
        }

        @Override
        public boolean isReleasable() {
            return ParallelBackpressure.tryAcquire(permits);
        }
    }

    private static <R, O> void handleSliceFailure(ParallelTaskSpec<?, R, O> spec,
                                                  CompletableFuture<R> targetFuture,
                                                  Throwable error,
                                                  AtomicBoolean failFast,
                                                  AtomicReference<Throwable> firstFailure,
                                                  List<CompletableFuture<R>> allFutures) {
        ParallelMetrics.recordCompletion(spec.modId(), false);
        targetFuture.completeExceptionally(error);
        firstFailure.compareAndSet(null, error);
        if (spec.failurePolicy() == ParallelFailurePolicy.FAIL_FAST && failFast.compareAndSet(false, true)) {
            for (CompletableFuture<R> future : allFutures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }
}
