package org.admany.quantified.core.common.parallel.throttle;

import org.admany.quantified.core.common.parallel.config.ParallelConfig;

import java.util.concurrent.Semaphore;

public final class ParallelBackpressure {
    private static final Semaphore PERMITS = new Semaphore(Math.max(128, ParallelConfig.queueLimit()), false);

    private ParallelBackpressure() {
    }

    public static void acquire(int slices) throws InterruptedException {
        if (slices <= 0) {
            return;
        }
        PERMITS.acquire(slices);
    }

    public static boolean tryAcquire(int slices) {
        if (slices <= 0) {
            return true;
        }
        return PERMITS.tryAcquire(slices);
    }

    public static void release(int slices) {
        if (slices <= 0) {
            return;
        }
        PERMITS.release(slices);
    }

    public static int queued() {
        int limit = Math.max(128, ParallelConfig.queueLimit());
        int available = PERMITS.availablePermits();
        int used = limit - available;
        return Math.max(0, used);
    }
}
