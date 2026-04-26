package org.admany.quantified.core.common.vulkan.core;

import java.util.List;

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

    public float[] mcDensityFunctions(float[] packedCoords, float[] encodedProgram, int instructionCount) {
        return manager.executeMcDensityFunctions(packedCoords, encodedProgram, instructionCount);
    }

    public float[] mcDensityFunctions(float[] packedCoords,
                                      float[] encodedProgram,
                                      int instructionCount,
                                      float[] auxValues,
                                      int auxValueCount) {
        return manager.executeMcDensityFunctions(packedCoords, encodedProgram, instructionCount, auxValues, auxValueCount);
    }

    public float[][] mcDensityFunctionsBatch(List<McDensityVulkanTask> tasks) {
        return manager.executeMcDensityFunctionBatch(tasks);
    }

    public String deviceName() {
        return VulkanManager.deviceName();
    }
}
