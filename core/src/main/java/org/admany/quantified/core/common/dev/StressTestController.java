package org.admany.quantified.core.common.dev;

import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.task.FluidAdvectionTask;
import org.admany.quantified.core.common.opencl.task.HistogramTask;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StressTestController {

    private static final Logger LOGGER = Logger.getLogger(StressTestController.class.getName());

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "quantified-stress");
        thread.setDaemon(true);
        return thread;
    });

    // Dedicated executor for stress tasks to avoid starving the primary AsyncManager worker pool
    private static volatile int stressPoolSize = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private static volatile ExecutorService stressPool = Executors.newFixedThreadPool(stressPoolSize, r -> {
        Thread t = new Thread(r, "quantified-stress-worker");
        t.setDaemon(true);
        return t;
    });

    // Per-chunk duration in milliseconds - how long each submitted stress task will run before yielding
    private static volatile int cpuChunkMs = 40;

    private static final AtomicBoolean enabled = new AtomicBoolean(false);
    private static final AtomicReference<StressTestProfile> profile = new AtomicReference<>(StressTestProfile.CPU_HEAVY);
    private static ScheduledFuture<?> stressTask;
    private static final Random RANDOM = new Random();
    private static final AtomicLong lastInjectionMs = new AtomicLong(0L);
    private static final AtomicLong cycleCount = new AtomicLong(0L);
    private static final AtomicLong networkTestPacketsSent = new AtomicLong();
    private static final AtomicLong networkTestPacketsReceived = new AtomicLong();
    private static final AtomicLong networkTestBytesTransferred = new AtomicLong();
    private static final AtomicLong gpuTestComputationCount = new AtomicLong();
    private static final AtomicLong cpuTestComputationCount = new AtomicLong();
    private static final AtomicLong taskIdCounter = new AtomicLong(0L);
    private static volatile ThreadSafeCache<String, byte[]> stressCache;

    private StressTestController() {
    }

    public static synchronized void setEnabled(boolean enable) {
        if (enable == enabled.get()) {
            return;
        }
        enabled.set(enable);
        if (enable) {
            stressTask = EXECUTOR.scheduleAtFixedRate(StressTestController::injectLoad, 1L, 1L, TimeUnit.SECONDS);
            String msg = "Developer stress-test task generator enabled using profile " + profile.get().configKey();
            LOGGER.info(() -> msg);
            DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
        } else if (stressTask != null) {
            stressTask.cancel(false);
            stressTask = null;
            String msg = "Developer stress-test task generator disabled";
            LOGGER.info(msg);
            DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
        }
        if (!enable) {
            cycleCount.set(0L);
            lastInjectionMs.set(0L);
            gpuTestComputationCount.set(0L);
            cpuTestComputationCount.set(0L);
            networkTestPacketsSent.set(0L);
            networkTestPacketsReceived.set(0L);
            networkTestBytesTransferred.set(0L);
            taskIdCounter.set(0L);
        }
    }

    public static synchronized void shutdown() {
        if (stressTask != null) {
            stressTask.cancel(false);
            stressTask = null;
        }
        try {
            ExecutorService pool = stressPool;
            if (pool != null) pool.shutdownNow();
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Failed to shutdown stress pool", t);
        }
        try {
            EXECUTOR.shutdownNow();
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Failed to shutdown executor", t);
        }
    }

    private static void injectLoad() {
        if (!enabled.get()) {
            return;
        }
        runProfileInjection(profile.get(), true);
    }

    private static final long MANUAL_PROFILE_DURATION_MS = 10_000L;

    public static void runOnce() {
        EXECUTOR.execute(() -> runManualSweep(MANUAL_PROFILE_DURATION_MS));
    }

    private static void runProfileInjection(StressTestProfile activeProfile, boolean recordCycle) {
        try {
            switch (activeProfile) {
                case CPU_HEAVY -> injectCpuLoad();
                case GPU_TEST -> injectGpuTestLoad();
                case CACHE_PRESSURE -> injectCacheLoad();
                case NETWORK_TEST -> injectNetworkLoad();
            }
            if (DeveloperFeatures.isTimelineEnabled()) {
                DeveloperOverlayManager.recordTimelineEvent(new DeveloperOverlayManager.TimelineEvent(
                    System.currentTimeMillis(),
                    DeveloperOverlayManager.TimelineEventType.STRESS_TEST,
                    "Injected synthetic load profile " + activeProfile.configKey(),
                    0,
                    0.0,
                    0.0,
                    0.0,
                    QuantifiedCoreRuntime.MODID
                ));
            }
            if (recordCycle) {
                cycleCount.incrementAndGet();
            }
            lastInjectionMs.set(System.currentTimeMillis());
        } catch (Throwable throwable) {
            LOGGER.log(Level.WARNING, "Stress-test injection failed", throwable);
        }
    }

    private static void runManualSweep(long perProfileMs) {
        boolean anyRun = false;
        for (StressTestProfile sweepProfile : StressTestProfile.values()) {
            long endAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(perProfileMs);
            boolean injected = false;
            while (System.nanoTime() < endAt) {
                runProfileInjection(sweepProfile, false);
                injected = true;
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (injected) {
                anyRun = true;
            }
        }
        if (anyRun) {
            cycleCount.incrementAndGet();
            lastInjectionMs.set(System.currentTimeMillis());
        }
    }

    public static StressTestProfile getProfile() {
        return profile.get();
    }

    public static void setProfile(StressTestProfile newProfile) {
        StressTestProfile previous = profile.getAndSet(newProfile);
        if (previous != newProfile) {
            String msg = "Developer stress-test profile switched from " + previous.configKey() + " to " + newProfile.configKey();
            LOGGER.info(() -> msg);
            DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
        }
    }

    public static long lastInjectionTimestamp() {
        return lastInjectionMs.get();
    }

    public static long cycleCount() {
        return cycleCount.get();
    }

    public static long gpuTestComputationCount() {
        return gpuTestComputationCount.get();
    }

    public static long cpuTestComputationCount() {
        return cpuTestComputationCount.get();
    }

    public static long networkTestPacketsSent() {
        return networkTestPacketsSent.get();
    }

    public static long networkTestPacketsReceived() {
        return networkTestPacketsReceived.get();
    }

    public static long networkTestBytesTransferred() {
        return networkTestBytesTransferred.get();
    }

    /**
     * Clear the stress test cache to relieve cache pressure.
     */
    public static synchronized void clearStressCache() {
        ThreadSafeCache<String, byte[]> cache = stressCache;
        if (cache != null) {
            long clearedEntries = cache.size();
            cache.invalidateAll();
            String msg = "Stress test cache cleared: removed " + clearedEntries + " entries";
            LOGGER.info(msg);
            DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
        }
    }

    @SuppressWarnings("unused")
    private static Integer heavyCompute() {
        // Legacy synchronous heavy compute - keep as a fallback but avoid using this directly
        double value = 0.0;
        for (int i = 0; i < 20_000; i++) {
            value += Math.sin(i) * Math.cos(i / 2.0);
        }
        return (int) value;
    }

    private static void doCpuWorkChunk(long durationMs) {
        final long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        double v = 0.0;
        // Busy loop performing some math to keep CPU busy for the chunk duration
        while (System.nanoTime() < end) {
            // Use a mix of operations to avoid trivial JIT elimination
            v += Math.sin(System.nanoTime() & 0xFFF) * Math.cos(v + (System.nanoTime() & 0x7FF));
            if ((int) v % 1024 == 0) {
                // small cooperative point - no-op
                Thread.yield();
            }
        }
    }

    private static void injectCpuLoad() {
        for (int i = 0; i < 16; i++) {
            final long taskKey = taskIdCounter.incrementAndGet();
            try {
                ExecutorService pool = stressPool;
                if (pool == null) {
                    LOGGER.fine(() -> "Stress pool not available, skipping CPU chunk submission");
                    return;
                }
                pool.submit(() -> {
                    try {
                        // Perform a bounded CPU chunk
                        doCpuWorkChunk(cpuChunkMs);
                        cpuTestComputationCount.incrementAndGet();
                        LOGGER.finest(() -> "CPU stress task " + taskKey + " completed chunk");
                        DeveloperOverlayManager.recordApiLog("[Quantified] CPU stress task " + taskKey + " completed chunk");
                    } catch (Throwable ex) {
                        String msg = "CPU stress task " + taskKey + " failed: " + ex.getClass().getSimpleName() + " -> " + ex.getMessage();
                        LOGGER.fine(() -> msg);
                        DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
                    }
                });
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "Failed to submit CPU stress task " + taskKey, t);
            }
        }
    }

    /**
     * Adjust CPU chunk duration at runtime. Value must be >= 10ms.
     */
    public static synchronized void setCpuChunkMs(int ms) {
        if (ms < 10) ms = 10;
        cpuChunkMs = ms;
        String msg = "StressTestController: cpuChunkMs set to " + cpuChunkMs + " ms";
        LOGGER.info(msg);
        DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
    }

    public static int getCpuChunkMs() {
        return cpuChunkMs;
    }

    /**
     * Resize the stress thread pool. This will create a new pool and attempt to shut down the old one.
     */
    public static synchronized void setStressPoolSize(int size) {
        if (size < 1) size = 1;
        if (size == stressPoolSize) return;
        ExecutorService old = stressPool;
        stressPoolSize = size;
        stressPool = Executors.newFixedThreadPool(stressPoolSize, r -> {
            Thread t = new Thread(r, "quantified-stress-worker");
            t.setDaemon(true);
            return t;
        });
        String msg = "StressTestController: stressPoolSize resized to " + stressPoolSize;
        LOGGER.info(msg);
        DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
        if (old != null) {
            try {
                old.shutdownNow();
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "Failed to shutdown old stress pool", t);
            }
        }
    }

    public static int getStressPoolSize() {
        return stressPoolSize;
    }

    /**
     * Get the current size of the stress test cache.
     */
    public static long getStressCacheSize() {
        ThreadSafeCache<String, byte[]> cache = stressCache;
        return cache != null ? cache.size() : 0L;
    }

    /**
     * Get the total size of all caches in the system.
     */
    public static long getTotalCacheSize() {
        return CacheManager.getTotalCacheSize();
    }

    private static void injectGpuTestLoad() {
        LOGGER.fine(() -> "Starting GPU test load injection - submitting 24 GPU tasks");

        // Check if GPU is available before proceeding
        if (!OpenCLManager.isAvailable()) {
            LOGGER.fine(() -> "GPU not available, skipping GPU test load injection");
            return;
        }

        // Direct GPU stress test - bypass async system and flood GPU with tasks (10x CPU test scale)
        LOGGER.fine(() -> "GPU available, proceeding with task submission");
        for (int i = 0; i < 16; i++) { // Increased for better GPU stress
            int[] data = RANDOM.ints(2048, 0, 2048).toArray();
            HistogramTask histogramTask = new HistogramTask.Builder(
                QuantifiedCoreRuntime.MODID,
                "dev_gpu_test_histogram",
                taskIdCounter.incrementAndGet(),
                data
            ).bucketCount(64).range(0, 2048).build();

            // Submit directly to OpenCL without going through AsyncManager
            try {
                OpenCLManager.executeOnGpu(histogramTask).whenComplete((result, throwable) -> {
                    // Task completed (success or failure)
                    if (throwable != null) {
                        String msg = "GPU test histogram task completed with error: " + throwable.getMessage();
                        LOGGER.fine(() -> msg);
                        DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
                    } else {
                        gpuTestComputationCount.incrementAndGet(); // Count successful GPU execution
                        LOGGER.finest(() -> "GPU test histogram task completed successfully");
                    }
                });
            } catch (Throwable t) {
                String msg = "Failed to submit GPU test histogram task: " + t.getMessage();
                LOGGER.fine(() -> msg);
                DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
            }
        }

        for (int i = 0; i < 8; i++) { // Increased for better GPU stress
            float[][] grid = new float[32][32]; // Smaller grid for reliability
            for (int y = 0; y < grid.length; y++) {
                for (int x = 0; x < grid[y].length; x++) {
                    grid[y][x] = RANDOM.nextFloat();
                }
            }
            FluidAdvectionTask fluidTask = new FluidAdvectionTask.Builder(
                QuantifiedCoreRuntime.MODID,
                "dev_gpu_test_fluid",
                taskIdCounter.incrementAndGet(),
                grid
            ).iterations(6).diffusion(0.18f).timeStep(0.1f).build(); // Simpler parameters

            // Submit directly to OpenCL without going through AsyncManager
            try {
                OpenCLManager.executeOnGpu(fluidTask).whenComplete((result, throwable) -> {
                    // Task completed (success or failure)
                    if (throwable != null) {
                        String msg = "GPU test fluid task completed with error: " + throwable.getMessage();
                        LOGGER.fine(() -> msg);
                        DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
                    } else {
                        gpuTestComputationCount.incrementAndGet(); // Count successful GPU execution
                        LOGGER.finest(() -> "GPU test fluid task completed successfully");
                    }
                });
            } catch (Throwable t) {
                String msg = "Failed to submit GPU test fluid task: " + t.getMessage();
                LOGGER.fine(() -> msg);
                DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
            }
        }
    }

    private static void injectCacheLoad() {
        ThreadSafeCache<String, byte[]> cache = stressCache();
        // Flood the cache with much more data - 200 entries per cycle with larger payloads
        for (int i = 0; i < 200; i++) {
            String key = "dev:" + profile.get().configKey() + ":" + RANDOM.nextInt(100_000) + ':' + System.nanoTime() + ':' + i;
            // Use variable sizes to create more realistic cache pressure
            int size = 1024 + RANDOM.nextInt(4096); // 1KB to 5KB entries
            cache.put(key, new byte[size]);
            // Also perform some cache lookups to simulate real usage
            cache.get(key, k -> new byte[512]);
        }
        // Don't auto-invalidate - let it build up to show real cache pressure
        if (cache.size() > 5000) {
            String msg = "Cache pressure test: cache now contains " + cache.size() + " entries (" +
                (cache.size() * 2048 / 1024) + " KB approximate memory usage)";
            LOGGER.fine(() -> msg);
            DeveloperOverlayManager.recordApiLog("[Quantified] " + msg);
        }
    }

    private static void injectNetworkLoad() {
        // Simulate network traffic by incrementing counters
        // This will show up in the dashboard as network activity
        for (int i = 0; i < 20; i++) { // Send 20 packets per cycle
            // Simulate packet sending
            networkTestPacketsSent.incrementAndGet();
            // Simulate some packets being received (echo/broadcast simulation)
            if (i % 3 == 0) { // Every 3rd packet gets "received"
                networkTestPacketsReceived.incrementAndGet();
            }
            // Simulate data transfer (1KB per packet)
            networkTestBytesTransferred.addAndGet(1024);
        }
    }

    private static ThreadSafeCache<String, byte[]> stressCache() {
        ThreadSafeCache<String, byte[]> existing = stressCache;
        if (existing != null) {
            return existing;
        }
        synchronized (StressTestController.class) {
            if (stressCache == null) {
                stressCache = CacheManager.register(
                    "quantified.dev.stress",
                    4096,
                    Duration.ofMinutes(5),
                    false
                );
            }
            return stressCache;
        }
    }

    public enum StressTestProfile {
        CPU_HEAVY("cpu_heavy", "CPU bound worker saturation"),
        GPU_TEST("gpu_test", "Direct GPU stress test bypassing async system"),
        CACHE_PRESSURE("cache_pressure", "Cache thrash and eviction pressure"),
        NETWORK_TEST("network_test", "Network packet traffic generation");

        private final String configKey;
        private final String description;

        StressTestProfile(String configKey, String description) {
            this.configKey = configKey;
            this.description = description;
        }

        public String configKey() {
            return configKey;
        }

        public String description() {
            return description;
        }

        public static StressTestProfile fromConfigKey(String key) {
            if (key == null || key.isBlank()) {
                return CPU_HEAVY;
            }
            String normalised = key.trim().toLowerCase();
            for (StressTestProfile profile : values()) {
                if (profile.configKey.equals(normalised)) {
                    return profile;
                }
            }
            throw new IllegalArgumentException("Unknown stress profile: " + key);
        }
    }
}
