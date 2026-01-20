package org.admany.quantified.core.common.parallel.throttle;

import java.util.concurrent.Semaphore;

/**
 * Semaphore variant that can adjust its total permits at runtime.
 *
 * <p>This is used so dashboard-driven config changes (queue limits / per-mod limits)
 * take effect without requiring a restart. When shrinking below currently acquired
 * permits, the semaphore may temporarily go negative, naturally throttling new acquires
 * until enough releases occur.</p>
 */
final class ResizableSemaphore extends Semaphore {

    private static final long serialVersionUID = 1L;

    private volatile int maxPermits;

    ResizableSemaphore(int permits, boolean fair) {
        super(permits, fair);
        this.maxPermits = permits;
    }

    int maxPermits() {
        return maxPermits;
    }

    void resize(int newMaxPermits) {
        if (newMaxPermits <= 0) {
            newMaxPermits = 1;
        }
        if (newMaxPermits == maxPermits) {
            return;
        }
        synchronized (this) {
            int currentMax = maxPermits;
            if (newMaxPermits == currentMax) {
                return;
            }
            int delta = newMaxPermits - currentMax;
            if (delta > 0) {
                release(delta);
            } else if (delta < 0) {
                super.reducePermits(-delta);
            }
            maxPermits = newMaxPermits;
        }
    }
}
