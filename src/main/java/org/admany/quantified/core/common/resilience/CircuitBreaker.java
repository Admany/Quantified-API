package org.admany.quantified.core.common.resilience;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class CircuitBreaker {
    private static final Logger LOGGER = Logger.getLogger(CircuitBreaker.class.getName());

    private final String name;
    private final int failureThreshold;
    private final Duration recoveryTimeout;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    public enum State {
        CLOSED,    // Normal operation
        OPEN,      // Failing fast
        HALF_OPEN  // Testing recovery
    }

    public CircuitBreaker(String name, int failureThreshold, Duration recoveryTimeout) {
        this(name, failureThreshold, recoveryTimeout, Duration.ofMinutes(1));
    }

    public CircuitBreaker(String name, int failureThreshold, Duration recoveryTimeout, Duration monitoringWindow) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.recoveryTimeout = recoveryTimeout;
    }

    public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> taskSupplier) {
        State currentState = state.get();

        switch (currentState) {
            case OPEN:
                if (shouldAttemptReset()) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        LOGGER.info("Circuit breaker '" + name + "' entering HALF_OPEN state");
                        return executeInHalfOpen(taskSupplier);
                    }
                }
                CompletableFuture<T> failed = new CompletableFuture<>();
                ErrorHandler.QuantifiedException exception = ErrorHandler.getInstance().createException(
                    ErrorHandler.ErrorType.CIRCUIT_OPEN,
                    "Circuit breaker execution",
                    Map.of("circuitBreaker", name, "state", currentState)
                );
                failed.completeExceptionally(exception);
                return failed;

            case HALF_OPEN:
                return executeInHalfOpen(taskSupplier);

            case CLOSED:
            default:
                return executeInClosed(taskSupplier);
        }
    }

    private <T> CompletableFuture<T> executeInClosed(Supplier<CompletableFuture<T>> taskSupplier) {
        try {
            CompletableFuture<T> task = taskSupplier.get();
            return task.whenComplete((result, error) -> {
                if (error != null) {
                    recordFailure();
                } else {
                    recordSuccess();
                }
            });
        } catch (Exception e) {
            recordFailure();
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private <T> CompletableFuture<T> executeInHalfOpen(Supplier<CompletableFuture<T>> taskSupplier) {
        try {
            CompletableFuture<T> task = taskSupplier.get();
            return task.whenComplete((result, error) -> {
                if (error != null) {
                    if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                        LOGGER.warning("Circuit breaker '" + name + "' test failed, returning to OPEN");
                        lastFailureTime.set(System.currentTimeMillis());
                        ErrorHandler.getInstance().handleError(
                            ErrorHandler.ErrorType.CIRCUIT_HALF_OPEN_FAILED,
                            "Circuit breaker recovery test",
                            Map.of("circuitBreaker", name)
                        );
                    }
                } else {
                    if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                        LOGGER.info("Circuit breaker '" + name + "' test succeeded, returning to CLOSED");
                        consecutiveFailures.set(0);
                    }
                }
            });
        } catch (Exception e) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                LOGGER.warning("Circuit breaker '" + name + "' test setup failed, returning to OPEN");
                lastFailureTime.set(System.currentTimeMillis());
            }
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            LOGGER.warning("Circuit breaker '" + name + "' opened after " + failures + " consecutive failures");
        }
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
    }

    private boolean shouldAttemptReset() {
        long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
        return timeSinceLastFailure >= recoveryTimeout.toMillis();
    }

    public State getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}


