package org.admany.quantified.core.common.dev.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class MemoryCore {

    private static final ThreadLocal<BlockPosPool> blockPosPool = ThreadLocal.withInitial(BlockPosPool::new);
    private static final ThreadLocal<BlockStatePool> blockStatePool = ThreadLocal.withInitial(BlockStatePool::new);
    private static final ConcurrentHashMap<BlockState, Integer> blockStateFrequency = new ConcurrentHashMap<>();

    private MemoryCore() {}

    public static BlockPosPool getBlockPosPool() {
        return blockPosPool.get();
    }

    public static BlockStatePool getBlockStatePool() {
        return blockStatePool.get();
    }

    public static void recordBlockStateUsage(BlockState state) {
        blockStateFrequency.merge(state, 1, Integer::sum);
    }


    public static BlockState getFrequentBlockState() {
        return blockStateFrequency.entrySet().stream()
            .max(java.util.Map.Entry.comparingByValue())
            .map(java.util.Map.Entry::getKey)
            .orElse(null);
    }

    public static class BlockPosPool {
        private final ConcurrentLinkedQueue<BlockPos.MutableBlockPos> pool = new ConcurrentLinkedQueue<>();

        public BlockPos.MutableBlockPos acquire(int x, int y, int z) {
            BlockPos.MutableBlockPos pos = pool.poll();
            if (pos == null) {
                pos = new BlockPos.MutableBlockPos();
            }
            pos.set(x, y, z);
            return pos;
        }

        public void release(BlockPos.MutableBlockPos pos) {
            pool.offer(pos);
        }

        public int size() {
            return pool.size();
        }
    }

    public static class BlockStatePool {
        private final ConcurrentLinkedQueue<BlockState> pool = new ConcurrentLinkedQueue<>();

        public BlockState acquire() {
            return pool.poll();
        }

        public void release(BlockState state) {
            if (state != null) {
                pool.offer(state);
            }
        }

        public int size() {
            return pool.size();
        }
    }
}