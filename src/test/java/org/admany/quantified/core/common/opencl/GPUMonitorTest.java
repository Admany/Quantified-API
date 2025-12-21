package org.admany.quantified.core.common.opencl;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GPUMonitorTest {

    @BeforeEach
    public void setUp() {
        // Initialize config if not already done
        if (MultithreadingConfig.CONFIG == null) {
            MultithreadingConfig.initializeGlobals(null);
        }
    }

    @Test
    public void testGetGPUNameFromSystemWhenProbesDisabled() {
        // External probes are always enabled now
        String name = org.admany.quantified.core.common.opencl.gpu.probe.HardwareProbeService.getGPUNameFromSystem();
        // May return actual GPU name or "Unknown GPU" depending on system
        Assertions.assertNotNull(name);
    }
}
