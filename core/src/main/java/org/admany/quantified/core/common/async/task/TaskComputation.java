package org.admany.quantified.core.common.async.task;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@FunctionalInterface
public interface TaskComputation<T> {

    CompletableFuture<T> execute();

    default boolean isThreadSafe() {
        return true;
    }

    default String description() {
        return getClass().getName();
    }

    default TaskComputation<T> withThreadSafety(boolean threadSafe) {
        TaskComputation<T> delegate = this;
        return new TaskComputation<>() {
            @Override
            public CompletableFuture<T> execute() {
                return delegate.execute();
            }

            @Override
            public boolean isThreadSafe() {
                return threadSafe;
            }

            @Override
            public String description() {
                return delegate.description();
            }
        };
    }

    static <T> TaskComputation<T> sync(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return new TaskComputation<>() {
            @Override
            public CompletableFuture<T> execute() {
                T value = supplier.get();
                return CompletableFuture.completedFuture(value);
            }

            @Override
            public String description() {
                return "SyncComputation(" + supplier + ")";
            }
        };
    }

    static <T> TaskComputation<T> async(Supplier<CompletableFuture<T>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return new TaskComputation<>() {
            @Override
            public CompletableFuture<T> execute() {
                CompletableFuture<T> future = supplier.get();
                return Objects.requireNonNull(future, "async computation future");
            }

            @Override
            public String description() {
                return "AsyncComputation(" + supplier + ")";
            }
        };
    }

    static <T> TaskComputation<T> async(Supplier<CompletableFuture<T>> supplier, boolean threadSafe) {
        return async(supplier).withThreadSafety(threadSafe);
    }
}
