package org.admany.quantified.core.common.vulkan.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class VulkanRuntimeActivityTracker {

    private static final AtomicLong ACTIVE_VRAM_BYTES = new AtomicLong();
    private static final AtomicInteger ACTIVE_COMPUTE_UNITS = new AtomicInteger();
    private static final AtomicLong LAST_TASK_ACTIVITY_MS = new AtomicLong(System.currentTimeMillis());

    private VulkanRuntimeActivityTracker() {
    }

    static TaskSample beginTask(long estimatedVramBytes, int estimatedComputeUnits) {
        long vram = Math.max(0L, estimatedVramBytes);
        int compute = Math.max(1, estimatedComputeUnits);
        ACTIVE_VRAM_BYTES.addAndGet(vram);
        ACTIVE_COMPUTE_UNITS.addAndGet(compute);
        LAST_TASK_ACTIVITY_MS.set(System.currentTimeMillis());
        return new TaskSample(vram, compute);
    }

    static void endTask(TaskSample sample) {
        if (sample == null) {
            return;
        }
        ACTIVE_VRAM_BYTES.updateAndGet(current -> Math.max(0L, current - sample.estimatedVramBytes()));
        ACTIVE_COMPUTE_UNITS.updateAndGet(current -> Math.max(0, current - sample.estimatedComputeUnits()));
        LAST_TASK_ACTIVITY_MS.set(System.currentTimeMillis());
    }

    public static long activeVramBytes() {
        return Math.max(0L, ACTIVE_VRAM_BYTES.get());
    }

    public static int activeComputeUnits() {
        return Math.max(0, ACTIVE_COMPUTE_UNITS.get());
    }

    public static long lastTaskActivityMs() {
        return LAST_TASK_ACTIVITY_MS.get();
    }

    record TaskSample(long estimatedVramBytes, int estimatedComputeUnits) {
    }
}
