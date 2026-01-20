package org.admany.quantified.core.common.resilience;

import org.admany.quantified.core.common.dev.DeveloperOverlayManager;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ErrorHandler {
    private static final Logger LOGGER = Logger.getLogger(ErrorHandler.class.getName());

    private static final ErrorHandler INSTANCE = new ErrorHandler();

    private final Map<String, ErrorContext> recentErrors = new ConcurrentHashMap<>();

    public enum ErrorType {
        // Circuit breaker errors
        CIRCUIT_OPEN("Circuit breaker is open", "The service is temporarily unavailable due to repeated failures. Try again later."),
        CIRCUIT_HALF_OPEN_FAILED("Circuit breaker recovery failed", "Service recovery attempt failed. Circuit remains open."),

        // Retry policy errors
        RETRY_EXHAUSTED("All retry attempts failed", "The operation failed after all retry attempts. Check system resources and try again."),
        RETRY_TIMEOUT("Retry timeout exceeded", "The operation timed out during retry attempts. Consider increasing timeout or checking network."),

        // Bulkhead errors
        BULKHEAD_FULL("Bulkhead capacity exceeded", "Too many concurrent operations. Wait for current operations to complete or increase bulkhead size."),
        BULKHEAD_TIMEOUT("Bulkhead queue timeout", "Operation queued too long in bulkhead. System may be overloaded."),

        // Scheduling errors
        SCHEDULER_OVERLOADED("Scheduler overloaded", "Task queue is full. Reduce task submission rate or increase queue capacity."),
        SCHEDULER_UNHEALTHY("System unhealthy", "System health checks failed. Tasks deferred until system recovers."),

        // Resource errors
        GPU_UNAVAILABLE("GPU unavailable", "GPU acceleration requested but no GPU available. Falling back to CPU."),
        MEMORY_INSUFFICIENT("Insufficient memory", "Not enough memory for operation. Free up memory or reduce operation size."),
        CACHE_FULL("Cache capacity exceeded", "Cache is full. Consider increasing cache size or implementing eviction policy."),

        // Network errors
        NETWORK_TIMEOUT("Network timeout", "Network operation timed out. Check connectivity and retry."),
        NETWORK_UNAVAILABLE("Network unavailable", "Network services are unavailable. Check network configuration."),

        // Mod integration errors
        MOD_NOT_REGISTERED("Mod not registered", "Attempting to use API with unregistered mod. Register the mod first."),
        MOD_PERMISSION_DENIED("Mod permission denied", "Mod lacks permission for this operation. Check mod permissions."),

        // Generic errors
        UNKNOWN_ERROR("Unknown error", "An unexpected error occurred. Check logs for details."),
        CONFIGURATION_ERROR("Configuration error", "Invalid configuration detected. Check configuration values.");

        public final String name;
        public final String description;

        ErrorType(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    private ErrorHandler() {
    }

    public static ErrorHandler getInstance() {
        return INSTANCE;
    }

    public void handleError(ErrorType errorType, String operation, Throwable cause,
                           Map<String, Object> context) {
        String errorId = generateErrorId();
        ErrorContext errorContext = new ErrorContext(
            errorId, errorType, operation, cause, context, Instant.now()
        );

        recentErrors.put(errorId, errorContext);
        if (recentErrors.size() > 100) { // Limit stored errors
            recentErrors.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .ifPresent(e -> recentErrors.remove(e.getKey()));
        }

        // Log the error
        Level logLevel = determineLogLevel(errorType);
        LOGGER.log(logLevel, formatErrorMessage(errorContext), cause);

        // Record in developer overlay
        DeveloperOverlayManager.recordApiLog("[ERROR] " + errorType.name() + ": " + errorType.description);

        // Provide recovery suggestions
        String recoverySuggestion = getRecoverySuggestion(errorType, context);
        if (recoverySuggestion != null) {
            DeveloperOverlayManager.recordApiLog("[RECOVERY] " + recoverySuggestion);
        }
    }

    public void handleError(ErrorType errorType, String operation, Throwable cause) {
        handleError(errorType, operation, cause, Map.of());
    }

    public void handleError(ErrorType errorType, String operation, Map<String, Object> context) {
        handleError(errorType, operation, null, context);
    }

    public QuantifiedException createException(ErrorType errorType, String operation,
                                             Map<String, Object> context) {
        String errorId = generateErrorId();
        return new QuantifiedException(errorType, operation, context, errorId);
    }

    public Map<String, ErrorContext> getRecentErrors() {
        return Map.copyOf(recentErrors);
    }
    public void clearErrorHistory() {
        recentErrors.clear();
    }

    private String generateErrorId() {
        return "ERR-" + System.currentTimeMillis() + "-" + safeThreadId(Thread.currentThread());
    }

    private static long safeThreadId(Thread thread) {
        if (thread == null) {
            return -1L;
        }
        try {
            java.lang.reflect.Method threadId = Thread.class.getMethod("threadId");
            Object value = threadId.invoke(thread);
            if (value instanceof Long) {
                return (Long) value;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method getId = Thread.class.getMethod("getId");
            Object value = getId.invoke(thread);
            if (value instanceof Long) {
                return (Long) value;
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private Level determineLogLevel(ErrorType errorType) {
        return switch (errorType) {
            case CIRCUIT_OPEN, CIRCUIT_HALF_OPEN_FAILED, SCHEDULER_OVERLOADED,
                 SCHEDULER_UNHEALTHY, GPU_UNAVAILABLE, NETWORK_UNAVAILABLE -> Level.WARNING;
            case RETRY_EXHAUSTED, RETRY_TIMEOUT, BULKHEAD_FULL, BULKHEAD_TIMEOUT,
                 MEMORY_INSUFFICIENT, CACHE_FULL -> Level.SEVERE;
            default -> Level.INFO;
        };
    }

    private String formatErrorMessage(ErrorContext context) {
        StringBuilder message = new StringBuilder();
        message.append("Error [").append(context.errorId).append("]: ");
        message.append(context.errorType.name()).append(" - ");
        message.append(context.errorType.description);
        message.append(" | Operation: ").append(context.operation);

        if (!context.context.isEmpty()) {
            message.append(" | Context: ").append(context.context);
        }

        return message.toString();
    }

    private String getRecoverySuggestion(ErrorType errorType, Map<String, Object> context) {
        return switch (errorType) {
            case CIRCUIT_OPEN -> "Wait for circuit breaker to transition to half-open state, or check service health.";
            case RETRY_EXHAUSTED -> "Verify service availability, check network connectivity, or reduce operation complexity.";
            case BULKHEAD_FULL -> "Reduce concurrent operations or increase bulkhead capacity in configuration.";
            case SCHEDULER_OVERLOADED -> "Implement backpressure in task submission or increase scheduler capacity.";
            case GPU_UNAVAILABLE -> "Ensure GPU drivers are installed and OpenCL is available, or disable GPU acceleration.";
            case MEMORY_INSUFFICIENT -> "Increase JVM heap size (-Xmx) or optimize memory usage in operations.";
            case NETWORK_TIMEOUT -> "Increase timeout values or check network stability.";
            case MOD_NOT_REGISTERED -> "Register the mod with QuantifiedAPI.registerMod() before using API features.";
            default -> null;
        };
    }

    public record ErrorContext(
        String errorId,
        ErrorType errorType,
        String operation,
        Throwable cause,
        Map<String, Object> context,
        Instant timestamp
    ) implements Comparable<ErrorContext> {
        @Override
        public int compareTo(ErrorContext other) {
            return other.timestamp.compareTo(this.timestamp); // Newest first
        }
    }

    public static class QuantifiedException extends RuntimeException {
        private final ErrorType errorType;
        private final String operation;
        private final Map<String, Object> context;
        private final String errorId;

        public QuantifiedException(ErrorType errorType, String operation,
                                 Map<String, Object> context, String errorId) {
            super(errorType.description);
            this.errorType = errorType;
            this.operation = operation;
            this.context = Map.copyOf(context);
            this.errorId = errorId;
        }

        public ErrorType getErrorType() { return errorType; }
        public String getOperation() { return operation; }
        public Map<String, Object> getContext() { return context; }
        public String getErrorId() { return errorId; }

        @Override
        public String toString() {
            return "QuantifiedException{" +
                   "errorId='" + errorId + '\'' +
                   ", errorType=" + errorType +
                   ", operation='" + operation + '\'' +
                   ", context=" + context +
                   '}';
        }
    }
}
