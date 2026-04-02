package org.admany.quantified.core.common.vulkan.core;

public final class VulkanContext {

    private final VulkanManager manager;

    VulkanContext(VulkanManager manager) {
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

    public String deviceName() {
        return VulkanManager.deviceName();
    }
}
