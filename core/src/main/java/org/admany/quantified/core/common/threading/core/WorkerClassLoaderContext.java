package org.admany.quantified.core.common.threading.core;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class WorkerClassLoaderContext {

    private static final Logger LOGGER = Logger.getLogger(WorkerClassLoaderContext.class.getName());
    private static final AtomicReference<ClassLoader> CAPTURED = new AtomicReference<>();

    private WorkerClassLoaderContext() {}

    public static void capture() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (CAPTURED.compareAndSet(null, cl)) {
            LOGGER.fine(() -> "[Quantified API] WorkerClassLoaderContext captured loader: " + cl);
        }
    }

    public static ClassLoader get() {
        return CAPTURED.get();
    }

    public static ThreadFactory wrap(ThreadFactory delegate) {
        return r -> {
            Thread t = delegate.newThread(r);
            ClassLoader cl = CAPTURED.get();
            if (cl != null) {
                t.setContextClassLoader(cl);
            }
            return t;
        };
    }

    public static ForkJoinPool.ForkJoinWorkerThreadFactory wrapForkJoin(
            ForkJoinPool.ForkJoinWorkerThreadFactory delegate) {
        return pool -> {
            ForkJoinWorkerThread t = delegate.newThread(pool);
            ClassLoader cl = CAPTURED.get();
            if (cl != null) {
                t.setContextClassLoader(cl);
            }
            return t;
        };
    }
}
