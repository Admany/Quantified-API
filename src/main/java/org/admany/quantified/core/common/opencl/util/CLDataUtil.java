package org.admany.quantified.core.common.opencl.util;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public final class CLDataUtil {
    private static final AtomicInteger instanceCounter = new AtomicInteger(0);

    public enum DataType {
        BUFFER_CACHE(8, "BC"),
        KERNEL_PARAMS(8, "KP"),
        RESULT_BUFFER(8, "RB"),
        WORKSPACE_DATA(16, "WD"),
        COMPUTE_GRID(32, "CG"),
        TEXTURE_DATA(64, "TX");

        private final int alignment;
        private final String code;

        DataType(int alignment, String code) {
            this.alignment = alignment;
            this.code = code;
        }

        public int alignment() { return alignment; }
        public String code() { return code; }
    }

    public record Allocation(int offset, int size, DataType type, boolean initialized) {}

    public static class MemoryLayout {
        private final ConcurrentHashMap<String, Allocation> allocations = new ConcurrentHashMap<>();
        private final AtomicInteger totalSize = new AtomicInteger(256);
        private final int headerSize;
        private final int instanceId;

        public MemoryLayout(int headerSize) {
            this.headerSize = headerSize;
            this.instanceId = instanceCounter.incrementAndGet();
            totalSize.set(alignTo(headerSize, 64));
        }

        public MemoryLayout() {
            this(256);
        }

        public MemoryLayout allocate(String key, DataType type, int size) {
            if (allocations.containsKey(key)) {
                throw new IllegalStateException("Allocation '" + key + "' already exists");
            }

            int offset = alignTo(totalSize.get(), type.alignment());
            allocations.put(key, new Allocation(offset, size, type, false));
            totalSize.set(offset + size);
            return this;
        }

        public MemoryLayout allocateInitialized(String key, DataType type, byte[] data) {
            allocate(key, type, data.length);
            Allocation alloc = allocations.get(key);
            allocations.put(key, new Allocation(alloc.offset(), alloc.size(), alloc.type(), true));
            return this;
        }

        public ByteBuffer construct() {
            int finalSize = alignTo(totalSize.get(), 64);
            ByteBuffer buffer = MemoryUtil.memAlloc(finalSize).order(ByteOrder.nativeOrder());

            buffer.putInt(0, instanceId);
            buffer.putInt(4, allocations.size());
            buffer.putInt(8, headerSize);

            int tableOffset = headerSize;
            int entryIndex = 0;

            for (Map.Entry<String, Allocation> entry : allocations.entrySet()) {
                Allocation alloc = entry.getValue();
                int entryOffset = tableOffset + (entryIndex * 20);

                buffer.putInt(entryOffset, alloc.offset());
                buffer.putInt(entryOffset + 4, alloc.size());
                buffer.putInt(entryOffset + 8, alloc.type().ordinal());
                buffer.putInt(entryOffset + 12, entry.getKey().hashCode());
                buffer.put((byte) (alloc.initialized() ? 1 : 0));

                entryIndex++;
            }

            return buffer.flip();
        }

        public Allocation getAllocation(String key) {
            return allocations.get(key);
        }

        public int getTotalSize() {
            return alignTo(totalSize.get(), 64);
        }

        public void forEachAllocation(BiConsumer<String, Allocation> consumer) {
            allocations.forEach(consumer);
        }
    }

    public static ByteBuffer createKernelBuffer(OpenCLContext context, Map<String, Object> params) {
        MemoryLayout layout = new MemoryLayout();

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            DataType type = inferDataType(key);

            if (value instanceof byte[] data) {
                layout.allocateInitialized(key, type, data);
            } else if (value instanceof Integer size) {
                layout.allocate(key, type, size);
            } else if (value instanceof ByteBuffer buffer) {
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                buffer.rewind();
                layout.allocateInitialized(key, type, data);
            }
        }

        return layout.construct();
    }

    public static ByteBuffer createComputeBuffer(OpenCLContext context, long size, long flags) {
        if (context == null || !context.isHealthy()) {
            throw new IllegalStateException("CL context unavailable");
        }

        MemoryLayout layout = new MemoryLayout(128);
        layout.allocate("computeBuffer", DataType.RESULT_BUFFER, (int)size);

        ByteBuffer buffer = layout.construct();
        long handle = context.createBuffer(flags, size);

        if (handle != 0) {
            buffer.putLong(16, handle);
            buffer.putLong(24, size);
            buffer.putInt(32, layout.getAllocation("computeBuffer").offset());
        }

        return buffer;
    }

    public static void writeLong(ByteBuffer buffer, String key, long value, MemoryLayout layout) {
        Allocation alloc = layout.getAllocation(key);
        if (alloc != null && alloc.offset() > 0) {
            buffer.putLong(alloc.offset(), value);
        }
    }

    public static void writeInt(ByteBuffer buffer, String key, int value, MemoryLayout layout) {
        Allocation alloc = layout.getAllocation(key);
        if (alloc != null && alloc.offset() > 0) {
            buffer.putInt(alloc.offset(), value);
        }
    }

    public static void writeFloat(ByteBuffer buffer, String key, float value, MemoryLayout layout) {
        Allocation alloc = layout.getAllocation(key);
        if (alloc != null && alloc.offset() > 0) {
            buffer.putFloat(alloc.offset(), value);
        }
    }

    public static long readLong(ByteBuffer buffer, String key, MemoryLayout layout) {
        Allocation alloc = layout.getAllocation(key);
        return alloc != null && alloc.offset() > 0 ? buffer.getLong(alloc.offset()) : 0L;
    }

    public static void transferToBuffer(ByteBuffer dest, int offset, byte[] source) {
        if (source != null && source.length > 0) {
            dest.position(offset);
            dest.put(source);
            dest.rewind();
        }
    }

    public static void transferFromBuffer(ByteBuffer source, int offset, byte[] dest) {
        if (dest != null && dest.length > 0) {
            source.position(offset);
            source.get(dest);
            source.rewind();
        }
    }

    public static record DataBlock(byte[] content, DataType type) {
        public DataBlock(byte[] content) {
            this(content, DataType.WORKSPACE_DATA);
        }
    }

    private static DataType inferDataType(String key) {
        return switch (key.toLowerCase()) {
            case "buffercache", "cache" -> DataType.BUFFER_CACHE;
            case "kernelparams", "params" -> DataType.KERNEL_PARAMS;
            case "resultbuffer", "result" -> DataType.RESULT_BUFFER;
            case "workspacedata", "workspace" -> DataType.WORKSPACE_DATA;
            case "computegrid", "grid" -> DataType.COMPUTE_GRID;
            case "texturedata", "texture" -> DataType.TEXTURE_DATA;
            default -> DataType.WORKSPACE_DATA;
        };
    }

    private static int alignTo(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }
}