package org.admany.quantified.core.common.parallel.throttle;

import org.admany.quantified.core.common.parallel.config.ParallelConfig;


public final class ParallelBackpressure {
    private static final Object RESIZE_LOCK = new Object();
    private static volatile ResizableSemaphore PERMITS = new ResizableSemaphore(Math.max(128, ParallelConfig.queueLimit()), false);

    private ParallelBackpressure() {
    }

    private static ResizableSemaphore permits() {
        ResizableSemaphore current = PERMITS;
        int desired = Math.max(128, ParallelConfig.queueLimit());
        if (current.maxPermits() != desired) {
            synchronized (RESIZE_LOCK) {
                current = PERMITS;
                if (current.maxPermits() != desired) {
                    current.resize(desired);
                }
            }
        }
        return current;
    }

    public static void acquire(int slices) throws InterruptedException {
        if (slices <= 0) {
            return;
        }
        permits().acquire(slices);
    }

    public static boolean tryAcquire(int slices) {
        if (slices <= 0) {
            return true;
        }
        return permits().tryAcquire(slices);
    }

    public static void release(int slices) {
        if (slices <= 0) {
            return;
        }
        permits().release(slices);
    }

    public static int queued() {
        ResizableSemaphore semaphore = permits();
        int limit = semaphore.maxPermits();
        int available = semaphore.availablePermits();
        int used = limit - available;
        return Math.max(0, used);
    }

    public static int availablePermits() {
        return Math.max(0, permits().availablePermits());
    }
}
