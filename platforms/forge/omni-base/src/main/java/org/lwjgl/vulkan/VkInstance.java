package org.lwjgl.vulkan;

import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import java.util.Collections;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;

/** Avoids LWJGL device-extension enumeration on the 64 KiB MemoryStack. */
public class VkInstance extends DispatchableHandleInstance {

    public VkInstance(long address, VkInstanceCreateInfo createInfo) {
        super(address, createInstanceCapabilities(address, createInfo));
    }

    private static VKCapabilitiesInstance createInstanceCapabilities(long address, VkInstanceCreateInfo createInfo) {
        int apiVersion = VK_API_VERSION_1_0;
        if (createInfo != null) {
            VkApplicationInfo applicationInfo = createInfo.pApplicationInfo();
            if (applicationInfo != null && applicationInfo.apiVersion() != 0) {
                apiVersion = applicationInfo.apiVersion();
            }
        }

        FunctionProvider functionProvider = functionName -> JNI.callPPP(
            address,
            MemoryUtil.memAddress(functionName),
            VK.getGlobalCommands().vkGetInstanceProcAddr
        );
        Set<String> enabledExtensions = VK.getEnabledExtensionSet(
            apiVersion,
            createInfo != null ? createInfo.ppEnabledExtensionNames() : null
        );
        return new VKCapabilitiesInstance(functionProvider, apiVersion, enabledExtensions, Collections.emptySet());
    }
}
