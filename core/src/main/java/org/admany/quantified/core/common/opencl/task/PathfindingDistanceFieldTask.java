package org.admany.quantified.core.common.opencl.task;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import java.util.function.Predicate;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;

public final class PathfindingDistanceFieldTask extends OpenCLTask<float[][]> {

    private final BlockPos[] targetPositions;


    public PathfindingDistanceFieldTask(String modId, String name, long taskKey,
                                        LevelChunk chunk, BlockPos[] targetPositions,
                                        Predicate<BlockState> passablePredicate, int maxDistance) {
        super(new Builder(modId, name, taskKey, chunk, targetPositions, passablePredicate, maxDistance));
        this.targetPositions = targetPositions;

    }

    @Override
    public long estimatedVramBytes() {
        return 16L * 16L * 4L * 2; 
    }

    @Override
    public int estimatedComputeUnits() {
        return Math.max(1, 16 * 16 * targetPositions.length / 10000);
    }

    @Override
    public float[][] executeOnGPU(OpenCLContext context) {
        return cpuFallback().get();
    }

    private static final class Builder extends OpenCLTask.Builder<float[][]> {
        private final LevelChunk chunk;
        private final BlockPos[] targetPositions;
        private final Predicate<BlockState> passablePredicate;
        private final int maxDistance;

        public Builder(String modId, String name, long taskKey,
                      LevelChunk chunk, BlockPos[] targetPositions,
                      Predicate<BlockState> passablePredicate, int maxDistance) {
            super(modId, name, taskKey, () -> computeDistanceFieldCPU(chunk, targetPositions, passablePredicate, maxDistance));
            this.chunk = chunk;
            this.targetPositions = targetPositions;
            this.passablePredicate = passablePredicate;
            this.maxDistance = maxDistance;
        }

        @Override
        public PathfindingDistanceFieldTask build() {
            return new PathfindingDistanceFieldTask(modId, name, taskKey, chunk, targetPositions, passablePredicate, maxDistance);
        }
    }

    private static float[][] computeDistanceFieldCPU(LevelChunk chunk,
                                                     BlockPos[] targetPositions,
                                                     Predicate<BlockState> passablePredicate,
                                                     int maxDistance) {
        float[][] distanceField = new float[16][16];
        boolean[][] visited = new boolean[16][16];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                distanceField[x][z] = maxDistance;
            }
        }

        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();

        for (BlockPos target : targetPositions) {
            if (target.getX() >= 0 && target.getX() < 16 &&
                target.getZ() >= 0 && target.getZ() < 16) {
                int localX = target.getX();
                int localZ = target.getZ();
                distanceField[localX][localZ] = 0;
                visited[localX][localZ] = true;
                queue.add(target);
            }
        }

        int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int currentX = current.getX();
            int currentZ = current.getZ();
            float currentDist = distanceField[currentX][currentZ];

            for (int[] dir : directions) {
                int newX = currentX + dir[0];
                int newZ = currentZ + dir[1];

                if (newX >= 0 && newX < 16 && newZ >= 0 && newZ < 16 &&
                    !visited[newX][newZ]) {

                    BlockState state = chunk.getBlockState(new BlockPos(newX, distanceField[newX][newZ] == maxDistance ? chunk.getMaxBuildHeight() - 1 : (int)distanceField[newX][newZ], newZ));
                    if (passablePredicate.test(state)) {
                        visited[newX][newZ] = true;
                        distanceField[newX][newZ] = currentDist + 1;
                        queue.add(new BlockPos(newX, 0, newZ));
                    }
                }
            }
        }

        return distanceField;
    }
}