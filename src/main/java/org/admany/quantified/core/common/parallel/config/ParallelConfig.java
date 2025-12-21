package org.admany.quantified.core.common.parallel.config;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.parallel.policy.ParallelFailurePolicy;

public final class ParallelConfig {
    private ParallelConfig() {
    }

    private static MultithreadingConfig.Config cfg() {
        MultithreadingConfig.Config config = MultithreadingConfig.CONFIG;
        if (config == null) {
            config = new MultithreadingConfig.Config();
            MultithreadingConfig.CONFIG = config;
        }
        return config;
    }

    public static int maxThreads() {
        return Math.max(2, cfg().parallelMaxThreads);
    }

    public static int queueLimit() {
        return Math.max(128, cfg().parallelQueueLimit);
    }

    public static int maxSlicesPerMod() {
        return Math.max(1, cfg().parallelMaxSlicesPerMod);
    }

    public static ParallelFailurePolicy defaultFailurePolicy() {
        String policy = cfg().parallelFailurePolicy;
        if (policy == null || policy.isBlank()) {
            return ParallelFailurePolicy.FAIL_FAST;
        }
        try {
            return ParallelFailurePolicy.valueOf(policy.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ParallelFailurePolicy.FAIL_FAST;
        }
    }
}
