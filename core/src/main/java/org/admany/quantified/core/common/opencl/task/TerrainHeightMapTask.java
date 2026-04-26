package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Predicate;

public final class TerrainHeightMapTask extends OpenCLTask<int[][]> {
    private final int minHeight;
    private final int maxHeight;

    public TerrainHeightMapTask(String modId, String name, long taskKey,
                                Object chunk, Predicate<Object> surfacePredicate,
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
        private final Object chunk;
        private final Predicate<Object> surfacePredicate;
        private final int minHeight;
        private final int maxHeight;

        private Builder(String modId, String name, long taskKey,
                        Object chunk, Predicate<Object> surfacePredicate,
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

    private static int[][] generateHeightMapCPU(Object chunk,
                                                Predicate<Object> surfacePredicate,
                                                int minHeight, int maxHeight) {
        int[][] heightMap = new int[16][16];
        try {
            Method getBlockState = chunk.getClass().getMethod("getBlockState", blockPosClass());
            Constructor<?> blockPosCtor = blockPosClass().getConstructor(int.class, int.class, int.class);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = maxHeight; y >= minHeight; y--) {
                        Object state = getBlockState.invoke(chunk, blockPosCtor.newInstance(x, y, z));
                        if (surfacePredicate.test(state)) {
                            heightMap[x][z] = y;
                            break;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read Minecraft chunk heightmap reflectively", e);
        }
        return heightMap;
    }

    public static TerrainHeightMapTask createSurfaceHeightMap(String modId, String name, long taskKey,
                                                              Object chunk, int minHeight, int maxHeight) {
        return new TerrainHeightMapTask(modId, name, taskKey, chunk, TerrainHeightMapTask::isSolidSurface,
            minHeight, maxHeight);
    }

    private static boolean isSolidSurface(Object state) {
        if (state == null || invokeBoolean(state, "isAir")) {
            return false;
        }
        Object block = invokeObject(state, "getBlock");
        String blockName = String.valueOf(block).toLowerCase(Locale.ROOT);
        return !blockName.contains("water") && !blockName.contains("lava");
    }

    private static boolean invokeBoolean(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Boolean result && result;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static Object invokeObject(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Class<?> blockPosClass() throws ClassNotFoundException {
        return Class.forName("net.minecraft.core.BlockPos");
    }
}
