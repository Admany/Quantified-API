package org.admany.quantified.core.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.disk.DiskCacheManager;
import org.admany.quantified.core.forge.commands.QuantifiedCommand;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperFeatures; 
import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.gpu.backend.VulkanProbeScheduler;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.gpu.AsyncProbeScheduler;
import org.admany.quantified.core.common.vulkan.core.VulkanManager;
import org.admany.quantified.core.common.dev.StressTestController;
import org.admany.quantified.core.common.dev.web.DeveloperDashboardServer;
import org.admany.quantified.core.common.network.NetworkManager;
import org.admany.quantified.core.common.telemetry.TelemetryService;
import org.admany.quantified.core.common.threading.core.MainThreadExecutor;
import org.admany.quantified.core.common.util.QuantifiedConnectionListener;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.util.QuantifiedPaths;
import org.admany.quantified.api.QuantifiedAPI;
import java.time.Duration;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;

@Mod(QuantifiedCoreForge.MODID)
public final class QuantifiedCoreForge {

    public static final String MODID = "quantified";
    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreForge.class);

    static {
        LwjglRuntimeTuning.ensureConfigured();
    }

    private static ScheduledExecutorService coalescer;
    private static NetworkManager networkManager;
    private static final ConcurrentHashMap<String, ModInfo> registeredMods = new ConcurrentHashMap<>();
    private static volatile boolean gpuNameUpdated = false;
    private static volatile boolean clientWorldProbeTriggered = false;
    private static final AtomicBoolean CORE_BOOTSTRAPPED = new AtomicBoolean(false);

    public static class ModInfo {
        public final String modId;
        public volatile String version;
        public volatile boolean active = true;
        public volatile long lastActivity = System.currentTimeMillis();

        public ModInfo(String modId, String version) {
            this.modId = modId;
            this.version = version;
        }

        void touch() {
            this.active = true;
            this.lastActivity = System.currentTimeMillis();
        }
    }
    public QuantifiedCoreForge(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(this);

        bootstrapCore();
    }

    @SuppressWarnings("removal")
    public QuantifiedCoreForge() {
        this(FMLJavaModLoadingContext.get().getModEventBus());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        bootstrapCore();
    }

    private static void bootstrapCore() {
        if (!CORE_BOOTSTRAPPED.compareAndSet(false, true)) {
            return;
        }

        QuantifiedPaths.setPathProvider(new QuantifiedPaths.PathProvider() {
            @Override
            public Path getGameDir() {
                return FMLPaths.GAMEDIR.get();
            }

            @Override
            public Path getConfigDir() {
                return FMLPaths.CONFIGDIR.get();
            }
        });

        int lwjglStackSizeBytes = LwjglRuntimeTuning.ensureConfigured();
        LOGGER.info("Configured LWJGL stack size to {} MiB ({} bytes)",
            lwjglStackSizeBytes / (1024 * 1024), lwjglStackSizeBytes);

        String startupMsg1 = "[Quantified] Quantified API starting";
        LOGGER.info(startupMsg1);
        DeveloperOverlayManager.recordApiLog(startupMsg1);

        MultithreadingConfig.initializeGlobals(LOGGER);

        String startupMsg2 = "[Quantified] Quantified Core Starting";
        LOGGER.info(startupMsg2);
        DeveloperOverlayManager.recordApiLog(startupMsg2);

        int availableProcessors = Math.max(2, Runtime.getRuntime().availableProcessors());
        int coalescerThreads = Math.max(2, Math.min(4, availableProcessors / 2));

        coalescer = Executors.newScheduledThreadPool(coalescerThreads, r -> {
            Thread t = new Thread(r, "quantified-coalescer");
            t.setDaemon(true);
            return t;
        });

        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(availableProcessors);
        int configuredQueueBound = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.taskQueueSize : 0;
        if (configuredQueueBound > 0) {
            bootstrap = bootstrap.withQueueBound(configuredQueueBound);
        }
        LOGGER.info("Async scheduler configured with " + bootstrap.foregroundThreads()
            + " foreground and " + bootstrap.backgroundThreads() + " background threads (" + availableProcessors + " cores detected)");
        String startupMsg3 = "[Quantified] Quantified Async Pool Start";
        LOGGER.info(startupMsg3);
        DeveloperOverlayManager.recordApiLog(startupMsg3);
        AsyncManager.initialise(bootstrap, coalescer);

        long ttlMs = Math.max(1_000L, 10000L);
        long idleMs = Math.max(30_000L, Math.min(ttlMs, 120_000L));
        CacheManager.startMaintenance(Duration.ofMinutes(1), Duration.ofMillis(idleMs));

        DiskCacheManager.initialize();

        TelemetryService.start();

        String startupMsg4 = "[Quantified] Quantified GPU Acceleration Starting";
        LOGGER.debug(startupMsg4);
        DeveloperOverlayManager.recordApiLog(startupMsg4);
        try {
            OpenCLManager.initialize();
            LOGGER.info("OpenCL initialization deferred to background probe.");
            DeveloperOverlayManager.recordApiLog("[Quantified] OpenCL probe deferred (background)");
        } catch (Exception e) {
            LOGGER.info("OpenCL acceleration not available: " + e.getMessage());
            DeveloperOverlayManager.recordApiLog("[Quantified] OpenCL not available: " + e.getMessage());
        }
        if (VulkanRuntime.hasBindings()) {
            try {
                VulkanProbeScheduler.scheduleBackgroundProbe();
                LOGGER.info("Vulkan initialization deferred to background probe.");
                DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan probe deferred (background)");
                DeveloperOverlayManager.recordApiLog("[Vulkan] Probe scheduler armed");
            } catch (Throwable e) {
                LOGGER.info("Vulkan acceleration not available: " + e.getMessage());
                DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan not available: " + e.getMessage());
            }
        } else {
            LOGGER.info("Vulkan acceleration not available: LWJGL Vulkan classes are missing from the runtime");
            DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan not available: LWJGL Vulkan classes missing");
        }

        if (MultithreadingConfig.CONFIG.enableNetworking) {
            networkManager = new NetworkManager();
            networkManager.initialize();
        }

        registerMod(MODID, "1.0.0");

        registerMod("test-mod", "1.0.0");

        QuantifiedAPI.addConnectionListener(new QuantifiedConnectionListener());

        QuantifiedAPI.register(MODID);

        String startupMsg5 = "[Quantified] Quantified API WebPanel starting";
        LOGGER.debug(startupMsg5);
        DeveloperOverlayManager.recordApiLog(startupMsg5);


        DeveloperFeatures.initialiseFromConfig();

        String startupMsg6 = "[Quantified] Forcing API class initialization";
        LOGGER.debug(startupMsg6);
        DeveloperOverlayManager.recordApiLog(startupMsg6);
        forceInitializeApiClasses();

        LOGGER.info("Quantified API initialized successfully.");

        logRegisteredMods();
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        AsyncProbeScheduler.triggerProbe("client-setup");
        VulkanProbeScheduler.triggerProbe("client-setup");
        MinecraftForge.EVENT_BUS.addListener((TickEvent e) -> {
            if (e.type == TickEvent.Type.RENDER && e.phase == TickEvent.Phase.START && !gpuNameUpdated) {
                try {
                    String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
                    if (renderer != null && !renderer.isBlank()) {
                        OpenCLManager.updateDeviceName(renderer);
                        LOGGER.debug("Updated GPU name from OpenGL: " + renderer);
                        DeveloperOverlayManager.recordApiLog("[OpenCL] GPU detected: " + renderer);

                        AsyncProbeScheduler.triggerProbe("opengl-ready:" + renderer);
                        DeveloperOverlayManager.recordApiLog("[OpenCL] OpenCL probe triggered (OpenGL context ready)");
                        VulkanProbeScheduler.triggerRendererProbe(renderer);
                        DeveloperOverlayManager.recordApiLog("[Vulkan] Vulkan probe triggered (OpenGL context ready)");

                        gpuNameUpdated = true;
                    }
                } catch (Exception ex) {
                    LOGGER.warn("Failed to get GPU name from OpenGL: " + ex.getMessage());
                    DeveloperOverlayManager.recordApiLog("GPU detection failed: " + ex.getMessage());
                }
            }
            if (e.type == TickEvent.Type.RENDER && e.phase == TickEvent.Phase.START && !clientWorldProbeTriggered) {
                try {
                    Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
                    Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
                    Object level = minecraft != null ? minecraftClass.getField("level").get(minecraft) : null;
                    if (level != null) {
                        Object dimension = level.getClass().getMethod("dimension").invoke(level);
                        Object location = dimension.getClass().getMethod("location").invoke(dimension);
                        String worldId = String.valueOf(location);
                        VulkanProbeScheduler.triggerWorldProbe(worldId);
                        DeveloperOverlayManager.recordApiLog("[Vulkan] Vulkan probe triggered (client world ready: " + worldId + ")");
                        clientWorldProbeTriggered = true;
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MainThreadExecutor.install(event.getServer());
        AsyncProbeScheduler.triggerProbe("server-start");
        VulkanProbeScheduler.triggerProbe("server-start");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        QuantifiedCommand.register(event.getDispatcher());
        LOGGER.debug("Quantified commands registered.");
    }

    @SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        MainThreadExecutor.clear();
        OpenCLManager.shutdown();
        if (VulkanRuntime.hasBindings()) {
            VulkanManager.shutdown();
        }
        VulkanProbeScheduler.reset();
        StressTestController.shutdown();
        DeveloperDashboardServer.stop();
        DiskCacheManager.shutdown();
    }

    public static NetworkManager getNetworkManager() {
        return networkManager;
    }

    public static void registerMod(String modId, String version) {
        Objects.requireNonNull(modId, "modId");
        registeredMods.compute(modId, (id, existing) -> {
            if (existing == null) {
                LOGGER.debug("Registered mod: " + modId + " v" + version);
                return new ModInfo(modId, version);
            }
            if (version != null && !version.isEmpty()) {
                existing.version = version;
            }
            existing.touch();
            LOGGER.debug("Updated mod registration: " + modId + " v" + existing.version);
            return existing;
        });
    }

    public static void touchMod(String modId) {
        if (modId == null) {
            return;
        }
        registeredMods.computeIfPresent(modId, (id, info) -> {
            info.touch();
            return info;
        });
    }

    public static ModInfo getModInfo(String modId) {
        return registeredMods.get(modId);
    }

    public static void logRegisteredMods() {
        if (registeredMods.isEmpty()) {
            String msg = "[Quantified] No mods have registered with the Quantified API yet.";
            LOGGER.info(msg);
            DeveloperOverlayManager.recordApiLog(msg);
            return;
        }

        StringBuilder consoleSb = new StringBuilder();
        consoleSb.append("[Quantified] Quantified API has been called out by:\n");

        StringBuilder webpanelSb = new StringBuilder();
        webpanelSb.append("[Quantified] Quantified API has been called out by:\n");

        java.util.Map<String, String> modColors = new java.util.HashMap<>();
        modColors.put("quantified", "#8B5CF6"); 

        java.util.Set<String> usedColors = new java.util.HashSet<>();
        usedColors.add("#8B5CF6"); 

        registeredMods.values().stream()
            .sorted((a, b) -> a.modId.compareTo(b.modId))
            .forEach(mod -> {
                String timeStr = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(mod.lastActivity));
                String modName = mod.modId.equals("quantified") ? "Quantified Frontend" : mod.modId;
                String version = mod.version != null ? mod.version : "unknown";

                consoleSb.append(String.format("  - [%s] (Version %s) at %s\n", modName, version, timeStr));

                webpanelSb.append(String.format("  - [%s] (Version %s) at %s\n", modName, version, timeStr));
            });

        String consoleMessage = consoleSb.toString().trim();
        String webpanelMessage = webpanelSb.toString().trim();

        LOGGER.info(consoleMessage);
        DeveloperOverlayManager.addApiLog(webpanelMessage);
    }

    public static ConcurrentHashMap<String, ModInfo> getRegisteredMods() {
        return new ConcurrentHashMap<>(registeredMods);
    }

    /**
     * Force initialization of all API classes to prevent lazy loading issues during mod usage.
     * This ensures all classes are loaded and ready before mods start calling the API.
     */
    private static void forceInitializeApiClasses() {
        try {
            // Force load main API classes
            Class.forName("org.admany.quantified.api.QuantifiedAPI");
            Class.forName("org.admany.quantified.api.QuantifiedHandle");

            // Force load OpenCL API classes
            Class.forName("org.admany.quantified.api.opencl.QuantifiedOpenCL");
            Class.forName("org.admany.quantified.api.opencl.QuantifiedOpenCL$Builder");
            Class.forName("org.admany.quantified.api.opencl.QuantifiedOpenCL$ApiOpenClTask");

            // Force load model classes
            Class.forName("org.admany.quantified.api.model.QuantifiedHybrid");
            Class.forName("org.admany.quantified.api.model.QuantifiedPacket");
            Class.forName("org.admany.quantified.api.model.QuantifiedStats");
            Class.forName("org.admany.quantified.api.model.QuantifiedTask");

            // Force load interface classes
            Class.forName("org.admany.quantified.api.interfaces.ConnectedMod");
            Class.forName("org.admany.quantified.api.interfaces.ModConnectionListener");
            Class.forName("org.admany.quantified.api.interfaces.ModStatistics");

            // Force load util classes
            Class.forName("org.admany.quantified.api.util.ForgeMetadataUtil");

            // Force load builder classes
            Class.forName("org.admany.quantified.api.builders.QuantifiedCacheBuilder");
            Class.forName("org.admany.quantified.api.builders.QuantifiedHybridBuilder");
            Class.forName("org.admany.quantified.api.builders.QuantifiedNetworkBuilder");
            Class.forName("org.admany.quantified.api.builders.QuantifiedTaskBuilder");
            Class.forName("org.admany.quantified.api.builders.SimpleTaskBuilder");

            // Force load networking transport classes
            Class.forName("org.admany.quantified.core.common.network.NetworkManager");
            Class.forName("org.admany.quantified.core.common.network.EncryptedPacket");
            Class.forName("org.admany.quantified.core.common.network.SecureChannel");
            Class.forName("org.admany.quantified.core.common.network.transport.DataTransport");
            Class.forName("org.admany.quantified.core.common.network.transport.TcpTransport");
            Class.forName("org.admany.quantified.core.common.network.transport.TcpServer");

            // Force load OpenCL core classes (if available)
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

                LOGGER.debug("OpenCL core classes loaded successfully.");
                DeveloperOverlayManager.recordApiLog("[Quantified] OpenCL core classes loaded");
            } catch (ClassNotFoundException e) {
                LOGGER.info("OpenCL core classes not available (expected if core JAR not present): " + e.getMessage());
                DeveloperOverlayManager.recordApiLog("[Quantified] OpenCL core classes missing - OpenCL backend unavailable");
            }

            if (VulkanRuntime.hasBindings()) {
                try {
                    Class.forName("org.admany.quantified.api.vulkan.QuantifiedVulkan");
                    Class.forName("org.admany.quantified.core.common.gpu.backend.VulkanRuntime");
                    Class.forName("org.admany.quantified.core.common.gpu.backend.VulkanProbeScheduler");
                    Class.forName("org.admany.quantified.core.common.vulkan.core.VulkanManager");
                    Class.forName("org.admany.quantified.core.common.vulkan.core.VulkanContext");
                    LOGGER.debug("Vulkan core classes loaded successfully.");
                    DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan core classes loaded");
                } catch (Throwable e) {
                    LOGGER.info("Vulkan core classes not available: " + e.getMessage());
                    DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan core classes missing - Vulkan backend unavailable");
                }
            } else {
                LOGGER.info("Vulkan core classes not available: LWJGL Vulkan classes are missing from the runtime");
                DeveloperOverlayManager.recordApiLog("[Quantified] Vulkan core classes missing - Vulkan backend unavailable");
            }

            LOGGER.info("All Quantified API classes successfully initialized.");
            DeveloperOverlayManager.recordApiLog("[Quantified] API classes initialized successfully");

        } catch (ClassNotFoundException e) {
            LOGGER.warn("Failed to initialize API class: " + e.getMessage());
            DeveloperOverlayManager.recordApiLog("[Quantified] API class initialization warning: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Unexpected error during API class initialization: " + e.getMessage(), e);
            DeveloperOverlayManager.recordApiLog("[Quantified] API class initialization error: " + e.getMessage());
        }
    }
}
