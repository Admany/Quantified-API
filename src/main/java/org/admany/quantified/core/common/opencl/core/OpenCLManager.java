package org.admany.quantified.core.common.opencl.core;

import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.opencl.cache.TieredGpuCache;
import org.admany.quantified.core.common.opencl.cache.TieredGpuCache.CacheHit;
import org.admany.quantified.core.common.opencl.gpu.AsyncProbeScheduler;
import org.admany.quantified.core.common.opencl.gpu.GPUDetector;
import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;
import org.admany.quantified.core.common.opencl.gpu.HardwareDetector;
import org.admany.quantified.core.common.opencl.task.OpenCLTaskManager;
import org.admany.quantified.core.common.opencl.task.OpenCLTestTask;
import org.admany.quantified.core.common.opencl.util.CLDataUtil;
import org.admany.quantified.core.common.opencl.util.NativeLibraryExtractor;
import org.lwjgl.opencl.CL10;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OpenCLManager {

    private static final Logger LOGGER = Logger.getLogger(OpenCLManager.class.getName());

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static volatile OpenCLContext context;
    private static volatile GPUMonitor monitor;
    private static volatile GPUDetector.GPUCapabilities capabilities;

    private static volatile TieredGpuCache tieredCache;
    private static volatile RuntimeStatus lastRuntimeStatus = RuntimeStatus.failed("OpenCL not initialized");

    private OpenCLManager() {}

    public static class RuntimeStatus {
        private final boolean available;
        private final String failureReason;

        private RuntimeStatus(boolean available, String failureReason) {
            this.available = available;
            this.failureReason = failureReason;
        }

        public boolean isAvailable() {
            return available;
        }

        public String failureReason() {
            return failureReason;
        }

        public static RuntimeStatus available() {
            return new RuntimeStatus(true, null);
        }

        public static RuntimeStatus failed(String reason) {
            return new RuntimeStatus(false, reason);
        }
    }

    public static void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            LOGGER.fine("OpenCL acceleration already initialized");
            DeveloperOverlayManager.recordApiLog("[OpenCL] Already initialized");
            // Do not claim availability unless the full stack is actually ready.
            if (isAvailable()) {
                lastRuntimeStatus = RuntimeStatus.available();
            } else {
                RuntimeStatus current = lastRuntimeStatus;
                if (current == null || current.isAvailable()) {
                    lastRuntimeStatus = RuntimeStatus.failed("OpenCL initialization in progress");
                }
            }
            return;
        }
        NativeLibraryExtractor.extractAndSetLibraryPath();

        AsyncProbeScheduler.scheduleBackgroundProbe();
        LOGGER.fine("OpenCL acceleration scheduled for background probe (deferred initialization)");
        DeveloperOverlayManager.recordApiLog("[OpenCL] Background probe scheduled");
        lastRuntimeStatus = RuntimeStatus.failed("Background probe pending");
    }

    public static boolean isAvailable() {
        return initialized.get() &&
               capabilities != null &&
               capabilities.supported() &&
               context != null &&
               context.isHealthy() &&
               monitor != null;
    }

    public static void cachePut(String modId, String key, ByteBuffer data) {
        TieredGpuCache cache = tieredCache;
        if (cache != null) {
            cache.put(modId, key, data);
        }
    }

    public static CacheHit cacheGet(String modId, String key) {
        TieredGpuCache cache = tieredCache;
        if (cache == null) {
            return CacheHit.miss();
        }
        return cache.get(modId, key);
    }

    public static boolean cacheHas(String modId, String key) {
        TieredGpuCache cache = tieredCache;
        return cache != null && cache.has(modId, key);
    }

    public static void cacheRemove(String modId, String key) {
        TieredGpuCache cache = tieredCache;
        if (cache != null) {
            cache.remove(modId, key);
        }
    }

    public static java.util.concurrent.CompletableFuture<Boolean> forceProbe() {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return performProbe();
            } catch (Throwable t) {
                String failureMsg = "Failed to initialize OpenCL acceleration: " + t.getMessage();
                LOGGER.log(Level.WARNING, "Force probe: " + failureMsg, t);
                DeveloperOverlayManager.recordApiLog("[OpenCL] Init exception - " + failureMsg);
                cleanupAfterFailure();
                lastRuntimeStatus = RuntimeStatus.failed(failureMsg);
                return false;
            }
        }, java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Quantified-OpenCL-Probe");
            t.setDaemon(true);
            return t;
        }));
    }

    public static boolean forceProbeSynchronous() {
        try {
            return performProbe();
        } catch (Throwable t) {
            String failureMsg = "Failed to initialize OpenCL acceleration: " + t.getMessage();
            LOGGER.log(Level.WARNING, "Force probe: " + failureMsg, t);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Init exception - " + failureMsg);
            cleanupAfterFailure();
            lastRuntimeStatus = RuntimeStatus.failed(failureMsg);
            return false;
        }
    }

    private static boolean performProbe() throws Exception {
        // If the OpenCL binding isn't present (common on dedicated servers), don't even attempt probing.
        // This prevents background threads from repeatedly throwing/linking LWJGL classes.
        OpenCLRuntime.AvailabilitySnapshot runtime = OpenCLRuntime.snapshot();
        if (!runtime.available()) {
            String reason = runtime.failureReason() != null ? runtime.failureReason() : "OpenCL runtime unavailable";
            String failureMsg = "OpenCL runtime unavailable: " + reason + " (binding: " + OpenCLRuntime.getBindingName() + ")";
            LOGGER.fine("Force probe: " + failureMsg);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Probe skipped - " + failureMsg);
            lastRuntimeStatus = RuntimeStatus.failed(failureMsg);
            return false;
        }

        var status = HardwareDetector.detailedDetect();
        capabilities = GPUDetector.detectCapabilities();
        if (!status.isOpenCLAvailable() || !status.isGPUAvailable()) {
            String failureMsg = String.format("OpenCL/GPU not available: OpenCL=%.0f%% confidence, GPU=%.0f%% confidence. Context test: %s",
                status.getOpenCLConfidence() * 100, status.getGPUConfidence() * 100,
                status.getDetectionResults().contextCreationSuccessful ? "passed" : "failed");
            LOGGER.fine("Force probe: " + failureMsg);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Probe failed - " + failureMsg);
            lastRuntimeStatus = RuntimeStatus.failed(failureMsg);
            return false;
        }

        // Runtime is already available per snapshot above, but keep this as a safety net.
        if (!OpenCLRuntime.ensureInitialised()) {
            String failureMsg = "OpenCL runtime failed to initialize: " + OpenCLRuntime.lastError() + " (binding: " + OpenCLRuntime.getBindingName() + ")";
            LOGGER.warning("Force probe: " + failureMsg);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Runtime init failed - " + failureMsg);
            lastRuntimeStatus = RuntimeStatus.failed(failureMsg);
            return false;
        }

        if (capabilities == null || !capabilities.supported()) {
            String failureReason = capabilities != null ? capabilities.failureReason() : null;
            String failureMsg = "Detected capabilities do not support OpenCL";
            if (failureReason != null && !failureReason.isBlank()) {
                failureMsg += " - " + failureReason;
            }
            if (capabilities != null && capabilities.device() != null) {
                failureMsg += " (device: " + capabilities.device().name() + ")";
            }
            LOGGER.warning("Force probe: " + failureMsg);
            DeveloperOverlayManager.recordApiLog("[OpenCL] GPU detection failed - " + failureMsg);
            cleanupAfterFailure();
            lastRuntimeStatus = RuntimeStatus.failed(failureMsg);
            return false;
        }

        monitor = GPUMonitor.getInstance();
        monitor.configure(capabilities.device().vramBytes(), capabilities.device().computeUnits(), capabilities.device().name());
        monitor.start();
        try {
            monitor.refreshNow();
        } catch (Throwable t) {
            LOGGER.fine("Immediate GPU refresh failed: " + t.getMessage());
        }

        context = OpenCLContext.create(capabilities);

        OpenCLTaskManager.initializeThrottle(monitor);
        tieredCache = new TieredGpuCache(monitor, context, () -> capabilities);
        OpenCLTaskManager.setDependencies(monitor, context, tieredCache);

        LOGGER.info("Force probe: OpenCL acceleration initialized successfully for: " + capabilities.device().name());
        DeveloperOverlayManager.recordApiLog("[OpenCL] Acceleration ready - " + capabilities.device().name());
        lastRuntimeStatus = RuntimeStatus.available();

        try {
            LOGGER.fine("Running OpenCL test task to verify GPU acceleration...");
            DeveloperOverlayManager.recordApiLog("[OpenCL] Running test task...");

            if (!org.admany.quantified.core.common.async.core.AsyncManager.isInitialised()) {
                LOGGER.warning("AsyncManager not initialized yet, skipping OpenCL test task");
                DeveloperOverlayManager.recordApiLog("[OpenCL] Test skipped - AsyncManager not ready");
            } else {
                OpenCLTestTask testTask = OpenCLTestTask.create("quantified.core", "OpenCL Test", System.nanoTime()).build();
                CompletableFuture<String> testResult = submitTask(testTask);
                testResult.thenAccept(result -> {
                        if (result != null && result.startsWith("PASSED")) {
                        LOGGER.info("OpenCL test task completed successfully - GPU acceleration verified");
                        DeveloperOverlayManager.recordApiLog("[OpenCL] Test SUCCEEDED - GPU acceleration working");
                    } else {
                        LOGGER.warning("OpenCL test task failed: " + result + " - GPU acceleration may have issues");
                        DeveloperOverlayManager.recordApiLog("[OpenCL] Test FAILED - " + result);
                    }

                    if (monitor != null) {
                        GPUMonitor.GPUStatus gpuStatus = monitor.getStatus();
                        if (gpuStatus != null) {
                            double temp = gpuStatus.temperatureC();
                            double util = gpuStatus.computeUtilization() * 100.0;
                            long used = gpuStatus.usedVramBytes() / (1024 * 1024);
                            long total = gpuStatus.totalVramBytes() / (1024 * 1024);
                            String statusMsg = String.format("GPU - Temp: %.1f°C · Util: %.0f%% · VRAM: %d/%d MB (API limited to 25%%)",
                                            temp, util, used, total);
                            LOGGER.fine("Post-test GPU status: " + statusMsg);
                            DeveloperOverlayManager.recordApiLog("[OpenCL] " + statusMsg);

                            try {
                                clearGPUCaches();
                                handleVramSaturation("post-initialization warmup cleanup");
                            } catch (Throwable ignore) {
                            }

                            if (gpuStatus.totalVramBytes() > 0 &&
                                (double) gpuStatus.usedVramBytes() / gpuStatus.totalVramBytes() >= 0.90d) {
                                handleVramSaturation("post-initialization warmup task");
                            }
                        }
                    }
                }).exceptionally(throwable -> {
                    LOGGER.warning("OpenCL test task threw exception: " + throwable.getMessage());
                    DeveloperOverlayManager.recordApiLog("[OpenCL] Test exception - " + throwable.getMessage());
                    return null;
                });
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to create/run OpenCL test task: " + e.getMessage());
            DeveloperOverlayManager.recordApiLog("[OpenCL] Test setup failed - " + e.getMessage());
        }

        return true;
    }

    public static RuntimeStatus runtimeStatus() {
        if (isAvailable()) {
            return RuntimeStatus.available();
        }
        return lastRuntimeStatus;
    }

    public static <T> CompletableFuture<T> submitTask(OpenCLTask<T> task) {
        return OpenCLTaskManager.submitTask(task);
    }

    public static void shutdown() {
        cleanupAfterFailure();
        initialized.set(false);
        AsyncProbeScheduler.reset();
        OpenCLTaskManager.setDependencies(null, null, null);

        lastRuntimeStatus = RuntimeStatus.failed("OpenCL shutdown");

        LOGGER.fine("OpenCL acceleration shutdown");
    }

    public static void clearGPUCaches() {
        TieredGpuCache cache = tieredCache;
        if (cache != null) {
            cache.clear();
        }
        if (monitor != null) {
            monitor.clearMemoryTracking();
            LOGGER.fine("GPU memory tracking cleared");
            DeveloperOverlayManager.recordApiLog("[OpenCL] GPU memory tracking cleared");
        }
    }

    public static GPUMonitor.GPUStatus getGPUStatus() {
        GPUMonitor.GPUStatus status = monitor != null ? monitor.getStatus() : null;
        if (status != null && status.totalVramBytes() > 0 && !OpenCLTaskManager.isInVramPressureCooldown()) {
            double util = (double) status.usedVramBytes() / status.totalVramBytes();
            if (util >= 0.90d) {
                String cause = String.format(Locale.ROOT,
                    "monitor poll threshold %.0f%% util", util * 100.0d);
                OpenCLTaskManager.handleVramSaturation(cause);
            }
        }
        return status;
    }

    public static void handleVramSaturation() {
        OpenCLTaskManager.handleVramSaturation();
    }

    public static void handleVramSaturation(String cause) {
        OpenCLTaskManager.handleVramSaturation(cause);
    }

    public static boolean isInVramPressureCooldown() {
        return OpenCLTaskManager.isInVramPressureCooldown();
    }

    public static void updateDeviceName(String name) {
        if (monitor != null) {
            monitor.updateDeviceName(name);
        }
        if (!isAvailable()) {
            AsyncProbeScheduler.triggerRendererProbe(name);
        }
    }

    public static boolean canAcceptTask(OpenCLTask<?> task) {
        return OpenCLTaskManager.canAcceptTask(task);
    }

    public static <T> CompletableFuture<T> executeOnGpu(OpenCLTask<T> task) {
        return OpenCLTaskManager.executeOnGpu(task);
    }

    private static void cleanupAfterFailure() {
        TieredGpuCache cache = tieredCache;
        if (cache != null) {
            try {
                cache.shutdown();
            } catch (Throwable ignored) {
            }
            tieredCache = null;
        }
        OpenCLTaskManager.setDependencies(null, null, null);
        if (monitor != null) {
            try {
                monitor.stop();
            } catch (Throwable ignored) {
            }
            monitor = null;
        }
        if (context != null) {
            try {
                context.close();
            } catch (Throwable ignored) {
            }
            context = null;
        }
        capabilities = null;
        OpenCLRuntime.destroy();
    }

    public static class PredictiveBufferCache {
        private final Map<Long, List<CLBuffer>> sizeBuckets = new ConcurrentHashMap<>();
        private final GPUMonitor monitor;
        private final OpenCLContext context;
        private final long maxMemory;
        private final Object cacheMutex = new Object();

        public PredictiveBufferCache(GPUMonitor monitor, OpenCLContext context, long maxMemory) {
            this.monitor = monitor;
            this.context = context;
            this.maxMemory = maxMemory;
        }

        public CLBuffer getOrCreate(long sizeBytes) {
            synchronized (cacheMutex) {
                List<CLBuffer> bucket = sizeBuckets.computeIfAbsent(sizeBytes, k -> new ArrayList<>());

                for (CLBuffer buf : bucket) {
                    if (!buf.isInUse()) {
                        buf.markInUse();
                        return buf;
                    }
                }

                if (shouldSpillToDisk(sizeBytes)) {
                    return null;
                }

                CLBuffer newBuf = createBuffer(sizeBytes);
                bucket.add(newBuf);
                newBuf.markInUse();
                return newBuf;
            }
        }

        public void release(CLBuffer buffer) {
            synchronized (cacheMutex) {
                buffer.markFree();
                if (getTotalMemoryUsage() > maxMemory * 0.9) {
                    evictLRU();
                }
            }
        }

        private boolean shouldSpillToDisk(long sizeBytes) {
            if (monitor == null) return false;
            GPUMonitor.GPUStatus status = monitor.getStatus();
            long currentUsage = status != null ? status.usedVramBytes() : 0;
            return currentUsage + sizeBytes > maxMemory * 0.8;
        }

        private void evictLRU() {
            final long[] freed = {0};
            sizeBuckets.values().forEach(bucket -> 
                bucket.removeIf(buf -> {
                    if (!buf.isInUse() && buf.getLastUsed() < System.nanoTime() - 60_000_000_000L) {
                        freed[0] += buf.getSize();
                        return true;
                    }
                    return false;
                })
            );
            LOGGER.fine("Evicted buffers, freed " + freed[0] + " bytes");
        }

        private long getTotalMemoryUsage() {
            return sizeBuckets.values().stream()
                .flatMap(List::stream)
                .mapToLong(CLBuffer::getSize)
                .sum();
        }

        private CLBuffer createBuffer(long sizeBytes) {
            return CLBuffer.createReadWrite(context, sizeBytes);
        }
    }

    public static class CLBuffer implements AutoCloseable {
        private final long bufferHandle;
        private final long sizeBytes;
        private final OpenCLContext context;
        private volatile boolean inUse = false;
        private volatile long lastUsed = System.nanoTime();
        private volatile int referenceCount = 1;
        private final Object bufferMutex = new Object();

        public static CLBuffer create(OpenCLContext context, long flags, long sizeBytes) {
            if (context == null || !context.isHealthy()) {
                throw new IllegalStateException("OpenCL context not available for buffer creation");
            }

            long bufferHandle = context.createBuffer(flags, sizeBytes);
            if (bufferHandle == 0) {
                throw new IllegalStateException("Failed to create OpenCL buffer");
            }

            return new CLBuffer(bufferHandle, sizeBytes, context);
        }

        public static CLBuffer createReadWrite(OpenCLContext context, long sizeBytes) {
            return create(context, CL10.CL_MEM_READ_WRITE, sizeBytes);
        }

        public static CLBuffer createReadOnly(OpenCLContext context, long sizeBytes) {
            return create(context, CL10.CL_MEM_READ_ONLY, sizeBytes);
        }

        public static CLBuffer createWriteOnly(OpenCLContext context, long sizeBytes) {
            return create(context, CL10.CL_MEM_WRITE_ONLY, sizeBytes);
        }

        private CLBuffer(long bufferHandle, long sizeBytes, OpenCLContext context) {
            this.bufferHandle = bufferHandle;
            this.sizeBytes = sizeBytes;
            this.context = context;
        }

        public void markInUse() {
            synchronized (bufferMutex) {
                inUse = true;
                lastUsed = System.nanoTime();
            }
        }

        public void markFree() {
            synchronized (bufferMutex) {
                inUse = false;
                lastUsed = System.nanoTime();
            }
        }

        public void retain() {
            synchronized (bufferMutex) {
                referenceCount++;
            }
        }

        public void release() {
            synchronized (bufferMutex) {
                referenceCount--;
                if (referenceCount <= 0) {
                    close();
                }
            }
        }

        public void write(ByteBuffer data, boolean blocking) {
            write(data, 0, blocking);
        }

        public void write(ByteBuffer data, long offset, boolean blocking) {
            if (data.remaining() + offset > sizeBytes) {
                throw new IllegalArgumentException("Data size exceeds buffer capacity");
            }
            context.enqueueWriteBuffer(bufferHandle, blocking, offset, data.remaining(), data);
            lastUsed = System.nanoTime();
        }

        public void read(ByteBuffer dest, boolean blocking) {
            read(dest, 0, blocking);
        }

        public void read(ByteBuffer dest, long offset, boolean blocking) {
            if (dest.remaining() + offset > sizeBytes) {
                throw new IllegalArgumentException("Read size exceeds buffer capacity");
            }
            context.enqueueReadBuffer(bufferHandle, blocking, offset, dest.remaining(), dest);
            lastUsed = System.nanoTime();
        }

        public void writeStructured(ByteBuffer data, String key, CLDataUtil.MemoryLayout layout) {
            CLDataUtil.Allocation alloc = layout.getAllocation(key);
            if (alloc != null) {
                write(data, alloc.offset(), true);
            }
        }

        public void readStructured(ByteBuffer dest, String key, CLDataUtil.MemoryLayout layout) {
            CLDataUtil.Allocation alloc = layout.getAllocation(key);
            if (alloc != null) {
                read(dest, alloc.offset(), true);
            }
        }

        public long getBufferHandle() {
            return bufferHandle;
        }

        public long getSize() {
            return sizeBytes;
        }

        public boolean isInUse() {
            synchronized (bufferMutex) {
                return inUse;
            }
        }

        public long getLastUsed() {
            synchronized (bufferMutex) {
                return lastUsed;
            }
        }

        public int getReferenceCount() {
            synchronized (bufferMutex) {
                return referenceCount;
            }
        }

        public boolean isValid() {
            return bufferHandle != 0 && context != null && context.isHealthy();
        }

        @Override
        public void close() {
            synchronized (bufferMutex) {
                if (bufferHandle != 0) {
                    try {
                        context.releaseBuffer(bufferHandle);
                        LOGGER.fine("Released OpenCL buffer: " + bufferHandle);
                    } catch (Exception e) {
                        LOGGER.warning("Failed to release OpenCL buffer: " + e.getMessage());
                    }
                }
                inUse = false;
                referenceCount = 0;
            }
        }

        @Override
        public String toString() {
            return String.format("CLBuffer{handle=%d, size=%d bytes, inUse=%s, refs=%d}",
                bufferHandle, sizeBytes, inUse, referenceCount);
        }
    }

    public static ByteBuffer createStructuredBuffer(OpenCLContext ctx, Map<String, Object> dataMap) {
        return CLDataUtil.createKernelBuffer(ctx, dataMap);
    }

    public static ByteBuffer createComputeStructuredBuffer(OpenCLContext ctx, long size, long flags) {
        return CLDataUtil.createComputeBuffer(ctx, size, flags);
    }
}
