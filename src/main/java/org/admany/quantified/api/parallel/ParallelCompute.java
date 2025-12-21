package org.admany.quantified.api.parallel;

import org.admany.quantified.api.model.ParallelTaskSpec;
import org.admany.quantified.core.common.parallel.ParallelTaskManager;
import org.admany.quantified.core.common.parallel.policy.ParallelFailurePolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ParallelCompute {
    private ParallelCompute() {
    }

    public static <S, R> Builder<S, R, List<R>> builder(String modId, String taskName, long taskKey) {
        return new Builder<>(modId, taskName, taskKey);
    }

    public static final class Builder<S, R, O> {
        private final String modId;
        private final String taskName;
        private final long taskKey;
        private Supplier<List<S>> sliceSupplier = List::of;
        private Function<S, CompletableFuture<R>> sliceExecutor;
        private Function<List<R>, O> reducer;
        private Consumer<R> sliceListener;
        private ParallelFailurePolicy failurePolicy = ParallelFailurePolicy.FAIL_FAST;
        private int maxParallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
        private ParallelSliceCachePolicy<S, R> cachePolicy;

        private Builder(String modId, String taskName, long taskKey) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.taskName = Objects.requireNonNull(taskName, "taskName");
            this.taskKey = taskKey;
        }

        public Builder<S, R, O> slices(Supplier<List<S>> supplier) {
            this.sliceSupplier = Objects.requireNonNull(supplier, "sliceSupplier");
            return this;
        }

        public Builder<S, R, O> sliceExecutor(Function<S, R> executor) {
            Objects.requireNonNull(executor, "executor");
            this.sliceExecutor = slice -> CompletableFuture.completedFuture(executor.apply(slice));
            return this;
        }

        public Builder<S, R, O> asyncSliceExecutor(Function<S, CompletableFuture<R>> executor) {
            this.sliceExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public Builder<S, R, O> sliceListener(Consumer<R> listener) {
            this.sliceListener = listener;
            return this;
        }

        public Builder<S, R, O> maxParallelism(int parallelism) {
            this.maxParallelism = Math.max(1, parallelism);
            return this;
        }

        public Builder<S, R, O> reducer(Function<List<R>, O> reducer) {
            this.reducer = Objects.requireNonNull(reducer, "reducer");
            return this;
        }

        public Builder<S, R, O> failurePolicy(ParallelFailurePolicy policy) {
            this.failurePolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder<S, R, O> sliceCache(ParallelSliceCachePolicy<S, R> policy) {
            this.cachePolicy = policy;
            return this;
        }

        public Builder<S, R, O> persistentSliceCache(String cacheName,
                                                     Function<S, String> keyFunction,
                                                     Function<R, byte[]> serializer,
                                                     Function<byte[], R> deserializer,
                                                     Duration ttl,
                                                     long maxEntries,
                                                     boolean compression) {
            this.cachePolicy = new ParallelSliceCachePolicy<>(
                cacheName,
                keyFunction,
                serializer,
                deserializer,
                ttl,
                maxEntries,
                true,
                compression
            );
            return this;
        }

        public Builder<S, R, O> memorySliceCache(String cacheName,
                                                 Function<S, String> keyFunction,
                                                 Function<R, byte[]> serializer,
                                                 Function<byte[], R> deserializer,
                                                 Duration ttl,
                                                 long maxEntries) {
            this.cachePolicy = new ParallelSliceCachePolicy<>(
                cacheName,
                keyFunction,
                serializer,
                deserializer,
                ttl,
                maxEntries,
                false,
                false
            );
            return this;
        }

        public CompletableFuture<O> submit() {
            Objects.requireNonNull(sliceExecutor, "sliceExecutor");
            Function<List<R>, O> finalReducer = reducer != null ? reducer : list -> {
                @SuppressWarnings("unchecked")
                O cast = (O) List.copyOf(list);
                return cast;
            };
            Supplier<List<S>> supplier = wrapSupplier(sliceSupplier);
            ParallelTaskSpec<S, R, O> spec = new ParallelTaskSpec<>(
                modId,
                taskName,
                taskKey,
                supplier,
                sliceExecutor,
                finalReducer,
                sliceListener,
                failurePolicy,
                maxParallelism,
                cachePolicy
            );
            return ParallelTaskManager.submit(spec);
        }

        private Supplier<List<S>> wrapSupplier(Supplier<List<S>> original) {
            return () -> {
                List<S> supplied = original.get();
                if (supplied == null || supplied.isEmpty()) {
                    return List.of();
                }
                if (supplied instanceof ArrayList<S> arrayList) {
                    return arrayList;
                }
                return new ArrayList<>(supplied);
            };
        }
    }
}
