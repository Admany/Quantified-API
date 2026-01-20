package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;

public final class TerrainScanTask extends OpenCLTask<Integer> {

    private final int[] blockData;


    public TerrainScanTask(String modId, String name, long taskKey, int[] blockData, int targetBlockId) {
        super(new Builder(modId, name, taskKey, blockData, targetBlockId));
        this.blockData = blockData.clone();

    }    

    @Override
    public long estimatedVramBytes() {
        return blockData.length * 4L * 2; // input + output
    }

    @Override
    public int estimatedComputeUnits() {
        return Math.max(1, blockData.length / 1000); // Very parallel
    }

    @Override
    public Integer executeOnGPU(OpenCLContext context) {
        try {
            return cpuFallback().get();
        } catch (Exception e) {
            return cpuFallback().get();
        }
    }

    public static final class Builder extends OpenCLTask.Builder<Integer> {
        private final int[] blockData;
        private final int targetBlockId;

        public Builder(String modId, String name, long taskKey, int[] blockData, int targetBlockId) {
            super(modId, name, taskKey, () -> scanBlocksCPU(blockData, targetBlockId));
            this.blockData = blockData;
            this.targetBlockId = targetBlockId;
        }

        @Override
        public TerrainScanTask build() {
            return new TerrainScanTask(modId, name, taskKey, blockData, targetBlockId);
        }
    }

    private static int scanBlocksCPU(int[] blockData, int targetBlockId) {
        int count = 0;
        for (int blockId : blockData) {
            if (blockId == targetBlockId) {
                count++;
            }
        }
        return count;
    }

    // Convenience methods
    public static Builder scanForBlock(String modId, String name, long taskKey, int[] blockData, int targetBlockId) {
        return new Builder(modId, name, taskKey, blockData, targetBlockId);
    }
}