package org.admany.quantified.vulkan.probe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.IOException;

public final class VulkanProbeEntrypoint {

    private static final int API_1_0 = vkApiVersion(1, 0, 0);
    private static final int API_1_2 = vkApiVersion(1, 2, 0);
    private static final int API_1_3 = vkApiVersion(1, 3, 0);

    private VulkanProbeEntrypoint() {
    }

    public static void main(String[] args) {
        heartbeat("startup");
        heartbeat("pre-librarypath-config");
        String originalLwjglLibraryPath = System.getProperty("org.lwjgl.librarypath");
        String originalSharedExtractPath = System.getProperty("org.lwjgl.system.SharedLibraryExtractPath");
        if (originalLwjglLibraryPath != null && !originalLwjglLibraryPath.isBlank()) {
            System.setProperty("org.lwjgl.system.SharedLibraryExtractPath", originalLwjglLibraryPath);
        }
        logDiagnostic("java=" + System.getProperty("java.version")
            + ", vendor=" + System.getProperty("java.vendor")
            + ", os=" + System.getProperty("os.name")
            + " " + System.getProperty("os.version")
            + ", arch=" + System.getProperty("os.arch"));
        logDiagnostic("org.lwjgl.system.stackSizeKb=" + System.getProperty("org.lwjgl.system.stackSize", "<unset>")
            + ", quantified.lwjgl.stackSizeBytes=" + System.getProperty("quantified.lwjgl.stackSizeBytes", "<unset>"));
        logDiagnostic("java.library.path=" + System.getProperty("java.library.path", "<unset>"));
        logDiagnostic("org.lwjgl.librarypath=" + (originalLwjglLibraryPath != null ? originalLwjglLibraryPath : "<unset>"));
        logDiagnostic("org.lwjgl.system.SharedLibraryExtractPath(original)="
            + (originalSharedExtractPath != null ? originalSharedExtractPath : "<unset>"));
        logDiagnostic("org.lwjgl.system.SharedLibraryExtractPath(effective)="
            + System.getProperty("org.lwjgl.system.SharedLibraryExtractPath", "<unset>"));
        heartbeat("post-librarypath-config");
        detectLoaderPath().ifPresent(path -> logDiagnostic("loader.path=" + path));
        Result result;
        try {
            heartbeat("pre-VK-init");
            String bootstrapFailure = bootstrapVulkanBinding();
            if (bootstrapFailure != null) {
                heartbeat("post-VK-init-failed");
                result = Result.failed("vk_class_init_failed", bootstrapFailure, 0, 0, List.of());
            } else {
                heartbeat("post-VK-init-ok");
                result = runProbe();
            }
        } catch (Throwable throwable) {
            heartbeat("probe-threw");
            result = Result.failed("probe_threw", throwable.getClass().getSimpleName() + ": " + throwable.getMessage(), 0, 0, List.of());
        }
        heartbeat("done");
        System.out.println(result.toJson());
        System.out.flush();
        System.exit(result.ok ? 0 : 1);
    }

    private static String bootstrapVulkanBinding() {
        try {
            Class.forName("org.lwjgl.vulkan.VK");
            logDiagnostic("VK init OK");
            return null;
        } catch (ExceptionInInitializerError error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            logDiagnostic("VK ExceptionInInitializerError: " + cause);
            cause.printStackTrace(System.out);
            return describeThrowable(cause);
        } catch (NoClassDefFoundError error) {
            logDiagnostic("VK NoClassDefFoundError: " + error.getMessage());
            error.printStackTrace(System.out);
            return describeThrowable(error);
        } catch (Throwable throwable) {
            logDiagnostic("VK bootstrap failure: " + throwable);
            throwable.printStackTrace(System.out);
            return describeThrowable(throwable);
        }
    }

