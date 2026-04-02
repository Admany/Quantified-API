package org.admany.quantified.core.common.vulkan.core;

import org.admany.quantified.api.vulkan.QuantifiedVulkan;

public final class ApiVulkanTaskWrapper<T> extends VulkanTask<T> {

    private final QuantifiedVulkan.ApiVulkanTask<T> apiTask;

    public ApiVulkanTaskWrapper(QuantifiedVulkan.ApiVulkanTask<T> apiTask) {
        super(apiTask.modId(), apiTask.name(), apiTask.taskKey(), apiTask.cpuFallback(), apiTask.timeout().orElse(null));
        this.apiTask = apiTask;
    }

    @Override
    public long estimatedVramBytes() {
        return apiTask.estimatedVramBytes();
    }

    @Override
    public int estimatedComputeUnits() {
        return apiTask.estimatedComputeUnits();
    }

    @Override
    public T executeOnGPU(VulkanContext context) {
        try {
            return apiTask.executeOnGPU(context);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Vulkan workload failed", exception);
        }
    }
}
