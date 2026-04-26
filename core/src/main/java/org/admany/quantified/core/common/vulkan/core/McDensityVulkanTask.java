package org.admany.quantified.core.common.vulkan.core;

import org.admany.quantified.api.vulkan.McDensityProgram;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

public final class McDensityVulkanTask extends VulkanTask<float[]> {

    private final float[] packedCoords;
    private final float[] encodedProgram;
    private final float[] auxValues;
    private final int instructionCount;
    private final int auxValueCount;
    private final int sampleCount;
    private final long estimatedVramBytes;

    public McDensityVulkanTask(String modId,
                               String name,
                               long taskKey,
                               float[] packedCoords,
                               float[] encodedProgram,
                               int instructionCount,
                               Supplier<float[]> cpuFallback,
                               Duration timeout) {
        this(modId, name, taskKey, packedCoords, encodedProgram, instructionCount, new float[0], 0, cpuFallback, timeout);
    }

    public McDensityVulkanTask(String modId,
                               String name,
                               long taskKey,
                               float[] packedCoords,
                               float[] encodedProgram,
                               int instructionCount,
                               float[] auxValues,
                               int auxValueCount,
                               Supplier<float[]> cpuFallback,
                               Duration timeout) {
        super(modId, name, taskKey, cpuFallback, timeout);
        Objects.requireNonNull(packedCoords, "packedCoords");
        Objects.requireNonNull(encodedProgram, "encodedProgram");
        Objects.requireNonNull(auxValues, "auxValues");
        if (packedCoords.length % 3 != 0) {
            throw new IllegalArgumentException("Packed coordinate array must be xyz triples");
        }
        int samples = packedCoords.length / 3;
        if (instructionCount <= 0) {
            throw new IllegalArgumentException("Instruction count must be positive: " + instructionCount);
        }
        if (auxValueCount < 0) {
            throw new IllegalArgumentException("Aux value count must be non-negative: " + auxValueCount);
        }
        if (auxValues.length < auxValueCount * samples) {
            throw new IllegalArgumentException("Aux value buffer is shorter than aux count: "
                + auxValues.length + " floats for " + auxValueCount + " aux values and " + samples + " samples");
        }
        int requiredProgramFloats = instructionCount * McDensityProgram.STRIDE;
        if (encodedProgram.length < requiredProgramFloats) {
            throw new IllegalArgumentException("Encoded program is shorter than instruction count: "
                + encodedProgram.length + " floats for " + instructionCount + " instructions");
        }
        this.packedCoords = packedCoords.clone();
        this.encodedProgram = Arrays.copyOf(encodedProgram, requiredProgramFloats);
        this.auxValues = Arrays.copyOf(auxValues, auxValueCount * samples);
        this.instructionCount = instructionCount;
        this.auxValueCount = auxValueCount;
        this.sampleCount = samples;
        this.estimatedVramBytes = (long) (this.packedCoords.length + this.encodedProgram.length
            + this.auxValues.length + this.sampleCount)
            * Float.BYTES;
    }

    @Override
    public long estimatedVramBytes() {
        return estimatedVramBytes;
    }

    @Override
    public int estimatedComputeUnits() {
        return Math.max(1, sampleCount);
    }

    @Override
    public float[] executeOnGPU(VulkanContext context) {
        return context.mcDensityFunctions(packedCoords, encodedProgram, instructionCount, auxValues, auxValueCount);
    }

    float[] packedCoords() {
        return packedCoords;
    }

    float[] encodedProgram() {
        return encodedProgram;
    }

    int instructionCount() {
        return instructionCount;
    }

    float[] auxValues() {
        return auxValues;
    }

    int auxValueCount() {
        return auxValueCount;
    }

    int sampleCount() {
        return sampleCount;
    }
}
