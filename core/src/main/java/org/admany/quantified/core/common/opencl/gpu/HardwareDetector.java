package org.admany.quantified.core.common.opencl.gpu;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.opencl.core.OpenCLRuntime;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

public final class HardwareDetector {

    private static final Logger LOGGER = Logger.getLogger(HardwareDetector.class.getName());
    private static final SystemInfo SYSTEM_INFO = new SystemInfo();
    private static final long MIN_DEDICATED_VRAM_BYTES = 2L * 1024L * 1024L * 1024L;

    private HardwareDetector() {}

    public static HardwareStatus detect() {
        return detectInternal(false);
    }

    public static HardwareStatus detailedDetect() {
        return detectInternal(true);
    }

    private static HardwareStatus detectInternal(boolean probeContext) {
        HardwareAbstractionLayer hal = SYSTEM_INFO.getHardware();
        OperatingSystem os = SYSTEM_INFO.getOperatingSystem();

        DetectionResults results = new DetectionResults();
        HardwareCapabilities caps = populateCapabilities(hal);
        results.hardwareCapabilities = caps;

        results.configEnabled = MultithreadingConfig.CONFIG.enableGpuAcceleration;
        if (!results.configEnabled) {
            results.failureReason = "GPU acceleration disabled in configuration";
            return buildStatus(results, os);
        }

        results.lwjglAvailable = checkLwjglBinding();
        OpenCLRuntime.AvailabilitySnapshot runtimeSnapshot = OpenCLRuntime.snapshot();
        results.openclRuntimeAvailable = runtimeSnapshot.available();
        if (!runtimeSnapshot.available()) {
            results.failureReason = runtimeSnapshot.failureReason();
        }
        OpenCLRuntime.ProbeSnapshot probeSnapshot = OpenCLRuntime.probeSnapshot();
        results.probeRuntimeAvailable = OpenCLRuntime.hasProbeRuntime();
        results.isolatedProbeDetected = !probeSnapshot.devices().isEmpty();

        Optional<GraphicsCard> candidate = selectDiscreteCard(hal.getGraphicsCards());
        if (candidate.isPresent()) {
            GraphicsCard card = candidate.get();
            caps.detectedGpuName = safeTrim(card.getName());
            caps.detectedGpuVendor = safeTrim(card.getVendor());
            caps.detectedVramBytes = Math.max(card.getVRam(), 0L);
        }

        results.discreteGpuDetected = candidate.isPresent();
        results.meetsVramRequirement = candidate.map(card -> card.getVRam() >= MIN_DEDICATED_VRAM_BYTES).orElse(false);
        if (!results.discreteGpuDetected) {
            results.failureReason = "No discrete GPU detected";
        } else if (!results.meetsVramRequirement) {
            results.failureReason = "Discrete GPU reports less than 2 GB of dedicated VRAM";
        }

        GPUDetector.GPUCapabilities gpuCaps = GPUDetector.GPUCapabilities.UNSUPPORTED;
        String preferredDeviceId = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.openclDeviceId : null;
        if (results.lwjglAvailable && results.openclRuntimeAvailable) {
            try {
                gpuCaps = GPUDetector.detectCapabilities(preferredDeviceId);
                results.openclDeviceDetected = gpuCaps.supported();
                results.opencl32Capable = gpuCaps.supported() && gpuCaps.supportsOpenCL32();
                if (!results.openclDeviceDetected) {
                    results.failureReason = gpuCaps.failureReason() != null
                        ? gpuCaps.failureReason()
                        : "No OpenCL-capable device found";
                }
                if (probeContext) {
                    results.contextCreationSuccessful = gpuCaps.supported();
                }
                if (gpuCaps.supported()) {
                    caps.detectedGpuName = gpuCaps.device().name();
                    caps.detectedGpuVendor = gpuCaps.device().vendor();
                    caps.detectedVramBytes = gpuCaps.device().vramBytes();
                } else if (gpuCaps.failureReason() != null) {
                    results.failureReason = gpuCaps.failureReason();
                }
            } catch (Throwable t) {
                LOGGER.warning("OpenCL capability detection failed: " + t.getMessage());
                results.failureReason = "OpenCL device enumeration failed";
            }
        } else if (!probeSnapshot.devices().isEmpty()) {
            OpenCLRuntime.ProbeDeviceInfo device = probeSnapshot.devices().get(0);
            results.openclDeviceDetected = true;
            results.opencl32Capable = device.supportsOpenCL32();
            caps.detectedGpuName = device.name();
            caps.detectedGpuVendor = device.vendor();
            caps.detectedVramBytes = device.vramBytes();
            if (results.failureReason == null || results.failureReason.isBlank()) {
                results.failureReason = "OpenCL hardware detected via isolated probe, but in-process runtime execution is unavailable";
            }
        }

        return buildStatus(results, os);
    }

