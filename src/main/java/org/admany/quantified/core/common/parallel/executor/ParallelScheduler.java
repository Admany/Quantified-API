package org.admany.quantified.core.common.parallel.executor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

import org.admany.quantified.core.common.parallel.config.ParallelConfig;

public final class ParallelScheduler {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static volatile ForkJoinPool SHARED;

    private ParallelScheduler() {
    }

    public static ExecutorService executor() {
        ForkJoinPool current = SHARED;
        int desiredParallelism = ParallelConfig.maxThreads();
        if (current == null || current.getParallelism() != desiredParallelism) {
            synchronized (ParallelScheduler.class) {
                current = SHARED;
                if (current == null || current.getParallelism() != desiredParallelism) {
                    if (current != null) {
                        current.shutdownNow();
                    }
                    SHARED = new ForkJoinPool(
                        desiredParallelism,
                        pool -> {
                            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                            worker.setName("quantified-parallel-" + COUNTER.incrementAndGet());
                            worker.setDaemon(true);
                            worker.setPriority(Thread.NORM_PRIORITY);
                            return worker;
                        },
                        null,
                        true);
                    current = SHARED;
                }
            }
        }
        return current;
    }
}
