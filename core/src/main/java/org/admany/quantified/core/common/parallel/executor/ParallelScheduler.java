package org.admany.quantified.core.common.parallel.executor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.admany.quantified.core.common.parallel.config.ParallelConfig;
import org.admany.quantified.core.common.threading.core.WorkerClassLoaderContext;

public final class ParallelScheduler {
    private static final Logger LOGGER = Logger.getLogger(ParallelScheduler.class.getName());
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static volatile ForkJoinPool SHARED;
    private static final AtomicBoolean PARALLELISM_WARNED = new AtomicBoolean(false);

    private ParallelScheduler() {
    }

    public static ExecutorService executor() {
        ForkJoinPool current = SHARED;
        int desiredParallelism = ParallelConfig.maxThreads();
        if (current == null) {
            synchronized (ParallelScheduler.class) {
                current = SHARED;
                if (current == null) {
                    SHARED = new ForkJoinPool(
                        desiredParallelism,
                        WorkerClassLoaderContext.wrapForkJoin(pool -> {
                            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                            worker.setName("quantified-parallel-" + COUNTER.incrementAndGet());
                            worker.setDaemon(true);
                            worker.setPriority(Thread.NORM_PRIORITY);
                            return worker;
                        }),
                        null,
                        true);
                    current = SHARED;
                }
            }
        } else if (current.getParallelism() != desiredParallelism && PARALLELISM_WARNED.compareAndSet(false, true)) {
            LOGGER.warning("Parallel scheduler already initialized; ignoring runtime parallelism change from "
                + current.getParallelism() + " to " + desiredParallelism);
        }
        return current;
    }
}