    private static HardwareCapabilities populateCapabilities(HardwareAbstractionLayer hal) {
        HardwareCapabilities caps = new HardwareCapabilities();
        CentralProcessor processor = hal.getProcessor();
        caps.cpuCores = processor.getLogicalProcessorCount();
        caps.cpuModel = safeTrim(processor.getProcessorIdentifier().getName());
        caps.memoryGB = hal.getMemory().getTotal() / (1024.0 * 1024.0 * 1024.0);

        String modelLower = caps.cpuModel.toLowerCase(Locale.ROOT);
        caps.hasSSE4_2 = inferSSE42(modelLower);
        caps.hasAVX = inferAVX(modelLower);
        caps.likelyOpenCL12Capable = caps.cpuCores >= 2;
        caps.likelyOpenCL30Capable = caps.hasAVX && caps.memoryGB >= 4.0;
        return caps;
    }

    private static String safeTrim(String value) {
        return value == null ? "Unknown" : value.trim();
    }

    private static boolean checkLwjglBinding() {
        return OpenCLRuntime.hasBindings();
    }

    private static Optional<GraphicsCard> selectDiscreteCard(List<GraphicsCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return Optional.empty();
        }
        return cards.stream()
            .filter(HardwareDetector::isLikelyDiscrete)
            .max(Comparator.comparingLong(GraphicsCard::getVRam));
    }

    private static boolean isLikelyDiscrete(GraphicsCard card) {
        if (card == null) {
            return false;
        }
        String vendor = safeTrim(card.getVendor()).toLowerCase(Locale.ROOT);
        String name = safeTrim(card.getName()).toLowerCase(Locale.ROOT);
        long vram = Math.max(card.getVRam(), 0L);

        if (vendor.contains("nvidia") || vendor.contains("advanced micro devices") || vendor.contains("amd")) {
            return vram >= MIN_DEDICATED_VRAM_BYTES;
        }
        if (vendor.contains("intel")) {
            return vram >= MIN_DEDICATED_VRAM_BYTES && !name.contains("iris") && !name.contains("uhd");
        }
        return vram >= MIN_DEDICATED_VRAM_BYTES && !name.contains("integrated");
    }

    private static boolean inferSSE42(String lowerModel) {
        if (lowerModel.contains("ryzen") || lowerModel.contains("epyc") || lowerModel.contains("fx") || lowerModel.contains("athlon")) {
            return true;
        }
        if (lowerModel.contains("core")) {
            return !(lowerModel.contains("duo") || lowerModel.contains("solo"));
        }
        return !(lowerModel.contains("pentium") || lowerModel.contains("celeron") || lowerModel.contains("atom"));
    }

    private static boolean inferAVX(String lowerModel) {
        if (lowerModel.contains("ryzen") || lowerModel.contains("epyc") || lowerModel.contains("threadripper")) {
            return true;
        }
        if (lowerModel.contains("core i")) {
            return !(lowerModel.contains("duo") || lowerModel.contains("solo"));
        }
        return lowerModel.contains("xeon");
    }

    private static HardwareStatus buildStatus(DetectionResults results, OperatingSystem os) {
        double openclConfidence = 0.0;
        double gpuConfidence = 0.0;

        if (results.configEnabled) {
            if (results.lwjglAvailable) {
                openclConfidence += 0.25;
            }
            if (results.openclRuntimeAvailable) {
                openclConfidence += 0.25;
            }
            if (results.discreteGpuDetected) {
                openclConfidence += 0.15;
                gpuConfidence += 0.5;
            }
            if (results.meetsVramRequirement) {
                openclConfidence += 0.10;
                gpuConfidence += 0.25;
            }
            if (results.opencl32Capable) {
                openclConfidence += 0.25;
                gpuConfidence += 0.15;
            }
            if (results.openclDeviceDetected) {
                openclConfidence = Math.max(openclConfidence, 0.85);
                gpuConfidence = Math.max(gpuConfidence, 0.85);
            }
            if (results.contextCreationSuccessful) {
                openclConfidence = 1.0;
            }
        }

        openclConfidence = Math.min(1.0, openclConfidence);
        gpuConfidence = Math.min(1.0, gpuConfidence);

        boolean openclAvailable = openclConfidence >= 0.65;
        boolean gpuAvailable = gpuConfidence >= 0.65;

        String guidance = generateGuidance(results, openclConfidence, gpuConfidence, os);
        HardwareStatus status = new HardwareStatus(
            openclAvailable,
            gpuAvailable,
            openclConfidence,
            gpuConfidence,
            results,
            os.getFamily(),
            os.getVersionInfo().getVersion(),
            os.getBitness() == 64,
            SYSTEM_INFO.getHardware().getProcessor().getLogicalProcessorCount(),
            SYSTEM_INFO.getHardware().getMemory().getTotal(),
            SYSTEM_INFO.getHardware().getMemory().getTotal(),
            guidance,
            results.failureReason
        );
        return status;
    }

    private static String generateGuidance(DetectionResults results, double openclConfidence, double gpuConfidence, OperatingSystem os) {
        StringBuilder builder = new StringBuilder();
        builder.append("GPU Acceleration Assessment\n");
        builder.append("===========================\n");
        builder.append(String.format(Locale.ROOT, "OpenCL Confidence: %.0f%%\n", openclConfidence * 100.0));
        builder.append(String.format(Locale.ROOT, "GPU Confidence: %.0f%%\n", gpuConfidence * 100.0));

        if (!results.configEnabled) {
            builder.append("Enable gpuAcceleration in QuantifiedAPI config to allow detection.\n");
        }
        if (!results.lwjglAvailable) {
            if (results.isolatedProbeDetected) {
                builder.append("OpenCL hardware was detected by the isolated probe, but the runtime is missing in-process org.lwjgl.opencl bindings.\n");
            } else {
                builder.append("LWJGL OpenCL binding is missing; ensure the runtime ships with org.lwjgl.opencl.\n");
            }
        }
        if (!results.openclRuntimeAvailable) {
            builder.append("OpenCL runtime failed to initialise. Install vendor OpenCL drivers or clruntime packages.\n");
        }
        if (!results.discreteGpuDetected) {
            builder.append("No discrete GPU reported by OSHI. Verify PCIe GPU visibility and disable forced iGPU modes.\n");
        } else if (!results.meetsVramRequirement) {
            builder.append("Discrete GPU reported less than 2 GB VRAM. Reduce workload scale or upgrade hardware.\n");
        }
        if (results.discreteGpuDetected && !results.opencl32Capable) {
            builder.append("A GPU was found but did not advertise OpenCL 3.2. Update vendor drivers or enable 3.x support.\n");
        }

        if (results.failureReason != null && !results.failureReason.isBlank()) {
            builder.append("Reason: ").append(results.failureReason).append('\n');
        }

        builder.append(String.format(Locale.ROOT, "OS: %s %s (%d-bit)\n", os.getFamily(), os.getVersionInfo().getVersion(), os.getBitness()));
        return builder.toString();
    }

    public static final class DetectionResults {
        boolean configEnabled;
        boolean lwjglAvailable;
        boolean probeRuntimeAvailable;
        boolean openclRuntimeAvailable;
        boolean discreteGpuDetected;
        boolean meetsVramRequirement;
        boolean opencl32Capable;
        boolean openclDeviceDetected;
        boolean isolatedProbeDetected;
        public boolean contextCreationSuccessful;
        String failureReason;
        HardwareCapabilities hardwareCapabilities = new HardwareCapabilities();

        public boolean isContextCreationSuccessful() {
            return contextCreationSuccessful;
        }
    }

    public static final class HardwareCapabilities {
        int cpuCores;
        double memoryGB;
        boolean hasSSE4_2;
        boolean hasAVX;
        boolean likelyOpenCL12Capable;
        boolean likelyOpenCL30Capable;
        String cpuModel = "Unknown CPU";
        String detectedGpuName = "Unknown GPU";
        String detectedGpuVendor = "UNKNOWN";
        long detectedVramBytes;
    }

    public static final class HardwareStatus {
        private final boolean openclAvailable;
        private final boolean gpuAvailable;
        private final double openclConfidence;
        private final double gpuConfidence;
        private final DetectionResults detectionResults;
        private final String operatingSystem;
        private final String architecture;
        private final boolean is64Bit;
        private final int availableProcessors;
        private final long maxMemory;
        private final long totalMemory;
        private final String guidance;
        private final String failureReason;

        HardwareStatus(boolean openclAvailable,
                       boolean gpuAvailable,
                       double openclConfidence,
                       double gpuConfidence,
                       DetectionResults detectionResults,
                       String operatingSystem,
                       String architecture,
                       boolean is64Bit,
                       int availableProcessors,
                       long maxMemory,
                       long totalMemory,
                       String guidance,
                       String failureReason) {
            this.openclAvailable = openclAvailable;
            this.gpuAvailable = gpuAvailable;
            this.openclConfidence = openclConfidence;
            this.gpuConfidence = gpuConfidence;
            this.detectionResults = detectionResults;
            this.operatingSystem = operatingSystem;
            this.architecture = architecture;
            this.is64Bit = is64Bit;
            this.availableProcessors = availableProcessors;
            this.maxMemory = maxMemory;
            this.totalMemory = totalMemory;
            this.guidance = guidance;
            this.failureReason = failureReason;
        }

        public boolean isOpenCLAvailable() { return openclAvailable; }
        public boolean isGPUAvailable() { return gpuAvailable; }
        public double getOpenCLConfidence() { return openclConfidence; }
        public double getGPUConfidence() { return gpuConfidence; }
        public DetectionResults getDetectionResults() { return detectionResults; }
        public String getOperatingSystem() { return operatingSystem; }
        public String getArchitecture() { return architecture; }
        public boolean is64Bit() { return is64Bit; }
        public int getAvailableProcessors() { return availableProcessors; }
        public long getMaxMemory() { return maxMemory; }
        public long getTotalMemory() { return totalMemory; }
        public String getGuidance() { return guidance; }
        public String getFailureReason() { return failureReason; }
    }
}
