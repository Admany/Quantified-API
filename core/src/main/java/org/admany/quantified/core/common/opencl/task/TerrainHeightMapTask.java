package org.admany.quantified.core.common.opencl.task;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import java.util.function.Predicate;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;

public final class TerrainHeightMapTask extends OpenCLTask<int[][]> {
    private final int minHeight;
    private final int maxHeight;

    public TerrainHeightMapTask(String modId, String name, long taskKey,
                                LevelChunk chunk, Predicate<BlockState> surfacePredicate,
                                int minHeight, int maxHeight) {
        super(new Builder(modId, name, taskKey, chunk, surfacePredicate, minHeight, maxHeight));
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
    }

    @Override
    public long estimatedVramBytes() {
        return 16L * 16L * 4L * 2; 
    }

    @Override
    public int estimatedComputeUnits() {
        return Math.max(1, (maxHeight - minHeight) * 16 * 16 / 50000);
    }

    @Override
    public int[][] executeOnGPU(OpenCLContext context) {
        return cpuFallback().get();
    }

    private static final class Builder extends OpenCLTask.Builder<int[][]> {
        private final LevelChunk chunk;
        private final Predicate<BlockState> surfacePredicate;
        private final int minHeight;
        private final int maxHeight;

        public Builder(String modId, String name, long taskKey,
                      LevelChunk chunk, Predicate<BlockState> surfacePredicate,
                      int minHeight, int maxHeight) {
            super(modId, name, taskKey, () -> generateHeightMapCPU(chunk, surfacePredicate, minHeight, maxHeight));
            this.chunk = chunk;
            this.surfacePredicate = surfacePredicate;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
        }

        @Override
        public TerrainHeightMapTask build() {
            return new TerrainHeightMapTask(modId, name, taskKey, chunk, surfacePredicate, minHeight, maxHeight);
        }
    }

    private static int[][] generateHeightMapCPU(LevelChunk chunk,
                                                Predicate<BlockState> surfacePredicate,
                                                int minHeight, int maxHeight) {
        int[][] heightMap = new int[16][16];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = maxHeight; y >= minHeight; y--) {
                    BlockState state = chunk.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                    if (surfacePredicate.test(state)) {
                        heightMap[x][z] = y;
                        break;
                    }
                }
            }
        }

        return heightMap;
    }

    public static TerrainHeightMapTask createSurfaceHeightMap(String modId, String name, long taskKey,
                                                              LevelChunk chunk, int minHeight, int maxHeight) {
        return new TerrainHeightMapTask(modId, name, taskKey, chunk,
            state -> !state.isAir() && state.getBlock() != Blocks.WATER && state.getBlock() != Blocks.LAVA,
            minHeight, maxHeight);
    }
}