package org.admany.quantified.core.common.opencl;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class OpenCLManagerTest {

    @BeforeEach
    public void setUp() {
        // Initialize config if not already done
        if (MultithreadingConfig.CONFIG == null) {
            MultithreadingConfig.initializeGlobals(null);
        }
    }

    @Test
    public void forceProbeReturnsFutureAndCompletes() throws Exception {
        // External probes are always enabled now

        CompletableFuture<Boolean> future = OpenCLManager.forceProbe();
        Assertions.assertNotNull(future, "forceProbe should return a CompletableFuture");

        Boolean result = future.get(15, TimeUnit.SECONDS);
        // Result may be true or false depending on environment; just ensure it completes without throwing
        Assertions.assertNotNull(result);
    }
}
