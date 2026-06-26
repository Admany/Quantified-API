package org.admany.quantified.core.common.vulkan.core;

import org.admany.quantified.core.common.gpu.backend.VulkanExecutionSupport;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Binary-compatible Vulkan facade retained for Quantified API V1 consumers.
 *
 * <p>This class deliberately contains no LWJGL Vulkan types. Older Minecraft
 * runtimes can therefore link legacy calls such as {@link #deviceName()}
 * while Quantified API V2 executes public API workloads through its isolated
 * Vulkan runtime.</p>
 */
public final class VulkanManager {

    private VulkanManager() {
    }

    public static boolean isAvailable() {
        return VulkanExecutionSupport.hasExecutableRuntime();
    }

    public static boolean hasExecutableRuntime() {
        return VulkanExecutionSupport.hasExecutableRuntime();
    }

    public static RuntimeStatus runtimeStatus() {
        return isAvailable()
            ? RuntimeStatus.available()
            : RuntimeStatus.failed(VulkanExecutionSupport.failureReason());
    }

    public static void notePending(String reason) {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            VulkanInProcessManager.notePending(reason);
        }
    }

    public static boolean isProbeRunning() {
        return VulkanExecutionSupport.isProbeRunning();
    }

    public static boolean isRuntimeWarmupRunning() {
        return VulkanExecutionSupport.isRuntimeWarmupRunning();
    }

    public static CompletableFuture<Boolean> forceProbe() {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            return VulkanInProcessManager.forceProbe();
        }
        return CompletableFuture.completedFuture(VulkanExecutionSupport.hasExecutableRuntime());
    }

    public static boolean forceProbeSynchronous() {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            return VulkanInProcessManager.forceProbeSynchronous();
        }
        return VulkanExecutionSupport.hasExecutableRuntime();
    }

    public static CompletableFuture<Boolean> warmupAsync() {
        return warmupAsync("legacy-api");
    }

    public static CompletableFuture<Boolean> warmupAsync(String reason) {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            return VulkanInProcessManager.warmupAsync(reason);
        }
        return CompletableFuture.completedFuture(VulkanExecutionSupport.hasExecutableRuntime());
    }

    public static boolean ensureInitialised() {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            return VulkanInProcessManager.ensureInitialised();
        }
        return VulkanExecutionSupport.hasExecutableRuntime();
    }

    public static void setPreferredDevice(String preferredDevice) {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            VulkanInProcessManager.setPreferredDevice(preferredDevice);
        }
    }

    public static void clearPreferredDevice() {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            VulkanInProcessManager.clearPreferredDevice();
        }
    }

    public static List<VulkanDeviceInfo> listDevices() {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            return VulkanInProcessManager.listDevices().stream()
                .map(device -> new VulkanDeviceInfo(device.id(), device.name(), device.vendor(),
                    device.localMemoryBytes(), device.deviceType(), device.softwareAdapter()))
                .toList();
        }
        return VulkanRuntime.snapshot().devices().stream()
            .map(device -> new VulkanDeviceInfo(device.id(), device.name(), device.vendor(),
                device.localMemoryBytes(), device.deviceType(), device.softwareAdapter()))
            .toList();
    }

    public static <T> CompletableFuture<T> executeOnGpu(VulkanTask<T> task) {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            return VulkanInProcessManager.executeOnGpu(task);
        }
        return CompletableFuture.failedFuture(new IllegalStateException(
            "Legacy VulkanTask execution requires the in-process Vulkan runtime; "
                + "use QuantifiedVulkan.Builder for isolated Vulkan execution"
        ));
    }

    public static boolean canAcceptTask(VulkanTask<?> task) {
        return VulkanExecutionSupport.canUseInProcessManager()
            && VulkanInProcessManager.canAcceptTask(task);
    }

    public static String deviceName() {
        return VulkanExecutionSupport.deviceName();
    }

    public static void shutdown() {
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            VulkanInProcessManager.shutdown();
        }
    }

    static VulkanContext sharedContext() {
        if (!VulkanExecutionSupport.canUseInProcessManager()) {
            throw new IllegalStateException("VulkanContext requires the in-process Vulkan runtime");
        }
        return VulkanInProcessManager.sharedContext();
    }

    public static final class RuntimeStatus {
        private final boolean available;
        private final String failureReason;

        private RuntimeStatus(boolean available, String failureReason) {
            this.available = available;
            this.failureReason = failureReason;
        }

        public boolean isAvailable() {
            return available;
        }

        public String failureReason() {
            return failureReason;
        }

        public static RuntimeStatus available() {
            return new RuntimeStatus(true, null);
        }

        public static RuntimeStatus failed(String reason) {
            return new RuntimeStatus(false, reason);
        }
    }

    public record VulkanDeviceInfo(String id, String name, String vendor, long localMemoryBytes,
                                   int deviceType, boolean softwareAdapter) {
    }
}
