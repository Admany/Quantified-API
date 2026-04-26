package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Predicate;

public final class PathfindingDistanceFieldTask extends OpenCLTask<float[][]> {

    private final Object[] targetPositions;

    public PathfindingDistanceFieldTask(String modId, String name, long taskKey,
                                        Object chunk, Object[] targetPositions,
                                        Predicate<Object> passablePredicate, int maxDistance) {
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
        private final Object chunk;
        private final Object[] targetPositions;
        private final Predicate<Object> passablePredicate;
        private final int maxDistance;

        private Builder(String modId, String name, long taskKey,
                        Object chunk, Object[] targetPositions,
                        Predicate<Object> passablePredicate, int maxDistance) {
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

    private static float[][] computeDistanceFieldCPU(Object chunk,
                                                     Object[] targetPositions,
                                                     Predicate<Object> passablePredicate,
                                                     int maxDistance) {
        float[][] distanceField = new float[16][16];
        boolean[][] visited = new boolean[16][16];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                distanceField[x][z] = maxDistance;
            }
        }

        Queue<GridPos> queue = new LinkedList<>();
        for (Object target : targetPositions) {
            GridPos pos = readGridPos(target);
            if (pos.x >= 0 && pos.x < 16 && pos.z >= 0 && pos.z < 16) {
                distanceField[pos.x][pos.z] = 0;
                visited[pos.x][pos.z] = true;
                queue.add(pos);
            }
        }

        try {
            Method getBlockState = chunk.getClass().getMethod("getBlockState", blockPosClass());
            Method getMaxBuildHeight = chunk.getClass().getMethod("getMaxBuildHeight");
            Constructor<?> blockPosCtor = blockPosClass().getConstructor(int.class, int.class, int.class);
            int maxBuildHeight = ((Number) getMaxBuildHeight.invoke(chunk)).intValue();
            int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

            while (!queue.isEmpty()) {
                GridPos current = queue.poll();
                float currentDist = distanceField[current.x][current.z];

                for (int[] dir : directions) {
                    int newX = current.x + dir[0];
                    int newZ = current.z + dir[1];

                    if (newX < 0 || newX >= 16 || newZ < 0 || newZ >= 16 || visited[newX][newZ]) {
                        continue;
                    }

                    int y = distanceField[newX][newZ] == maxDistance
                        ? maxBuildHeight - 1
                        : (int) distanceField[newX][newZ];
                    Object state = getBlockState.invoke(chunk, blockPosCtor.newInstance(newX, y, newZ));
                    if (passablePredicate.test(state)) {
                        visited[newX][newZ] = true;
                        distanceField[newX][newZ] = currentDist + 1;
                        queue.add(new GridPos(newX, newZ));
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read Minecraft chunk pathing data reflectively", e);
        }

        return distanceField;
    }

    private static GridPos readGridPos(Object pos) {
        try {
            int x = ((Number) pos.getClass().getMethod("getX").invoke(pos)).intValue();
            int z = ((Number) pos.getClass().getMethod("getZ").invoke(pos)).intValue();
            return new GridPos(x, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Target position must expose getX() and getZ()", e);
        }
    }

    private static Class<?> blockPosClass() throws ClassNotFoundException {
        return Class.forName("net.minecraft.core.BlockPos");
    }

    private record GridPos(int x, int z) {
    }
}
