package org.admany.quantified.core.common.resilience;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class ResilientTaskBuilder<T> {
    private CircuitBreaker circuitBreaker;
    private org.admany.quantified.core.common.resilience.RetryPolicy retryPolicy = org.admany.quantified.core.common.resilience.RetryPolicy.none();
    private Bulkhead bulkhead;
    private Duration timeout = Duration.ofSeconds(30);

    public record BulkheadMetrics(int activeCount, int queueSize, int maxConcurrent) {}

    public ResilientTaskBuilder() {
    }

    public static ResilientTaskBuilder<?> create(String taskName) {
        return new ResilientTaskBuilder<>();
    }

    public ResilientTaskBuilder<T> circuitBreaker(String name, int failureThreshold, Duration recoveryTimeout) {
        this.circuitBreaker = new CircuitBreaker(name, failureThreshold, recoveryTimeout);
        return this;
    }

    public ResilientTaskBuilder<T> retry(org.admany.quantified.core.common.resilience.RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }

    public ResilientTaskBuilder<T> bulkhead(String name, int maxConcurrent) {
        this.bulkhead = new Bulkhead(name, maxConcurrent);
        return this;
    }

    public ResilientTaskBuilder<T> timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public CompletableFuture<T> executeAsync(Supplier<CompletableFuture<T>> taskSupplier) {
        Supplier<CompletableFuture<T>> currentSupplier = () -> retryPolicy.execute(taskSupplier);

        if (bulkhead != null) {
            Supplier<CompletableFuture<T>> previousSupplier = currentSupplier;
            currentSupplier = () -> bulkhead.execute(previousSupplier);
        }

        if (circuitBreaker != null) {
            return circuitBreaker.execute(currentSupplier)
                .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            return currentSupplier.get()
                .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker != null ? circuitBreaker.getState() : null;
    }

    public BulkheadMetrics getBulkheadMetrics() {
        if (bulkhead == null) return null;

        return new BulkheadMetrics(
            bulkhead.getActiveCount(),
            bulkhead.getQueueSize(),
            bulkhead.getMaxConcurrent()
        );
    }

    public ResilientTaskBuilder<T> withCircuitBreaker(int failureThreshold, long recoveryTimeoutMs) {
        return circuitBreaker("default", failureThreshold, Duration.ofMillis(recoveryTimeoutMs));
    }

    public ResilientTaskBuilder<T> withRetryPolicy(RetryPolicy policy, int maxRetries) {
        org.admany.quantified.core.common.resilience.RetryPolicy corePolicy;
        switch (policy) {
            case NONE:
                corePolicy = org.admany.quantified.core.common.resilience.RetryPolicy.none();
                break;
            case LINEAR_BACKOFF:
                corePolicy = org.admany.quantified.core.common.resilience.RetryPolicy.linear(maxRetries, Duration.ofSeconds(1));
                break;
            case EXPONENTIAL_BACKOFF:
                corePolicy = org.admany.quantified.core.common.resilience.RetryPolicy.exponential(maxRetries, Duration.ofMillis(100));
                break;
            default:
                corePolicy = org.admany.quantified.core.common.resilience.RetryPolicy.none();
        }
        return retry(corePolicy);
    }

    public ResilientTaskBuilder<T> withBulkhead(int maxConcurrent) {
        return bulkhead("default", maxConcurrent);
    }

    public CompletableFuture<T> execute(java.util.function.Supplier<T> task) {
        return executeAsync(() -> CompletableFuture.completedFuture(task.get()));
    }

    public enum RetryPolicy { NONE, LINEAR_BACKOFF, EXPONENTIAL_BACKOFF }
}