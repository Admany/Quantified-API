package org.admany.quantified.core.common.gpu.backend;

import org.admany.quantified.core.common.vulkan.core.VulkanIsolatedExecutor;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Safe Vulkan runtime facade for mixed Minecraft/LWJGL environments.
 *
 * <p>Do not reference VulkanManager directly from loader glue or command/status
 * code. VulkanManager imports org.lwjgl.vulkan classes, and older Minecraft
 * versions do not always provide those classes in the parent loader.</p>
 */
public final class VulkanExecutionSupport {

    private static final String VULKAN_MANAGER = "org.admany.quantified.core.common.vulkan.core.VulkanInProcessManager";

    private VulkanExecutionSupport() {
    }

    public static boolean inProcessAvailable() {
        if (!canUseInProcessManager()) {
            return false;
        }
        return invokeBoolean("isAvailable", false);
    }

    public static boolean hasExecutableRuntime() {
        return inProcessAvailable() || VulkanIsolatedExecutor.canExecute();
    }

    public static String deviceName() {
        if (inProcessAvailable()) {
            String device = invokeString("deviceName", "");
            if (device != null && !device.isBlank()) {
                return device;
            }
        }
        VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.snapshot();
        if (!snapshot.devices().isEmpty()) {
            return snapshot.devices().get(0).name();
        }
        return VulkanIsolatedExecutor.canExecute() ? "Isolated bundled Vulkan runtime" : "";
    }

    public static String failureReason() {
        if (canUseInProcessManager()) {
            Object status = invoke("runtimeStatus");
            if (status != null) {
                try {
                    Method method = status.getClass().getMethod("failureReason");
                    Object value = method.invoke(status);
                    if (value instanceof String reason && !reason.isBlank()) {
                        return reason;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.snapshot();
        if (snapshot.failureReason() != null && !snapshot.failureReason().isBlank()) {
            return snapshot.failureReason();
        }
        if (VulkanIsolatedExecutor.canExecute()) {
            return "Native in-process LWJGL Vulkan classes are absent; using isolated bundled Vulkan runtime";
        }
        return "Vulkan runtime unavailable";
    }

    public static boolean isProbeRunning() {
        return canUseInProcessManager() && invokeBoolean("isProbeRunning", false);
    }

    public static boolean isRuntimeWarmupRunning() {
        return canUseInProcessManager() && invokeBoolean("isRuntimeWarmupRunning", false);
    }

    public static List<?> listInProcessDevices() {
        if (!canUseInProcessManager()) {
            return List.of();
        }
        Object value = invoke("listDevices");
        return value instanceof List<?> list ? list : List.of();
    }

    public static void shutdownInProcess() {
        if (canUseInProcessManager()) {
            invoke("shutdown");
        }
    }

    public static void setPreferredInProcessDevice(String deviceId) {
        if (!canUseInProcessManager()) {
            return;
        }
        try {
            Class<?> manager = Class.forName(VULKAN_MANAGER, false, VulkanExecutionSupport.class.getClassLoader());
            manager.getMethod("setPreferredDevice", String.class).invoke(null, deviceId);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    public static void warmupInProcessAsync(String reason) {
        if (!canUseInProcessManager()) {
            return;
        }
        try {
            Class<?> manager = Class.forName(VULKAN_MANAGER, false, VulkanExecutionSupport.class.getClassLoader());
            manager.getMethod("warmupAsync", String.class).invoke(null, reason);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    public static boolean canUseInProcessManager() {
        try {
            return VulkanRuntime.runtimeMode() == VulkanRuntime.RuntimeMode.IN_PROCESS && VulkanRuntime.hasBindings();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean invokeBoolean(String method, boolean fallback) {
        Object value = invoke(method);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String invokeString(String method, String fallback) {
        Object value = invoke(method);
        return value instanceof String string ? string : fallback;
    }

    private static Object invoke(String method) {
        try {
            Class<?> manager = Class.forName(VULKAN_MANAGER, false, VulkanExecutionSupport.class.getClassLoader());
            return manager.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
