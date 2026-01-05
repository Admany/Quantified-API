package org.admany.quantified.core.common.async.core;

import java.time.Duration;
import java.util.Objects;

import org.admany.quantified.core.common.threading.pool.ThreadPoolErrorHandler;

public record AsyncManagerBootstrap(int foregroundThreads,
                                    int backgroundThreads,
                                    int maxForegroundThreads,
                                    int maxBackgroundThreads,
                                    int queueBound,
                                    Duration promotionDelay,
                                    ThreadPoolErrorHandler errorHandler) {

    public AsyncManagerBootstrap {
        Objects.requireNonNull(errorHandler, "errorHandler");
    }

    public static AsyncManagerBootstrap defaults(int availableProcessors) {
        int cores = Math.max(4, availableProcessors);

        int minThreads = Math.max(2, cores / 2); 
        int maxThreads = Math.max(4, (int)(cores * 0.9)); 
        int fgMin = Math.max(1, minThreads * 3 / 4);
        int bgMin = Math.max(1, minThreads / 4);
        int fgMax = Math.max(2, maxThreads * 3 / 4);
        int bgMax = Math.max(2, maxThreads / 4);

        return new AsyncManagerBootstrap(fgMin, bgMin, fgMax, bgMax, 15000, Duration.ofMillis(250),
            ThreadPoolErrorHandler.logging(java.util.logging.Logger.getLogger(PriorityScheduler.class.getName())));
    }

    public AsyncManagerBootstrap withErrorHandler(ThreadPoolErrorHandler handler) {
        return new AsyncManagerBootstrap(foregroundThreads, backgroundThreads, maxForegroundThreads, maxBackgroundThreads, queueBound, promotionDelay, handler);
    }
}
