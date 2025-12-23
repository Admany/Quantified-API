package org.admany.quantified.core.common.async.validation;

import org.admany.quantified.core.common.async.metrics.AsyncMetrics;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskComputation;

import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TaskSubmissionValidator {

    private TaskSubmissionValidator() {
    }

    public static <T> ValidatedSubmission<T> validate(long taskKey,
                                                      PriorityTaskType type,
                                                      double score,
                                                      TaskComputation<T> computation,
                                                      AsyncMetrics metrics,
                                                      Logger logger) {
        return validate(taskKey, type, score, computation, null, metrics, logger);
    }

    public static <T> ValidatedSubmission<T> validate(long taskKey,
                                                      PriorityTaskType type,
                                                      double score,
                                                      TaskComputation<T> computation,
                                                      Duration timeout,
                                                      AsyncMetrics metrics,
                                                      Logger logger) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(logger, "logger");
        TaskComputation<T> resolvedComputation = Objects.requireNonNull(computation, "computation");

        PriorityTaskType resolvedType = type;
        if (resolvedType == null) {
            metrics.recordTypeDefaulted();
            resolvedType = PriorityTaskType.OTHER;
            logger.log(Level.WARNING, "Task {0} defaulted priority type to {1} due to null input", new Object[]{taskKey, resolvedType});
        }

        double resolvedScore = resolvedType.sanitiseScore(score);
        if (Double.compare(resolvedScore, score) != 0) {
            metrics.recordScoreSanitised();
            logger.log(Level.FINER, "Task {0} score sanitised from {1} to {2}", new Object[]{taskKey, score, resolvedScore});
        }

        TaskSafetyRegistry.Resolution resolution = TaskSafetyRegistry.resolve(resolvedComputation);
        if (resolution.overrideUsed()) {
            metrics.recordSafetyOverride();
        }

        ensureThreadSafety(taskKey, resolvedType, resolvedComputation, resolution.threadSafe(), metrics, logger);

        Duration resolvedTimeout = validateTimeout(taskKey, timeout, logger);

        return new ValidatedSubmission<>(taskKey, resolvedType, resolvedScore, resolvedComputation, resolution.threadSafe(), resolvedTimeout);
    }

    public static <T> ValidatedSubmission<T> validate(long taskKey,
                                                      PriorityTaskType type,
                                                      double score,
                                                      TaskComputation<T> computation,
                                                      Duration timeout,
                                                      boolean threadSafeOverride,
                                                      AsyncMetrics metrics,
                                                      Logger logger) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(logger, "logger");
        TaskComputation<T> resolvedComputation = Objects.requireNonNull(computation, "computation");

        PriorityTaskType resolvedType = type;
        if (resolvedType == null) {
            metrics.recordTypeDefaulted();
            resolvedType = PriorityTaskType.OTHER;
            logger.log(Level.WARNING, "Task {0} defaulted priority type to {1} due to null input", new Object[]{taskKey, resolvedType});
        }

        double resolvedScore = resolvedType.sanitiseScore(score);
        if (Double.compare(resolvedScore, score) != 0) {
            metrics.recordScoreSanitised();
            logger.log(Level.FINER, "Task {0} score sanitised from {1} to {2}", new Object[]{taskKey, score, resolvedScore});
        }

        TaskSafetyRegistry.Resolution resolution = TaskSafetyRegistry.resolve(resolvedComputation);
        if (resolution.overrideUsed()) {
            metrics.recordSafetyOverride();
        }

        if (threadSafeOverride != resolution.threadSafe()) {
            metrics.recordSafetyOverride();
        }

        ensureThreadSafety(taskKey, resolvedType, resolvedComputation, threadSafeOverride, metrics, logger);

        Duration resolvedTimeout = validateTimeout(taskKey, timeout, logger);

        return new ValidatedSubmission<>(taskKey, resolvedType, resolvedScore, resolvedComputation, threadSafeOverride, resolvedTimeout);
    }

    private static <T> void ensureThreadSafety(long taskKey,
                                               PriorityTaskType type,
                                               TaskComputation<T> computation,
                                               boolean threadSafe,
                                               AsyncMetrics metrics,
                                               Logger logger) {
        if (type.requiresThreadSafe() && !threadSafe) {
            metrics.recordThreadSafetyViolation();
            String message = "Task " + computation.description() + " declared non-thread-safe but type " + type + " requires thread safety";
            logger.log(Level.SEVERE, message + " (key=" + taskKey + ")");
        }
    }

    private static Duration validateTimeout(long taskKey,
                                            Duration timeout,
                                            Logger logger) {
        if (timeout == null) {
            return null;
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout for task " + taskKey + " must be positive");
        }
        Duration capped = timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
        if (!capped.equals(timeout)) {
            logger.log(Level.FINE, "Task {0} timeout capped to {1} (requested {2})", new Object[]{taskKey, capped, timeout});
        }
        return capped;
    }

    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    public record ValidatedSubmission<T>(long taskKey,
                                         PriorityTaskType type,
                                         double score,
                                         TaskComputation<T> computation,
                                         boolean threadSafe,
                                         Duration timeout) {
    }
}