    private static Result runProbe() {
        heartbeat("pre-vkEnumerateInstanceVersion");
        VersionQuery versionQuery = queryInstanceApiVersion();
        int maxInstanceApi = versionQuery.version();
        logDiagnostic("vkEnumerateInstanceVersion -> " + vkResultName(versionQuery.resultCode())
            + ", version=" + formatVersion(maxInstanceApi));
        if (versionQuery.resultCode() != VK10.VK_SUCCESS) {
            return diagnoseVersionQueryFailure(maxInstanceApi, versionQuery.resultCode());
        }
        if (compare(maxInstanceApi, API_1_2) < 0) {
            return diagnoseTooOldApi(maxInstanceApi);
        }

        StringBuilder failure = new StringBuilder();
        for (int apiVersion : candidateApiVersions(maxInstanceApi)) {
            heartbeat("pre-vkCreateInstance-" + formatVersion(apiVersion));
            InstanceAttempt attempt = tryCreateInstance(apiVersion);
            if (attempt.instance != null) {
                try {
                    logDiagnostic("vkCreateInstance(" + formatVersion(apiVersion) + ") -> VK_SUCCESS");
                    List<DeviceCandidate> candidates = enumerateDeviceCandidates(attempt.instance);
                    List<DeviceInfo> devices = candidates.stream().map(DeviceCandidate::info).toList();
                    BringUpAttempt bringUp = validateLogicalDeviceBringUp(candidates, apiVersion);
                    if (bringUp.ok()) {
                        return Result.success(maxInstanceApi, apiVersion, devices);
                    }
                    return Result.failed("logical_device_bringup_failed",
                        "logical device bring-up failed: " + bringUp.detail(),
                        maxInstanceApi,
                        apiVersion,
                        devices);
                } finally {
                    VK10.vkDestroyInstance(attempt.instance, null);
                }
            }
            logDiagnostic("vkCreateInstance(" + formatVersion(apiVersion) + ") -> "
                + vkResultName(attempt.resultCode())
                + (attempt.detail != null && !attempt.detail.isBlank() ? " (" + attempt.detail + ")" : ""));
            if (failure.length() > 0) {
                failure.append(", ");
            }
            failure.append(formatVersion(apiVersion)).append(':').append(vkResultName(attempt.resultCode));
            if (attempt.detail != null && !attempt.detail.isBlank()) {
                failure.append(" (").append(attempt.detail).append(')');
            }
        }

        return Result.failed("create_instance_failed",
            "vkCreateInstance failed for candidates=" + failure,
            maxInstanceApi,
            0,
            List.of());
    }

    private static Result diagnoseVersionQueryFailure(int maxInstanceApi, int queryResultCode) {
        heartbeat("pre-vkCreateInstance-1.0.0-diagnostic");
        InstanceAttempt fallbackAttempt = tryCreateInstance(API_1_0);
        if (fallbackAttempt.instance != null) {
            try {
                List<DeviceInfo> devices = enumerateDeviceCandidates(fallbackAttempt.instance).stream()
                    .map(DeviceCandidate::info)
                    .toList();
                logDiagnostic("vkCreateInstance(1.0.0) -> VK_SUCCESS, devices=" + devices.size());
                return Result.failed("version_query_failed",
                    "vkEnumerateInstanceVersion failed with " + vkResultName(queryResultCode)
                        + "; vkCreateInstance 1.0 succeeded with " + devices.size() + " device(s)",
                    maxInstanceApi,
                    API_1_0,
                    devices);
            } finally {
                VK10.vkDestroyInstance(fallbackAttempt.instance, null);
            }
        }
        logDiagnostic("vkCreateInstance(1.0.0) -> " + vkResultName(fallbackAttempt.resultCode())
            + (fallbackAttempt.detail != null && !fallbackAttempt.detail.isBlank()
                ? " (" + fallbackAttempt.detail + ")"
                : ""));
        return Result.failed("version_query_failed",
            "vkEnumerateInstanceVersion failed with " + vkResultName(queryResultCode)
                + "; vkCreateInstance 1.0 failed with " + vkResultName(fallbackAttempt.resultCode())
                + (fallbackAttempt.detail != null && !fallbackAttempt.detail.isBlank()
                    ? " (" + fallbackAttempt.detail + ")"
                    : ""),
            maxInstanceApi,
            0,
            List.of());
    }

