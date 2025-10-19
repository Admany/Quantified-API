package org.admany.quantified.api.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * TEMPLATE CLASS - Copy this into your mod and customize the enum and initialization.
 *
 * This provides global static access to Quantified API features with automatic
 * fallback when the API is not available. Uses reflection to avoid compile time
 * dependencies on the Quantified API for simpler use ig.
 *
 * BENEFITS:
 * - No compile time dependency on QuantifiedAPI
 * - Works even if QuantifiedAPI jar is missing or is sleepin
 * - Automatic fallback to basic implementations
 * - Intelligent CPU/GPU task routing when API is available 
 * - Resource aware task scheduling based on VRAM/compute requirements
 *
 * INSTRUCTIONS (DO NOT FUCK UP):
 * 1. Copy this class into your mod's package
 * 2. Change the MODID, VERSION, and DISPLAY_NAME constants to your mod's data
 * 3. Add your cache types to the CacheType enum if u usin it
 * 4. Call QuantifiedAPI.init() in your mod's initialization
 * 5. Use QuantifiedAPI.task(), cache(), etc. throughout your mod
 * 6. Add Quantified API as an OPTIONAL dependency in your mod's build.gradle or FORCE IT
 */
public class QuantifiedAPI {

    // === CUSTOMIZE THESE CONSTANTS ===

    private static final String MODID = "your_mod_id";           // Change this to your mod ID
    private static final String VERSION = "1.0.0";              // Your mod version
    private static final String DISPLAY_NAME = "Your Awesome Mod"; // Display name

    // === ADD YOUR CACHE TYPES HERE ===
    public enum CacheType {
        // Example cache types - replace with your own
        PLAYER_DATA("Player statistics and positions"),
        WORLD_CHUNKS("Generated world chunk data"),
        AI_DECISIONS("AI behavior decisions"),
        PATHFINDING("Navigation path caches"),
        ENTITY_STATES("Entity AI and behavior data");

        private final String description;

        CacheType(String desc) {
            this.description = desc;
        }

        public String getDescription() {
            return description;
        }
    }

