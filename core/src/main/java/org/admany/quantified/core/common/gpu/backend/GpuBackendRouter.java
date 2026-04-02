package org.admany.quantified.core.common.gpu.backend;

import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.GpuBackendType;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class GpuBackendRouter {

    private static final ConcurrentMap<String, GpuBackendPreference> MOD_PREFERENCES = new ConcurrentHashMap<>();

    private GpuBackendRouter() {
    }

    public static GpuBackendPreference getDefaultPreference() {
        String configured = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.preferredGpuBackend : null;
        return parsePreference(configured);
    }

    public static void setModPreference(String modId, GpuBackendPreference preference) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        GpuBackendPreference safePreference = Objects.requireNonNullElse(preference, getDefaultPreference());
        if (safePreference == GpuBackendPreference.AUTO) {
            MOD_PREFERENCES.remove(modId);
            return;
        }
        MOD_PREFERENCES.put(modId, safePreference);
    }

    public static GpuBackendPreference getModPreference(String modId) {
        if (modId == null || modId.isBlank()) {
            return getDefaultPreference();
        }
        return MOD_PREFERENCES.getOrDefault(modId, getDefaultPreference());
    }

    public static GpuBackendPreference resolvePreference(String modId, GpuBackendPreference taskPreference) {
        if (taskPreference != null && taskPreference != GpuBackendPreference.AUTO) {
            return taskPreference;
        }
        return getModPreference(modId);
    }

    public static Selection selectBackend(String modId,
                                          GpuBackendPreference taskPreference,
                                          boolean openclSupported,
                                          boolean vulkanSupported) {
        return selectBackend(
            modId,
            taskPreference,
            openclSupported,
            OpenCLManager.isAvailable(),
            vulkanSupported,
            VulkanRuntime.isAvailable()
        );
    }

    static Selection selectBackend(String modId,
                                   GpuBackendPreference taskPreference,
                                   boolean openclSupported,
                                   boolean openclAvailable,
                                   boolean vulkanSupported,
                                   boolean vulkanAvailable) {
        GpuBackendPreference effective = resolvePreference(modId, taskPreference);
        if (effective.isCpuOnly()) {
            return new Selection(effective, GpuBackendType.CPU);
        }
        if (effective.requiresOpenCL()) {
            return new Selection(effective, openclSupported && openclAvailable ? GpuBackendType.OPENCL : GpuBackendType.CPU);
        }
        if (effective.requiresVulkan()) {
            return new Selection(effective, vulkanSupported && vulkanAvailable ? GpuBackendType.VULKAN : GpuBackendType.CPU);
        }
        if (effective.prefersOpenCL()) {
            if (openclSupported && openclAvailable) {
                return new Selection(effective, GpuBackendType.OPENCL);
            }
            if (vulkanSupported && vulkanAvailable) {
                return new Selection(effective, GpuBackendType.VULKAN);
            }
            return new Selection(effective, GpuBackendType.CPU);
        }
        if (effective.prefersVulkan()) {
            if (vulkanSupported && vulkanAvailable) {
                return new Selection(effective, GpuBackendType.VULKAN);
            }
            if (openclSupported && openclAvailable) {
                return new Selection(effective, GpuBackendType.OPENCL);
            }
            return new Selection(effective, GpuBackendType.CPU);
        }
        if (vulkanSupported && vulkanAvailable) {
            return new Selection(effective, GpuBackendType.VULKAN);
        }
        if (openclSupported && openclAvailable) {
            return new Selection(effective, GpuBackendType.OPENCL);
        }
        return new Selection(effective, GpuBackendType.CPU);
    }

    public record Selection(GpuBackendPreference effectivePreference, GpuBackendType backendType) {
    }

    public static void resetForTesting() {
        MOD_PREFERENCES.clear();
    }

    private static GpuBackendPreference parsePreference(String configured) {
        if (configured == null || configured.isBlank()) {
            return GpuBackendPreference.VULKAN_PREFERRED;
        }
        String normalized = configured.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "AUTO", "VULKAN", "VULKAN_PREFERRED" -> GpuBackendPreference.VULKAN_PREFERRED;
            case "OPENCL", "OPENCL_PREFERRED" -> GpuBackendPreference.OPENCL_PREFERRED;
            case "VULKAN_ONLY", "VULKAN_REQUIRED" -> GpuBackendPreference.VULKAN_REQUIRED;
            case "OPENCL_ONLY", "OPENCL_REQUIRED" -> GpuBackendPreference.OPENCL_REQUIRED;
            case "CPU", "CPU_ONLY" -> GpuBackendPreference.CPU_ONLY;
            default -> {
                try {
                    yield GpuBackendPreference.valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    yield GpuBackendPreference.VULKAN_PREFERRED;
                }
            }
        };
    }
}