    private static Result diagnoseTooOldApi(int maxInstanceApi) {
        heartbeat("pre-vkCreateInstance-1.0.0-legacy");
        InstanceAttempt fallbackAttempt = tryCreateInstance(API_1_0);
        if (fallbackAttempt.instance != null) {
            try {
                List<DeviceInfo> devices = enumerateDeviceCandidates(fallbackAttempt.instance).stream()
                    .map(DeviceCandidate::info)
                    .toList();
                logDiagnostic("vkCreateInstance(1.0.0) -> VK_SUCCESS, devices=" + devices.size());
                return Result.failed("api_too_old",
                    "Vulkan 1.2 or newer required (detected " + formatVersion(maxInstanceApi)
                        + "; vkCreateInstance 1.0 succeeded with " + devices.size() + " device(s))",
                    maxInstanceApi,
                    API_1_0,
                    devices);
            } finally {
                VK10.vkDestroyInstance(fallbackAttempt.instance, null);
            }
        }
        logDiagnostic("vkCreateInstance(1.0.0) -> " + vkResultName(fallbackAttempt.resultCode())
            + (fallbackAttempt.detail != null && !fallbackAttempt.detail.isBlank()
                ? " (" + fallbackAttempt.detail + ")"
                : ""));
        return Result.failed("api_too_old",
            "Vulkan 1.2 or newer required (detected " + formatVersion(maxInstanceApi)
                + "; vkCreateInstance 1.0 failed with " + vkResultName(fallbackAttempt.resultCode())
                + (fallbackAttempt.detail != null && !fallbackAttempt.detail.isBlank()
                    ? " (" + fallbackAttempt.detail + ")"
                    : "") + ")",
            maxInstanceApi,
            0,
            List.of());
    }

    private static InstanceAttempt tryCreateInstance(int apiVersion) {
        VkApplicationInfo appInfo = null;
        VkInstanceCreateInfo createInfo = null;
        ByteBuffer applicationName = null;
        ByteBuffer engineName = null;
        PointerBuffer instancePtr = null;
        try {
            applicationName = MemoryUtil.memUTF8("QuantifiedProbe");
            engineName = MemoryUtil.memUTF8("QuantifiedProbe");
            appInfo = VkApplicationInfo.calloc()
                .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(applicationName)
                .applicationVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                .pEngineName(engineName)
                .engineVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                .apiVersion(apiVersion);

            createInfo = VkInstanceCreateInfo.calloc()
                .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            instancePtr = MemoryUtil.memAllocPointer(1);
            int result = VK10.vkCreateInstance(createInfo, null, instancePtr);
            if (result != VK10.VK_SUCCESS) {
                return new InstanceAttempt(null, result, null);
            }
            return new InstanceAttempt(new VkInstance(instancePtr.get(0), createInfo), VK10.VK_SUCCESS, null);
        } catch (Throwable throwable) {
            return new InstanceAttempt(null, VK10.VK_ERROR_INITIALIZATION_FAILED,
                throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        } finally {
            if (instancePtr != null) {
                MemoryUtil.memFree(instancePtr);
            }
            if (createInfo != null) {
                createInfo.free();
            }
            if (appInfo != null) {
                appInfo.free();
            }
            if (engineName != null) {
                MemoryUtil.memFree(engineName);
            }
            if (applicationName != null) {
                MemoryUtil.memFree(applicationName);
            }
        }
    }

    private static List<DeviceCandidate> enumerateDeviceCandidates(VkInstance instance) {
        IntBuffer deviceCount = null;
        PointerBuffer devices = null;
        try {
            deviceCount = MemoryUtil.memAllocInt(1);
            int firstResult = VK10.vkEnumeratePhysicalDevices(instance, deviceCount, null);
            if (firstResult != VK10.VK_SUCCESS || deviceCount.get(0) <= 0) {
                return List.of();
            }
            devices = MemoryUtil.memAllocPointer(deviceCount.get(0));
            int secondResult = VK10.vkEnumeratePhysicalDevices(instance, deviceCount, devices);
            if (secondResult != VK10.VK_SUCCESS) {
                return List.of();
            }
            List<DeviceCandidate> result = new ArrayList<>(devices.capacity());
            for (int i = 0; i < devices.capacity(); i++) {
                long handle = devices.get(i);
                VkPhysicalDevice physicalDevice = new VkPhysicalDevice(handle, instance);
                int computeQueueFamily = findComputeQueueFamily(physicalDevice);
                if (computeQueueFamily < 0) {
                    continue;
                }
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc();
                try {
                    VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);
                    String deviceName = properties.deviceNameString();
                    int vendorId = properties.vendorID();
                    String vendorName = vendorName(vendorId, deviceName);
                    int deviceType = properties.deviceType();
                    long localMemoryBytes = queryDeviceLocalMemory(physicalDevice);
                    boolean softwareAdapter = isSoftwareAdapter(deviceName, vendorName, deviceType);
                    DeviceInfo info = new DeviceInfo(
                        buildDeviceId(vendorName, deviceName),
                        deviceName,
                        vendorName,
                        deviceType,
                        localMemoryBytes,
                        softwareAdapter
                    );
                    result.add(new DeviceCandidate(
                        physicalDevice,
                        computeQueueFamily,
                        info,
                        scoreDevice(deviceType, localMemoryBytes, softwareAdapter)
                    ));
                } finally {
                    properties.free();
                }
            }
            result.sort(Comparator.comparingDouble(DeviceCandidate::score).reversed());
            return result;
        } finally {
            if (devices != null) {
                MemoryUtil.memFree(devices);
            }
            if (deviceCount != null) {
                MemoryUtil.memFree(deviceCount);
            }
        }
    }

