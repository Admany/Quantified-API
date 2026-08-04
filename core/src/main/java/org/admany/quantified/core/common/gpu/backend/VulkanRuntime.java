package org.admany.quantified.core.common.gpu.backend;

import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VulkanRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanRuntime.class);
    private static final AtomicReference<AvailabilitySnapshot> SNAPSHOT = new AtomicReference<>();
    private static final AtomicReference<Boolean> BINDINGS_PRESENT = new AtomicReference<>();
    private static final AtomicReference<Boolean> PROBE_RUNTIME_PRESENT = new AtomicReference<>();
    private static final AtomicReference<Boolean> EMBEDDED_PROBE_BUNDLE_PRESENT = new AtomicReference<>();
    private static final AtomicReference<RuntimeMode> RUNTIME_MODE = new AtomicReference<>();
    private static final AtomicInteger PROBE_SEQUENCE = new AtomicInteger(0);

    private VulkanRuntime() {
    }

    public enum RuntimeMode {
        NONE,
        IN_PROCESS,
        ISOLATED
    }

    public static AvailabilitySnapshot snapshot() {
        AvailabilitySnapshot cached = SNAPSHOT.get();
        if (cached != null) {
            return cached;
        }
    // Never run the probe lazily from an arbitrary thread. vkCreateInstance may use
    // several MB of native stack during NVIDIA DXGI/DX12 initialisation, so it must
    // only run on large-stack threads such as PROBE_EXECUTOR or SCHEDULER (64 MB).
    // Call reprobe() from the probe executor to populate the cached result.

        return new AvailabilitySnapshot(false, false, false, 0, 0, "Vulkan probe not yet run", List.of());
    }

    public static AvailabilitySnapshot cachedSnapshot() {
        return SNAPSHOT.get();
    }

    public static boolean hasBindings() {
        Boolean cached = BINDINGS_PRESENT.get();
        if (cached != null) {
            return cached;
        }
        boolean present = detectBindingsPresent();
        BINDINGS_PRESENT.compareAndSet(null, present);
        return present;
    }

    public static boolean hasProbeRuntime() {
        Boolean cached = PROBE_RUNTIME_PRESENT.get();
        if (cached != null) {
            return cached;
        }
        boolean present = hasBindings() || hasEmbeddedProbeBundle();
        PROBE_RUNTIME_PRESENT.compareAndSet(null, present);
        return present;
    }

    public static RuntimeMode runtimeMode() {
        RuntimeMode cached = RUNTIME_MODE.get();
        if (cached != null) {
            return cached;
        }
        RuntimeMode resolved;
        if (hasBindings()) {
            resolved = RuntimeMode.IN_PROCESS;
        } else if (hasEmbeddedProbeBundle()) {
            resolved = RuntimeMode.ISOLATED;
        } else {
            resolved = RuntimeMode.NONE;
        }
        RUNTIME_MODE.compareAndSet(null, resolved);
        return RUNTIME_MODE.get();
    }

    /**
    * Runs a synchronous Vulkan availability probe and atomically replaces the cached snapshot.
    * Must execute on a large-stack probe thread, such as PROBE_EXECUTOR or SCHEDULER.
     */

    public static AvailabilitySnapshot reprobe() {
        Thread current = Thread.currentThread();
        int probeId = PROBE_SEQUENCE.incrementAndGet();
        LOGGER.info(prefix(probeId) + "Reprobe requested on thread '" + current.getName()
            + "' (group=" + current.getThreadGroup().getName() + "')");
        AvailabilitySnapshot computed = probe(probeId);
        SNAPSHOT.set(computed);
        return computed;
    }

    public static boolean isAvailable() {
        return snapshot().available();
    }

    public static void invalidate() {
        SNAPSHOT.set(null);
    }

    private static AvailabilitySnapshot probe(int probeId) {
        int lwjglStackBytes = LwjglRuntimeTuning.ensureConfigured();
        LOGGER.info(prefix(probeId) + "Probe start on thread '" + Thread.currentThread().getName()
            + "' LWJGL-stack=" + (lwjglStackBytes / (1024 * 1024)) + " MiB");
        logProbeEnvironment(probeId, lwjglStackBytes);
        boolean inProcessBindingsPresent = hasBindings();
        boolean embeddedProbeBundlePresent = hasEmbeddedProbeBundle();
        if (!inProcessBindingsPresent && !embeddedProbeBundlePresent) {
            return reportProbe(loggerSnapshot(false, false, false, 0, 0,
                "LWJGL Vulkan binding not present and embedded Vulkan probe bundle is missing", List.of()),
                probeId, inProcessBindingsPresent, embeddedProbeBundlePresent);
        }

        VulkanSubprocessProbe.Result result = VulkanSubprocessProbe.run(LOGGER, probeId);
        List<ProbeDeviceInfo> devices = result.devices().stream()
            .map(device -> new ProbeDeviceInfo(
                device.normalizedId(),
                device.name(),
                device.vendor(),
                device.deviceType(),
                device.localMemoryBytes(),
                device.softwareAdapter()))
            .toList();
        if (result.ok()) {
            if (!inProcessBindingsPresent) {
                String reason = "Isolated Vulkan probe succeeded; in-process LWJGL Vulkan binding is not present, so execution will use the isolated bundled runtime";
                LOGGER.info(prefix(probeId) + reason);
                return reportProbe(loggerSnapshot(false, true, false,
                    result.maxApiVersion(), result.selectedApiVersion(), reason, devices),
                    probeId, inProcessBindingsPresent, embeddedProbeBundlePresent);
            }
            String conservativeBlockReason = conservativeBlockReason(devices);
            if (conservativeBlockReason != null) {
                LOGGER.warn(prefix(probeId) + conservativeBlockReason);
                return reportProbe(loggerSnapshot(true, true, false,
                    result.maxApiVersion(), result.selectedApiVersion(), conservativeBlockReason, devices),
                    probeId, inProcessBindingsPresent, embeddedProbeBundlePresent);
            }
            LOGGER.info(prefix(probeId) + "Isolated Vulkan probe succeeded with API "
                + formatVersion(result.selectedApiVersion()));
            return reportProbe(loggerSnapshot(true, true, true,
                result.maxApiVersion(), result.selectedApiVersion(), null, devices),
                probeId, inProcessBindingsPresent, embeddedProbeBundlePresent);
        }
        String reason = result.failureReason() != null && !result.failureReason().isBlank()
            ? result.failureReason()
            : "Isolated Vulkan probe failed";
        LOGGER.warn(prefix(probeId) + reason);
        return reportProbe(loggerSnapshot(inProcessBindingsPresent, true, false,
            result.maxApiVersion(), result.selectedApiVersion(), reason, devices),
            probeId, inProcessBindingsPresent, embeddedProbeBundlePresent);
    }

    private static AvailabilitySnapshot loggerSnapshot(boolean bindingPresent,
                                                        boolean shaderCompilerPresent,
                                                        boolean available,
                                                        int maxApiVersion,
                                                        int selectedApiVersion,
                                                        String failureReason,
                                                        List<ProbeDeviceInfo> devices) {
        return new AvailabilitySnapshot(bindingPresent, shaderCompilerPresent, available, maxApiVersion,
            selectedApiVersion, failureReason, devices);
    }

    private static AvailabilitySnapshot reportProbe(AvailabilitySnapshot snapshot,
                                                     int probeId,
                                                     boolean inProcessBindingsPresent,
                                                     boolean embeddedProbeBundlePresent) {
        GpuStartupDiagnostics.reportVulkan(LOGGER, probeId, inProcessBindingsPresent, embeddedProbeBundlePresent,
            runtimeMode(), snapshot.available(), snapshot.failureReason(), snapshot.devices());
        return snapshot;
    }

    private static String conservativeBlockReason(List<ProbeDeviceInfo> devices) {
        if (Boolean.getBoolean("quantified.vulkan.allowUnsafeLegacyNvidia")) {
            return null;
        }
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        ProbeDeviceInfo primary = devices.get(0);
        String vendor = primary.vendor() != null ? primary.vendor().toLowerCase(java.util.Locale.ROOT) : "";
        if (!vendor.contains("nvidia")) {
            return null;
        }
        long localMemoryBytes = primary.localMemoryBytes();
        long conservativeThreshold = 3L * 1024L * 1024L * 1024L;
        if (localMemoryBytes > 0 && localMemoryBytes <= conservativeThreshold) {
            return "Vulkan conservatively disabled on low-VRAM NVIDIA adapters ("
                + primary.name() + ", " + (localMemoryBytes / (1024 * 1024)) + " MiB local VRAM); "
                + "OpenCL is preferred for stability. Set -Dquantified.vulkan.allowUnsafeLegacyNvidia=true to override.";
        }
        return null;
    }

    public static String formatVersion(int apiVersion) {
        if (apiVersion == 0) {
            return "none";
        }
        int major = (apiVersion >>> 22) & 0x7F;
        int minor = (apiVersion >>> 12) & 0x3FF;
        int patch = apiVersion & 0xFFF;
        return major + "." + minor + "." + patch;
    }

    private static void logProbeEnvironment(int probeId, int lwjglStackBytes) {
        LOGGER.info(prefix(probeId) + "Environment: os=" + System.getProperty("os.name")
            + " " + System.getProperty("os.version")
            + ", arch=" + System.getProperty("os.arch")
            + ", java=" + System.getProperty("java.version")
            + ", vendor=" + System.getProperty("java.vendor")
            + ", lwjgl.stack.bytes=" + lwjglStackBytes
            + ", quantified.lwjgl.stackSizeBytes=" + propertyOrUnset("quantified.lwjgl.stackSizeBytes")
            + ", quantified.lwjgl.stackSizeKb=" + propertyOrUnset("quantified.lwjgl.stackSizeKb")
            + ", quantified.gpuThreadStackBytes=" + propertyOrUnset("quantified.gpuThreadStackBytes")
            + ", quantified.gpuThreadStackKb=" + propertyOrUnset("quantified.gpuThreadStackKb")
            + ", quantified.probeThreadStackBytes=" + propertyOrUnset("quantified.probeThreadStackBytes")
            + ", quantified.probeThreadStackKb=" + propertyOrUnset("quantified.probeThreadStackKb")
            + ", org.lwjgl.system.stackSizeKb=" + propertyOrUnset("org.lwjgl.system.stackSize"));
        LOGGER.info(prefix(probeId) + "Loader env: VK_ICD_FILENAMES=" + envOrUnset("VK_ICD_FILENAMES")
            + ", VK_DRIVER_FILES=" + envOrUnset("VK_DRIVER_FILES")
            + ", VK_LAYER_PATH=" + envOrUnset("VK_LAYER_PATH")
            + ", VK_INSTANCE_LAYERS=" + envOrUnset("VK_INSTANCE_LAYERS")
            + ", VK_LOADER_DEBUG=" + envOrUnset("VK_LOADER_DEBUG")
            + ", VK_LOADER_LAYERS_DISABLE=" + envOrUnset("VK_LOADER_LAYERS_DISABLE"));
    }

    private static String propertyOrUnset(String key) {
        String value = System.getProperty(key);
        return value != null && !value.isBlank() ? value : "<unset>";
    }

    private static String envOrUnset(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() ? value : "<unset>";
    }

    private static String prefix(int probeId) {
        return "[Vulkan][Probe " + probeId + "] ";
    }

    private static boolean detectBindingsPresent() {
        try {
            ClassLoader loader = VulkanRuntime.class.getClassLoader();
            Class.forName("org.lwjgl.vulkan.VK10", false, loader);
            Class.forName("org.lwjgl.vulkan.VkDescriptorBufferInfo", false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasEmbeddedProbeBundle() {
        Boolean cached = EMBEDDED_PROBE_BUNDLE_PRESENT.get();
        if (cached != null) {
            return cached;
        }
        ClassLoader loader = VulkanRuntime.class.getClassLoader();
        boolean present = loader.getResource("quantified/embedded/vulkanProbe/classpath.index") != null
            && loader.getResource("quantified/embedded/vulkanProbe/quantified-vulkan-probe.jar.bin") != null;
        EMBEDDED_PROBE_BUNDLE_PRESENT.compareAndSet(null, present);
        return present;
    }

    public static final class AvailabilitySnapshot {
        private final boolean bindingPresent;
        private final boolean shaderCompilerPresent;
        private final boolean available;
        private final int maxApiVersion;
        private final int selectedApiVersion;
        private final String failureReason;
        private final List<ProbeDeviceInfo> devices;

        private AvailabilitySnapshot(boolean bindingPresent,
                                     boolean shaderCompilerPresent,
                                     boolean available,
                                     int maxApiVersion,
                                     int selectedApiVersion,
                                     String failureReason,
                                     List<ProbeDeviceInfo> devices) {
            this.bindingPresent = bindingPresent;
            this.shaderCompilerPresent = shaderCompilerPresent;
            this.available = available;
            this.maxApiVersion = maxApiVersion;
            this.selectedApiVersion = selectedApiVersion;
            this.failureReason = failureReason;
            this.devices = devices != null ? List.copyOf(devices) : List.of();
        }

        public boolean bindingPresent() {
            return bindingPresent;
        }

        public boolean shaderCompilerPresent() {
            return shaderCompilerPresent;
        }

        public boolean available() {
            return available;
        }

        public int maxApiVersion() {
            return maxApiVersion;
        }

        public int selectedApiVersion() {
            return selectedApiVersion;
        }

        public String failureReason() {
            return failureReason;
        }

        public List<ProbeDeviceInfo> devices() {
            return devices;
        }
    }

    public record ProbeDeviceInfo(String id,
                                  String name,
                                  String vendor,
                                  int deviceType,
                                  long localMemoryBytes,
                                  boolean softwareAdapter) {
    }
}
