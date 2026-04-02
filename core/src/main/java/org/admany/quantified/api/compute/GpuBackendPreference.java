package org.admany.quantified.api.compute;

public enum GpuBackendPreference {
    AUTO,
    VULKAN_PREFERRED,
    OPENCL_PREFERRED,
    VULKAN_REQUIRED,
    OPENCL_REQUIRED,
    CPU_ONLY;

    public boolean prefersVulkan() {
        return this == VULKAN_PREFERRED || this == VULKAN_REQUIRED;
    }

    public boolean prefersOpenCL() {
        return this == OPENCL_PREFERRED || this == OPENCL_REQUIRED;
    }

    public boolean requiresVulkan() {
        return this == VULKAN_REQUIRED;
    }

    public boolean requiresOpenCL() {
        return this == OPENCL_REQUIRED;
    }

    public boolean isCpuOnly() {
        return this == CPU_ONLY;
    }
}