    // === INTERNAL STATE - DO NOT MODIFY ===

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedAPI.class);
    private static Object mod; // Using Object to avoid import dependency
    private static boolean initialized = false;
    private static boolean apiAvailable = false;

    // Simple in-memory fallback caches used when API not present
    private static final java.util.concurrent.ConcurrentHashMap<CacheType, java.util.concurrent.ConcurrentHashMap<String, Object>> fallbackCaches = new java.util.concurrent.ConcurrentHashMap<>();

    // Reflection helpers
    private static Class<?> quantifiedApiClass;
    private static Method connectMethod;

    static {
        try {
            // Try to load the API classes at runtime
            quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
            connectMethod = quantifiedApiClass.getMethod("connect", String.class, String.class, String.class);
            apiAvailable = true;
            LOGGER.debug("Quantified API classes found and loaded");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            apiAvailable = false;
            LOGGER.debug("Quantified API not available, using fallback mode");
        } catch (Exception e) {
            apiAvailable = false;
            LOGGER.warn("Error loading Quantified API: {}", e.getMessage());
        }
    }

    // === INITIALIZATION ===

    /**
     * Initialize the API connection.
     * Call this once during your mod's initialization.
     */
    public static void init() {
        if (initialized) {
            LOGGER.warn("QuantifiedAPI already initialized for {}", MODID);
            return;
        }

        LOGGER.info("Initializing QuantifiedAPI for {} ({})", DISPLAY_NAME, MODID);

        try {
            if (apiAvailable) {
                mod = connectMethod.invoke(null, MODID, VERSION, DISPLAY_NAME);
                if (mod == null) {
                    LOGGER.info("Using fallback mode - Quantified API connection rejected");
                    mod = new FallbackMod(MODID, VERSION, DISPLAY_NAME);
                } else {
                    LOGGER.info("Successfully connected to Quantified API");
                    registerCacheNames();
                }
            } else {
                LOGGER.info("Using fallback mode - Quantified API not available");
                mod = new FallbackMod(MODID, VERSION, DISPLAY_NAME);
            }
            initialized = true;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize QuantifiedAPI for {}", MODID, e);
            mod = new FallbackMod(MODID, VERSION, DISPLAY_NAME);
            initialized = true;
        }
    }

    /**
     * Register cache names to avoid conflicts.
     * Called automatically during init.
     */
    private static void registerCacheNames() {
        // This would be called on the real API to register cache names
        // For now, just log them
        for (CacheType type : CacheType.values()) {
            LOGGER.debug("Registered cache type: {} - {}", type.name(), type.getDescription());
        }
    }

    // === PUBLIC API METHODS ===

    /**
     * Submit an async task for execution.
     */
    public static TaskBuilder task(String taskName) {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).task(taskName);
        }
        // Use reflection to call the real API
        try {
            Object realBuilder = mod.getClass().getMethod("task", String.class).invoke(mod, taskName);
            return new ReflectiveTaskBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create task builder, using fallback", e);
            return new FallbackTaskBuilder();
        }
    }

    /**
     * Get a cache builder for the specified cache type.
     */
    public static CacheBuilder cache(CacheType cacheType) {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).cache(cacheType);
        }
        // Use reflection to call the real API
        try {
            Object realBuilder = mod.getClass().getMethod("cache", Enum.class).invoke(mod, cacheType);
            return new ReflectiveCacheBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create cache builder, using fallback", e);
            return new FallbackCacheBuilder(cacheType);
        }
    }

    /**
     * Get a hybrid builder for cached async computation.
     */
    public static HybridBuilder hybrid(String operationName) {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).hybrid(operationName);
        }
        // Use reflection to call the real API
        try {
            Object realBuilder = mod.getClass().getMethod("hybrid", String.class).invoke(mod, operationName);
            return new ReflectiveHybridBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create hybrid builder, using fallback", e);
            return new FallbackHybridBuilder();
        }
    }

    /**
     * Get a network builder for the specified channel.
     */
    public static NetworkBuilder network(String channel) {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).network(channel);
        }
        // Use reflection to call the real API
        try {
            Object realBuilder = mod.getClass().getMethod("network", String.class).invoke(mod, channel);
            return new ReflectiveNetworkBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create network builder, using fallback", e);
            return new FallbackNetworkBuilder();
        }
    }

    /**
     * Get statistics for this mod.
     */
    public static ModStatistics getStatistics() {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).getStatistics();
        }
        // Use reflection to call the real API
        try {
            Object realStats = mod.getClass().getMethod("getStatistics").invoke(mod);
            return new ReflectiveModStatistics(realStats);
        } catch (Exception e) {
            LOGGER.warn("Failed to get statistics, using fallback", e);
            return new FallbackStatistics();
        }
    }

    /**
     * Check if the API is available and connected.
     */
    public static boolean isAPIAvailable() {
        return initialized && apiAvailable && mod != null && !(mod instanceof FallbackMod);
    }

    /**
     * Check if initialized (may be using fallback mode).
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Shutdown the API connection.
     */
    public static void shutdown() {
        if (mod != null && !(mod instanceof FallbackMod)) {
            try {
                mod.getClass().getMethod("disconnect").invoke(mod);
            } catch (Exception e) {
                LOGGER.warn("Failed to disconnect from API", e);
            }
        }
        mod = null;
        initialized = false;
        LOGGER.info("QuantifiedAPI shutdown for {}", MODID);
    }

    // === INTERNAL METHODS ===

    private static void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("QuantifiedAPI not initialized. Call init() first.");
        }
    }

    // === UTILITY METHODS FOR COMMON PATTERNS ===

    /**
     * Quick async task submission.
     */
    public static <T> CompletableFuture<T> submit(String taskName, java.util.function.Supplier<T> work) {
        return task(taskName).submit(work);
    }

    /**
     * Quick cached value retrieval.
     */
    public static <T> T getCached(CacheType cacheType, String key, java.util.function.Supplier<T> loader) {
        return cache(cacheType).get(key, loader);
    }

    /**
     * Quick cached value storage.
     */
    public static <T> void putCached(CacheType cacheType, String key, T value) {
        cache(cacheType).put(key, value);
    }

    // === BUILDER INTERFACES (LOCAL COPIES TO AVOID IMPORTS) ===

    public interface TaskBuilder {
        TaskBuilder priority(Priority priority);
        TaskBuilder timeout(java.time.Duration timeout);
        TaskBuilder retry(int maxRetries);
        TaskBuilder resourceEstimate(long vramBytes, int computeUnits);
        TaskBuilder onProgress(java.util.function.Consumer<Double> progressCallback);
        TaskBuilder onFailure(java.util.function.Consumer<Throwable> failureCallback);
        <T> CompletableFuture<T> submit(java.util.function.Supplier<T> work);

        enum Priority { AUTO, FOREGROUND, BACKGROUND, CRITICAL }
    }

    public interface CacheBuilder {
        CacheBuilder ttl(java.time.Duration ttl);
        CacheBuilder maxSize(long maxSize);
        CacheBuilder compression(boolean enabled);
        CacheBuilder persistence(boolean enabled);
        <T> T get(String key, java.util.function.Supplier<T> loader);
        <T> void put(String key, T value);
        boolean contains(String key);
        void remove(String key);
        void clear();
    }

    public interface HybridBuilder {
        HybridBuilder priority(TaskBuilder.Priority priority);
        HybridBuilder timeout(java.time.Duration timeout);
        HybridBuilder ttl(java.time.Duration ttl);
        HybridBuilder maxSize(long maxSize);
        HybridBuilder compression(boolean enabled);
        <T> CompletableFuture<T> submit(java.util.function.Supplier<T> work);
    }

    public interface NetworkBuilder {
        NetworkBuilder chunkSize(int bytes);
        NetworkBuilder timeout(java.time.Duration timeout);
        NetworkBuilder retryPolicy(RetryPolicy policy);
        NetworkBuilder callback(java.util.function.Consumer<Object> responseCallback);
        CompletableFuture<Void> send(Object data);
        <T> CompletableFuture<T> sendAndReceive(Object data);

        enum RetryPolicy { NONE, LINEAR_BACKOFF, EXPONENTIAL_BACKOFF }
    }

    public interface ModStatistics {
        String getModId();
        String getModVersion();
        java.time.Instant getLastActivity();
        long getTotalTasksSubmitted();
        long getTasksCompleted();
        long getTasksFailed();
        long getCurrentQueueDepth();
        boolean isThrottled();
        double getThrottleFactor();
        java.time.Duration getAverageTaskTime();
        java.time.Duration getMaxTaskTime();
        double getTasksPerSecond();
        double getCacheHitRate();
        long getCacheSize();
        long getCacheMaxSize();
        long getCacheEvictions();
        long getCacheMemoryUsage();
        long getPacketsSent();
        long getPacketsReceived();
        long getNetworkErrors();
        long getNetworkBytesTransferred();
        java.time.Duration getTotalGPUTime();
        long getPeakVRAMUsage();
        double getGPUUtilization();
        double getCPUFallbackRate();
    }

    // === REFLECTIVE WRAPPERS ===

    private static class ReflectiveTaskBuilder implements TaskBuilder {
        private final Object realBuilder;

        ReflectiveTaskBuilder(Object realBuilder) {
            this.realBuilder = realBuilder;
        }

        @Override public TaskBuilder priority(Priority p) {
            try { realBuilder.getClass().getMethod("priority", Enum.class).invoke(realBuilder, p); } catch (Exception e) {}
            return this;
        }
        @Override public TaskBuilder timeout(java.time.Duration d) {
            try { realBuilder.getClass().getMethod("timeout", java.time.Duration.class).invoke(realBuilder, d); } catch (Exception e) {}
            return this;
        }
        @Override public TaskBuilder retry(int i) {
            try { realBuilder.getClass().getMethod("retry", int.class).invoke(realBuilder, i); } catch (Exception e) {}
            return this;
        }
        @Override public TaskBuilder resourceEstimate(long l, int i) {
            try { realBuilder.getClass().getMethod("resourceEstimate", long.class, int.class).invoke(realBuilder, l, i); } catch (Exception e) {}
            return this;
        }
        @Override public TaskBuilder onProgress(java.util.function.Consumer<Double> c) {
            try { realBuilder.getClass().getMethod("onProgress", java.util.function.Consumer.class).invoke(realBuilder, c); } catch (Exception e) {}
            return this;
        }
        @Override public TaskBuilder onFailure(java.util.function.Consumer<Throwable> c) {
            try { realBuilder.getClass().getMethod("onFailure", java.util.function.Consumer.class).invoke(realBuilder, c); } catch (Exception e) {}
            return this;
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> s) {
            try { return (CompletableFuture<T>) realBuilder.getClass().getMethod("submit", java.util.function.Supplier.class).invoke(realBuilder, s); }
            catch (Exception e) { return CompletableFuture.completedFuture(s.get()); }
        }
    }

    private static class ReflectiveCacheBuilder implements CacheBuilder {
        private final Object realBuilder;

        ReflectiveCacheBuilder(Object realBuilder) {
            this.realBuilder = realBuilder;
        }

        @Override public CacheBuilder ttl(java.time.Duration d) {
            try { realBuilder.getClass().getMethod("ttl", java.time.Duration.class).invoke(realBuilder, d); } catch (Exception e) {}
            return this;
        }
        @Override public CacheBuilder maxSize(long l) {
            try { realBuilder.getClass().getMethod("maxSize", long.class).invoke(realBuilder, l); } catch (Exception e) {}
            return this;
        }
        @Override public CacheBuilder compression(boolean b) {
            try { realBuilder.getClass().getMethod("compression", boolean.class).invoke(realBuilder, b); } catch (Exception e) {}
            return this;
        }
        @Override public CacheBuilder persistence(boolean b) {
            try { realBuilder.getClass().getMethod("persistence", boolean.class).invoke(realBuilder, b); } catch (Exception e) {}
            return this;
        }
        @SuppressWarnings("unchecked")
        @Override public <T> T get(String s, java.util.function.Supplier<T> s2) {
            try { return (T) realBuilder.getClass().getMethod("get", String.class, java.util.function.Supplier.class).invoke(realBuilder, s, s2); }
            catch (Exception e) { return s2.get(); }
        }
        @Override public <T> void put(String s, T t) {
            try { realBuilder.getClass().getMethod("put", String.class, Object.class).invoke(realBuilder, s, t); } catch (Exception e) {}
        }
        @Override public boolean contains(String s) {
            try { return (Boolean) realBuilder.getClass().getMethod("contains", String.class).invoke(realBuilder, s); }
            catch (Exception e) { return false; }
        }
        @Override public void remove(String s) {
            try { realBuilder.getClass().getMethod("remove", String.class).invoke(realBuilder, s); } catch (Exception e) {}
        }
        @Override public void clear() {
            try { realBuilder.getClass().getMethod("clear").invoke(realBuilder); } catch (Exception e) {}
        }
    }

    private static class ReflectiveHybridBuilder implements HybridBuilder {
        private final Object realBuilder;

        ReflectiveHybridBuilder(Object realBuilder) {
            this.realBuilder = realBuilder;
        }

        @Override public HybridBuilder priority(TaskBuilder.Priority p) {
            try { realBuilder.getClass().getMethod("priority", Enum.class).invoke(realBuilder, p); } catch (Exception e) {}
            return this;
        }
        @Override public HybridBuilder timeout(java.time.Duration d) {
            try { realBuilder.getClass().getMethod("timeout", java.time.Duration.class).invoke(realBuilder, d); } catch (Exception e) {}
            return this;
        }
        @Override public HybridBuilder ttl(java.time.Duration d) {
            try { realBuilder.getClass().getMethod("ttl", java.time.Duration.class).invoke(realBuilder, d); } catch (Exception e) {}
            return this;
        }
        @Override public HybridBuilder maxSize(long l) {
            try { realBuilder.getClass().getMethod("maxSize", long.class).invoke(realBuilder, l); } catch (Exception e) {}
            return this;
        }
        @Override public HybridBuilder compression(boolean b) {
            try { realBuilder.getClass().getMethod("compression", boolean.class).invoke(realBuilder, b); } catch (Exception e) {}
            return this;
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> s) {
            try { return (CompletableFuture<T>) realBuilder.getClass().getMethod("submit", java.util.function.Supplier.class).invoke(realBuilder, s); }
            catch (Exception e) { return CompletableFuture.completedFuture(s.get()); }
        }
    }

    private static class ReflectiveNetworkBuilder implements NetworkBuilder {
        private final Object realBuilder;

        ReflectiveNetworkBuilder(Object realBuilder) {
            this.realBuilder = realBuilder;
        }

        @Override public NetworkBuilder chunkSize(int i) {
            try { realBuilder.getClass().getMethod("chunkSize", int.class).invoke(realBuilder, i); } catch (Exception e) {}
            return this;
        }
        @Override public NetworkBuilder timeout(java.time.Duration d) {
            try { realBuilder.getClass().getMethod("timeout", java.time.Duration.class).invoke(realBuilder, d); } catch (Exception e) {}
            return this;
        }
        @Override public NetworkBuilder retryPolicy(RetryPolicy r) {
            try { realBuilder.getClass().getMethod("retryPolicy", Enum.class).invoke(realBuilder, r); } catch (Exception e) {}
            return this;
        }
        @Override public NetworkBuilder callback(java.util.function.Consumer<Object> c) {
            try { realBuilder.getClass().getMethod("callback", java.util.function.Consumer.class).invoke(realBuilder, c); } catch (Exception e) {}
            return this;
        }
        @Override
        @SuppressWarnings("unchecked")
        public CompletableFuture<Void> send(Object o) {
            try { return (CompletableFuture<Void>) realBuilder.getClass().getMethod("send", Object.class).invoke(realBuilder, o); }
            catch (Exception e) { return CompletableFuture.completedFuture(null); }
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> sendAndReceive(Object o) {
            try { return (CompletableFuture<T>) realBuilder.getClass().getMethod("sendAndReceive", Object.class).invoke(realBuilder, o); }
            catch (Exception e) { return CompletableFuture.completedFuture(null); }
        }
    }

    private static class ReflectiveModStatistics implements ModStatistics {
        private final Object realStats;

        ReflectiveModStatistics(Object realStats) {
            this.realStats = realStats;
        }

        @Override public String getModId() {
            try { return (String) realStats.getClass().getMethod("getModId").invoke(realStats); }
            catch (Exception e) { return "unknown"; }
        }
        @Override public String getModVersion() {
            try { return (String) realStats.getClass().getMethod("getModVersion").invoke(realStats); }
            catch (Exception e) { return "0.0.0"; }
        }
        @Override public java.time.Instant getLastActivity() {
            try { return (java.time.Instant) realStats.getClass().getMethod("getLastActivity").invoke(realStats); }
            catch (Exception e) { return java.time.Instant.now(); }
        }
        @Override public long getTotalTasksSubmitted() {
            try { return (Long) realStats.getClass().getMethod("getTotalTasksSubmitted").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getTasksCompleted() {
            try { return (Long) realStats.getClass().getMethod("getTasksCompleted").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getTasksFailed() {
            try { return (Long) realStats.getClass().getMethod("getTasksFailed").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getCurrentQueueDepth() {
            try { return (Long) realStats.getClass().getMethod("getCurrentQueueDepth").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public boolean isThrottled() {
            try { return (Boolean) realStats.getClass().getMethod("isThrottled").invoke(realStats); }
            catch (Exception e) { return false; }
        }
        @Override public double getThrottleFactor() {
            try { return (Double) realStats.getClass().getMethod("getThrottleFactor").invoke(realStats); }
            catch (Exception e) { return 0.0; }
        }
        @Override public java.time.Duration getAverageTaskTime() {
            try { return (java.time.Duration) realStats.getClass().getMethod("getAverageTaskTime").invoke(realStats); }
            catch (Exception e) { return java.time.Duration.ZERO; }
        }
        @Override public java.time.Duration getMaxTaskTime() {
            try { return (java.time.Duration) realStats.getClass().getMethod("getMaxTaskTime").invoke(realStats); }
            catch (Exception e) { return java.time.Duration.ZERO; }
        }
        @Override public double getTasksPerSecond() {
            try { return (Double) realStats.getClass().getMethod("getTasksPerSecond").invoke(realStats); }
            catch (Exception e) { return 0.0; }
        }
        @Override public double getCacheHitRate() {
            try { return (Double) realStats.getClass().getMethod("getCacheHitRate").invoke(realStats); }
            catch (Exception e) { return 0.0; }
        }
        @Override public long getCacheSize() {
            try { return (Long) realStats.getClass().getMethod("getCacheSize").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getCacheMaxSize() {
            try { return (Long) realStats.getClass().getMethod("getCacheMaxSize").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getCacheEvictions() {
            try { return (Long) realStats.getClass().getMethod("getCacheEvictions").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getCacheMemoryUsage() {
            try { return (Long) realStats.getClass().getMethod("getCacheMemoryUsage").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getPacketsSent() {
            try { return (Long) realStats.getClass().getMethod("getPacketsSent").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getPacketsReceived() {
            try { return (Long) realStats.getClass().getMethod("getPacketsReceived").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getNetworkErrors() {
            try { return (Long) realStats.getClass().getMethod("getNetworkErrors").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public long getNetworkBytesTransferred() {
            try { return (Long) realStats.getClass().getMethod("getNetworkBytesTransferred").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public java.time.Duration getTotalGPUTime() {
            try { return (java.time.Duration) realStats.getClass().getMethod("getTotalGPUTime").invoke(realStats); }
            catch (Exception e) { return java.time.Duration.ZERO; }
        }
        @Override public long getPeakVRAMUsage() {
            try { return (Long) realStats.getClass().getMethod("getPeakVRAMUsage").invoke(realStats); }
            catch (Exception e) { return 0; }
        }
        @Override public double getGPUUtilization() {
            try { return (Double) realStats.getClass().getMethod("getGPUUtilization").invoke(realStats); }
            catch (Exception e) { return 0.0; }
        }
        @Override public double getCPUFallbackRate() {
            try { return (Double) realStats.getClass().getMethod("getCPUFallbackRate").invoke(realStats); }
            catch (Exception e) { return 0.0; }
        }
    }

    // === FALLBACK IMPLEMENTATION ===

    private static class FallbackMod {
        private final String modId;
        private final String version;
        private final String displayName;

        FallbackMod(String modId, String version, String displayName) {
            this.modId = modId;
            this.version = version;
            this.displayName = displayName;
        }

        @SuppressWarnings("unused")
        public String getModId() { return modId; }
        @SuppressWarnings("unused")
        public String getVersion() { return version; }
        @SuppressWarnings("unused")
        public String getDisplayName() { return displayName; }

        public ModStatistics getStatistics() {
            return new FallbackStatistics();
        }

        public TaskBuilder task(String name) {
            return new FallbackTaskBuilder();
        }

        public CacheBuilder cache(CacheType cacheType) {
            return new FallbackCacheBuilder(cacheType);
        }

        public HybridBuilder hybrid(String name) {
            return new FallbackHybridBuilder();
        }

        public NetworkBuilder network(String channel) {
            return new FallbackNetworkBuilder();
        }

        @SuppressWarnings("unused")
        public void disconnect() {
            // No-op for fallback
        }
    }

    private static class FallbackStatistics implements ModStatistics {
        @Override public String getModId() { return "fallback"; }
        @Override public String getModVersion() { return "0.0.0"; }
        @Override public java.time.Instant getLastActivity() { return java.time.Instant.now(); }
        @Override public long getTotalTasksSubmitted() { return 0; }
        @Override public long getTasksCompleted() { return 0; }
        @Override public long getTasksFailed() { return 0; }
        @Override public long getCurrentQueueDepth() { return 0; }
        @Override public boolean isThrottled() { return false; }
        @Override public double getThrottleFactor() { return 0.0; }
        @Override public java.time.Duration getAverageTaskTime() { return java.time.Duration.ZERO; }
        @Override public java.time.Duration getMaxTaskTime() { return java.time.Duration.ZERO; }
        @Override public double getTasksPerSecond() { return 0.0; }
        @Override public double getCacheHitRate() { return 0.0; }
        @Override public long getCacheSize() { return 0; }
        @Override public long getCacheMaxSize() { return 0; }
        @Override public long getCacheEvictions() { return 0; }
        @Override public long getCacheMemoryUsage() { return 0; }
        @Override public long getPacketsSent() { return 0; }
        @Override public long getPacketsReceived() { return 0; }
        @Override public long getNetworkErrors() { return 0; }
        @Override public long getNetworkBytesTransferred() { return 0; }
        @Override public java.time.Duration getTotalGPUTime() { return java.time.Duration.ZERO; }
        @Override public long getPeakVRAMUsage() { return 0; }
        @Override public double getGPUUtilization() { return 0.0; }
        @Override public double getCPUFallbackRate() { return 0.0; }
    }

    private static class FallbackTaskBuilder implements TaskBuilder {
        @Override public TaskBuilder priority(Priority p) { return this; }
        @Override public TaskBuilder timeout(java.time.Duration d) { return this; }
        @Override public TaskBuilder retry(int i) { return this; }
        @Override public TaskBuilder resourceEstimate(long l, int i) { return this; }
        @Override public TaskBuilder onProgress(java.util.function.Consumer<Double> c) { return this; }
        @Override public TaskBuilder onFailure(java.util.function.Consumer<Throwable> c) { return this; }
        @Override public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> s) {
            try {
                return CompletableFuture.completedFuture(s.get());
            } catch (Throwable t) {
                CompletableFuture<T> cf = new CompletableFuture<>();
                cf.completeExceptionally(t);
                return cf;
            }
        }
    }

    private static class FallbackCacheBuilder implements CacheBuilder {
        private final CacheType cacheType;

        FallbackCacheBuilder(CacheType cacheType) {
            this.cacheType = cacheType;
            fallbackCaches.computeIfAbsent(cacheType, k -> new java.util.concurrent.ConcurrentHashMap<>());
        }

        @Override public CacheBuilder ttl(java.time.Duration d) { return this; }
        @Override public CacheBuilder maxSize(long l) { return this; }
        @Override public CacheBuilder compression(boolean b) { return this; }
        @Override public CacheBuilder persistence(boolean b) { return this; }
        @Override @SuppressWarnings("unchecked")
        public <T> T get(String key, java.util.function.Supplier<T> loader) {
            java.util.concurrent.ConcurrentHashMap<String, Object> map = fallbackCaches.get(cacheType);
            Object v = map.get(key);
            if (v != null) return (T) v;
            T loaded = loader.get();
            map.put(key, loaded);
            return loaded;
        }
        @Override public <T> void put(String key, T value) { fallbackCaches.computeIfAbsent(cacheType, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(key, value); }
        @Override public boolean contains(String s) { return fallbackCaches.getOrDefault(cacheType, new java.util.concurrent.ConcurrentHashMap<>()).containsKey(s); }
        @Override public void remove(String s) { fallbackCaches.getOrDefault(cacheType, new java.util.concurrent.ConcurrentHashMap<>()).remove(s); }
        @Override public void clear() { fallbackCaches.getOrDefault(cacheType, new java.util.concurrent.ConcurrentHashMap<>()).clear(); }
    }

    private static class FallbackHybridBuilder implements HybridBuilder {
        @Override public HybridBuilder priority(TaskBuilder.Priority p) { return this; }
        @Override public HybridBuilder timeout(java.time.Duration d) { return this; }
        @Override public HybridBuilder ttl(java.time.Duration d) { return this; }
        @Override public HybridBuilder maxSize(long l) { return this; }
        @Override public HybridBuilder compression(boolean b) { return this; }
        @Override public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> s) { return CompletableFuture.completedFuture(s.get()); }
    }

    private static class FallbackNetworkBuilder implements NetworkBuilder {
        @Override public NetworkBuilder chunkSize(int i) { return this; }
        @Override public NetworkBuilder timeout(java.time.Duration d) { return this; }
        @Override public NetworkBuilder retryPolicy(RetryPolicy r) { return this; }
        @Override public NetworkBuilder callback(java.util.function.Consumer<Object> c) { return this; }
        @Override public CompletableFuture<Void> send(Object o) { return CompletableFuture.completedFuture(null); }
        @Override public <T> CompletableFuture<T> sendAndReceive(Object o) { return CompletableFuture.completedFuture(null); }
    }
}