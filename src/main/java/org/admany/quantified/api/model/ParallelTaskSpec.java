package org.admany.quantified.api.model;

import org.admany.quantified.core.common.parallel.config.ParallelConfig;
import org.admany.quantified.core.common.parallel.policy.ParallelFailurePolicy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.admany.quantified.api.parallel.ParallelSliceCachePolicy;

public final class ParallelTaskSpec<S, R, O> {
    private final String modId;
    private final String taskName;
    private final long taskKey;
    private final Supplier<List<S>> sliceSupplier;
    private final Function<S, CompletableFuture<R>> sliceExecutor;
    private final Function<List<R>, O> reducer;
    private final Consumer<R> sliceListener;
    private final ParallelFailurePolicy failurePolicy;
    private final int maxParallelism;
    private final ParallelSliceCachePolicy<S, R> cachePolicy;

    public ParallelTaskSpec(String modId,
                            String taskName,
                            long taskKey,
                            Supplier<List<S>> sliceSupplier,
                            Function<S, CompletableFuture<R>> sliceExecutor,
                            Function<List<R>, O> reducer,
                            Consumer<R> sliceListener,
                            ParallelFailurePolicy failurePolicy,
                            int maxParallelism,
                            ParallelSliceCachePolicy<S, R> cachePolicy) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.taskName = Objects.requireNonNull(taskName, "taskName");
        this.taskKey = taskKey;
        this.sliceSupplier = Objects.requireNonNull(sliceSupplier, "sliceSupplier");
        this.sliceExecutor = Objects.requireNonNull(sliceExecutor, "sliceExecutor");
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.sliceListener = sliceListener;
        this.failurePolicy = failurePolicy == null ? ParallelConfig.defaultFailurePolicy() : failurePolicy;
        this.maxParallelism = Math.max(1, maxParallelism);
        this.cachePolicy = cachePolicy;
    }

    public String modId() {
        return modId;
    }

    public String taskName() {
        return taskName;
    }

    public long taskKey() {
        return taskKey;
    }

    public Supplier<List<S>> sliceSupplier() {
        return sliceSupplier;
    }

    public Function<S, CompletableFuture<R>> sliceExecutor() {
        return sliceExecutor;
    }

    public Function<List<R>, O> reducer() {
        return reducer;
    }

    public Consumer<R> sliceListener() {
        return sliceListener;
    }

    public ParallelFailurePolicy failurePolicy() {
        return failurePolicy;
    }

    public int maxParallelism() {
        return maxParallelism;
    }

    public ParallelSliceCachePolicy<S, R> cachePolicy() {
        return cachePolicy;
    }
}
