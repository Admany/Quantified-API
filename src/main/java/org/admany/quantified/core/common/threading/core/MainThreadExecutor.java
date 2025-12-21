package org.admany.quantified.core.common.threading.core;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.admany.quantified.core.common.async.task.TaskComputation;


public final class MainThreadExecutor {

    private static final Logger LOGGER = Logger.getLogger(MainThreadExecutor.class.getName());

    private static volatile Executor executor;

    private MainThreadExecutor() {
    }

    public static void install(Executor mainThreadExecutor) {
        executor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Installed main-thread executor: {0}", mainThreadExecutor);
        }
    }

    public static void clear() {
        executor = null;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Cleared main-thread executor");
        }
    }

    public static Optional<Executor> executor() {
        return Optional.ofNullable(executor);
    }

    public static <T> CompletableFuture<T> reroute(TaskComputation<T> computation) {
        Objects.requireNonNull(computation, "computation");
        Executor exec = executor;
        if (exec == null) {
            IllegalStateException exception = new IllegalStateException("Main thread executor not installed");
            LOGGER.log(Level.SEVERE, "Failed to reroute task to main thread", exception);
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(exception);
            return failed;
        }
        CompletableFuture<T> wrapper = new CompletableFuture<>();
        exec.execute(() -> {
            try {
                CompletableFuture<T> delegate = computation.execute();
                delegate.whenComplete((result, error) -> {
                    if (error != null) {
                        wrapper.completeExceptionally(error);
                    } else {
                        wrapper.complete(result);
                    }
                });
            } catch (Throwable throwable) {
                wrapper.completeExceptionally(throwable);
            }
        });
        return wrapper;
    }
}
