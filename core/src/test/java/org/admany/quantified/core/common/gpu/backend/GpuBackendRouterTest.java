package org.admany.quantified.core.common.gpu.backend;

import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.GpuBackendType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GpuBackendRouterTest {

    @Test
    void prefersOpenClWhenVulkanUnavailable() {
        GpuBackendRouter.Selection selection = GpuBackendRouter.selectBackend(
            "lc2h",
            GpuBackendPreference.VULKAN_PREFERRED,
            true,
            true,
            false,
            false
        );

        assertThat(selection.backendType()).isEqualTo(GpuBackendType.OPENCL);
    }

    @Test
    void cpuOnlyAlwaysRoutesToCpu() {
        GpuBackendRouter.Selection selection = GpuBackendRouter.selectBackend(
            "lc2h",
            GpuBackendPreference.CPU_ONLY,
            true,
            true,
            true,
            true
        );

        assertThat(selection.backendType()).isEqualTo(GpuBackendType.CPU);
    }

    @Test
    void requiredOpenClDoesNotRouteToVulkan() {
        GpuBackendRouter.Selection selection = GpuBackendRouter.selectBackend(
            "lc2h",
            GpuBackendPreference.OPENCL_REQUIRED,
            false,
            false,
            true,
            true
        );

        assertThat(selection.backendType()).isEqualTo(GpuBackendType.CPU);
    }
}
