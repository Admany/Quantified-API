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

    public String displayLabel() {
        return switch (this) {
            case AUTO, VULKAN_PREFERRED -> "Auto (Vulkan first)";
            case OPENCL_PREFERRED -> "OpenCL preferred";
            case VULKAN_REQUIRED -> "Vulkan only";
            case OPENCL_REQUIRED -> "OpenCL only";
            case CPU_ONLY -> "CPU only";
        };
    }
}
