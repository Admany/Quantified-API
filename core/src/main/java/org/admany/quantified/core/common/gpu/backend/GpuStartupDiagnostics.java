package org.admany.quantified.core.common.gpu.backend;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Emits a compact, once-per-state explanation of GPU backend startup. This is
 * deliberately independent from the dashboard so dedicated servers receive the
 * same evidence as an integrated client without loading client-only classes.
 */
public final class GpuStartupDiagnostics {

    private static final Set<String> EMITTED = ConcurrentHashMap.newKeySet();

    private GpuStartupDiagnostics() {
    }

    public static void reportVulkan(Logger logger,
                                    int probeId,
                                    boolean inProcessBindings,
                                    boolean embeddedBundle,
                                    VulkanRuntime.RuntimeMode runtimeMode,
                                    boolean inProcessAvailable,
                                    String reason,
                                    Collection<VulkanRuntime.ProbeDeviceInfo> devices) {
        String detail = clean(reason);
        boolean executionCandidate = inProcessAvailable
            || (runtimeMode == VulkanRuntime.RuntimeMode.ISOLATED && devices != null && !devices.isEmpty());
        String key = "vulkan|" + inProcessBindings + '|' + embeddedBundle + '|' + runtimeMode + '|'
            + inProcessAvailable + '|' + executionCandidate + '|' + detail + '|' + deviceSummary(devices);
        if (!EMITTED.add(key)) {
            return;
        }
        logger.info("[Quantified][GPU Startup][Vulkan] probe=" + probeId
            + " inProcessAvailable=" + inProcessAvailable
            + " executionCandidate=" + executionCandidate
            + " mode=" + runtimeMode
            + " inProcessBindings=" + inProcessBindings
            + " embeddedProbeBundle=" + embeddedBundle
            + " devices=" + deviceSummary(devices)
            + " reason=" + detail
            + linuxContext());
    }

    public static void reportOpenCl(java.util.logging.Logger logger,
                                    String phase,
                                    boolean lwjglBinding,
                                    boolean embeddedBundle,
                                    boolean available,
                                    String reason,
                                    Collection<?> devices) {
        String detail = clean(reason);
        String key = "opencl|" + phase + '|' + lwjglBinding + '|' + embeddedBundle + '|'
            + available + '|' + detail + '|' + (devices == null ? 0 : devices.size());
        if (!EMITTED.add(key)) {
            return;
        }
        logger.info("[Quantified][GPU Startup][OpenCL] phase=" + phase
            + " available=" + available
            + " lwjglBinding=" + lwjglBinding
            + " embeddedProbeBundle=" + embeddedBundle
            + " deviceCount=" + (devices == null ? 0 : devices.size())
            + " reason=" + detail
            + linuxContext());
    }

    private static String deviceSummary(Collection<VulkanRuntime.ProbeDeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (VulkanRuntime.ProbeDeviceInfo device : devices) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(clean(device.name())).append("/")
                .append(clean(device.vendor())).append("/")
                .append(device.softwareAdapter() ? "software" : "hardware");
        }
        return builder.toString();
    }

    private static String linuxContext() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return "";
        }
        return " linux{libvulkan=" + libraryAvailable("libvulkan.so.1")
            + ",libopencl=" + libraryAvailable("libOpenCL.so.1")
            + ",dri=" + readable("/dev/dri")
            + ",nvidia=" + (readable("/dev/nvidia0") || readable("/dev/nvidiactl"))
            + ",vkIcdOverride=" + configured("VK_ICD_FILENAMES")
            + ",vkDriverOverride=" + configured("VK_DRIVER_FILES")
            + ",openclVendorDir=" + readable("/etc/OpenCL/vendors")
            + "}";
    }

    private static boolean readable(String value) {
        try {
            return Files.isReadable(Path.of(value));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean libraryAvailable(String libraryName) {
        String[] locations = {
            "/usr/lib/x86_64-linux-gnu/" + libraryName,
            "/usr/lib64/" + libraryName,
            "/usr/lib/" + libraryName,
            "/lib64/" + libraryName,
            "/lib/" + libraryName
        };
        for (String location : locations) {
            if (readable(location)) {
                return true;
            }
        }
        String libraryPath = System.getProperty("java.library.path", "");
        for (String entry : libraryPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank() && readable(Path.of(entry, libraryName).toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean configured(String variable) {
        String value = System.getenv(variable);
        return value != null && !value.isBlank();
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
