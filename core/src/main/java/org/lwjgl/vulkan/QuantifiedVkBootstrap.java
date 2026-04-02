package org.lwjgl.vulkan;

import org.lwjgl.system.Checks;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;

import static org.lwjgl.system.APIUtil.apiLog;
import static org.lwjgl.system.JNI.callPPP;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;

/**
 * Builds Vulkan dispatch handles without triggering LWJGL's stack-heavy capability bootstrap.
 */
public final class QuantifiedVkBootstrap {

    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final long POINTER_ADDRESS_OFFSET = objectFieldOffset(Pointer.Default.class, "address");
    private static final long INSTANCE_CAPABILITIES_OFFSET = objectFieldOffset(DispatchableHandleInstance.class, "capabilities");
    private static final long DEVICE_CAPABILITIES_OFFSET = objectFieldOffset(DispatchableHandleDevice.class, "capabilities");
    private static final long DEVICE_PHYSICAL_DEVICE_OFFSET = objectFieldOffset(VkDevice.class, "physicalDevice");

    private QuantifiedVkBootstrap() {
    }

    public static VkInstance wrapInstance(long handle, VkInstanceCreateInfo createInfo) {
        int apiVersion = resolveApiVersion(createInfo);
        VKCapabilitiesInstance capabilities = new VKCapabilitiesInstance(
            instanceFunctionProvider(handle),
            apiVersion,
            VK.getEnabledExtensionSet(apiVersion, createInfo.ppEnabledExtensionNames()),
            Collections.emptySet()
        );
        try {
            VkInstance instance = (VkInstance) UNSAFE.allocateInstance(VkInstance.class);
            UNSAFE.putLong(instance, POINTER_ADDRESS_OFFSET, handle);
            UNSAFE.putObject(instance, INSTANCE_CAPABILITIES_OFFSET, capabilities);
            return instance;
        } catch (InstantiationException e) {
            throw new IllegalStateException("Failed to allocate Vulkan instance wrapper", e);
        }
    }

    public static VkDevice wrapDevice(long handle, VkPhysicalDevice physicalDevice, VkDeviceCreateInfo createInfo, int apiVersionOverride) {
        int apiVersion = apiVersionOverride != 0 ? apiVersionOverride : resolveDeviceApiVersion(physicalDevice);
        long getDeviceProcAddr = resolveDeviceProcAddr(physicalDevice.getInstance());
        VKCapabilitiesDevice capabilities = new VKCapabilitiesDevice(
            deviceFunctionProvider(handle, getDeviceProcAddr),
            physicalDevice.getCapabilities(),
            apiVersion,
            VK.getEnabledExtensionSet(apiVersion, createInfo.ppEnabledExtensionNames())
        );
        try {
            VkDevice device = (VkDevice) UNSAFE.allocateInstance(VkDevice.class);
            UNSAFE.putLong(device, POINTER_ADDRESS_OFFSET, handle);
            UNSAFE.putObject(device, DEVICE_CAPABILITIES_OFFSET, capabilities);
            UNSAFE.putObject(device, DEVICE_PHYSICAL_DEVICE_OFFSET, physicalDevice);
            return device;
        } catch (InstantiationException e) {
            throw new IllegalStateException("Failed to allocate Vulkan device wrapper", e);
        }
    }

    private static int resolveApiVersion(VkInstanceCreateInfo createInfo) {
        VkApplicationInfo appInfo = createInfo.pApplicationInfo();
        return appInfo != null && appInfo.apiVersion() != 0 ? appInfo.apiVersion() : VK_API_VERSION_1_0;
    }

    private static int resolveDeviceApiVersion(VkPhysicalDevice physicalDevice) {
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc();
        try {
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);
            return Math.min(properties.apiVersion(), physicalDevice.getInstance().getCapabilities().apiVersion) & 0xFFFFF000;
        } finally {
            properties.free();
        }
    }

    private static long resolveDeviceProcAddr(VkInstance instance) {
        ByteBuffer functionName = MemoryUtil.memASCII("vkGetDeviceProcAddr");
        try {
            long address = callPPP(instance.address(), memAddress(functionName), VK.getGlobalCommands().vkGetInstanceProcAddr);
            if (address == NULL) {
                throw new IllegalStateException("Failed to locate vkGetDeviceProcAddr");
            }
            return address;
        } finally {
            MemoryUtil.memFree(functionName);
        }
    }

    private static FunctionProvider instanceFunctionProvider(long instanceHandle) {
        return functionName -> {
            long address = callPPP(instanceHandle, memAddress(functionName), VK.getGlobalCommands().vkGetInstanceProcAddr);
            if (address == NULL && Checks.DEBUG_FUNCTIONS) {
                apiLog("Failed to locate address for VK instance function " + MemoryUtil.memASCII(functionName));
            }
            return address;
        };
    }

    private static FunctionProvider deviceFunctionProvider(long deviceHandle, long getDeviceProcAddr) {
        return functionName -> {
            long address = callPPP(deviceHandle, memAddress(functionName), getDeviceProcAddr);
            if (address == NULL && Checks.DEBUG_FUNCTIONS) {
                apiLog("Failed to locate address for VK device function " + MemoryUtil.memASCII(functionName));
            }
            return address;
        };
    }

    private static long objectFieldOffset(Class<?> owner, String fieldName) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            return UNSAFE.objectFieldOffset(field);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
