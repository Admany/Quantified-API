package org.admany.quantified.core.common.opencl.gpu;

import org.admany.quantified.core.common.opencl.core.OpenCLRuntime;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL12;
import org.lwjgl.opencl.CLCapabilities;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GPUDetector {

    private static final Logger LOGGER = Logger.getLogger(GPUDetector.class.getName());

    private static final int CL_PLATFORM_NOT_FOUND_KHR = -1001;

    private static final long MIN_VRAM_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MIN_COMPUTE_UNITS = 2;

    private static final int CL_DEVICE_UUID_KHR = 0x106A;
    private static final int CL_DRIVER_UUID_KHR = 0x106B;

    private GPUDetector() {}

    public static GPUCapabilities detectCapabilities() {
        return detectCapabilities(null);
    }

    public static GPUCapabilities detectCapabilities(String preferredDeviceId) {
        try {
            if (!isOpenCLAvailable()) {
                OpenCLRuntime.ProbeSnapshot probeSnapshot = OpenCLRuntime.probeSnapshot();
                if (!probeSnapshot.devices().isEmpty()) {
                    OpenCLRuntime.ProbeDeviceInfo device = probeSnapshot.devices().get(0);
                    String reason = "OpenCL hardware detected via isolated probe (" + device.name()
                        + "), but in-process runtime execution is unavailable";
                    LOGGER.info(reason);
                    return new GPUCapabilities(false, null, null, null, null, false, reason);
                }
                LOGGER.info("OpenCL not available on this system");
                return GPUCapabilities.UNSUPPORTED;
            }

            DeviceCriteria criteria = DeviceCriteria.auto();
            List<DeviceCandidate> candidates = enumerateAllDevices(criteria);
            if (candidates.isEmpty()) {
                LOGGER.info("No suitable OpenCL device found");
                return new GPUCapabilities(false, null, null, null, null, false,
                    "No compatible OpenCL GPU devices were detected");
            }

            DeviceCandidate bestDevice = selectPreferredDevice(preferredDeviceId, candidates);
            if (bestDevice == null && (preferredDeviceId == null || preferredDeviceId.isBlank()
                || "auto".equalsIgnoreCase(preferredDeviceId.trim()))) {
                List<DeviceCandidate> discrete = candidates.stream()
                    .filter(candidate -> candidate.type() == DeviceType.DISCRETE)
                    .toList();
                if (!discrete.isEmpty()) {
                    candidates = discrete;
                } else {
                    LOGGER.info("No discrete OpenCL GPU detected (auto selection). Specify openclDeviceId to use an integrated device.");
                    return new GPUCapabilities(false, null, null, null, null, false,
                        "No discrete OpenCL GPU detected; set openclDeviceId to select an integrated GPU");
                }
            }
            if (bestDevice == null) {
                bestDevice = candidates.stream()
                    .max((a, b) -> Double.compare(a.score(), b.score()))
                    .orElse(null);
            }
            if (bestDevice == null) {
                LOGGER.info("No suitable OpenCL device found");
                return new GPUCapabilities(false, null, null, null, null, false,
                    "No compatible OpenCL GPU devices were detected");
            }

            String info = "OpenCL device selected: " + bestDevice.device().name() +
                " (" + bestDevice.device().vendor() + ", CU=" + bestDevice.device().computeUnits() +
                ", VRAM=" + humanReadableVram(bestDevice.device().vramBytes()) + ", type=" + bestDevice.type() + ")";
            LOGGER.fine(info);

            return new GPUCapabilities(true, bestDevice.device(), bestDevice.type(), bestDevice.deviceUUID(), bestDevice.driverUUID(), bestDevice.supportsOpenCL32(), null);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during GPU detection", e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new GPUCapabilities(false, null, null, null, null, false, reason);
        }
    }

    private static boolean isOpenCLAvailable() {
        try {
            if (!OpenCLRuntime.hasBindings()) {
                LOGGER.info("OpenCL binding unavailable in-process: " + OpenCLRuntime.getBindingName());
                return false;
            }
            OpenCLRuntime.AvailabilitySnapshot status = OpenCLRuntime.snapshot();
            if (!status.available()) {
                String reason = status.failureReason() != null ? status.failureReason() : "unknown";
                LOGGER.info("OpenCL runtime unavailable: " + reason);
            }
            return status.available();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Failed to initialize OpenCL", t);
            return false;
        }
    }

    public static List<OpenCLDeviceInfo> listDevices() {
        if (!isOpenCLAvailable()) {
            OpenCLRuntime.ProbeSnapshot probeSnapshot = OpenCLRuntime.probeSnapshot();
            if (probeSnapshot.devices().isEmpty()) {
                return List.of();
            }
            List<OpenCLDeviceInfo> probedDevices = new ArrayList<>(probeSnapshot.devices().size());
            for (OpenCLRuntime.ProbeDeviceInfo device : probeSnapshot.devices()) {
                probedDevices.add(new OpenCLDeviceInfo(
                    device.id(),
                    device.name(),
                    device.vendor(),
                    mapProbeType(device.type()),
                    device.vramBytes(),
                    device.computeUnits(),
                    device.supportsOpenCL32()
                ));
            }
            return probedDevices;
        }
        List<DeviceCandidate> candidates = enumerateAllDevices(DeviceCriteria.listing());
        java.util.LinkedHashMap<String, DeviceCandidate> unique = new java.util.LinkedHashMap<>();
        for (DeviceCandidate candidate : candidates) {
            String key = normalizeDeviceKey(candidate.device().name());
            DeviceCandidate existing = unique.get(key);
            if (existing == null || preferCandidate(candidate, existing)) {
                unique.put(key, candidate);
            }
        }
        List<OpenCLDeviceInfo> devices = new ArrayList<>();
        for (DeviceCandidate candidate : unique.values()) {
            devices.add(new OpenCLDeviceInfo(
                candidate.deviceUUID() != null ? candidate.deviceUUID().toString() : candidate.device().name(),
                candidate.device().name(),
                candidate.device().vendor(),
                candidate.type(),
                candidate.device().vramBytes(),
                candidate.device().computeUnits(),
                candidate.supportsOpenCL32()
            ));
        }
        return devices;
    }

    private static DeviceCandidate selectPreferredDevice(String preferredDeviceId, List<DeviceCandidate> candidates) {
        if (preferredDeviceId == null) {
            return null;
        }
        String trimmed = preferredDeviceId.trim();
        if (trimmed.isEmpty() || "auto".equalsIgnoreCase(trimmed)) {
            return null;
        }
        String normalized = normalizeDeviceKey(trimmed);
        for (DeviceCandidate candidate : candidates) {
            if (candidate.deviceUUID() != null) {
                if (candidate.deviceUUID().toString().equalsIgnoreCase(trimmed)) {
                    return candidate;
                }
                if (normalizeDeviceKey(candidate.deviceUUID().toString()).equals(normalized)) {
                    return candidate;
                }
            }
            String name = candidate.device().name();
            if (name != null && normalizeDeviceKey(name).equals(normalized)) {
                return candidate;
            }
            String vendorName = (candidate.device().vendor() + " " + candidate.device().name()).trim();
            if (!vendorName.isBlank() && normalizeDeviceKey(vendorName).equals(normalized)) {
                return candidate;
            }
        }
        for (DeviceCandidate candidate : candidates) {
            String name = candidate.device().name();
            if (name != null && normalizeDeviceKey(name).contains(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static String normalizeDeviceKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static DeviceType mapProbeType(String type) {
        if (type == null) {
            return DeviceType.LEGACY;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "DISCRETE" -> DeviceType.DISCRETE;
            case "INTEGRATED" -> DeviceType.INTEGRATED;
            case "CPU" -> DeviceType.CPU;
            default -> DeviceType.LEGACY;
        };
    }

    private static boolean preferCandidate(DeviceCandidate candidate, DeviceCandidate existing) {
        boolean candidateLegacy = candidate.type() == DeviceType.LEGACY;
        boolean existingLegacy = existing.type() == DeviceType.LEGACY;
        if (existingLegacy && !candidateLegacy) {
            return true;
        }
        if (!existingLegacy && candidateLegacy) {
            return false;
        }
        String candidateVendor = candidate.device().vendor() != null ? candidate.device().vendor().toLowerCase(Locale.ROOT) : "";
        String existingVendor = existing.device().vendor() != null ? existing.device().vendor().toLowerCase(Locale.ROOT) : "";
        boolean candidateMicrosoft = candidateVendor.contains("microsoft");
        boolean existingMicrosoft = existingVendor.contains("microsoft");
        if (existingMicrosoft && !candidateMicrosoft) {
            return true;
        }
        if (!existingMicrosoft && candidateMicrosoft) {
            return false;
        }
        if (candidate.device().vramBytes() != existing.device().vramBytes()) {
            return candidate.device().vramBytes() > existing.device().vramBytes();
        }
        if (candidate.device().computeUnits() != existing.device().computeUnits()) {
            return candidate.device().computeUnits() > existing.device().computeUnits();
        }
        return candidate.score() > existing.score();
    }

    private static List<DeviceCandidate> enumerateAllDevices(DeviceCriteria criteria) {
        List<DeviceCandidate> devices = new ArrayList<>();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer platformCount = stack.mallocInt(1);
            int err = CL10.clGetPlatformIDs(null, platformCount);
            if (err == CL_PLATFORM_NOT_FOUND_KHR) {
                LOGGER.info("No OpenCL platforms found - OpenCL drivers may not be installed");
                return devices;
            }
            checkCleError(err, "clGetPlatformIDs(count)");

            int count = platformCount.get(0);
            if (count <= 0) {
                LOGGER.info("OpenCL platforms query returned 0 platforms");
                return devices;
            }

            PointerBuffer platforms = stack.mallocPointer(count);
            checkCleError(CL10.clGetPlatformIDs(platforms, (IntBuffer) null), "clGetPlatformIDs(list)");

            for (int i = 0; i < count; i++) {
                long platformId = platforms.get(i);
                String platformName = getPlatformInfoString(platformId, CL10.CL_PLATFORM_NAME);
                String platformVersion = getPlatformInfoString(platformId, CL10.CL_PLATFORM_VERSION);

                CLCapabilities platformCaps;
                try {
                    platformCaps = CL.createPlatformCapabilities(platformId);
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, String.format("Failed to create platform capabilities for %s: %s", platformName, t.getMessage()));
                    continue;
                }

                LOGGER.fine(String.format("Found OpenCL platform: %s version %s", platformName, platformVersion));

                List<DeviceCandidate> platformDevices = enumeratePlatformDevices(platformId, platformVersion, platformCaps, criteria);
                devices.addAll(platformDevices);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error enumerating OpenCL devices", e);
        }

        return devices;
    }

    private static List<DeviceCandidate> enumeratePlatformDevices(long platformId, String platformVersion, CLCapabilities platformCaps, DeviceCriteria criteria) {
        List<DeviceCandidate> devices = new ArrayList<>();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] deviceTypes = {CL10.CL_DEVICE_TYPE_GPU, CL10.CL_DEVICE_TYPE_ACCELERATOR, CL10.CL_DEVICE_TYPE_CPU};

            for (long deviceType : deviceTypes) {
                if (deviceType == CL10.CL_DEVICE_TYPE_CPU && !criteria.allowCpu()) {
                    continue;
                }
                try {
                    IntBuffer deviceCount = stack.mallocInt(1);
                    int err = CL10.clGetDeviceIDs(platformId, deviceType, null, deviceCount);
                    if (err == CL10.CL_DEVICE_NOT_FOUND) {
                        continue;
                    }
                    checkCleError(err, "clGetDeviceIDs(count)");

                    int count = deviceCount.get(0);
                    if (count <= 0) continue;

                    PointerBuffer deviceIds = stack.mallocPointer(count);
                    checkCleError(CL10.clGetDeviceIDs(platformId, deviceType, deviceIds, (IntBuffer) null), "clGetDeviceIDs(list)");

                    for (int i = 0; i < count; i++) {
                        long deviceId = deviceIds.get(i);
                        DeviceCandidate candidate = evaluateDevice(platformId, platformVersion, deviceId, platformCaps, criteria);
                        if (candidate != null) {
                            devices.add(candidate);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, String.format("Failed to enumerate %s devices: %s", getDeviceTypeName(deviceType), e.getMessage()));
                }
            }
        }

        return devices;
    }

    private static DeviceCandidate evaluateDevice(long platformId, String platformVersion, long deviceId, CLCapabilities platformCaps, DeviceCriteria criteria) {
        try {
            String deviceName = getDeviceInfoString(deviceId, CL10.CL_DEVICE_NAME);
            String deviceVendor = getDeviceInfoString(deviceId, CL10.CL_DEVICE_VENDOR);
            String deviceVersion = getDeviceInfoString(deviceId, CL10.CL_DEVICE_VERSION);
            String deviceExtensions = getDeviceInfoString(deviceId, CL10.CL_DEVICE_EXTENSIONS);

            double platformVersionValue = parseOpenCLVersion(platformVersion);
            double deviceVersionValue = parseOpenCLVersion(deviceVersion);
            boolean supportsOpenCL32 = platformVersionValue >= 3.2 || deviceVersionValue >= 3.2;

            int computeUnits = getDeviceInfoInt(deviceId, CL10.CL_DEVICE_MAX_COMPUTE_UNITS);
            long vramBytes = getDeviceInfoLong(deviceId, CL10.CL_DEVICE_GLOBAL_MEM_SIZE);
            boolean unifiedMemory = getDeviceInfoBoolean(deviceId, CL12.CL_DEVICE_HOST_UNIFIED_MEMORY);
            long deviceTypeFlags = getDeviceInfoLong(deviceId, CL10.CL_DEVICE_TYPE);

            CLCapabilities deviceCaps;
            try {
                deviceCaps = CL.createDeviceCapabilities(deviceId, platformCaps);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, String.format("Failed to create device capabilities for %s: %s", deviceName, t.getMessage()));
                return null;
            }

            if (!deviceCaps.OpenCL12) {
                LOGGER.fine(String.format("Device %s rejected: requires OpenCL 1.2+", deviceName));
                return null;
            }

            boolean supportsFp64 = deviceCaps.cl_khr_fp64 || deviceCaps.cl_amd_fp64;
            if (criteria.requireFp64() && !supportsFp64) {
                LOGGER.fine(String.format("Device %s rejected: requires double precision support", deviceName));
                return null;
            }

            DeviceType deviceType = classifyDevice(deviceTypeFlags, deviceName, deviceVendor, unifiedMemory, computeUnits);
            if (criteria.requireDiscrete() && deviceType != DeviceType.DISCRETE) {
                LOGGER.fine(String.format("Device %s rejected: requires discrete GPU", deviceName));
                return null;
            }
            if (criteria.requireMinResources() && !hasAdequateResources(vramBytes, computeUnits)) {
                LOGGER.fine(String.format("Device %s rejected: insufficient resources (CU=%d, VRAM=%s)",
                    deviceName, computeUnits, humanReadableVram(vramBytes)));
                return null;
            }

            UUID deviceUUID;
            UUID driverUUID;

            if (!deviceCaps.cl_khr_device_uuid) {
                LOGGER.fine(String.format("Device %s doesn't support hardware UUID, using software fallback", deviceName));
                String deviceSignature = deviceVendor + deviceName + deviceVersion + deviceExtensions;
                String platformSignature = getPlatformInfoString(platformId, CL10.CL_PLATFORM_VENDOR) +
                    getPlatformInfoString(platformId, CL10.CL_PLATFORM_NAME) +
                    getPlatformInfoString(platformId, CL10.CL_PLATFORM_VERSION) +
                    getPlatformInfoString(platformId, CL10.CL_PLATFORM_EXTENSIONS);

                deviceUUID = UUID.nameUUIDFromBytes(deviceSignature.getBytes(StandardCharsets.UTF_8));
                driverUUID = UUID.nameUUIDFromBytes(platformSignature.getBytes(StandardCharsets.UTF_8));
            } else {
                deviceUUID = getDeviceUUID(deviceId);
                driverUUID = getDriverUUID(deviceId);
            }

            double score = calculateDeviceScore(deviceType, computeUnits, vramBytes, deviceVendor, deviceName);

            OpenCLDevice device = new OpenCLDevice(
                platformId,
                deviceId,
                platformVersion,
                deviceName,
                deviceVendor,
                deviceVersion,
                vramBytes,
                computeUnits,
                unifiedMemory,
                deviceExtensions
            );

            LOGGER.fine(String.format("Device accepted: %s (score: %.1fB, UUID: %s)",
                deviceName, score / 1_000_000_000d, deviceUUID));

            return new DeviceCandidate(device, deviceType, score, deviceUUID, driverUUID, supportsOpenCL32);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to evaluate device " + deviceId, e);
            return null;
        }
    }

    private static DeviceType classifyDevice(long deviceTypeFlags, String name, String vendor,
                                           boolean unifiedMemory, int computeUnits) {
        if ((deviceTypeFlags & CL10.CL_DEVICE_TYPE_CPU) != 0L) {
            return DeviceType.CPU;
        }

        if (computeUnits < MIN_COMPUTE_UNITS) {
            return DeviceType.LEGACY;
        }

        if (unifiedMemory) {
            return DeviceType.INTEGRATED;
        }

        String loweredName = name != null ? name.toLowerCase() : "";
        String loweredVendor = vendor != null ? vendor.toLowerCase() : "";

        if (loweredVendor.contains("intel") || loweredName.contains("iris") ||
            loweredName.contains("uhd") || loweredName.contains("intel")) {
            return DeviceType.INTEGRATED;
        }

        if ((deviceTypeFlags & CL10.CL_DEVICE_TYPE_GPU) != 0L ||
            (deviceTypeFlags & CL10.CL_DEVICE_TYPE_ACCELERATOR) != 0L) {
            return DeviceType.DISCRETE;
        }

        return DeviceType.LEGACY;
    }

    private static double calculateDeviceScore(DeviceType type, int computeUnits, long vramBytes,
                                             String vendor, String name) {
        double score = computeUnits * 1_000_000d + (vramBytes / 1_000_000d);

        String loweredVendor = vendor != null ? vendor.toLowerCase() : "";
        String loweredName = name != null ? name.toLowerCase() : "";

        boolean preferredVendor = loweredVendor.contains("nvidia") ||
            loweredVendor.contains("advanced micro devices") || loweredVendor.contains("amd");
        boolean preferredName = loweredName.contains("rtx") || loweredName.contains("gtx") ||
            loweredName.contains("radeon") || loweredName.contains("rx");

        if (preferredVendor || preferredName) {
            score += 2_500_000_000d;
        }

        if (type == DeviceType.DISCRETE) {
            score += 10_000_000_000d;
        } else if (type == DeviceType.CPU) {
            score += 1_000_000_000d;
        }

        return score;
    }

    private static boolean hasAdequateResources(long vramBytes, int computeUnits) {
        return vramBytes >= MIN_VRAM_BYTES && computeUnits >= MIN_COMPUTE_UNITS;
    }

    private static String getDeviceTypeName(long deviceType) {
        if (deviceType == CL10.CL_DEVICE_TYPE_CPU) {
            return "CPU";
        }
        if (deviceType == CL10.CL_DEVICE_TYPE_GPU) {
            return "GPU";
        }
        if (deviceType == CL10.CL_DEVICE_TYPE_ACCELERATOR) {
            return "Accelerator";
        }
        return "Unknown";
    }

    private static UUID getDeviceUUID(long deviceId) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(16);
            int err = CL10.clGetDeviceInfo(deviceId, CL_DEVICE_UUID_KHR, buffer, null);
            checkCleError(err, "clGetDeviceInfo(deviceUUID)");
            return uuidFromBytes(buffer);
        }
    }

    private static UUID getDriverUUID(long deviceId) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(16);
            int err = CL10.clGetDeviceInfo(deviceId, CL_DRIVER_UUID_KHR, buffer, null);
            checkCleError(err, "clGetDeviceInfo(driverUUID)");
            return uuidFromBytes(buffer);
        }
    }

    private static UUID uuidFromBytes(ByteBuffer buffer) {
        long msb = buffer.getLong(0);
        long lsb = buffer.getLong(8);
        return new UUID(msb, lsb);
    }

    private static String getDeviceInfoString(long deviceId, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer sizeBuffer = stack.mallocPointer(1);
            int err = CL10.clGetDeviceInfo(deviceId, param, (ByteBuffer) null, sizeBuffer);
            checkCleError(err, "clGetDeviceInfo(size) param=" + param);
            int length = (int) sizeBuffer.get(0);
            if (length <= 1) return "";

            ByteBuffer buffer = stack.malloc(length);
            err = CL10.clGetDeviceInfo(deviceId, param, buffer, null);
            checkCleError(err, "clGetDeviceInfo(data) param=" + param);

            byte[] bytes = new byte[length - 1];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String getPlatformInfoString(long platformId, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer sizeBuffer = stack.mallocPointer(1);
            int err = CL10.clGetPlatformInfo(platformId, param, (ByteBuffer) null, sizeBuffer);
            checkCleError(err, "clGetPlatformInfo(size) param=" + param);
            int length = (int) sizeBuffer.get(0);
            if (length <= 1) return "";

            ByteBuffer buffer = stack.malloc(length);
            err = CL10.clGetPlatformInfo(platformId, param, buffer, null);
            checkCleError(err, "clGetPlatformInfo(data) param=" + param);

            byte[] bytes = new byte[length - 1];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static int getDeviceInfoInt(long deviceId, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(4);
            int err = CL10.clGetDeviceInfo(deviceId, param, buffer, null);
            checkCleError(err, "clGetDeviceInfo(int) param=" + param);
            return buffer.getInt(0);
        }
    }

    private static long getDeviceInfoLong(long deviceId, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(8);
            int err = CL10.clGetDeviceInfo(deviceId, param, buffer, null);
            checkCleError(err, "clGetDeviceInfo(long) param=" + param);
            return buffer.getLong(0);
        }
    }

    private static boolean getDeviceInfoBoolean(long deviceId, int param) {
        return getDeviceInfoInt(deviceId, param) != 0;
    }

    private static void checkCleError(int errorCode, String operation) {
        if (errorCode != CL10.CL_SUCCESS) {
            throw new IllegalStateException(operation + " failed with error code " + errorCode);
        }
    }

    private static String humanReadableVram(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024.0);
        if (gb >= 1.0) {
            return String.format("%.1f GB", gb);
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format("%.0f MB", mb);
    }

    private static double parseOpenCLVersion(String versionString) {
        if (versionString == null) {
            return 0.0;
        }
        String trimmed = versionString.trim();
        int idx = trimmed.toLowerCase(Locale.ROOT).indexOf("opencl");
        if (idx >= 0) {
            trimmed = trimmed.substring(idx + "opencl".length()).trim();
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0.0;
        }
        try {
            return Double.parseDouble(digits.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static void main(String[] args) {
        GPUCapabilities caps = detectCapabilities();
        System.out.println("OpenCL Supported: " + caps.supported());
        if (caps.supported()) {
            System.out.println("Device: " + caps.device().name());
            System.out.println("Vendor: " + caps.device().vendor());
            System.out.println("Type: " + caps.deviceType());
            System.out.println("VRAM: " + humanReadableVram(caps.device().vramBytes()));
            System.out.println("Compute Units: " + caps.device().computeUnits());
            System.out.println("Device UUID: " + caps.deviceUUID());
            System.out.println("Driver UUID: " + caps.driverUUID());
        }
    }

    public enum DeviceType {
        DISCRETE,
        INTEGRATED,
        CPU,
        LEGACY
    }

    public record GPUCapabilities(
        boolean supported,
        OpenCLDevice device,
        DeviceType deviceType,
        UUID deviceUUID,
        UUID driverUUID,
        boolean supportsOpenCL32,
        String failureReason
    ) {
        public static final GPUCapabilities UNSUPPORTED = new GPUCapabilities(false, null, null, null, null, false, "OpenCL runtime unavailable");

        public boolean supportsOpenCL32() {
            return supportsOpenCL32;
        }
    }

    public record OpenCLDevice(
        long platformId,
        long deviceId,
        String platformVersion,
        String name,
        String vendor,
        String version,
        long vramBytes,
        int computeUnits,
        boolean unifiedMemory,
        String extensions
    ) {}

    public record OpenCLDeviceInfo(
        String id,
        String name,
        String vendor,
        DeviceType type,
        long vramBytes,
        int computeUnits,
        boolean supportsOpenCL32
    ) {}

    private record DeviceCandidate(
        OpenCLDevice device,
        DeviceType type,
        double score,
        UUID deviceUUID,
        UUID driverUUID,
        boolean supportsOpenCL32
    ) {}

    private record DeviceCriteria(
        boolean requireFp64,
        boolean requireDiscrete,
        boolean requireMinResources,
        boolean allowCpu
    ) {
        static DeviceCriteria auto() {
            return new DeviceCriteria(false, false, false, false);
        }

        static DeviceCriteria listing() {
            return new DeviceCriteria(false, false, false, false);
        }
    }
}
