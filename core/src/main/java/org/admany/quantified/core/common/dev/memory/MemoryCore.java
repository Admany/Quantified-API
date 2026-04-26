package org.admany.quantified.core.common.dev.memory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class MemoryCore {

    private static final ThreadLocal<BlockPosPool> blockPosPool = ThreadLocal.withInitial(BlockPosPool::new);
    private static final ThreadLocal<BlockStatePool> blockStatePool = ThreadLocal.withInitial(BlockStatePool::new);
    private static final ConcurrentHashMap<Object, Integer> blockStateFrequency = new ConcurrentHashMap<>();

    private MemoryCore() {}

    public static BlockPosPool getBlockPosPool() {
        return blockPosPool.get();
    }

    public static BlockStatePool getBlockStatePool() {
        return blockStatePool.get();
    }

    public static void recordBlockStateUsage(Object state) {
        blockStateFrequency.merge(state, 1, Integer::sum);
    }

    public static Object getFrequentBlockState() {
        return blockStateFrequency.entrySet().stream()
            .max(java.util.Map.Entry.comparingByValue())
            .map(java.util.Map.Entry::getKey)
            .orElse(null);
    }

    public static class BlockPosPool {
        private final ConcurrentLinkedQueue<Object> pool = new ConcurrentLinkedQueue<>();

        public Object acquire(int x, int y, int z) {
            Object pos = pool.poll();
            if (pos == null) {
                pos = newMutableBlockPos();
            }
            setPosition(pos, x, y, z);
            return pos;
        }

        public void release(Object pos) {
            if (pos != null) {
                pool.offer(pos);
            }
        }

        public int size() {
            return pool.size();
        }
    }

    public static class BlockStatePool {
        private final ConcurrentLinkedQueue<Object> pool = new ConcurrentLinkedQueue<>();

        public Object acquire() {
            return pool.poll();
        }

        public void release(Object state) {
            if (state != null) {
                pool.offer(state);
            }
        }

        public int size() {
            return pool.size();
        }
    }

    private static Object newMutableBlockPos() {
        try {
            Class<?> mutable = Class.forName("net.minecraft.core.BlockPos$MutableBlockPos");
            Constructor<?> ctor = mutable.getConstructor();
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Minecraft MutableBlockPos is unavailable", e);
        }
    }

    private static void setPosition(Object pos, int x, int y, int z) {
        try {
            Method set = pos.getClass().getMethod("set", int.class, int.class, int.class);
            set.invoke(pos, x, y, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to update Minecraft MutableBlockPos reflectively", e);
        }
    }
}
