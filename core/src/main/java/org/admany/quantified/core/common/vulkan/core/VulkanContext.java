package org.admany.quantified.core.common.vulkan.core;

public final class VulkanContext {

    private final VulkanInProcessManager manager;

    VulkanContext(VulkanInProcessManager manager) {
        this.manager = manager;
    }

    public float[] vectorAdd(float[] a, float[] b) {
        return manager.executeVectorAdd(a, b);
    }

    public float[][] matrixMultiply(float[][] a, float[][] b) {
        return manager.executeMatrixMultiply(a, b);
    }

    public double monteCarloPi(int samples) {
        return manager.executeMonteCarloPi(samples);
    }

    public float[] terrainGeneration(float[] inputCoords) {
        return manager.executeTerrainGeneration(inputCoords);
    }

    public float[] dispatchSpirv(String programKey,
                                 byte[] spirv,
                                 int storageBufferCount,
                                 int pushConstantBytes,
                                 float[][] inputBuffers,
                                 int outputFloatCount,
                                 int[] pushConstants,
                                 int groupCountX,
                                 int groupCountY,
                                 int groupCountZ) {
        return manager.executeSpirv(programKey, spirv, storageBufferCount, pushConstantBytes, inputBuffers,
            outputFloatCount, pushConstants, groupCountX, groupCountY, groupCountZ);
    }

    public String deviceName() {
        return VulkanManager.deviceName();
    }
}
