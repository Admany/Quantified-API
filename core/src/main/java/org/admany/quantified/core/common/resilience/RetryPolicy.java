package org.admany.quantified.core.common.resilience;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class RetryPolicy {
    private static final Logger LOGGER = Logger.getLogger(RetryPolicy.class.getName());

    public enum BackoffStrategy {
        NONE,
        LINEAR_BACKOFF,
        EXPONENTIAL_BACKOFF
    }

    private final int maxRetries;
    private final BackoffStrategy backoffStrategy;
    private final Duration initialDelay;
    private final double backoffMultiplier;
    private final Duration maxDelay;

    private RetryPolicy(int maxRetries, BackoffStrategy backoffStrategy,
                       Duration initialDelay, double backoffMultiplier, Duration maxDelay) {
        this.maxRetries = maxRetries;
        this.backoffStrategy = backoffStrategy;
        this.initialDelay = initialDelay;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelay = maxDelay;
    }

    public static RetryPolicy none() {
        return new RetryPolicy(0, BackoffStrategy.NONE, Duration.ZERO, 1.0, Duration.ZERO);
    }

    public static RetryPolicy linear(int maxRetries, Duration delay) {
        return new RetryPolicy(maxRetries, BackoffStrategy.LINEAR_BACKOFF, delay, 1.0, Duration.ofHours(1));
    }

    public static RetryPolicy exponential(int maxRetries, Duration initialDelay) {
        return new RetryPolicy(maxRetries, BackoffStrategy.EXPONENTIAL_BACKOFF, initialDelay, 2.0, Duration.ofMinutes(5));
    }

    public static RetryPolicy exponential(int maxRetries, Duration initialDelay, double multiplier, Duration maxDelay) {
        return new RetryPolicy(maxRetries, BackoffStrategy.EXPONENTIAL_BACKOFF, initialDelay, multiplier, maxDelay);
    }

    public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> taskSupplier) {
        return executeWithRetry(taskSupplier, 0);
    }

    private <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> taskSupplier, int attempt) {
        try {
            CompletableFuture<T> task = taskSupplier.get();
            return task.exceptionally(error -> {
                if (attempt < maxRetries) {
                    Duration delay = calculateDelay(attempt);
                    LOGGER.fine("Task failed (attempt " + (attempt + 1) + "/" + (maxRetries + 1) +
                               "), retrying in " + delay.toMillis() + "ms: " + error.getMessage());

                    CompletableFuture<T> retryFuture = new CompletableFuture<>();
                    CompletableFuture.delayedExecutor(delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            executeWithRetry(taskSupplier, attempt + 1)
                                .whenComplete((result, retryError) -> {
                                    if (retryError != null) {
                                        retryFuture.completeExceptionally(retryError);
                                    } else {
                                        retryFuture.complete(result);
                                    }
                                });
                        });
                    return retryFuture.join(); 
                } else {
                    if (error instanceof RuntimeException) {
                        throw (RuntimeException) error;
                    } else {
                        throw new RuntimeException(error);
                    }
                }
            });
        } catch (Exception e) {
            if (attempt < maxRetries) {
                Duration delay = calculateDelay(attempt);
                LOGGER.fine("Task setup failed (attempt " + (attempt + 1) + "/" + (maxRetries + 1) +
                           "), retrying in " + delay.toMillis() + "ms: " + e.getMessage());

                CompletableFuture<T> retryFuture = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        executeWithRetry(taskSupplier, attempt + 1)
                            .whenComplete((result, retryError) -> {
                                if (retryError != null) {
                                    retryFuture.completeExceptionally(retryError);
                                } else {
                                    retryFuture.complete(result);
                                }
                            });
                    });
                return retryFuture;
            } else {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }
    }

    private Duration calculateDelay(int attempt) {
        switch (backoffStrategy) {
            case NONE:
                return Duration.ZERO;

            case LINEAR_BACKOFF:
                return initialDelay;

            case EXPONENTIAL_BACKOFF:
                long delayMillis = (long) (initialDelay.toMillis() * Math.pow(backoffMultiplier, attempt));
                return Duration.ofMillis(Math.min(delayMillis, maxDelay.toMillis()));

            default:
                return initialDelay;
        }
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public BackoffStrategy getBackoffStrategy() {
        return backoffStrategy;
    }
}