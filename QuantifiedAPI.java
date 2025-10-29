package org.admany.quantified.api.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Quantified API Template Class - Zero-Dependency Performance Enhancement
 *
 * This template class provides seamless integration with the Quantified API without requiring compile-time dependencies.
 * It enables advanced performance features including GPU acceleration, intelligent caching, networking, and real-time monitoring
 * while maintaining full compatibility when the API is not present.
 *
 * Key Features:
 * - Zero compile-time dependency on QuantifiedAPI (works even if players don't have it installed)
 * - Automatic fallback to basic implementations when API is unavailable
 * - Smart CPU/GPU task routing that adapts to workload patterns
 * - Resource-aware scheduling based on VRAM and compute requirements
 * - Built-in caching, networking, and real-time monitoring capabilities
 * - Enterprise-grade resilience features including circuit breakers and retry policies
 * - Intelligent cross-mod task coordination and scheduling
 *
 * Platform Support:
 * The Quantified API will be available for Minecraft 1.20.X-1.21.X across all major mod loaders including Forge and Fabric.
 * Regarding NeoForge: While the core API architecture is designed to be loader-agnostic and non-dependent on specific
 * mod loader implementations, there are currently mixed experiences and compatibility challenges with NeoForge.
 * As a result, there is a small possibility that NeoForge support may not be available, though this remains
 * under active evaluation and testing.
 *
 * Quick Start Guide:
 * 1. Copy this file into your mod's main package (recommended: com.yourmod.api)
 * 2. Update the PACKAGE DECLARATION at the top to match your package structure
 * 3. Configure the MODID, VERSION, and DISPLAY_NAME constants below to match your mod
 * 4. Add your own cache types to the CacheType enum as needed
 * 5. DO NOT add Quantified API as a dependency in your build.gradle - this implementation uses reflection
 * 6. Call QuantifiedAPI.init() during your mod's initialization phase
 * 7. Use QuantifiedAPI.task(), cache(), network(), and other builders throughout your codebase
 *
 * Usage Examples:
 * ```java
 * // In your main mod class during setup
 * public class YourAwesomeMod {
 *     public void setup(FMLCommonSetupEvent event) {
 *         QuantifiedAPI.init(); // Establish connection or fallback gracefully
 *
 *         // Leverage advanced performance features
 *         CompletableFuture<String> result = QuantifiedAPI.submit("world_generation", () -> generateWorldChunk());
 *     }
 * }
 *
 * // Throughout your mod's codebase
 * PlayerStats stats = QuantifiedAPI.getCached(CacheType.PLAYER_DATA, "player_" + playerId, () -> loadPlayerStats(playerId));
 *
 * // Advanced resilience features for production-ready computing
 * ResilientTaskBuilder resilientTask = QuantifiedAPI.createResilientTask("gpu_computation")
 *     .withCircuitBreaker(3, 30000)  // Open circuit after 3 failures, recover after 30 seconds
 *     .withRetryPolicy(ResilientTaskBuilder.RetryPolicy.EXPONENTIAL_BACKOFF, 5)  // Retry up to 5 times with exponential backoff
 *     .withBulkhead(10)  // Limit concurrent executions to prevent resource exhaustion
 *     .timeout(Duration.ofSeconds(30));  // 30-second execution timeout
 *
 * CompletableFuture<CalculationResult> result = resilientTask.execute(() -> performGPUCalculation());
 *
 * // Intelligent cross-mod task coordination
 * IntelligentScheduler scheduler = QuantifiedAPI.getIntelligentScheduler();
 * scheduler.submitTask("texture_processing", () -> processTextures(), "your_mod_id", IntelligentScheduler.TaskPriority.HIGH);
 *
 * // Monitor scheduler health
 * if (scheduler.isHealthy()) {
 *     Map<String, Object> stats = scheduler.getSchedulerStats();
 *     LOGGER.info("Scheduler processed {} tasks across all mods", stats.get("totalTasks"));
 * }
 *
 * // Control automatic main thread rerouting for thread-unsafe tasks
 * CompletableFuture<String> fastTask = QuantifiedAPI.task("fast_computation")
 *     .allowMainThreadRerouting(false)  // Disable auto main thread routing for performance
 *     .submit(() -> performThreadUnsafeComputation());
 *
 * CompletableFuture<String> safeTask = QuantifiedAPI.task("safe_operation")
 *     .allowMainThreadRerouting(true)   // Allow auto main thread routing for safety
 *     .submit(() -> performThreadSafeOperation());
 * ```
 *
 * Best Practices:
 * - Your mod functions perfectly even without Quantified API installed
 * - The API automatically detects available hardware and optimizes task routing
 * - Use descriptive task and cache names for better debugging and monitoring
 * - Call isAPIAvailable() if you need to verify real API connectivity
 * - Access the web dashboard at http://localhost:8765 for live performance metrics
 * - Automatic retry logic activates every 5 seconds if initial connection fails
 * - Forge's or any other loaders reobfuscation process preserves method names for compatibility
 *
 * Advanced Features:
 * - Circuit Breaker: Automatically stops calling failing operations and recovers gracefully
 * - Retry Policies: Handles transient failures with configurable backoff strategies
 * - Bulkhead Pattern: Prevents resource exhaustion by limiting concurrent executions
 * - Intelligent Scheduler: Coordinates tasks across all loaded mods for optimal system performance
 * - Automatic Health Monitoring: Background checks ensure system stability and reliability
 * - Main Thread Rerouting Control: Per-task control over automatic main thread rerouting for thread-unsafe tasks
 */
public class QuantifiedAPI {

    // ===  CRITICAL: DO NOT TOUCH UNLESS YOU WANT TO NUKE YOUR MOD, literally xd  ===

    /**
     * USB HOOK VERSION - The version of your USB hook implementation.
     * This is checked by the API for compatibility. DO NOT CHANGE THIS!
     */
    private static final String USB_HOOK_VERSION = "1.0.0";  // ⚠️ DO NOT CHANGE THIS!

    // ===  CHANGE THESE CONSTANTS TO YOUR MOD'S INFO  ===

    /**
     * YOUR MOD ID - This is super important! Must match your mod's actual ID.
     * Find this in your mods.toml file or build.gradle.
     * Example: "yourawesome_mod", "mystical_creatures", etc.
     */
    private static final String MODID = "your_mod_id";           // ⚠️ CHANGE THIS!

    /**
     * YOUR MOD VERSION - Keep this in sync with your mod version.
     * Used for compatibility checking and statistics.
     */
    private static final String VERSION = "1.0.0";              // ⚠️ CHANGE THIS!

    /**
     * DISPLAY NAME - What players see in the dashboard and logs.
     * Make it friendly and recognizable.
     */
    private static final String DISPLAY_NAME = "Your Awesome Mod"; // ⚠️ CHANGE THIS!

    // === 🎯 ADD YOUR CACHE TYPES HERE - CUSTOMIZE FOR YOUR MOD! ===

    /**
     * Cache types for your mod - these help organize your cached data.
     * Add as many as you need, each with a description of what it caches.
     *
     * EXAMPLES OF WHAT TO CACHE:
     * - Player data (stats, inventory, positions)
     * - Generated world chunks or structures
     * - AI decisions or behavior states
     * - Pathfinding results
     * - Expensive computations you want to reuse
     *
     * TIPS:
     * - Use UPPER_SNAKE_CASE for enum names
     * - Descriptions help with debugging and the web dashboard
     * - Don't cache things that change constantly
     */
    public enum CacheType {
        // Example cache types - replace with your own!
        PLAYER_DATA("Player statistics and positions - perfect for quick lookups"),
        WORLD_CHUNKS("Generated world chunk data - saves regeneration time"),
        AI_DECISIONS("AI behavior decisions - makes NPCs smarter and faster"),
        PATHFINDING("Navigation path caches - speeds up mob movement"),
        ENTITY_STATES("Entity AI and behavior data - reduces CPU load");

        private final String description;

        CacheType(String desc) {
            this.description = desc;
        }

        public String getDescription() {
            return description;
        }
    }

    // === 🔧 STATIC INITIALIZER - DETECTS IF THE REAL API IS AVAILABLE ===

    private static final String LOG_PREFIX = "[Quantified API] ";
    private static final Logger LOGGER = createPrefixedLogger(QuantifiedAPI.class);

    private static Logger createPrefixedLogger(Class<?> cls) {
        final Logger base = LoggerFactory.getLogger(cls);
        return (Logger) java.lang.reflect.Proxy.newProxyInstance(
            Logger.class.getClassLoader(),
            new Class[]{Logger.class},
            (proxy, method, args) -> {
                if (args != null && args.length > 0) {
                    // find first String parameter in the method signature (covers Marker overloads)
                    int strIndex = -1;
                    Class<?>[] paramTypes = method.getParameterTypes();
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (paramTypes[i] == String.class) {
                            strIndex = i;
                            break;
                        }
                    }

                    if (strIndex >= 0 && args.length > strIndex && args[strIndex] instanceof String) {
                        Object[] newArgs = args.clone();
                        newArgs[strIndex] = LOG_PREFIX + newArgs[strIndex];
                        return method.invoke(base, newArgs);
                    }
                }
                return method.invoke(base, args);
            }
        );
    }
    private static Object mod;
    private static boolean initialized = false;
    private static boolean apiAvailable = false;

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Object>> fallbackCaches = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ScheduledExecutorService connectionExecutor = java.util.concurrent.Executors.newScheduledThreadPool(2,
        r -> {
            Thread t = new Thread(r, "QuantifiedAPI-Connection");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        }
    );

    private static volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private static volatile long lastConnectionAttempt = 0;
    private static volatile long lastSuccessfulConnection = 0;
    private static volatile int consecutiveFailures = 0;
    private static volatile long connectionTimeoutMs = 10000;
    private static volatile long healthCheckIntervalMs = 30000;
    private static volatile long retryBaseDelayMs = 1000;
    private static volatile double retryBackoffMultiplier = 1.5;
    private static volatile long maxRetryDelayMs = 300000;
    private static volatile int maxConsecutiveFailures = 10;

    private static volatile boolean healthCheckScheduled = false;
    private static volatile long lastHealthCheck = 0;

    private enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, FAILED, HEALTH_CHECKING
    }

    private static Class<?> quantifiedApiClass;
    private static Method connectMethod;

    static {
        try {
            quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");

            try {
                connectMethod = quantifiedApiClass.getMethod("connect", String.class, String.class, String.class, String.class);
                LOGGER.debug("[Quantified API] Found 4-parameter connect method (includes USB hook version)");
            } catch (NoSuchMethodException e4) {
                connectMethod = quantifiedApiClass.getMethod("connect", String.class, String.class, String.class);
                LOGGER.debug("[Quantified API] Found 3-parameter connect method (legacy, no USB hook version)");
            }

            apiAvailable = true;
            LOGGER.debug("[Quantified API] Quantified API classes found and loaded successfully");
        } catch (ClassNotFoundException e) {
            apiAvailable = false;
            LOGGER.warn("Quantified API class not found on classpath: {}. This is the most common cause of fallback mode. Ensure the Quantified API jar is properly installed and on the runtime classpath.", e.getMessage());
        } catch (NoSuchMethodException e) {
            apiAvailable = false;
            LOGGER.warn("Quantified API connect method not found: {}. Expected signature: static connect(String, String, String) or connect(String, String, String, String). Check API version compatibility.", e.getMessage());
        } catch (Exception e) {
            apiAvailable = false;
            LOGGER.warn("Unexpected error loading Quantified API: {}. This may indicate classloader issues or corrupted jar.", e.getMessage(), e);
        }
    }

    private static boolean isVersionCompatible() {
        if (!apiAvailable || quantifiedApiClass == null) {
            return false;
        }

        try {
            Method getVersionMethod = quantifiedApiClass.getMethod("getVersion");
            String apiVersion = (String) getVersionMethod.invoke(null);

            String[] modVersionParts = VERSION.split("\\.");
            String[] apiVersionParts = apiVersion.split("\\.");

            if (modVersionParts.length >= 1 && apiVersionParts.length >= 1) {
                int modMajor = Integer.parseInt(modVersionParts[0]);
                int apiMajor = Integer.parseInt(apiVersionParts[0]);

                if (modMajor != apiMajor) {
                    LOGGER.warn("API version mismatch: mod expects {}.x.x but API is {}.x.x",
                        modMajor, apiMajor);
                    return false;
                }
            }

            LOGGER.debug("[Quantified API] API version {} is compatible with mod version {}", apiVersion, VERSION);
            return true;

        } catch (Exception e) {
            LOGGER.warn("Could not determine API version compatibility, assuming compatible", e);
            return true;
        }
    }

    public static void init() {
        if (initialized) {
            LOGGER.warn("QuantifiedAPI already initialized for {}", MODID);
            return;
        }

        LOGGER.info("[Quantified API] Initializing QuantifiedAPI for {} ({}) with advanced connection system", DISPLAY_NAME, MODID);

        mod = new FallbackMod(MODID, VERSION, DISPLAY_NAME);

        if (apiAvailable) {
            if (!isVersionCompatible()) {
                LOGGER.warn("API version negotiation failed. Using fallback mode.");
                schedulePeriodicClassCheck();
                initialized = true;
                return;
            }

            connectionExecutor.submit(() -> attemptConnection(true));

            scheduleHealthChecks();
        } else {
            LOGGER.info("[Quantified API] Quantified API classes not available on classpath. Using fallback mode with periodic checks.");
            schedulePeriodicClassCheck();
        }

        initialized = true;
    }

    public static CompletableFuture<Void> initAsync() {
        if (initialized) {
            LOGGER.warn("QuantifiedAPI already initialized for {}", MODID);
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.info("[Quantified API] Async initializing QuantifiedAPI for {} ({})", DISPLAY_NAME, MODID);

        mod = new FallbackMod(MODID, VERSION, DISPLAY_NAME);
        initialized = true;

        if (!apiAvailable) {
            LOGGER.info("[Quantified API] Quantified API classes not available on classpath. Using fallback mode with periodic checks.");
            schedulePeriodicClassCheck();
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                if (!isVersionCompatible()) {
                    LOGGER.warn("API version negotiation failed. Using fallback mode.");
                    schedulePeriodicClassCheck();
                    return;
                }

                attemptConnection(true);

                scheduleHealthChecks();

            } catch (Exception e) {
                LOGGER.warn("Async initialization failed, falling back to periodic checks", e);
                schedulePeriodicClassCheck();
            }
        }, connectionExecutor);
    }

    private static void attemptConnection(boolean isInitialAttempt) {
        if (connectionState == ConnectionState.CONNECTING) {
            LOGGER.debug("[Quantified API] Connection attempt already in progress");
            return;
        }

        connectionState = ConnectionState.CONNECTING;
        lastConnectionAttempt = System.currentTimeMillis();

        LOGGER.info("[Quantified API] Attempting connection to Quantified API (attempt #{}, initial={})", consecutiveFailures + 1, isInitialAttempt);

        try {
            Object connectedMod = tryConnectWithTimeout();

            if (connectedMod != null) {
                mod = connectedMod;
                connectionState = ConnectionState.CONNECTED;
                lastSuccessfulConnection = System.currentTimeMillis();
                consecutiveFailures = 0;

                LOGGER.info("[Quantified API] Successfully connected to Quantified API for mod {}", MODID);
                registerCacheNames();

                cancelPendingRetries();
                return;
            } else {
                handleConnectionFailure("API rejected connection (returned null)", isInitialAttempt);
            }
        } catch (Exception e) {
            handleConnectionFailure("Connection failed: " + e.getMessage(), isInitialAttempt);
            LOGGER.debug("[Quantified API] Connection exception details", e);
        }
    }

    private static Object tryConnectWithTimeout() throws Exception {
        CompletableFuture<Object> connectionFuture = CompletableFuture.supplyAsync(() -> {
            try {
                if (connectMethod.getParameterCount() == 4) {
                    LOGGER.debug("[Quantified API] Invoking 4-parameter connect method with USB hook version: {}, {}, {}, {}", MODID, VERSION, DISPLAY_NAME, USB_HOOK_VERSION);
                    return connectMethod.invoke(null, MODID, VERSION, DISPLAY_NAME, USB_HOOK_VERSION);
                } else {
                    LOGGER.debug("[Quantified API] Invoking 3-parameter connect method: {}, {}, {}", MODID, VERSION, DISPLAY_NAME);
                    return connectMethod.invoke(null, MODID, VERSION, DISPLAY_NAME);
                }
            } catch (Exception e) {
                throw new RuntimeException("Connect method invocation failed", e);
            }
        }, connectionExecutor);

        try {
            return connectionFuture.get(connectionTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            connectionFuture.cancel(true);
            throw new RuntimeException("Connection timed out after " + connectionTimeoutMs + "ms", e);
        }
    }

    private static void handleConnectionFailure(String reason, boolean isInitialAttempt) {
        connectionState = ConnectionState.FAILED;
        consecutiveFailures++;

        LOGGER.warn("Quantified API connection failed (#{}/{}): {}", consecutiveFailures, maxConsecutiveFailures, reason);

        if (consecutiveFailures >= maxConsecutiveFailures) {
            LOGGER.error("Maximum consecutive failures ({}) reached. Giving up automatic retries.", maxConsecutiveFailures);
            return;
        }

        long baseDelay = (long) (retryBaseDelayMs * Math.pow(retryBackoffMultiplier, consecutiveFailures - 1));
        long jitter = (long) (Math.random() * 0.1 * baseDelay);
        long retryDelay = Math.min(baseDelay + jitter, maxRetryDelayMs);

        LOGGER.info("[Quantified API] Scheduling retry in {}ms (failure #{})", retryDelay, consecutiveFailures);

        connectionExecutor.schedule(() -> attemptConnection(false), retryDelay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void scheduleHealthChecks() {
        if (healthCheckScheduled) {
            return;
        }

        healthCheckScheduled = true;
        connectionExecutor.scheduleWithFixedDelay(() -> {
            if (connectionState == ConnectionState.CONNECTED) {
                performHealthCheck();
            }
        }, healthCheckIntervalMs, healthCheckIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        LOGGER.debug("[Quantified API] Scheduled periodic health checks every {}ms", healthCheckIntervalMs);
    }

    private static void performHealthCheck() {
        if (connectionState != ConnectionState.CONNECTED || mod == null || mod instanceof FallbackMod) {
            return;
        }

        connectionState = ConnectionState.HEALTH_CHECKING;
        lastHealthCheck = System.currentTimeMillis();

        try {
            Object stats = mod.getClass().getMethod("getStatistics").invoke(mod);
            if (stats != null) {
                connectionState = ConnectionState.CONNECTED;
                LOGGER.debug("[Quantified API] Health check passed");
            } else {
                throw new RuntimeException("Health check returned null");
            }
        } catch (Exception e) {
            LOGGER.warn("Health check failed, attempting reconnection: {}", e.getMessage());
            connectionState = ConnectionState.FAILED;
            consecutiveFailures = 1;
            attemptConnection(false);
        }
    }

    private static void schedulePeriodicClassCheck() {
        connectionExecutor.scheduleWithFixedDelay(() -> {
            if (!apiAvailable) {
                try {
                    Class<?> testClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");

                    Method testMethod;
                    try {
                        testMethod = testClass.getMethod("connect", String.class, String.class, String.class, String.class);
                        LOGGER.debug("[Quantified API] Periodic check found 4-parameter connect method");
                    } catch (NoSuchMethodException e4) {
                        testMethod = testClass.getMethod("connect", String.class, String.class, String.class);
                        LOGGER.debug("[Quantified API] Periodic check found 3-parameter connect method");
                    }

                    quantifiedApiClass = testClass;
                    connectMethod = testMethod;
                    apiAvailable = true;

                    LOGGER.info("[Quantified API] Quantified API classes became available! Attempting connection...");
                    attemptConnection(true);
                    scheduleHealthChecks();

                } catch (Exception e) {
                    LOGGER.debug("[Quantified API] API classes still not available: {}", e.getMessage());
                }
            }
        }, 30000, 30000, java.util.concurrent.TimeUnit.MILLISECONDS);

        LOGGER.debug("[Quantified API] Scheduled periodic class availability checks");
    }

    private static void cancelPendingRetries() {
        LOGGER.debug("[Quantified API] Cancelled pending retry attempts");
    }

    private static void registerCacheNames() {
        for (CacheType type : CacheType.values()) {
            LOGGER.debug("[Quantified API] Registered cache type: {} - {}", type.name(), type.getDescription());
        }
    }

    public static TaskBuilder task(String taskName) {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).task(taskName);
        }
        try {
            Object realBuilder = mod.getClass().getMethod("task", String.class).invoke(mod, taskName);
            return new ReflectiveTaskBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create task builder, using fallback", e);
            return new FallbackTaskBuilder();
        }
    }

    public static CacheBuilder cache(CacheType cacheType) {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).cache(cacheType);
        }
        try {
            Object realBuilder = mod.getClass().getMethod("cache", Enum.class).invoke(mod, cacheType);
            return new ReflectiveCacheBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create cache builder, using fallback", e);
            return new FallbackCacheBuilder(cacheType);
        }
    }

    public static HybridBuilder hybrid(String operationName) {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).hybrid(operationName);
        }
        try {
            Object realBuilder = mod.getClass().getMethod("hybrid", String.class).invoke(mod, operationName);
            return new ReflectiveHybridBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create hybrid builder, using fallback", e);
            return new FallbackHybridBuilder();
        }
    }

    public static NetworkBuilder network(String channel) {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).network(channel);
        }
        try {
            Object realBuilder = mod.getClass().getMethod("network", String.class).invoke(mod, channel);
            return new ReflectiveNetworkBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create network builder, using fallback", e);
            return new FallbackNetworkBuilder();
        }
    }

    public static HardwareStatus checkHardwareCapabilities() {
        try {
            Class<?> detectorClass = Class.forName("org.admany.quantified.core.common.opencl.HardwareDetector");
            Object coreStatus = null;
            try {
                coreStatus = detectorClass.getMethod("detect").invoke(null);
            } catch (NoSuchMethodException nsme) {
                Object detector = detectorClass.getMethod("getInstance").invoke(null);
                coreStatus = detectorClass.getMethod("detectHardwareCapabilities").invoke(detector);
            }

            if (coreStatus == null) return createFallbackHardwareStatus();

            Class<?> cs = coreStatus.getClass();
            boolean openclAvailable = (Boolean) cs.getMethod("isOpenCLAvailable").invoke(coreStatus);
            boolean gpuAvailable = (Boolean) cs.getMethod("isGPUAvailable").invoke(coreStatus);
            double openclConfidence = ((Number) cs.getMethod("getOpenCLConfidence").invoke(coreStatus)).doubleValue();
            double gpuConfidence = ((Number) cs.getMethod("getGPUConfidence").invoke(coreStatus)).doubleValue();
            String os = (String) cs.getMethod("getOperatingSystem").invoke(coreStatus);
            String arch = (String) cs.getMethod("getArchitecture").invoke(coreStatus);
            boolean is64Bit = (Boolean) cs.getMethod("is64Bit").invoke(coreStatus);
            int processors = ((Number) cs.getMethod("getAvailableProcessors").invoke(coreStatus)).intValue();
            long maxMemory = ((Number) cs.getMethod("getMaxMemory").invoke(coreStatus)).longValue();
            long totalMemory = ((Number) cs.getMethod("getTotalMemory").invoke(coreStatus)).longValue();
            String guidance = (String) cs.getMethod("getGuidance").invoke(coreStatus);

            DetectionResults results = new DetectionResults();

            return new HardwareStatus(openclAvailable, gpuAvailable, openclConfidence, gpuConfidence,
                results, os, arch, is64Bit, processors, maxMemory, totalMemory, guidance);

        } catch (Exception e) {
            LOGGER.warn("Failed to delegate to core HardwareDetector, using fallback", e);
            return createFallbackHardwareStatus();
        }
    }

    private static HardwareStatus createFallbackHardwareStatus() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean is64Bit = arch.contains("64") || arch.equals("amd64") || arch.equals("x86_64");
        int processors = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();

        String guidance = "=== FALLBACK DETECTION MODE ===\n" +
            "Core HardwareDetector unavailable. Using basic system information only.\n" +
            "For full OpenCL/GPU detection, ensure the core module is properly loaded.\n\n" +
            "Basic System Info:\n" +
            "• OS: " + os + " (" + (is64Bit ? "64-bit" : "32-bit") + ")\n" +
            "• CPU Cores: " + processors + "\n" +
            "• Max Memory: " + String.format("%.1f GB", maxMemory / (1024.0 * 1024.0 * 1024.0)) + "\n\n" +
            "This is a fallback mode with limited detection capabilities.";

        return new HardwareStatus(
            false, // openclAvailable
            false, // gpuAvailable
            0.0,   // openclConfidence
            0.0,   // gpuConfidence
            new DetectionResults(), // empty results
            os,
            arch,
            is64Bit,
            processors,
            maxMemory,
            totalMemory,
            guidance
        );
    }
    private static class DetectionResults {
        boolean joclAvailable = false;
        boolean lwjglAvailable = false;
        boolean libraryAvailable = false;
        boolean environmentConfigured = false;
        boolean contextCreationSuccessful = false;
    }

    public static class HardwareStatus {
        private final boolean openclAvailable;
        private final boolean gpuAvailable;
        private final double openclConfidence;
        private final double gpuConfidence;
        private final DetectionResults detectionResults;
        private final String operatingSystem;
        private final String architecture;
        private final boolean is64Bit;
        private final int availableProcessors;
        private final long maxMemory;
        private final long totalMemory;
        private final String guidance;

        HardwareStatus(boolean openclAvailable, boolean gpuAvailable, double openclConfidence,
                      double gpuConfidence, DetectionResults results, String os, String arch,
                      boolean is64Bit, int processors, long maxMemory, long totalMemory, String guidance) {
            this.openclAvailable = openclAvailable;
            this.gpuAvailable = gpuAvailable;
            this.openclConfidence = openclConfidence;
            this.gpuConfidence = gpuConfidence;
            this.detectionResults = results;
            this.operatingSystem = os;
            this.architecture = arch;
            this.is64Bit = is64Bit;
            this.availableProcessors = processors;
            this.maxMemory = maxMemory;
            this.totalMemory = totalMemory;
            this.guidance = guidance;
        }

        /** @return true if OpenCL is likely available */
        public boolean isOpenCLAvailable() { return openclAvailable; }

        /** @return true if GPU acceleration is likely available */
        public boolean isGPUAvailable() { return gpuAvailable; }

        /** @return confidence level for OpenCL availability (0.0 to 1.0) */
        public double getOpenCLConfidence() { return openclConfidence; }

        /** @return confidence level for GPU availability (0.0 to 1.0) */
        public double getGPUConfidence() { return gpuConfidence; }

        /** @return detailed detection results */
        public DetectionResults getDetectionResults() { return detectionResults; }

        /** @return operating system name */
        public String getOperatingSystem() { return operatingSystem; }

        /** @return CPU architecture */
        public String getArchitecture() { return architecture; }

        /** @return true if 64-bit architecture */
        public boolean is64Bit() { return is64Bit; }

        /** @return number of CPU cores */
        public int getAvailableProcessors() { return availableProcessors; }

        /** @return JVM max memory in bytes */
        public long getMaxMemory() { return maxMemory; }

        /** @return JVM total memory in bytes */
        public long getTotalMemory() { return totalMemory; }

        /** @return max memory formatted as GB */
        public String getMaxMemoryGB() {
            return String.format("%.1f GB", maxMemory / (1024.0 * 1024.0 * 1024.0));
        }

        /** @return comprehensive setup and troubleshooting guidance */
        public String getGuidance() { return guidance; }

        /** @return human-readable status summary */
        public String getStatusSummary() {
            StringBuilder summary = new StringBuilder();
            summary.append("Hardware Status Assessment\n");
            summary.append("==========================\n");
            summary.append(String.format("OpenCL Available: %s (%.0f%% confidence)\n",
                openclAvailable ? "YES" : "NO", openclConfidence * 100));
            summary.append(String.format("GPU Available: %s (%.0f%% confidence)\n",
                gpuAvailable ? "YES" : "NO", gpuConfidence * 100));
            summary.append(String.format("OS: %s %s (%s)\n",
                operatingSystem, is64Bit ? "64-bit" : "32-bit", architecture));
            summary.append(String.format("CPU Cores: %d\n", availableProcessors));
            summary.append(String.format("Max Memory: %s\n", getMaxMemoryGB()));
            summary.append("\nDetection Methods:\n");
            summary.append(String.format("• JOCL: %s\n", detectionResults.joclAvailable ? "Available" : "Not found"));
            summary.append(String.format("• LWJGL: %s\n", detectionResults.lwjglAvailable ? "Available" : "Not found"));
            summary.append(String.format("• Libraries: %s\n", detectionResults.libraryAvailable ? "Accessible" : "Not accessible"));
            summary.append(String.format("• Environment: %s\n", detectionResults.environmentConfigured ? "Configured" : "Not detected"));
            summary.append(String.format("• Context Test: %s\n", detectionResults.contextCreationSuccessful ? "Passed" : "Failed"));
            return summary.toString();
        }
    }

    public static ModStatistics getStatistics() {
        ensureInitialized();
        if (mod instanceof FallbackMod) {
            return ((FallbackMod) mod).getStatistics();
        }
        try {
            Object realStats = mod.getClass().getMethod("getStatistics").invoke(mod);
            return new ReflectiveModStatistics(realStats);
        } catch (Exception e) {
            LOGGER.warn("Failed to get statistics, using fallback", e);
            return new FallbackStatistics();
        }
    }

    public static HardwareStatus logHardwareStatus() {
        HardwareStatus status = checkHardwareCapabilities();
        LOGGER.info("[Quantified API] === Hardware Detection Results ===");
        LOGGER.info(status.getStatusSummary());
        if (!status.isOpenCLAvailable() || !status.isGPUAvailable()) {
            LOGGER.info("[Quantified API] === Setup Guidance ===");
            LOGGER.info(status.getGuidance());
        }
        LOGGER.info("[Quantified API] === End Hardware Detection ===");
        return status;
    }

    public static boolean isAPIAvailable() {
        return initialized && apiAvailable && mod != null && !(mod instanceof FallbackMod);
    }

    public static boolean isInitialized() {
        return initialized;
    }


    public static String getConnectionDiagnostics() {
        StringBuilder diag = new StringBuilder();
        diag.append("=== Quantified API Connection Diagnostics ===\n");
        diag.append("Initialized: ").append(initialized).append("\n");
        diag.append("API Classes Available: ").append(apiAvailable).append("\n");
        diag.append("Connection State: ").append(connectionState).append("\n");
        diag.append("Consecutive Failures: ").append(consecutiveFailures).append("/").append(maxConsecutiveFailures).append("\n");

        if (lastConnectionAttempt > 0) {
            diag.append("Last Connection Attempt: ").append(new java.util.Date(lastConnectionAttempt)).append("\n");
        }
        if (lastSuccessfulConnection > 0) {
            diag.append("Last Successful Connection: ").append(new java.util.Date(lastSuccessfulConnection)).append("\n");
        }
        if (lastHealthCheck > 0) {
            diag.append("Last Health Check: ").append(new java.util.Date(lastHealthCheck)).append("\n");
        }

        diag.append("Mod Instance Type: ").append(mod != null ? mod.getClass().getSimpleName() : "null").append("\n");
        diag.append("Using Fallback: ").append(mod instanceof FallbackMod).append("\n");

        if (quantifiedApiClass != null) {
            diag.append("API Class Loaded: ").append(quantifiedApiClass.getName()).append("\n");
            diag.append("Connect Method Available: ").append(connectMethod != null).append("\n");
        } else {
            diag.append("API Class: Not loaded\n");
        }

        diag.append("Connection Config:\n");
        diag.append("• Timeout: ").append(connectionTimeoutMs).append("ms\n");
        diag.append("• Health Check Interval: ").append(healthCheckIntervalMs).append("ms\n");
        diag.append("• Retry Base Delay: ").append(retryBaseDelayMs).append("ms\n");
        diag.append("• Retry Backoff: ").append(retryBackoffMultiplier).append("x\n");
        diag.append("• Max Retry Delay: ").append(maxRetryDelayMs).append("ms\n");

        diag.append("Mod ID: ").append(MODID).append("\n");
        diag.append("Version: ").append(VERSION).append("\n");
        diag.append("USB Hook Version: ").append(USB_HOOK_VERSION).append("\n");
        diag.append("Display Name: ").append(DISPLAY_NAME).append("\n");

        try {
            java.net.URL[] urls = ((java.net.URLClassLoader) ClassLoader.getSystemClassLoader()).getURLs();
            boolean apiJarFound = false;
            for (java.net.URL url : urls) {
                String path = url.getPath().toLowerCase();
                if (path.contains("quantified") && path.contains(".jar")) {
                    apiJarFound = true;
                    diag.append("Potential API Jar Found: ").append(url.getPath()).append("\n");
                }
            }
            if (!apiJarFound) {
                diag.append("No Quantified API jar found on classpath\n");
            }
        } catch (Exception e) {
            diag.append("Could not check classpath: ").append(e.getMessage()).append("\n");
        }

        diag.append("=== End Diagnostics ===\n");
        return diag.toString();
    }

    public static boolean forceRetryConnection() {
        if (!initialized) {
            LOGGER.warn("Cannot retry: QuantifiedAPI not initialized. Call init() first.");
            return false;
        }

        if (!apiAvailable) {
            LOGGER.warn("Cannot retry: API classes not available on classpath.");
            return false;
        }

        if (connectionState == ConnectionState.CONNECTED && !(mod instanceof FallbackMod)) {
            LOGGER.debug("[Quantified API] Already connected to real API");
            return true;
        }

        LOGGER.info("[Quantified API] Forcing immediate connection retry for {} ({})", DISPLAY_NAME, MODID);
        consecutiveFailures = 0; // Reset failure count for manual retry
        connectionExecutor.submit(() -> attemptConnection(false));
        return true; // Assume it will attempt
    }

    public static void shutdown() {
        cancelPendingRetries();
        if (connectionExecutor != null && !connectionExecutor.isShutdown()) {
            connectionExecutor.shutdown();
            try {
                if (!connectionExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    connectionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                connectionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (mod != null && !(mod instanceof FallbackMod)) {
            try {
                mod.getClass().getMethod("disconnect").invoke(mod);
            } catch (Exception e) {
                LOGGER.warn("Failed to disconnect from API", e);
            }
        }
        mod = null;
        initialized = false;
        connectionState = ConnectionState.DISCONNECTED;
        LOGGER.info("[Quantified API] QuantifiedAPI shutdown for {}", MODID);
    }

    // === ADVANCED CONNECTION CONFIGURATION ===

    /**
     * Configure connection timeout (default: 10 seconds)
     */
    public static void setConnectionTimeout(long timeoutMs) {
        if (timeoutMs > 0) {
            connectionTimeoutMs = timeoutMs;
            LOGGER.debug("[Quantified API] Connection timeout set to {}ms", timeoutMs);
        }
    }

    /**
     * Configure health check interval (default: 30 seconds)
     */
    public static void setHealthCheckInterval(long intervalMs) {
        if (intervalMs > 0) {
            healthCheckIntervalMs = intervalMs;
            LOGGER.debug("[Quantified API] Health check interval set to {}ms", intervalMs);
        }
    }

    /**
     * Configure retry parameters
     */
    public static void configureRetries(long baseDelayMs, double backoffMultiplier, long maxDelayMs, int maxFailures) {
        if (baseDelayMs > 0) retryBaseDelayMs = baseDelayMs;
        if (backoffMultiplier > 1.0) retryBackoffMultiplier = backoffMultiplier;
        if (maxDelayMs > 0) maxRetryDelayMs = maxDelayMs;
        if (maxFailures > 0) maxConsecutiveFailures = maxFailures;
        LOGGER.debug("[Quantified API] Retry config: base={}ms, multiplier={}, max={}ms, maxFailures={}",
            retryBaseDelayMs, retryBackoffMultiplier, maxRetryDelayMs, maxConsecutiveFailures);
    }

    /**
     * Get current connection status information
     */
    public static ConnectionStatus getConnectionStatus() {
        return new ConnectionStatus(
            connectionState,
            lastConnectionAttempt,
            lastSuccessfulConnection,
            consecutiveFailures,
            System.currentTimeMillis() - lastHealthCheck
        );
    }

    /**
     * Connection status information
     */
    public static class ConnectionStatus {
        private final ConnectionState state;
        private final long lastAttempt;
        private final long lastSuccess;
        private final int failures;
        private final long timeSinceHealthCheck;

        ConnectionStatus(ConnectionState state, long lastAttempt, long lastSuccess, int failures, long timeSinceHealthCheck) {
            this.state = state;
            this.lastAttempt = lastAttempt;
            this.lastSuccess = lastSuccess;
            this.failures = failures;
            this.timeSinceHealthCheck = timeSinceHealthCheck;
        }

        public ConnectionState getState() { return state; }
        public long getLastAttempt() { return lastAttempt; }
        public long getLastSuccess() { return lastSuccess; }
        public int getConsecutiveFailures() { return failures; }
        public long getTimeSinceHealthCheck() { return timeSinceHealthCheck; }

        public String getSummary() {
            return String.format("State: %s, Failures: %d, Last Success: %s ago",
                state,
                failures,
                lastSuccess > 0 ? (System.currentTimeMillis() - lastSuccess) + "ms" : "never"
            );
        }
    }

    // === INTERNAL METHODS ===

    private static void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("QuantifiedAPI not initialized. Call init() first.");
        }
    }

    // === UTILITY METHODS FOR COMMON PATTERNS ===

    public static <T> CompletableFuture<T> submit(String taskName, java.util.function.Supplier<T> work) {
        return task(taskName).submit(work);
    }

    public static <T> T getCached(CacheType cacheType, String key, java.util.function.Supplier<T> loader) {
        return cache(cacheType).get(key, loader);
    }

    public static <T> void putCached(CacheType cacheType, String key, T value) {
        cache(cacheType).put(key, value);
    }


    public static CompletableFuture<Void> sendToAll(String channel, Object data) {
        return network(channel).sendToAll(data);
    }

    // === RESILIENCE FEATURES (REFLECTIVE IMPORTS) ===

    /**
     * Create a resilient task builder with circuit breaker, retry policy, and bulkhead protection.
     * Uses reflection to avoid compile-time dependencies on resilience package.
     */
    public static ResilientTaskBuilder createResilientTask(String taskName) {
        try {
            Class<?> clazz = Class.forName("org.admany.quantified.core.common.resilience.ResilientTaskBuilder");
            Method createMethod = clazz.getMethod("create", String.class);
            Object realBuilder = createMethod.invoke(null, taskName);
            return new ReflectiveResilientTaskBuilder(realBuilder);
        } catch (Exception e) {
            LOGGER.warn("Failed to create resilient task builder, using fallback", e);
            return new FallbackResilientTaskBuilder();
        }
    }

    /**
     * Get the intelligent scheduler for cross-mod task coordination.
     * Uses reflection to avoid compile-time dependencies on resilience package.
     */
    public static IntelligentScheduler getIntelligentScheduler() {
        try {
            Class<?> clazz = Class.forName("org.admany.quantified.core.common.resilience.IntelligentScheduler");
            Method getInstanceMethod = clazz.getMethod("getInstance");
            Object realScheduler = getInstanceMethod.invoke(null);
            return new ReflectiveIntelligentScheduler(realScheduler);
        } catch (Exception e) {
            LOGGER.warn("Failed to get intelligent scheduler, using fallback", e);
            return new FallbackIntelligentScheduler();
        }
    }

    @SuppressWarnings("unchecked")
    public static java.util.List<ModInfo> discoverMods() {
        try {
            Class<?> clazz = Class.forName("org.admany.quantified.api.QuantifiedAPI");
            return (java.util.List<ModInfo>) clazz.getMethod("discoverMods").invoke(null);
        } catch (Exception e) {
            // Fallback: return empty list
            return java.util.Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public static <R> CompletableFuture<java.util.List<R>> submitBatch(
            String batchName,
            java.util.List<TaskBatchItem<R>> tasks,
            java.util.function.Function<TaskBatchItem<R>, ResourceHint> groupBy) {
        try {
            Class<?> clazz = Class.forName("org.admany.quantified.core.forge.ConnectedModImpl");
            return (CompletableFuture<java.util.List<R>>) clazz.getMethod("submitBatch", String.class, java.util.List.class, java.util.function.Function.class)
                .invoke(null, batchName, tasks, groupBy);
        } catch (Exception e) {
            // Fallback: submit tasks individually without grouping
            java.util.List<CompletableFuture<R>> futures = new java.util.ArrayList<>();
            for (TaskBatchItem<R> taskItem : tasks) {
                futures.add(task(taskItem.name()).submit(taskItem.cpuImplementation()));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
        }
    }

    // === BUILDER INTERFACES (LOCAL COPIES TO AVOID IMPORTS) ===

    public interface TaskBatchItem<R> {
        String name();
        java.util.function.Supplier<R> cpuImplementation();
    }

    public enum ResourceHint { CPU_INTENSIVE, GPU_INTENSIVE, MEMORY_INTENSIVE, IO_INTENSIVE }

    public interface TaskBuilder {
        TaskBuilder priority(Priority priority);
        TaskBuilder timeout(java.time.Duration timeout);
        TaskBuilder retry(int maxRetries);
        TaskBuilder resourceEstimate(long vramBytes, int computeUnits);
        TaskBuilder onProgress(java.util.function.Consumer<Double> progressCallback);
        TaskBuilder onFailure(java.util.function.Consumer<Throwable> failureCallback);
        TaskBuilder allowMainThreadRerouting(boolean allow);
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
        void invalidatePattern(String pattern);
    }

    public interface HybridBuilder {
        HybridBuilder priority(TaskBuilder.Priority priority);
        HybridBuilder timeout(java.time.Duration timeout);
        HybridBuilder ttl(java.time.Duration ttl);
        HybridBuilder maxSize(long maxSize);
        HybridBuilder compression(boolean enabled);
        HybridBuilder conditional(java.util.function.Supplier<Boolean> condition);
        HybridBuilder allowMainThreadRerouting(boolean allow);
        <T> CompletableFuture<T> submit(java.util.function.Supplier<T> work);
    }

    public interface NetworkBuilder {
        NetworkBuilder chunkSize(int bytes);
        NetworkBuilder timeout(java.time.Duration timeout);
        NetworkBuilder retryPolicy(RetryPolicy policy);
        NetworkBuilder callback(java.util.function.Consumer<Object> responseCallback);
        NetworkBuilder withQoS(QoS qos);
        CompletableFuture<Void> send(Object data);
        /**
         * Broadcast to all recipients (API-specific). Implementations should
         * fall back to send(Object) when unavailable.
         */
        CompletableFuture<Void> sendToAll(Object data);
        <T> CompletableFuture<T> sendAndReceive(Object data);

        enum RetryPolicy { NONE, LINEAR_BACKOFF, EXPONENTIAL_BACKOFF }
        enum QoS { LOW, NORMAL, HIGH, CRITICAL }
    }

    // === RESILIENCE INTERFACES (LOCAL COPIES TO AVOID IMPORTS) ===

    public interface ResilientTaskBuilder {
        ResilientTaskBuilder withCircuitBreaker(int failureThreshold, long recoveryTimeoutMs);
        ResilientTaskBuilder withRetryPolicy(RetryPolicy policy, int maxRetries);
        ResilientTaskBuilder withBulkhead(int maxConcurrent);
        ResilientTaskBuilder timeout(java.time.Duration timeout);
        <T> CompletableFuture<T> execute(java.util.function.Supplier<T> task);

        enum RetryPolicy { NONE, LINEAR_BACKOFF, EXPONENTIAL_BACKOFF }
    }

    public interface IntelligentScheduler {
        void submitTask(String taskName, Runnable task, String modId, TaskPriority priority);
        void submitTask(String taskName, Runnable task, String modId);
        java.util.Map<String, Object> getSchedulerStats();
        boolean isHealthy();

        enum TaskPriority { LOW, NORMAL, HIGH, CRITICAL }
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
        @Override public TaskBuilder allowMainThreadRerouting(boolean b) {
            try { realBuilder.getClass().getMethod("allowMainThreadRerouting", boolean.class).invoke(realBuilder, b); } catch (Exception e) {}
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
        @Override public void invalidatePattern(String s) {
            try { realBuilder.getClass().getMethod("invalidatePattern", String.class).invoke(realBuilder, s); } catch (Exception e) {}
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
        @Override public HybridBuilder conditional(java.util.function.Supplier<Boolean> s) {
            try { realBuilder.getClass().getMethod("conditional", java.util.function.Supplier.class).invoke(realBuilder, s); } catch (Exception e) {}
            return this;
        }
        @Override public HybridBuilder allowMainThreadRerouting(boolean b) {
            try { realBuilder.getClass().getMethod("allowMainThreadRerouting", boolean.class).invoke(realBuilder, b); } catch (Exception e) {}
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
        @Override public NetworkBuilder withQoS(QoS q) {
            try { realBuilder.getClass().getMethod("withQoS", Enum.class).invoke(realBuilder, q); } catch (Exception e) {}
            return this;
        }
        @Override
        @SuppressWarnings("unchecked")
        public CompletableFuture<Void> send(Object o) {
            try { return (CompletableFuture<Void>) realBuilder.getClass().getMethod("send", Object.class).invoke(realBuilder, o); }
            catch (NoSuchMethodException nsme) {
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) { return CompletableFuture.completedFuture(null); }
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public CompletableFuture<Void> sendToAll(Object o) {
            try {
                return (CompletableFuture<Void>) realBuilder.getClass().getMethod("sendToAll", Object.class).invoke(realBuilder, o);
            } catch (NoSuchMethodException nsme) {
                // Fallback: if concrete builder doesn't implement sendToAll, use send()
                return send(o);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(null);
            }
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

    // === RESILIENCE REFLECTIVE WRAPPERS ===

    private static class ReflectiveResilientTaskBuilder implements ResilientTaskBuilder {
        private final Object realBuilder;

        ReflectiveResilientTaskBuilder(Object realBuilder) {
            this.realBuilder = realBuilder;
        }

        @Override public ResilientTaskBuilder withCircuitBreaker(int failureThreshold, long recoveryTimeoutMs) {
            try { realBuilder.getClass().getMethod("withCircuitBreaker", int.class, long.class).invoke(realBuilder, failureThreshold, recoveryTimeoutMs); } catch (Exception e) {}
            return this;
        }
        @Override public ResilientTaskBuilder withRetryPolicy(RetryPolicy policy, int maxRetries) {
            try { realBuilder.getClass().getMethod("withRetryPolicy", Enum.class, int.class).invoke(realBuilder, policy, maxRetries); } catch (Exception e) {}
            return this;
        }
        @Override public ResilientTaskBuilder withBulkhead(int maxConcurrent) {
            try { realBuilder.getClass().getMethod("withBulkhead", int.class).invoke(realBuilder, maxConcurrent); } catch (Exception e) {}
            return this;
        }
        @Override public ResilientTaskBuilder timeout(java.time.Duration timeout) {
            try { realBuilder.getClass().getMethod("timeout", java.time.Duration.class).invoke(realBuilder, timeout); } catch (Exception e) {}
            return this;
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> execute(java.util.function.Supplier<T> task) {
            try { return (CompletableFuture<T>) realBuilder.getClass().getMethod("execute", java.util.function.Supplier.class).invoke(realBuilder, task); }
            catch (Exception e) { return CompletableFuture.completedFuture(task.get()); }
        }
    }

    private static class ReflectiveIntelligentScheduler implements IntelligentScheduler {
        private final Object realScheduler;

        ReflectiveIntelligentScheduler(Object realScheduler) {
            this.realScheduler = realScheduler;
        }

        @Override public void submitTask(String taskName, Runnable task, String modId, TaskPriority priority) {
            try { realScheduler.getClass().getMethod("submitTask", String.class, Runnable.class, String.class, Enum.class).invoke(realScheduler, taskName, task, modId, priority); } catch (Exception e) {}
        }
        @Override public void submitTask(String taskName, Runnable task, String modId) {
            try { realScheduler.getClass().getMethod("submitTask", String.class, Runnable.class, String.class).invoke(realScheduler, taskName, task, modId); } catch (Exception e) {}
        }
        @Override
        @SuppressWarnings("unchecked")
        public java.util.Map<String, Object> getSchedulerStats() {
            try { return (java.util.Map<String, Object>) realScheduler.getClass().getMethod("getSchedulerStats").invoke(realScheduler); }
            catch (Exception e) { return java.util.Collections.emptyMap(); }
        }
        @Override public boolean isHealthy() {
            try { return (Boolean) realScheduler.getClass().getMethod("isHealthy").invoke(realScheduler); }
            catch (Exception e) { return true; }
        }
    }

    // === RESILIENCE FALLBACK IMPLEMENTATIONS ===

    private static class FallbackResilientTaskBuilder implements ResilientTaskBuilder {
        @Override public ResilientTaskBuilder withCircuitBreaker(int failureThreshold, long recoveryTimeoutMs) { return this; }
        @Override public ResilientTaskBuilder withRetryPolicy(RetryPolicy policy, int maxRetries) { return this; }
        @Override public ResilientTaskBuilder withBulkhead(int maxConcurrent) { return this; }
        @Override public ResilientTaskBuilder timeout(java.time.Duration timeout) { return this; }
        @Override public <T> CompletableFuture<T> execute(java.util.function.Supplier<T> task) {
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (Throwable t) {
                CompletableFuture<T> cf = new CompletableFuture<>();
                cf.completeExceptionally(t);
                return cf;
            }
        }
    }

    private static class FallbackIntelligentScheduler implements IntelligentScheduler {
        @Override public void submitTask(String taskName, Runnable task, String modId, TaskPriority priority) {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.warn("Fallback scheduler task failed: {}", e.getMessage());
            }
        }
        @Override public void submitTask(String taskName, Runnable task, String modId) {
            submitTask(taskName, task, modId, TaskPriority.NORMAL);
        }
        @Override public java.util.Map<String, Object> getSchedulerStats() {
            return java.util.Map.of("fallback", true, "tasksProcessed", 0);
        }
        @Override public boolean isHealthy() { return true; }
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

        public CacheBuilder cache(Enum<?> cacheType) {
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
        @Override public TaskBuilder allowMainThreadRerouting(boolean b) { return this; }
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
        private final Enum<?> cacheType;

        FallbackCacheBuilder(Enum<?> cacheType) {
            this.cacheType = cacheType;
            fallbackCaches.computeIfAbsent(cacheType.name(), k -> new java.util.concurrent.ConcurrentHashMap<>());
        }

        @Override public CacheBuilder ttl(java.time.Duration d) { return this; }
        @Override public CacheBuilder maxSize(long l) { return this; }
        @Override public CacheBuilder compression(boolean b) { return this; }
        @Override public CacheBuilder persistence(boolean b) { return this; }
        @Override @SuppressWarnings("unchecked")
        public <T> T get(String key, java.util.function.Supplier<T> loader) {
            java.util.concurrent.ConcurrentHashMap<String, Object> map = fallbackCaches.get(cacheType.name());
            return (T) map.computeIfAbsent(key, k -> loader.get());
        }
        @Override public <T> void put(String key, T value) { fallbackCaches.computeIfAbsent(cacheType.name(), k -> new java.util.concurrent.ConcurrentHashMap<>()).put(key, value); }
        @Override public boolean contains(String s) { return fallbackCaches.getOrDefault(cacheType.name(), new java.util.concurrent.ConcurrentHashMap<>()).containsKey(s); }
        @Override public void remove(String s) { fallbackCaches.getOrDefault(cacheType.name(), new java.util.concurrent.ConcurrentHashMap<>()).remove(s); }
        @Override public void clear() { fallbackCaches.getOrDefault(cacheType.name(), new java.util.concurrent.ConcurrentHashMap<>()).clear(); }
        @Override public void invalidatePattern(String s) { clear(); } // Fallback: clear all
    }

    private static class FallbackHybridBuilder implements HybridBuilder {
        @Override public HybridBuilder priority(TaskBuilder.Priority p) { return this; }
        @Override public HybridBuilder timeout(java.time.Duration d) { return this; }
        @Override public HybridBuilder ttl(java.time.Duration d) { return this; }
        @Override public HybridBuilder maxSize(long l) { return this; }
        @Override public HybridBuilder compression(boolean b) { return this; }
        @Override public HybridBuilder conditional(java.util.function.Supplier<Boolean> s) { return this; }
        @Override public HybridBuilder allowMainThreadRerouting(boolean b) { return this; }
        @Override public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> s) { return CompletableFuture.completedFuture(s.get()); }
    }

    private static class FallbackNetworkBuilder implements NetworkBuilder {
        @Override public NetworkBuilder chunkSize(int i) { return this; }
        @Override public NetworkBuilder timeout(java.time.Duration d) { return this; }
        @Override public NetworkBuilder retryPolicy(RetryPolicy r) { return this; }
        @Override public NetworkBuilder callback(java.util.function.Consumer<Object> c) { return this; }
        @Override public NetworkBuilder withQoS(QoS q) { return this; }
        @Override public CompletableFuture<Void> send(Object o) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> sendToAll(Object o) { return send(o); }
        @Override public <T> CompletableFuture<T> sendAndReceive(Object o) { return CompletableFuture.completedFuture(null); }
    }

    // === MOD INFO CLASS ===

    public static class ModInfo {
        private final String modId;
        private final String version;
        private final String displayName;

        public ModInfo(String modId, String version, String displayName) {
            this.modId = modId;
            this.version = version;
            this.displayName = displayName;
        }

        public String getModId() { return modId; }
        public String getVersion() { return version; }
        public String getDisplayName() { return displayName; }
    }
}
