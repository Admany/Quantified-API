package org.admany.quantified.core.common.opencl.gpu.task;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;

public final class GPUTaskTracker {

    private final AtomicLong activeVramBytes = new AtomicLong();
    private final AtomicInteger activeComputeUnits = new AtomicInteger();
    private final AtomicInteger fallbackHeat = new AtomicInteger();
    private final AtomicLong lastTaskActivityMs = new AtomicLong(System.currentTimeMillis());

    public GPUTaskTracker() {}

    public long estimatedActiveVramBytes() {
        return Math.max(0L, activeVramBytes.get());
    }

    public GPUMonitor.TaskSample beginTask(long estimatedVramBytes, int estimatedComputeUnits) {
        long vram = Math.max(0L, estimatedVramBytes);
        int compute = Math.max(1, estimatedComputeUnits);
        activeVramBytes.addAndGet(vram);
        activeComputeUnits.addAndGet(compute);
        lastTaskActivityMs.set(System.currentTimeMillis());
        return new GPUMonitor.TaskSample(vram, compute, System.nanoTime());
    }

    public void endTask(GPUMonitor.TaskSample sample) {
        if (sample == null) {
            return;
        }
        activeVramBytes.updateAndGet(current -> Math.max(0L, current - sample.estimatedVramBytes()));
        activeComputeUnits.updateAndGet(current -> Math.max(0, current - sample.estimatedComputeUnits()));
        lastTaskActivityMs.set(System.currentTimeMillis());
    }

    public void recordFallback() {
        fallbackHeat.addAndGet(2);
        lastTaskActivityMs.set(System.currentTimeMillis());
    }

    public void clearTracking() {
        activeVramBytes.set(0L);
        activeComputeUnits.set(0);
        fallbackHeat.set(0);
        lastTaskActivityMs.set(System.currentTimeMillis());
    }

    public long getActiveVramBytes() {
        return activeVramBytes.get();
    }

    public int getActiveComputeUnits() {
        return activeComputeUnits.get();
    }

    public int getFallbackHeat() {
        return fallbackHeat.getAndUpdate(existing -> existing > 0 ? existing - 1 : 0);
    }

    public long getLastTaskActivityMs() {
        return lastTaskActivityMs.get();
    }
}