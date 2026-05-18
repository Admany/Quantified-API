package org.admany.quantified.api;

import org.admany.quantified.api.parallel.ParallelCompute;
import org.admany.quantified.core.common.parallel.policy.ParallelFailurePolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

public final class ParallelRequest {
    private final String modId;
    private final String taskName;
    private long taskKey;
    private boolean explicitKey;
    private int maxParallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
    private ParallelFailurePolicy failurePolicy = ParallelFailurePolicy.FAIL_FAST;

    ParallelRequest(String modId, String taskName) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.taskName = Objects.requireNonNull(taskName, "taskName");
        this.taskKey = StableTaskKeys.of("parallel", modId, taskName);
    }

    public ParallelRequest key(long taskKey) {
        this.taskKey = taskKey;
        this.explicitKey = true;
        return this;
    }

    public ParallelRequest key(String taskKey) {
        this.taskKey = StableTaskKeys.named("parallel", modId, taskName, taskKey);
        this.explicitKey = true;
        return this;
    }

    public ParallelRequest maxParallelism(int maxParallelism) {
        this.maxParallelism = Math.max(1, maxParallelism);
        return this;
    }

    public ParallelRequest failurePolicy(ParallelFailurePolicy failurePolicy) {
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        return this;
    }

    public <S> ItemPlan<S> items(Collection<S> items) {
        return items(() -> List.copyOf(items));
    }

    public <S> ItemPlan<S> items(java.util.function.Supplier<List<S>> itemsSupplier) {
        return new ItemPlan<>(this, itemsSupplier);
    }

    public RangePlan range(int startInclusive, int endExclusive) {
        return new RangePlan(this, startInclusive, endExclusive);
    }

    private long resolveKey(String qualifier) {
        if (explicitKey) {
            return taskKey;
        }
        return StableTaskKeys.of("parallel", modId, taskName, qualifier);
    }

    public static final class ItemPlan<S> {
        private final ParallelRequest owner;
        private final java.util.function.Supplier<List<S>> itemsSupplier;

        private ItemPlan(ParallelRequest owner, java.util.function.Supplier<List<S>> itemsSupplier) {
            this.owner = owner;
            this.itemsSupplier = Objects.requireNonNull(itemsSupplier, "itemsSupplier");
        }

        public <R> MappingPlan<S, R> map(Function<S, R> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new MappingPlan<>(owner, itemsSupplier, slice -> CompletableFuture.completedFuture(mapper.apply(slice)), mapper);
        }

        public <R> MappingPlan<S, R> mapAsync(Function<S, CompletableFuture<R>> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new MappingPlan<>(owner, itemsSupplier, mapper, null);
        }

        public CompletableFuture<Void> forEach(Consumer<S> consumer) {
            return map(slice -> {
                consumer.accept(slice);
                return null;
            }).discardResults();
        }
    }

    public static final class RangePlan {
        private final ParallelRequest owner;
        private final int startInclusive;
        private final int endExclusive;

        private RangePlan(ParallelRequest owner, int startInclusive, int endExclusive) {
            this.owner = owner;
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
        }

        public <R> MappingPlan<Integer, R> map(IntFunction<R> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new MappingPlan<>(owner, this::indexes, index -> CompletableFuture.completedFuture(mapper.apply(index)), mapper::apply);
        }

        public <R> MappingPlan<Integer, R> mapAsync(Function<Integer, CompletableFuture<R>> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new MappingPlan<>(owner, this::indexes, mapper, null);
        }

        public CompletableFuture<Void> forEach(java.util.function.IntConsumer consumer) {
            return map(index -> {
                consumer.accept(index);
                return null;
            }).discardResults();
        }

        private List<Integer> indexes() {
            if (endExclusive <= startInclusive) {
                return List.of();
            }
            List<Integer> indexes = new ArrayList<>(endExclusive - startInclusive);
            for (int i = startInclusive; i < endExclusive; i++) {
                indexes.add(i);
            }
            return indexes;
        }
    }

    public static final class MappingPlan<S, R> {
        private final ParallelRequest owner;
        private final java.util.function.Supplier<List<S>> itemsSupplier;
        private final Function<S, CompletableFuture<R>> asyncMapper;
        private final Function<S, R> directMapper;
        private Consumer<R> streamListener;
        private Function<List<R>, ?> reducer;

        private MappingPlan(ParallelRequest owner,
                            java.util.function.Supplier<List<S>> itemsSupplier,
                            Function<S, CompletableFuture<R>> asyncMapper,
                            Function<S, R> directMapper) {
            this.owner = owner;
            this.itemsSupplier = itemsSupplier;
            this.asyncMapper = asyncMapper;
            this.directMapper = directMapper;
        }

        public MappingPlan<S, R> stream(Consumer<R> listener) {
            this.streamListener = listener;
            return this;
        }

        public <O> MappingPlan<S, R> reduce(Function<List<R>, O> reducer) {
            this.reducer = Objects.requireNonNull(reducer, "reducer");
            return this;
        }

        public CompletableFuture<List<R>> submit() {
            return submitInternal(results -> List.copyOf(results));
        }

        public CompletableFuture<Void> discardResults() {
            return submitInternal(results -> null);
        }

        @SuppressWarnings("unchecked")
        public <O> CompletableFuture<O> collect(Function<List<R>, O> reducer) {
            this.reducer = Objects.requireNonNull(reducer, "reducer");
            return (CompletableFuture<O>) submitInternal((Function<List<R>, ?>) reducer);
        }

        @SuppressWarnings("unchecked")
        private <O> CompletableFuture<O> submitInternal(Function<List<R>, ?> fallbackReducer) {
            Function<List<R>, ?> finalReducer = reducer != null ? reducer : fallbackReducer;
            @SuppressWarnings("unchecked")
            Function<List<R>, Object> typedReducer = (Function<List<R>, Object>) finalReducer;
            @SuppressWarnings({"rawtypes", "unchecked"})
            ParallelCompute.Builder<S, R, Object> builder = (ParallelCompute.Builder) ParallelCompute.<S, R>builder(
                    owner.modId,
                    owner.taskName,
                    owner.resolveKey("map"))
                .slices(itemsSupplier)
                .maxParallelism(owner.maxParallelism)
                .failurePolicy(owner.failurePolicy)
                .sliceListener(streamListener);
            if (directMapper != null) {
                builder.sliceExecutor(directMapper);
            } else {
                builder.asyncSliceExecutor(asyncMapper);
            }
            builder.reducer(typedReducer);
            return (CompletableFuture<O>) builder.submit();
        }
    }
}
