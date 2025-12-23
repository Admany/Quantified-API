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
import java.util.*;
import java.util.concurrent.*;
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

    private static final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Quantified-OpenCL-Probe");
        t.setDaemon(true);
        return t;
    });

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
            if (isAvailable()) {
                lastRuntimeStatus = RuntimeStatus.available();
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
        if (cache != null) cache.put(modId, key, data);
    }

    public static CacheHit cacheGet(String modId, String key) {
        TieredGpuCache cache = tieredCache;
        return cache != null ? cache.get(modId, key) : CacheHit.miss();
    }

    public static boolean cacheHas(String modId, String key) {
        TieredGpuCache cache = tieredCache;
        return cache != null && cache.has(modId, key);
    }

    public static void cacheRemove(String modId, String key) {
        TieredGpuCache cache = tieredCache;
        if (cache != null) cache.remove(modId, key);
    }

    public static CompletableFuture<Boolean> forceProbe() {
        return CompletableFuture.supplyAsync(() -> {
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
        }, probeExecutor);
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
            String failureMsg = String.format("OpenCL/GPU not available: OpenCL=%.0f%%, GPU=%.0f%%. Context test: %s",
                status.getOpenCLConfidence() * 100, status.getGPUConfidence() * 100,
                status.getDetectionResults().contextCreationSuccessful ? "passed" : "failed");
            LOGGER.fine("Force probe: " + failureMsg);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Probe failed - " + failureMsg);
            lastRuntimeStatus = RuntimeStatus.failed(failureMsg);
            return false;
        }

        if (!OpenCLRuntime.ensureInitialised()) {
            String failureMsg = "OpenCL runtime failed to initialize: " + OpenCLRuntime.lastError();
            LOGGER.warning("Force probe: " + failureMsg);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Runtime init failed - " + failureMsg);
            lastRuntimeStatus = RuntimeStatus.failed(failureMsg);
            return false;
        }

        monitor = GPUMonitor.getInstance();
        monitor.configure(capabilities.device().vramBytes(), capabilities.device().computeUnits(), capabilities.device().name());
        monitor.start();
        try { monitor.refreshNow(); } catch (Throwable ignore) {}

        context = OpenCLContext.create(capabilities);

        OpenCLTaskManager.initializeThrottle(monitor);
        tieredCache = new TieredGpuCache(monitor, context, () -> capabilities);
        OpenCLTaskManager.setDependencies(monitor, context, tieredCache);

        lastRuntimeStatus = RuntimeStatus.available();
        runTestTask();

        LOGGER.info("Force probe: OpenCL acceleration initialized successfully for: " + capabilities.device().name());
        DeveloperOverlayManager.recordApiLog("[OpenCL] Acceleration ready - " + capabilities.device().name());
        return true;
    }

    private static void runTestTask() {
        try {
            if (!org.admany.quantified.core.common.async.core.AsyncManager.isInitialised()) return;

            OpenCLTestTask testTask = OpenCLTestTask.create("quantified.core", "OpenCL Test", System.nanoTime()).build();
            submitTask(testTask).thenAccept(result -> {
                if (result != null && result.startsWith("PASSED")) {
                    LOGGER.info("OpenCL test task succeeded");
                    DeveloperOverlayManager.recordApiLog("[OpenCL] Test SUCCEEDED");
                } else {
                    LOGGER.warning("OpenCL test task failed: " + result);
                    DeveloperOverlayManager.recordApiLog("[OpenCL] Test FAILED - " + result);
                }
            }).exceptionally(throwable -> {
                LOGGER.warning("OpenCL test task exception: " + throwable.getMessage());
                return null;
            });
        } catch (Exception e) {
            LOGGER.warning("Failed to create/run OpenCL test task: " + e.getMessage());
        }
    }

    public static RuntimeStatus runtimeStatus() {
        return isAvailable() ? RuntimeStatus.available() : lastRuntimeStatus;
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
        if (cache != null) cache.clear();
        if (monitor != null) {
            monitor.clearMemoryTracking();
            DeveloperOverlayManager.recordApiLog("[OpenCL] GPU memory tracking cleared");
        }
    }

    public static GPUMonitor.GPUStatus getGPUStatus() {
        GPUMonitor.GPUStatus status = monitor != null ? monitor.getStatus() : null;
        if (status != null && status.totalVramBytes() > 0 && !OpenCLTaskManager.isInVramPressureCooldown()) {
            if ((double) status.usedVramBytes() / status.totalVramBytes() >= 0.90d) {
                OpenCLTaskManager.handleVramSaturation("monitor poll threshold");
            }
        }
        return status;
    }

    public static void handleVramSaturation() { OpenCLTaskManager.handleVramSaturation(); }
    public static void handleVramSaturation(String cause) { OpenCLTaskManager.handleVramSaturation(cause); }
    public static boolean isInVramPressureCooldown() { return OpenCLTaskManager.isInVramPressureCooldown(); }

    public static void updateDeviceName(String name) {
        if (monitor != null) monitor.updateDeviceName(name);
        if (!isAvailable()) AsyncProbeScheduler.triggerRendererProbe(name);
    }

    public static boolean canAcceptTask(OpenCLTask<?> task) { return OpenCLTaskManager.canAcceptTask(task); }
    public static <T> CompletableFuture<T> executeOnGpu(OpenCLTask<T> task) { return OpenCLTaskManager.executeOnGpu(task); }

    private static void cleanupAfterFailure() {
        TieredGpuCache cache = tieredCache;
        if (cache != null) {
            try { cache.shutdown(); } catch (Throwable ignored) {}
            tieredCache = null;
        }
        OpenCLTaskManager.setDependencies(null, null, null);
        if (monitor != null) {
            try { monitor.stop(); } catch (Throwable ignored) {}
            monitor = null;
        }
        if (context != null) {
            try { context.close(); } catch (Throwable ignored) {}
            context = null;
        }
        capabilities = null;
        OpenCLRuntime.destroy();
    }

    // -------------------- CLBuffer --------------------
    public static class CLBuffer implements AutoCloseable {
        private final long bufferHandle;
        private final long sizeBytes;
        private final OpenCLContext context;
        private volatile boolean inUse = false;
        private volatile long lastUsed = System.nanoTime();
        private volatile int referenceCount = 1;
        private final Object bufferMutex = new Object();

        public static CLBuffer create(OpenCLContext context, long flags, long sizeBytes) {
            if (context == null || !context.isHealthy()) throw new IllegalStateException("OpenCL context not available");
            long bufferHandle = context.createBuffer(flags, sizeBytes);
            if (bufferHandle == 0) throw new IllegalStateException("Failed to create OpenCL buffer");
            return new CLBuffer(bufferHandle, sizeBytes, context);
        }

        public static CLBuffer createReadWrite(OpenCLContext context, long sizeBytes) {
            return create(context, CL10.CL_MEM_READ_WRITE, sizeBytes);
        }

        private CLBuffer(long bufferHandle, long sizeBytes, OpenCLContext context) {
            this.bufferHandle = bufferHandle;
            this.sizeBytes = sizeBytes;
            this.context = context;
        }

        public void markInUse() { synchronized (bufferMutex) { inUse = true; lastUsed = System.nanoTime(); } }
        public void markFree() { synchronized (bufferMutex) { inUse = false; lastUsed = System.nanoTime(); } }
        public void retain() { synchronized (bufferMutex) { referenceCount++; } }
        public void release() { synchronized (bufferMutex) { if (--referenceCount <= 0) close(); } }

        public void write(ByteBuffer data, boolean blocking) { write(data, 0, blocking); }
        public void write(ByteBuffer data, long offset, boolean blocking) {
            if (data.remaining() + offset > sizeBytes) throw new IllegalArgumentException("Data size exceeds buffer capacity");
            context.enqueueWriteBuffer(bufferHandle, blocking, offset, data.remaining(), data);
            lastUsed = System.nanoTime();
        }

        public void read(ByteBuffer dest, boolean blocking) { read(dest, 0, blocking); }
        public void read(ByteBuffer dest, long offset, boolean blocking) {
            if (dest.remaining() + offset > sizeBytes) throw new IllegalArgumentException("Read size exceeds buffer capacity");
            context.enqueueReadBuffer(bufferHandle, blocking, offset, dest.remaining(), dest);
            lastUsed = System.nanoTime();
        }

        public long getBufferHandle() { return bufferHandle; }
        public long getSize() { return sizeBytes; }
        public boolean isInUse() { synchronized (bufferMutex) { return inUse; } }
        public long getLastUsed() { synchronized (bufferMutex) { return lastUsed; } }
        public int getReferenceCount() { synchronized (bufferMutex) { return referenceCount; } }
        public boolean isValid() { return bufferHandle != 0 && context != null && context.isHealthy(); }

        @Override
        public void close() {
            synchronized (bufferMutex) {
                if (bufferHandle != 0) {
                    try { context.releaseBuffer(bufferHandle); } catch (Exception ignored) {}
                }
                inUse = false;
                referenceCount = 0;
            }
        }
    }

    // -------------------- PredictiveBufferCache --------------------
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

                CLBuffer newBuf = CLBuffer.createReadWrite(context, sizeBytes);
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
    }

    // -------------------- Structured Buffer Helpers --------------------
    public static ByteBuffer createStructuredBuffer(OpenCLContext ctx, Map<String, Object> dataMap) {
        return CLDataUtil.createKernelBuffer(ctx, dataMap);
    }

    public static ByteBuffer createComputeStructuredBuffer(OpenCLContext ctx, long size, long flags) {
        return CLDataUtil.createComputeBuffer(ctx, size, flags);
    }
}
