package org.admany.quantified.core.common.parallel.throttle;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.admany.quantified.core.common.parallel.config.ParallelConfig;

public final class ParallelModTracker {
    private static final ConcurrentHashMap<String, Semaphore> SEMAPHORES = new ConcurrentHashMap<>();

    private ParallelModTracker() {
    }

    public static Semaphore semaphore(String modId) {
        int permits = ParallelConfig.maxSlicesPerMod();
        return SEMAPHORES.computeIfAbsent(modId, ignored -> new Semaphore(permits, false));
    }
}