    private static BringUpAttempt validateLogicalDeviceBringUp(List<DeviceCandidate> candidates, int apiVersion) {
        if (candidates.isEmpty()) {
            return BringUpAttempt.failed("no compute-capable physical devices found");
        }
        StringBuilder failures = new StringBuilder();
        for (DeviceCandidate candidate : candidates) {
            BringUpAttempt attempt = tryBringUpLogicalDevice(candidate, apiVersion);
            logDiagnostic("vkCreateDevice(" + candidate.info().name() + ") -> " + attempt.detail());
            if (attempt.ok()) {
                return attempt;
            }
            if (failures.length() > 0) {
                failures.append(", ");
            }
            failures.append(candidate.info().name()).append(": ").append(attempt.detail());
        }
        return BringUpAttempt.failed(failures.toString());
    }

    private static BringUpAttempt tryBringUpLogicalDevice(DeviceCandidate candidate, int apiVersion) {
        FloatBuffer priorities = null;
        VkDeviceQueueCreateInfo.Buffer queueInfos = null;
        VkDeviceQueueCreateInfo queueInfo = null;
        VkPhysicalDeviceFeatures features = null;
        VkDeviceCreateInfo deviceInfo = null;
        PointerBuffer devicePtr = null;
        LongBuffer commandPoolPtr = null;
        PointerBuffer queuePtr = null;
        VkDevice device = null;
        long commandPool = MemoryUtil.NULL;
        try {
            priorities = MemoryUtil.memAllocFloat(1).put(0, 1.0f);
            queueInfos = VkDeviceQueueCreateInfo.calloc(1);
            queueInfo = queueInfos.get(0)
                .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(candidate.computeQueueFamily())
                .pQueuePriorities(priorities);
            features = VkPhysicalDeviceFeatures.calloc();
            deviceInfo = VkDeviceCreateInfo.calloc()
                .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueInfos)
                .pEnabledFeatures(features);
            devicePtr = MemoryUtil.memAllocPointer(1);
            int createDeviceResult = VK10.vkCreateDevice(candidate.physicalDevice(), deviceInfo, null, devicePtr);
            if (createDeviceResult != VK10.VK_SUCCESS) {
                return BringUpAttempt.failed("vkCreateDevice returned " + vkResultName(createDeviceResult));
            }

            device = new VkDevice(devicePtr.get(0), candidate.physicalDevice(), deviceInfo);
            queuePtr = MemoryUtil.memAllocPointer(1);
            VK10.vkGetDeviceQueue(device, candidate.computeQueueFamily(), 0, queuePtr);
            VkQueue queue = new VkQueue(queuePtr.get(0), device);
            if (queue.address() == MemoryUtil.NULL) {
                return BringUpAttempt.failed("vkGetDeviceQueue returned NULL");
            }

            commandPoolPtr = MemoryUtil.memAllocLong(1);
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc()
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(candidate.computeQueueFamily())
                .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            try {
                int commandPoolResult = VK10.vkCreateCommandPool(device, poolInfo, null, commandPoolPtr);
                if (commandPoolResult != VK10.VK_SUCCESS) {
                    return BringUpAttempt.failed("vkCreateCommandPool returned " + vkResultName(commandPoolResult));
                }
                commandPool = commandPoolPtr.get(0);
            } finally {
                poolInfo.free();
            }

            return BringUpAttempt.ok(candidate.info().name());
        } catch (Throwable throwable) {
            return BringUpAttempt.failed(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        } finally {
            if (device != null) {
                try {
                    if (commandPool != MemoryUtil.NULL) {
                        VK10.vkDestroyCommandPool(device, commandPool, null);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    VK10.vkDestroyDevice(device, null);
                } catch (Throwable ignored) {
                }
            }
            if (queuePtr != null) {
                MemoryUtil.memFree(queuePtr);
            }
            if (commandPoolPtr != null) {
                MemoryUtil.memFree(commandPoolPtr);
            }
            if (devicePtr != null) {
                MemoryUtil.memFree(devicePtr);
            }
            if (deviceInfo != null) {
                deviceInfo.free();
            }
            if (features != null) {
                features.free();
            }
            if (queueInfos != null) {
                queueInfos.free();
            }
            if (priorities != null) {
                MemoryUtil.memFree(priorities);
            }
        }
    }

    private static int findComputeQueueFamily(VkPhysicalDevice device) {
        IntBuffer count = null;
        VkQueueFamilyProperties.Buffer families = null;
        try {
            count = MemoryUtil.memAllocInt(1);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
            if (count.get(0) <= 0) {
                return -1;
            }
            families = VkQueueFamilyProperties.calloc(count.get(0));
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
            for (int i = 0; i < families.capacity(); i++) {
                if ((families.get(i).queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) != 0) {
                    return i;
                }
            }
            return -1;
        } finally {
            if (families != null) {
                families.free();
            }
            if (count != null) {
                MemoryUtil.memFree(count);
            }
        }
    }

    private static long queryDeviceLocalMemory(VkPhysicalDevice physicalDevice) {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc();
        try {
            VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
            long bytes = 0L;
            for (int i = 0; i < properties.memoryHeapCount(); i++) {
                if ((properties.memoryHeaps(i).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    bytes += properties.memoryHeaps(i).size();
                }
            }
            return bytes;
        } finally {
            properties.free();
        }
    }

    private static int[] candidateApiVersions(int maxInstanceApi) {
        int[] preferred = new int[]{API_1_3, API_1_2};
        int supported = 0;
        for (int apiVersion : preferred) {
            if (compare(maxInstanceApi, apiVersion) >= 0) {
                supported++;
            }
        }
        int[] candidates = new int[supported];
        int index = 0;
        for (int apiVersion : preferred) {
            if (compare(maxInstanceApi, apiVersion) >= 0) {
                candidates[index++] = apiVersion;
            }
        }
        return candidates;
    }

    private static VersionQuery queryInstanceApiVersion() {
        IntBuffer version = null;
        try {
            version = MemoryUtil.memAllocInt(1);
            version.put(0, API_1_0);
            int result = VK11.vkEnumerateInstanceVersion(version);
            if (result == VK10.VK_SUCCESS) {
                return new VersionQuery(result, version.get(0));
            }
            return new VersionQuery(result, API_1_0);
        } catch (Throwable ignored) {
        } finally {
            if (version != null) {
                MemoryUtil.memFree(version);
            }
        }
        return new VersionQuery(VK10.VK_ERROR_INITIALIZATION_FAILED, API_1_0);
    }

    private static int vkApiVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    private static int compare(int left, int right) {
        return Integer.compareUnsigned(left, right);
    }

    private static String formatVersion(int apiVersion) {
        int major = (apiVersion >>> 22) & 0x7F;
        int minor = (apiVersion >>> 12) & 0x3FF;
        int patch = apiVersion & 0xFFF;
        return major + "." + minor + "." + patch;
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            case VK10.VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK10.VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            default -> "VK_RESULT_" + result;
        };
    }

    private static String vendorName(int vendorId, String deviceName) {
        return switch (vendorId) {
            case 0x10DE -> "NVIDIA";
            case 0x1002, 0x1022 -> "AMD";
            case 0x8086 -> "Intel";
            case 0x1414 -> "Microsoft";
            default -> inferVendorFromName(deviceName);
        };
    }

    private static String inferVendorFromName(String deviceName) {
        if (deviceName == null) {
            return "Unknown";
        }
        String lowered = deviceName.toLowerCase(Locale.ROOT);
        if (lowered.contains("nvidia") || lowered.contains("geforce") || lowered.contains("quadro")) {
            return "NVIDIA";
        }
        if (lowered.contains("radeon") || lowered.contains("amd")) {
            return "AMD";
        }
        if (lowered.contains("intel") || lowered.contains("iris")) {
            return "Intel";
        }
        if (lowered.contains("microsoft")) {
            return "Microsoft";
        }
        return "Unknown";
    }

    private static boolean isSoftwareAdapter(String deviceName, String vendorName, int deviceType) {
        if (deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_CPU) {
            return true;
        }
        String loweredName = deviceName == null ? "" : deviceName.toLowerCase(Locale.ROOT);
        String loweredVendor = vendorName == null ? "" : vendorName.toLowerCase(Locale.ROOT);
        return loweredName.contains("microsoft basic render")
            || loweredName.contains("swiftshader")
            || loweredName.contains("lavapipe")
            || loweredVendor.contains("microsoft");
    }

    private static String buildDeviceId(String vendorName, String deviceName) {
        String combined = ((vendorName != null ? vendorName : "") + "-" + (deviceName != null ? deviceName : "")).trim();
        String normalized = combined.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        return normalized.isBlank() ? "unknown-vulkan-device" : normalized;
    }

    private static double scoreDevice(int deviceType, long localMemoryBytes, boolean softwareAdapter) {
        double score = localMemoryBytes;
        if (softwareAdapter) {
            score -= 10_000_000_000d;
        }
        if (deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            score += 3_000_000_000d;
        } else if (deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
            score += 1_000_000_000d;
        }
        return score;
    }

    private static String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "Unknown Vulkan bootstrap failure";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message != null && !message.isBlank() ? ": " + message : "");
    }

    private static void logDiagnostic(String message) {
        System.out.println("[probe] " + message);
        System.out.flush();
    }

    private static void heartbeat(String stage) {
        System.out.println("[probe] [heartbeat] " + stage);
        System.out.flush();
    }

    private static Optional<String> detectLoaderPath() {
        try {
            Process process = new ProcessBuilder("where", "vulkan-1.dll")
                .redirectErrorStream(true)
                .start();
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor();
            String[] lines = output.split("\\R");
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return Optional.of(trimmed);
                }
            }
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return Optional.empty();
    }

    private record InstanceAttempt(VkInstance instance, int resultCode, String detail) {
    }

    private record DeviceCandidate(VkPhysicalDevice physicalDevice,
                                   int computeQueueFamily,
                                   DeviceInfo info,
                                   double score) {
    }

    private record BringUpAttempt(boolean ok, String detail) {
        private static BringUpAttempt ok(String deviceName) {
            return new BringUpAttempt(true, "VK_SUCCESS on " + deviceName);
        }

        private static BringUpAttempt failed(String detail) {
            return new BringUpAttempt(false, detail);
        }
    }

    private record VersionQuery(int resultCode, int version) {
    }

    private record DeviceInfo(String id,
                              String name,
                              String vendor,
                              int deviceType,
                              long localMemoryBytes,
                              boolean softwareAdapter) {

        private String toJson() {
            return new StringBuilder(192)
                .append('{')
                .append("\"id\":\"").append(escape(id)).append('"')
                .append(",\"name\":\"").append(escape(name)).append('"')
                .append(",\"vendor\":\"").append(escape(vendor)).append('"')
                .append(",\"deviceType\":").append(deviceType)
                .append(",\"localMemoryBytes\":").append(localMemoryBytes)
                .append(",\"softwareAdapter\":").append(softwareAdapter)
                .append('}')
                .toString();
        }
    }

    private record Result(boolean ok,
                          String code,
                          String failure,
                          int maxApiVersion,
                          int selectedApiVersion,
                          List<DeviceInfo> devices) {

        static Result success(int maxApiVersion, int selectedApiVersion, List<DeviceInfo> devices) {
            return new Result(true, "ok", null, maxApiVersion, selectedApiVersion, devices);
        }

        static Result failed(String code, String failure, int maxApiVersion, int selectedApiVersion, List<DeviceInfo> devices) {
            return new Result(false, code, failure, maxApiVersion, selectedApiVersion, devices);
        }

        String toJson() {
            StringBuilder builder = new StringBuilder(512);
            builder.append('{')
                .append("\"ok\":").append(ok)
                .append(",\"code\":\"").append(escape(code)).append('"')
                .append(",\"maxApiVersion\":").append(maxApiVersion)
                .append(",\"selectedApiVersion\":").append(selectedApiVersion)
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
