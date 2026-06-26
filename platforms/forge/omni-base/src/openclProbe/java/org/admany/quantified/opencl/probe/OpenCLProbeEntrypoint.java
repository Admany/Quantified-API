package org.admany.quantified.opencl.probe;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class OpenCLProbeEntrypoint {

    private static final int CL_PLATFORM_NOT_FOUND_KHR = -1001;

    private OpenCLProbeEntrypoint() {
    }

    public static void main(String[] args) {
        Result result;
        try {
            ensureOpenClCreated();
            try {
                result = Result.success(enumerateDevices());
            } finally {
                try {
                    CL.destroy();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            result = Result.failed(describeThrowable(throwable), List.of());
        }
        System.out.println(result.toJson());
        System.out.flush();
        System.exit(result.ok ? 0 : 1);
    }

    private static void ensureOpenClCreated() {
        try {
            CL.create();
        } catch (IllegalStateException exception) {
            String message = exception.getMessage();
            if (message == null || !message.contains("already been created")) {
                throw exception;
            }
        }
    }

    private static List<DeviceInfo> enumerateDevices() {
        LinkedHashMap<String, DeviceInfo> unique = new LinkedHashMap<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer platformCount = stack.mallocInt(1);
            int err = CL10.clGetPlatformIDs(null, platformCount);
            if (err == CL_PLATFORM_NOT_FOUND_KHR || platformCount.get(0) <= 0) {
                return List.of();
            }
            check(err, "clGetPlatformIDs(count)");

            PointerBuffer platforms = stack.mallocPointer(platformCount.get(0));
            check(CL10.clGetPlatformIDs(platforms, (IntBuffer) null), "clGetPlatformIDs(list)");
            for (int i = 0; i < platforms.capacity(); i++) {
                long platformId = platforms.get(i);
                CLCapabilities platformCaps;
                try {
                    platformCaps = CL.createPlatformCapabilities(platformId);
                } catch (Throwable ignored) {
                    continue;
                }
                enumeratePlatformDevices(stack, platformId, platformCaps, unique);
            }
        }
        ArrayList<DeviceInfo> devices = new ArrayList<>(unique.values());
        devices.sort(Comparator.comparingDouble(DeviceInfo::score).reversed());
        return devices;
    }

    private static void enumeratePlatformDevices(MemoryStack stack,
                                                 long platformId,
                                                 CLCapabilities platformCaps,
                                                 LinkedHashMap<String, DeviceInfo> unique) {
        long[] deviceTypes = {CL10.CL_DEVICE_TYPE_GPU, CL10.CL_DEVICE_TYPE_ACCELERATOR, CL10.CL_DEVICE_TYPE_CPU};
        for (long deviceType : deviceTypes) {
            IntBuffer deviceCount = stack.mallocInt(1);
            int err = CL10.clGetDeviceIDs(platformId, deviceType, null, deviceCount);
            if (err == CL10.CL_DEVICE_NOT_FOUND || deviceCount.get(0) <= 0) {
                continue;
            }
            check(err, "clGetDeviceIDs(count)");

            PointerBuffer deviceIds = stack.mallocPointer(deviceCount.get(0));
            check(CL10.clGetDeviceIDs(platformId, deviceType, deviceIds, (IntBuffer) null), "clGetDeviceIDs(list)");

            for (int i = 0; i < deviceIds.capacity(); i++) {
                DeviceInfo info = evaluateDevice(stack, platformId, deviceIds.get(i), platformCaps);
                if (info == null) {
                    continue;
                }
                String key = normalizeKey(info.vendor + "-" + info.name);
                DeviceInfo existing = unique.get(key);
                if (existing == null || info.score > existing.score) {
                    unique.put(key, info);
                }
            }
        }
    }

    private static DeviceInfo evaluateDevice(MemoryStack stack, long platformId, long deviceId, CLCapabilities platformCaps) {
        try {
            String name = getDeviceInfoString(stack, deviceId, CL10.CL_DEVICE_NAME);
            String vendor = getDeviceInfoString(stack, deviceId, CL10.CL_DEVICE_VENDOR);
            String version = getDeviceInfoString(stack, deviceId, CL10.CL_DEVICE_VERSION);
            String platformVersion = getPlatformInfoString(stack, platformId, CL10.CL_PLATFORM_VERSION);
            long typeFlags = getDeviceInfoLong(stack, deviceId, CL10.CL_DEVICE_TYPE);
            int computeUnits = getDeviceInfoInt(stack, deviceId, CL10.CL_DEVICE_MAX_COMPUTE_UNITS);
            long vramBytes = getDeviceInfoLong(stack, deviceId, CL10.CL_DEVICE_GLOBAL_MEM_SIZE);
            boolean unifiedMemory = getDeviceInfoBoolean(stack, deviceId, CL12.CL_DEVICE_HOST_UNIFIED_MEMORY);

            CLCapabilities deviceCaps = CL.createDeviceCapabilities(deviceId, platformCaps);
            boolean supportsOpenCL32 = parseOpenClVersion(platformVersion) >= 3.2 || parseOpenClVersion(version) >= 3.2;
            String type = classifyDeviceType(typeFlags, name, vendor, unifiedMemory, computeUnits);
            double score = scoreDevice(type, vendor, name, vramBytes, computeUnits);

            return new DeviceInfo(
                buildDeviceId(vendor, name),
                name == null || name.isBlank() ? "Unknown OpenCL Device" : name.trim(),
                vendor == null || vendor.isBlank() ? "Unknown" : vendor.trim(),
                type,
                vramBytes,
                computeUnits,
                supportsOpenCL32,
                deviceCaps.OpenCL12,
                score
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String classifyDeviceType(long typeFlags,
                                             String name,
                                             String vendor,
                                             boolean unifiedMemory,
                                             int computeUnits) {
        if ((typeFlags & CL10.CL_DEVICE_TYPE_CPU) != 0L) {
            return "CPU";
        }
        if (computeUnits < 2) {
            return "LEGACY";
        }
        if (unifiedMemory) {
            return "INTEGRATED";
        }
        String loweredName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String loweredVendor = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
        if (loweredVendor.contains("intel") || loweredName.contains("iris") || loweredName.contains("uhd")) {
            return "INTEGRATED";
        }
        if ((typeFlags & CL10.CL_DEVICE_TYPE_GPU) != 0L || (typeFlags & CL10.CL_DEVICE_TYPE_ACCELERATOR) != 0L) {
            return "DISCRETE";
        }
        return "LEGACY";
    }

    private static double scoreDevice(String type,
                                      String vendor,
                                      String name,
                                      long vramBytes,
                                      int computeUnits) {
        double score = computeUnits * 1_000_000d + (vramBytes / 1_000_000d);
        String loweredVendor = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
        String loweredName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("DISCRETE".equals(type)) {
            score += 10_000_000_000d;
        } else if ("CPU".equals(type)) {
            score += 1_000_000_000d;
        }
        if (loweredVendor.contains("nvidia") || loweredVendor.contains("amd")
            || loweredName.contains("rtx") || loweredName.contains("gtx") || loweredName.contains("radeon")) {
            score += 2_500_000_000d;
        }
        return score;
    }

    private static int getDeviceInfoInt(MemoryStack stack, long deviceId, int param) {
        ByteBuffer buffer = stack.malloc(4);
        check(CL10.clGetDeviceInfo(deviceId, param, buffer, null), "clGetDeviceInfo(int)");
        return buffer.getInt(0);
    }

    private static long getDeviceInfoLong(MemoryStack stack, long deviceId, int param) {
        ByteBuffer buffer = stack.malloc(8);
        check(CL10.clGetDeviceInfo(deviceId, param, buffer, null), "clGetDeviceInfo(long)");
        return buffer.getLong(0);
    }

    private static boolean getDeviceInfoBoolean(MemoryStack stack, long deviceId, int param) {
        return getDeviceInfoInt(stack, deviceId, param) != 0;
    }

    private static String getDeviceInfoString(MemoryStack stack, long deviceId, int param) {
        PointerBuffer sizeBuffer = stack.mallocPointer(1);
        check(CL10.clGetDeviceInfo(deviceId, param, (ByteBuffer) null, sizeBuffer), "clGetDeviceInfo(size)");
        int length = (int) sizeBuffer.get(0);
        if (length <= 1) {
            return "";
        }
        ByteBuffer buffer = stack.malloc(length);
        check(CL10.clGetDeviceInfo(deviceId, param, buffer, null), "clGetDeviceInfo(data)");
        byte[] bytes = new byte[length - 1];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String getPlatformInfoString(MemoryStack stack, long platformId, int param) {
        PointerBuffer sizeBuffer = stack.mallocPointer(1);
        check(CL10.clGetPlatformInfo(platformId, param, (ByteBuffer) null, sizeBuffer), "clGetPlatformInfo(size)");
        int length = (int) sizeBuffer.get(0);
        if (length <= 1) {
            return "";
        }
        ByteBuffer buffer = stack.malloc(length);
        check(CL10.clGetPlatformInfo(platformId, param, buffer, null), "clGetPlatformInfo(data)");
        byte[] bytes = new byte[length - 1];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void check(int errorCode, String operation) {
        if (errorCode != CL10.CL_SUCCESS) {
            throw new IllegalStateException(operation + " failed with error code " + errorCode);
        }
    }

    private static double parseOpenClVersion(String versionString) {
        if (versionString == null) {
            return 0.0;
        }
        String trimmed = versionString.trim().toLowerCase(Locale.ROOT);
        int index = trimmed.indexOf("opencl");
        if (index >= 0) {
            trimmed = trimmed.substring(index + "opencl".length()).trim();
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
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static String buildDeviceId(String vendor, String name) {
        String normalized = normalizeKey((vendor == null ? "" : vendor) + "-" + (name == null ? "" : name));
        return normalized.isBlank() ? "unknown-opencl-device" : normalized;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String describeThrowable(Throwable throwable) {
        Throwable cause = throwable instanceof ExceptionInInitializerError && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message != null && !message.isBlank() ? ": " + message : "");
    }

    private record DeviceInfo(String id,
                              String name,
                              String vendor,
                              String type,
                              long vramBytes,
                              int computeUnits,
                              boolean supportsOpenCL32,
                              boolean supportsOpenCL12,
                              double score) {

        private String toJson() {
            return new StringBuilder(192)
                .append('{')
                .append("\"id\":\"").append(escape(id)).append('"')
                .append(",\"name\":\"").append(escape(name)).append('"')
                .append(",\"vendor\":\"").append(escape(vendor)).append('"')
                .append(",\"type\":\"").append(escape(type)).append('"')
                .append(",\"vramBytes\":").append(vramBytes)
                .append(",\"computeUnits\":").append(computeUnits)
                .append(",\"supportsOpenCL32\":").append(supportsOpenCL32)
                .append(",\"supportsOpenCL12\":").append(supportsOpenCL12)
                .append('}')
                .toString();
        }
    }

    private record Result(boolean ok, String failure, List<DeviceInfo> devices) {
        static Result success(List<DeviceInfo> devices) {
            return new Result(true, null, devices);
        }

        static Result failed(String failure, List<DeviceInfo> devices) {
            return new Result(false, failure, devices);
        }

        String toJson() {
            StringBuilder builder = new StringBuilder(512);
            builder.append('{')
                .append("\"ok\":").append(ok)
                .append(",\"devices\":[");
            for (int i = 0; i < devices.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(devices.get(i).toJson());
            }
            builder.append(']');
            if (failure != null) {
                builder.append(",\"failure\":\"").append(escape(failure)).append('"');
            }
            builder.append('}');
            return builder.toString();
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }
}
