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
        try {
            if (!isOpenCLAvailable()) {
                LOGGER.info("OpenCL not available on this system");
                return GPUCapabilities.UNSUPPORTED;
            }

            DeviceCandidate bestDevice = findBestDevice();
            if (bestDevice == null) {
                LOGGER.info("No suitable OpenCL device found");
                return new GPUCapabilities(false, null, null, null, null, false,
                    "No OpenCL 3.2 discrete GPU with at least 2 GB VRAM was detected");
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
            CL.class.getName();
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

    private static DeviceCandidate findBestDevice() {
        if (!isOpenCLAvailable()) {
            return null;
        }

        List<DeviceCandidate> candidates = enumerateAllDevices();
        if (candidates.isEmpty()) {
            return null;
        }

        return candidates.stream()
            .max((a, b) -> Double.compare(a.score(), b.score()))
            .orElse(null);
    }

    private static List<DeviceCandidate> enumerateAllDevices() {
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

                List<DeviceCandidate> platformDevices = enumeratePlatformDevices(platformId, platformVersion, platformCaps);
                devices.addAll(platformDevices);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error enumerating OpenCL devices", e);
        }

        return devices;
    }

    private static List<DeviceCandidate> enumeratePlatformDevices(long platformId, String platformVersion, CLCapabilities platformCaps) {
        List<DeviceCandidate> devices = new ArrayList<>();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int[] deviceTypes = {CL10.CL_DEVICE_TYPE_GPU, CL10.CL_DEVICE_TYPE_ACCELERATOR, CL10.CL_DEVICE_TYPE_CPU};

            for (int deviceType : deviceTypes) {
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
                        DeviceCandidate candidate = evaluateDevice(platformId, platformVersion, deviceId, platformCaps);
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

    private static DeviceCandidate evaluateDevice(long platformId, String platformVersion, long deviceId, CLCapabilities platformCaps) {
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

            if (!deviceCaps.cl_khr_fp64 && !deviceCaps.cl_amd_fp64) {
                LOGGER.fine(String.format("Device %s rejected: requires double precision support", deviceName));
                return null;
            }

            DeviceType deviceType = classifyDevice(deviceTypeFlags, deviceName, deviceVendor, unifiedMemory, computeUnits);
            if (deviceType != DeviceType.DISCRETE) {
                LOGGER.fine(String.format("Device %s rejected: requires discrete GPU", deviceName));
                return null;
            }

            if (!hasAdequateResources(vramBytes, computeUnits)) {
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

    private static String getDeviceTypeName(int deviceType) {
        switch (deviceType) {
            case CL10.CL_DEVICE_TYPE_CPU: return "CPU";
            case CL10.CL_DEVICE_TYPE_GPU: return "GPU";
            case CL10.CL_DEVICE_TYPE_ACCELERATOR: return "Accelerator";
            default: return "Unknown";
        }
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

    private record DeviceCandidate(
        OpenCLDevice device,
        DeviceType type,
        double score,
        UUID deviceUUID,
        UUID driverUUID,
        boolean supportsOpenCL32
    ) {}
}