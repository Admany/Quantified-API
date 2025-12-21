package org.admany.quantified.core.common.threading.pool;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.admany.quantified.core.common.threading.core.ThreadRole;

@FunctionalInterface
public interface ThreadPoolErrorHandler {

    void handle(ThreadRole role, Throwable throwable);

    static ThreadPoolErrorHandler logging(Logger logger) {
        Objects.requireNonNull(logger, "logger");
        return (role, throwable) -> logger.log(Level.SEVERE,
            "Thread pool exception in role " + role,
            throwable);
    }

    default ThreadPoolErrorHandler andThen(ThreadPoolErrorHandler other) {
        Objects.requireNonNull(other, "other");
        return (role, throwable) -> {
            handle(role, throwable);
            other.handle(role, throwable);
        };
    }

    static ThreadPoolErrorHandler composite(ThreadPoolErrorHandler... handlers) {
        Objects.requireNonNull(handlers, "handlers");
        return (role, throwable) -> {
            for (ThreadPoolErrorHandler handler : handlers) {
                if (handler != null) {
                    handler.handle(role, throwable);
                }
            }
        };
    }

    static ThreadPoolErrorHandler failing() {
        return (role, throwable) -> {
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Thread pool failure in role " + role, throwable);
        };
    }
}
