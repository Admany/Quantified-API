package org.admany.quantified.core.common.platform;

import org.admany.quantified.core.common.config.QuantifiedLogging;

import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.core.client.legacy.LegacyApiClientNotifier;
import org.admany.quantified.core.compat.LegacyModScanner;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.disk.DiskCacheManager;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperFeatures;
import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.dev.StressTestController;
import org.admany.quantified.core.common.dev.web.DeveloperDashboardServer;
import org.admany.quantified.core.common.gpu.backend.VulkanProbeScheduler;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.network.NetworkManager;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.gpu.AsyncProbeScheduler;
import org.admany.quantified.core.common.telemetry.TelemetryService;
import org.admany.quantified.core.common.threading.core.MainThreadExecutor;
import org.admany.quantified.core.common.threading.core.WorkerClassLoaderContext;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.util.QuantifiedConnectionListener;
import org.admany.quantified.core.common.util.QuantifiedPaths;
import org.admany.quantified.core.common.vulkan.core.VulkanManager;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuantifiedCoreRuntime {

    public static final String MODID = "quantified";

    private static final AtomicBoolean CORE_BOOTSTRAPPED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean RUNTIME_SHUTDOWN = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, ModInfo> REGISTERED_MODS = new ConcurrentHashMap<>();

    private static volatile ScheduledExecutorService coalescer;
    private static volatile NetworkManager networkManager;
    private static volatile boolean gpuNameUpdated;
    private static volatile boolean clientWorldProbeTriggered;

    private QuantifiedCoreRuntime() {
    }

    public record PlatformPaths(Path gameDir, Path configDir) {
        public PlatformPaths {
            Objects.requireNonNull(gameDir, "gameDir");
            Objects.requireNonNull(configDir, "configDir");
        }
    }

    public static final class ModInfo {
        public final String modId;
        public volatile String version;
        public volatile boolean active = true;
        public volatile long lastActivity = System.currentTimeMillis();

        private ModInfo(String modId, String version) {
            this.modId = modId;
            this.version = version;
        }

        private void touch() {
            this.active = true;
            this.lastActivity = System.currentTimeMillis();
        }
    }

    public static void bootstrap(Logger logger, PlatformPaths paths) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(paths, "paths");
        if (!CORE_BOOTSTRAPPED.compareAndSet(false, true)) {
            return;
        }
        QuantifiedLogging.configureFromFile(paths.configDir());
        WorkerClassLoaderContext.capture();
        registerRuntimeShutdownHook();

        QuantifiedPaths.setPathProvider(new QuantifiedPaths.PathProvider() {
            @Override
            public Path getGameDir() {
                return paths.gameDir();
            }

            @Override
            public Path getConfigDir() {
                return paths.configDir();
            }
        });

        int lwjglStackSizeBytes = LwjglRuntimeTuning.ensureConfigured();
        logger.info("Configured LWJGL stack size to {} MiB ({} bytes)",
            lwjglStackSizeBytes / (1024 * 1024), lwjglStackSizeBytes);

        recordLog(logger, "[Quantified] Quantified API starting");
        MultithreadingConfig.initializeGlobals(logger);
        QuantifiedLogging.configure(logger, MultithreadingConfig.CONFIG);
        recordLog(logger, "[Quantified] Quantified Core Starting");

        int availableProcessors = Math.max(2, Runtime.getRuntime().availableProcessors());
        int coalescerThreads = Math.max(1,
            Integer.getInteger("quantified.coalescerThreads", 1));

        coalescer = Executors.newScheduledThreadPool(coalescerThreads,
            WorkerClassLoaderContext.wrap(r -> {
                Thread t = new Thread(r, "quantified-coalescer");
                t.setDaemon(true);
                return t;
            }));

        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(availableProcessors);
        int configuredQueueBound = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.taskQueueSize : 0;
        if (configuredQueueBound > 0) {
            bootstrap = bootstrap.withQueueBound(configuredQueueBound);
        }
        logger.info("Async scheduler configured with {} foreground and {} background threads ({} cores detected)",
            bootstrap.foregroundThreads(), bootstrap.backgroundThreads(), availableProcessors);
        recordLog(logger, "[Quantified] Quantified Async Pool Start");
        AsyncManager.initialise(bootstrap, coalescer);

        long ttlMs = Math.max(1_000L, 10000L);
        long idleMs = Math.max(30_000L, Math.min(ttlMs, 120_000L));
        CacheManager.startMaintenance(Duration.ofMinutes(1), Duration.ofMillis(idleMs));

        DiskCacheManager.initialize();
        TelemetryService.start();

        logger.debug("[Quantified] Quantified GPU Acceleration Starting");
        DeveloperOverlayManager.recordApiLog("[Quantified] Quantified GPU Acceleration Starting");
        initializeGpuBackends(logger);

        if (MultithreadingConfig.CONFIG.enableNetworking) {
            networkManager = new NetworkManager();
            networkManager.initialize();
        }

        registerMod(MODID, "1.0.0", logger);
        registerMod("test-mod", "1.0.0", logger);

        QuantifiedAPI.addConnectionListener(new QuantifiedConnectionListener());
        QuantifiedAPI.registerV2(MODID);

        logger.debug("[Quantified] Quantified API WebPanel starting");
        DeveloperOverlayManager.recordApiLog("[Quantified] Quantified API WebPanel starting");

        DeveloperFeatures.initialiseFromConfig();

        logger.debug("[Quantified] Forcing API class initialization");
        DeveloperOverlayManager.recordApiLog("[Quantified] Forcing API class initialization");
        forceInitializeApiClasses(logger);

        logger.info("Quantified API initialized successfully.");
        logRegisteredMods(logger);
        try {
            LegacyModScanner.scanLoadedMods();
        } catch (Throwable t) {
            logger.warn("Legacy API scan failed", t);
        }
    }

    public static void onClientSetup(Logger logger) {
        LegacyApiClientNotifier.initialize(logger);
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            if (logger != null) {
                logger.debug("Quantified client GPU probes skipped because GPU acceleration is disabled.");
            }
            return;
        }
        AsyncProbeScheduler.triggerProbe("client-setup");
        VulkanProbeScheduler.triggerProbe("client-setup");
        if (logger != null) {
            logger.debug("Quantified client GPU probes triggered.");
        }
    }

    public static void onRenderTickStart(Logger logger) {
        if (!gpuNameUpdated) {
            updateGpuNameFromOpenGl(logger);
        }
        if (!clientWorldProbeTriggered) {
            triggerClientWorldProbe();
        }
    }

    public static void onClientTickEnd(Logger logger) {
    }

    public static void onServerStarting(Executor mainThreadExecutor) {
        MainThreadExecutor.install(mainThreadExecutor);
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            return;
        }
        AsyncProbeScheduler.triggerProbe("server-start");
        VulkanProbeScheduler.triggerProbe("server-start");
    }

    public static void onServerStopping() {
        MainThreadExecutor.clear();
        OpenCLManager.shutdown();
        if (VulkanRuntime.hasBindings()) {
            VulkanManager.shutdown();
        }
        VulkanProbeScheduler.reset();
        StressTestController.shutdown();
    }

    public static void shutdownRuntime() {
        if (!RUNTIME_SHUTDOWN.compareAndSet(false, true)) {
            return;
        }
        MainThreadExecutor.clear();
        try {
            OpenCLManager.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            if (VulkanRuntime.hasBindings()) {
                VulkanManager.shutdown();
            }
        } catch (Throwable ignored) {
        }
        try {
            VulkanProbeScheduler.reset();
        } catch (Throwable ignored) {
        }
        try {
            StressTestController.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            DeveloperDashboardServer.stop();
        } catch (Throwable ignored) {
        }
        try {
            DiskCacheManager.shutdown();
        } catch (Throwable ignored) {
        }
    }

    public static NetworkManager getNetworkManager() {
        return networkManager;
    }

    public static void registerMod(String modId, String version) {
        registerMod(modId, version, null);
    }

    public static void registerMod(String modId, String version, Logger logger) {
        Objects.requireNonNull(modId, "modId");
        REGISTERED_MODS.compute(modId, (id, existing) -> {
            if (existing == null) {
                if (logger != null) {
                    logger.debug("Registered mod: {} v{}", modId, version);
                }
                return new ModInfo(modId, version);
            }
            if (version != null && !version.isEmpty()) {
                existing.version = version;
            }
            existing.touch();
            if (logger != null) {
                logger.debug("Updated mod registration: {} v{}", modId, existing.version);
            }
            return existing;
        });
    }

    public static void touchMod(String modId) {
        if (modId == null) {
            return;
        }
        ModInfo info = REGISTERED_MODS.get(modId);
        if (info != null) {
            info.touch();
        }
    }

    public static ModInfo getModInfo(String modId) {
        return REGISTERED_MODS.get(modId);
    }

    public static ConcurrentHashMap<String, ModInfo> getRegisteredMods() {
        return new ConcurrentHashMap<>(REGISTERED_MODS);
    }

    public static void logRegisteredMods(Logger logger) {
        if (REGISTERED_MODS.isEmpty()) {
            String msg = "[Quantified] No mods have registered with the Quantified API yet.";
            if (logger != null) {
                logger.info(msg);
            }
            DeveloperOverlayManager.recordApiLog(msg);
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("[Quantified] Quantified API has been called out by:\n");
        REGISTERED_MODS.values().stream()
            .sorted((a, b) -> a.modId.compareTo(b.modId))
            .forEach(mod -> {
                String timeStr = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(mod.lastActivity));
                String modName = mod.modId.equals(MODID) ? "Quantified Frontend" : mod.modId;
                String version = mod.version != null ? mod.version : "unknown";
                message.append(String.format("  - [%s] (Version %s) at %s\n", modName, version, timeStr));
            });

        String rendered = message.toString().trim();
        if (logger != null) {
            logger.info(rendered);
        }
        DeveloperOverlayManager.addApiLog(rendered);
    }

    private static void initializeGpuBackends(Logger logger) {
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            logger.info("GPU acceleration disabled in Quantified config; skipping OpenCL/Vulkan probes.");
            DeveloperOverlayManager.recordApiLog("[Quantified] GPU acceleration disabled in config");
            return;
        }
        try {
            OpenCLManager.initialize();
            logger.info("OpenCL initialization deferred to background probe.");
            DeveloperOverlayManager.recordApiLog("[Quantified] OpenCL probe deferred (background)");
        } catch (Exception e) {
            logger.info("OpenCL acceleration not available: {}", e.getMessage());
            DeveloperOverlayManager.recordApiLog("[Quantified] OpenCL not available: " + e.getMessage());
        }
        if (VulkanRuntime.hasProbeRuntime()) {
            try {
                VulkanProbeScheduler.scheduleBackgroundProbe();
                VulkanRuntime.RuntimeMode runtimeMode = VulkanRuntime.runtimeMode();
                if (runtimeMode == VulkanRuntime.RuntimeMode.IN_PROCESS) {
                    logger.info("Vulkan initialization deferred to background probe (in-process runtime).");
                    DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan probe deferred (background, native runtime)");
                } else if (runtimeMode == VulkanRuntime.RuntimeMode.ISOLATED) {
                    logger.info("Vulkan initialization deferred to background probe (isolated bundled runtime for legacy Minecraft).");
                    DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan probe deferred (background, isolated legacy runtime)");
                } else {
                    logger.info("Vulkan initialization deferred to background probe.");
                    DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan probe deferred (background)");
                }
                DeveloperOverlayManager.recordApiLog("[Vulkan] Probe scheduler armed");
            } catch (Throwable e) {
                logger.info("Vulkan acceleration not available: {}", e.getMessage());
                DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan not available: " + e.getMessage());
            }
        } else {
            logger.info("Vulkan acceleration not available: no in-process LWJGL Vulkan classes or embedded probe bundle present");
            DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan not available: no Vulkan probe runtime");
        }
    }

    private static void updateGpuNameFromOpenGl(Logger logger) {
        if (!PhysicalEnvironment.isClient()) {
            gpuNameUpdated = true;
            return;
        }
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            gpuNameUpdated = true;
            return;
        }
        try {
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            if (renderer == null || renderer.isBlank()) {
                return;
            }
            OpenCLManager.updateDeviceName(renderer);
            if (logger != null) {
                logger.debug("Updated GPU name from OpenGL: {}", renderer);
            }
            DeveloperOverlayManager.recordApiLog("[OpenCL] GPU detected: " + renderer);

            AsyncProbeScheduler.triggerProbe("opengl-ready:" + renderer);
            DeveloperOverlayManager.recordApiLog("[OpenCL] OpenCL probe triggered (OpenGL context ready)");
            VulkanProbeScheduler.triggerRendererProbe(renderer);
            DeveloperOverlayManager.recordApiLog("[Vulkan] Vulkan probe triggered (OpenGL context ready)");

            gpuNameUpdated = true;
        } catch (Exception ex) {
            if (logger != null) {
                logger.warn("Failed to get GPU name from OpenGL: {}", ex.getMessage());
            }
            DeveloperOverlayManager.recordApiLog("GPU detection failed: " + ex.getMessage());
        }
    }

    private static void triggerClientWorldProbe() {
        if (!PhysicalEnvironment.isClient()) {
            clientWorldProbeTriggered = true;
            return;
        }
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            clientWorldProbeTriggered = true;
            return;
        }
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
            Object level = minecraft != null ? minecraftClass.getField("level").get(minecraft) : null;
            if (level == null) {
                return;
            }
            Object dimension = level.getClass().getMethod("dimension").invoke(level);
            Object location = dimension.getClass().getMethod("location").invoke(dimension);
            String worldId = String.valueOf(location);
            VulkanProbeScheduler.triggerWorldProbe(worldId);
            DeveloperOverlayManager.recordApiLog("[Vulkan] Vulkan probe triggered (client world ready: " + worldId + ")");
            clientWorldProbeTriggered = true;
        } catch (Throwable ignored) {
        }
    }

    private static void forceInitializeApiClasses(Logger logger) {
        try {
            Class.forName("org.admany.quantified.api.QuantifiedAPI");
            Class.forName("org.admany.quantified.api.QuantifiedHandle");
            Class.forName("org.admany.quantified.api.opencl.QuantifiedOpenCL");
            Class.forName("org.admany.quantified.api.opencl.QuantifiedOpenCL$Builder");
            Class.forName("org.admany.quantified.api.opencl.QuantifiedOpenCL$ApiOpenClTask");
            Class.forName("org.admany.quantified.api.model.QuantifiedHybrid");
            Class.forName("org.admany.quantified.api.model.QuantifiedPacket");
            Class.forName("org.admany.quantified.api.model.QuantifiedStats");
            Class.forName("org.admany.quantified.api.model.QuantifiedTask");
            Class.forName("org.admany.quantified.api.interfaces.ConnectedMod");
            Class.forName("org.admany.quantified.api.interfaces.ModConnectionListener");
            Class.forName("org.admany.quantified.api.interfaces.ModStatistics");
            Class.forName("org.admany.quantified.api.util.ForgeMetadataUtil");
            Class.forName("org.admany.quantified.api.builders.QuantifiedCacheBuilder");
            Class.forName("org.admany.quantified.api.builders.QuantifiedHybridBuilder");
            Class.forName("org.admany.quantified.api.builders.QuantifiedNetworkBuilder");
            Class.forName("org.admany.quantified.api.builders.QuantifiedTaskBuilder");
            Class.forName("org.admany.quantified.api.builders.SimpleTaskBuilder");
            Class.forName("org.admany.quantified.core.common.network.NetworkManager");
            Class.forName("org.admany.quantified.core.common.network.EncryptedPacket");
            Class.forName("org.admany.quantified.core.common.network.SecureChannel");
            Class.forName("org.admany.quantified.core.common.network.transport.DataTransport");
            Class.forName("org.admany.quantified.core.common.network.transport.TcpTransport");
            Class.forName("org.admany.quantified.core.common.network.transport.TcpServer");

            forceInitializeOpenClClasses(logger);
            forceInitializeVulkanClasses(logger);

            logger.info("All Quantified API classes successfully initialized.");
            DeveloperOverlayManager.recordApiLog("[Quantified] API classes initialized successfully");
        } catch (ClassNotFoundException e) {
            logger.warn("Failed to initialize API class: {}", e.getMessage());
            DeveloperOverlayManager.recordApiLog("[Quantified] API class initialization warning: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error during API class initialization: " + e.getMessage(), e);
            DeveloperOverlayManager.recordApiLog("[Quantified] API class initialization error: " + e.getMessage());
        }
    }

    private static void forceInitializeOpenClClasses(Logger logger) {
        try {
            Class.forName("org.admany.quantified.core.common.opencl.core.OpenCLManager");
            Class.forName("org.admany.quantified.core.common.opencl.core.OpenCLTask");
            Class.forName("org.admany.quantified.core.common.opencl.core.OpenCLContext");
            Class.forName("org.admany.quantified.core.common.opencl.gpu.AsyncProbeScheduler");
            Class.forName("org.admany.quantified.core.common.opencl.gpu.GPUDetector");
            Class.forName("org.admany.quantified.core.common.opencl.gpu.GPUMonitor");
            Class.forName("org.admany.quantified.core.common.opencl.gpu.HardwareDetector");
            Class.forName("org.admany.quantified.core.common.opencl.core.OpenCLRuntime");
            Class.forName("org.admany.quantified.core.common.opencl.util.NativeLibraryExtractor");
            Class.forName("org.admany.quantified.core.common.util.TaskScheduler");
            Class.forName("org.admany.quantified.core.common.util.TaskScheduler$ResourceHint");
            Class.forName("org.admany.quantified.core.common.util.TaskScheduler$TaskBatchItem");
            Class.forName("org.admany.quantified.core.common.util.QuantifiedConnectionListener");
            logger.debug("OpenCL core classes loaded successfully.");
            DeveloperOverlayManager.recordApiLog("[Quantified] OpenCL core classes loaded");
        } catch (ClassNotFoundException e) {
            logger.info("OpenCL core classes not available: {}", e.getMessage());
            DeveloperOverlayManager.recordApiLog("[Quantified] OpenCL core classes missing - OpenCL backend unavailable");
        }
    }

    private static void forceInitializeVulkanClasses(Logger logger) {
        if (!VulkanRuntime.hasProbeRuntime()) {
            logger.info("Vulkan core classes not available: no in-process LWJGL Vulkan classes or embedded probe bundle present");
            DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan core classes missing - Vulkan backend unavailable");
            return;
        }
        try {
            Class.forName("org.admany.quantified.api.vulkan.QuantifiedVulkan");
            Class.forName("org.admany.quantified.core.common.gpu.backend.VulkanRuntime");
            Class.forName("org.admany.quantified.core.common.gpu.backend.VulkanProbeScheduler");
            if (VulkanRuntime.hasBindings()) {
                Class.forName("org.admany.quantified.core.common.vulkan.core.VulkanManager");
                Class.forName("org.admany.quantified.core.common.vulkan.core.VulkanContext");
                logger.debug("Vulkan core classes loaded successfully.");
                DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan core classes loaded");
            } else if (VulkanRuntime.runtimeMode() == VulkanRuntime.RuntimeMode.ISOLATED) {
                Class.forName("org.admany.quantified.core.common.vulkan.core.VulkanIsolatedExecutor");
                logger.info("Vulkan isolated runtime classes loaded successfully for legacy Minecraft.");
                DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan isolated runtime loaded for legacy Minecraft");
            } else {
                logger.info("Vulkan probe bundle loaded, but no executable Vulkan runtime is currently available.");
                DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan probe bundle loaded without executable runtime");
            }
        } catch (Throwable e) {
            logger.info("Vulkan core classes not available: {}", e.getMessage());
            DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan core classes missing - Vulkan backend unavailable");
        }
    }

    private static void recordLog(Logger logger, String message) {
        logger.info(message);
        DeveloperOverlayManager.recordApiLog(message);
    }

    private static void registerRuntimeShutdownHook() {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(QuantifiedCoreRuntime::shutdownRuntime, "quantified-runtime-shutdown"));
    }
}
