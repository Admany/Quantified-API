package org.admany.quantified.core.common.dev.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.KeyManagerFactory;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.net.URLDecoder;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.GpuBackendType;
import org.admany.quantified.api.interfaces.ConnectedMod;
import org.admany.quantified.api.interfaces.ModStatistics;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.CacheManager.DetailedInventory;
import org.admany.quantified.core.common.cache.disk.DiskCacheManager;
import org.admany.quantified.core.common.cache.disk.DiskCacheManager.CacheFileDescriptor;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperFeatures;
import org.admany.quantified.core.common.dev.StressTestController;
import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.gpu.backend.VulkanExecutionSupport;
import org.admany.quantified.core.common.gpu.backend.VulkanProbeScheduler;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.gpu.AsyncProbeScheduler;
import org.admany.quantified.core.common.opencl.gpu.GPUDetector;
import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;
import org.admany.quantified.core.common.platform.PhysicalEnvironment;
import org.admany.quantified.core.common.opencl.gpu.probe.GpuTelemetryService;
import org.admany.quantified.core.common.opencl.task.OpenCLTaskManager;
import org.admany.quantified.core.common.telemetry.TaskKindTelemetry;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HWDiskStore;
import oshi.hardware.Sensors;
import oshi.software.os.OperatingSystem;



public final class DeveloperDashboardServer {

    private static final Logger LOGGER = Logger.getLogger(DeveloperDashboardServer.class.getName());
    static {
        suppressOshiWmiNoise();
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final int EXPORT_HISTORY_MAX = 500;
    private static final Deque<JsonObject> recentSnapshots = new ArrayDeque<>();
    private static final Object HISTORY_LOCK = new Object();
    private static final long SENSOR_REFRESH_INTERVAL_MS = 2_500L;
    private static final AtomicLong LAST_CPU_SENSOR_QUERY = new AtomicLong(0L);
    private static final AtomicReference<Double> LAST_CPU_SENSOR_VALUE = new AtomicReference<>(Double.NaN);
    private static final AtomicReference<Boolean> CPU_TEMP_UNAVAILABLE = new AtomicReference<>(false);
    private static final AtomicLong LAST_CPU_LOAD_QUERY = new AtomicLong(0L);
    private static final AtomicReference<long[]> LAST_CPU_TICKS = new AtomicReference<>();
    private static final AtomicReference<Double> LAST_CPU_LOAD = new AtomicReference<>(Double.NaN);
    private static final AtomicReference<SystemInfo> SYSTEM_INFO = new AtomicReference<>();

    private static final String RESOURCE_ROOT = "dashboard/";
    private static final String LOGO_RESOURCE = "quantifiedapi.png";
    private static final String LOGO_ENDPOINT = "/dashboard-logo.png";

    private static volatile com.sun.net.httpserver.HttpServer server;
    private static volatile ExecutorService executor;
    private static volatile int boundPort = -1;
    private static volatile String boundHost = "";
    private static volatile boolean isHttps = false;
    private static volatile boolean authRequired = false;
    private static volatile String lastStartFailure = "";

    private static final Map<String, Long> sessions = new HashMap<>();
    private static final Object SESSIONS_LOCK = new Object();

    private DeveloperDashboardServer() {
    }

    private static void suppressOshiWmiNoise() {
        try {
            java.util.logging.Logger wmiLogger = java.util.logging.Logger.getLogger("oshi.util.platform.windows.WmiQueryHandler");
            if (wmiLogger != null) {
                wmiLogger.setLevel(Level.OFF);
            }
            java.util.logging.Logger sensorsLogger = java.util.logging.Logger.getLogger("oshi.hardware.platform.windows.WindowsSensors");
            if (sensorsLogger != null) {
                sensorsLogger.setLevel(Level.OFF);
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> configurator = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Object levelOff = enumConstantOrThrow(levelClass, "OFF");
            java.lang.reflect.Method setLevel = configurator.getMethod("setLevel", String.class, levelClass);
            setLevel.invoke(null, "oshi.util.platform.windows.WmiQueryHandler", levelOff);
            setLevel.invoke(null, "oshi.hardware.platform.windows.WindowsSensors", levelOff);
        } catch (Throwable ignored) {
        }
    }

    private static Object enumConstantOrThrow(Class<?> enumType, String name) {
        Objects.requireNonNull(enumType, "enumType");
        Objects.requireNonNull(name, "name");
        if (!enumType.isEnum()) {
            throw new IllegalArgumentException("Not an enum type: " + enumType.getName());
        }
        Object[] constants = enumType.getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                Enum<?> enumConstant = (Enum<?>) constant;
                if (enumConstant.name().equals(name)) {
                    return constant;
                }
            }
        }
        throw new IllegalArgumentException("No enum constant " + enumType.getName() + "." + name);
    }

    public static void applyConfiguration(boolean enabled, int port, String host, boolean https, boolean auth) {
        LOGGER.fine("DeveloperDashboardServer.applyConfiguration called: enabled=" + enabled + ", port=" + port + ", host=" + host + ", https=" + https + ", auth=" + auth);
        synchronized (LOCK) {
            if (!enabled) {
                stopInternal();
                return;
            }
            if (server != null && boundPort == port && Objects.equals(boundHost, host) && isHttps == https && authRequired == auth) {
                return;
            }
            stopInternal();
            startInternal(port, host, https, auth);
        }
    }


    public static void stop() {
        synchronized (LOCK) {
            stopInternal();
        }
    }

    public static boolean isRunning() {
        return server != null && boundPort >= 0;
    }

    public static int boundPort() {
        return boundPort;
    }

    public static String boundHost() {
        return boundHost == null ? "" : boundHost;
    }

    public static String lastStartFailure() {
        return lastStartFailure == null ? "" : lastStartFailure;
    }

    private static void startInternal(int port, String host, boolean https, boolean auth) {
        try {
            String bindHost = normalizeBindHost(host);
            LOGGER.fine("Starting developer dashboard server on " + bindHost + ":" + port + " (https=" + https + ", auth=" + auth + ")");
            ServerBinding binding = createServer(bindHost, port, https);
            server = binding.server();
            bindHost = binding.host();
            isHttps = https && server instanceof HttpsServer;
            Objects.requireNonNull(server, "server");
            executor = Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "quantified-dashboard");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);

            registerRoutes(server, auth);

            server.start();
            boundPort = port;
            boundHost = bindHost;
            authRequired = auth;
            lastStartFailure = "";
            String protocol = isHttps ? "https" : "http";
            String finalBindHost = bindHost;
            LOGGER.fine(() -> "Developer dashboard listening on " + protocol + "://" + finalBindHost + ":" + port);
        } catch (Exception ex) {
            boundPort = -1;
            boundHost = "";
            isHttps = false;
            authRequired = false;
            lastStartFailure = ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName() + ": " + ex.getMessage()
                : ex.getClass().getSimpleName();
            LOGGER.log(Level.WARNING, "Failed to start developer dashboard on " + host + ":" + port, ex);
        }
    }

    private static ServerBinding createServer(String host, int port, boolean https) throws Exception {
        try {
            return new ServerBinding(createServerOnce(host, port, https), host);
        } catch (BindException bindException) {
            if (!shouldFallbackToWildcard(host)) {
                throw bindException;
            }
            LOGGER.warning("Dashboard host '" + host + "' is not bindable on this machine, retrying on 0.0.0.0");
            return new ServerBinding(createServerOnce("0.0.0.0", port, https), "0.0.0.0");
        }
    }

    private record ServerBinding(HttpServer server, String host) {
    }

    private static HttpServer createServerOnce(String host, int port, boolean https) throws Exception {
        if (https) {
            try {
                return createHttpsServer(host, port);
            } catch (Exception httpsEx) {
                LOGGER.warning("Failed to create HTTPS server, falling back to HTTP: " + httpsEx.getMessage());
                return HttpServer.create(new InetSocketAddress(host, port), 0);
            }
        }
        return HttpServer.create(new InetSocketAddress(host, port), 0);
    }

    private static String normalizeBindHost(String host) {
        if (host == null) {
            return "127.0.0.1";
        }
        String normalized = host.trim();
        return normalized.isEmpty() ? "127.0.0.1" : normalized;
    }

    private static boolean shouldFallbackToWildcard(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
            || normalized.equals("0.0.0.0")
            || normalized.equals("::")
            || normalized.equals("127.0.0.1")
            || normalized.equals("localhost")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
                return false;
            }
        } catch (UnknownHostException ignored) {
            return true;
        }
        return true;
    }

    private static HttpServer createHttpsServer(String host, int port) throws Exception {
        KeyStore keyStore;
        String ksPath = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.developerDashboardKeystorePath : null;
        String ksPass = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.developerDashboardKeystorePassword : null;

        if (ksPath != null && !ksPath.isBlank()) {
            File ksFile = new File(ksPath);
            if (!ksFile.exists()) {
                throw new IOException("Keystore file not found: " + ksPath);
            }
            String lower = ksPath.toLowerCase();
            String type = (lower.endsWith(".p12") || lower.endsWith(".pfx")) ? "PKCS12" : "JKS";
            keyStore = KeyStore.getInstance(type);
            try (java.io.FileInputStream fis = new java.io.FileInputStream(ksFile)) {
                keyStore.load(fis, (ksPass == null || ksPass.isEmpty()) ? null : ksPass.toCharArray());
            }
            LOGGER.info("Loaded keystore from " + ksPath + " (type=" + keyStore.getType() + ")");
        } else {
            LOGGER.info("No keystore configured for dashboard HTTPS; attempting to generate a development self-signed certificate");
            keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null, null);

            throw new IOException("No keystore configured for dashboard HTTPS; please set developerDashboardKeystorePath/Password to enable HTTPS");
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, (ksPass == null) ? new char[0] : ksPass.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());

        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(host, port), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    SSLContext context = getSSLContext();
                    params.setSSLParameters(context.getDefaultSSLParameters());
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Failed to configure HTTPS parameters", ex);
                }
            }
        });

        return httpsServer;
    }

    private static void stopInternal() {
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception ex) {
                LOGGER.log(Level.FINE, "Error while stopping developer dashboard", ex);
            } finally {
                server = null;
                boundPort = -1;
                boundHost = "";
                isHttps = false;
                authRequired = false;
                LOGGER.info("Developer dashboard stopped");
            }
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void registerRoutes(HttpServer httpServer, boolean auth) {
    httpServer.createContext("/", wrap(auth ? DeveloperDashboardServer::handleAuthCheck : DeveloperDashboardServer::handleIndex));
    httpServer.createContext("/index.html", wrap(auth ? DeveloperDashboardServer::handleAuthCheck : exchange -> handleStatic(exchange, RESOURCE_ROOT + "index.html", "text/html;charset=UTF-8")));
    // Static assets must always be accessible so the login page can load resources even before authentication.
    httpServer.createContext("/dashboard.js", wrap(exchange -> handleStatic(exchange, RESOURCE_ROOT + "dashboard.js", "application/javascript;charset=UTF-8")));
    httpServer.createContext("/dashboard.css", wrap(exchange -> handleStatic(exchange, RESOURCE_ROOT + "dashboard.css", "text/css;charset=UTF-8")));
    httpServer.createContext("/logo_white.png", wrap(exchange -> handleStaticBinary(exchange, RESOURCE_ROOT + "logo_white.png", "image/png")));
    httpServer.createContext("/dashboard/logo_white.png", wrap(exchange -> handleStaticBinary(exchange, RESOURCE_ROOT + "logo_white.png", "image/png")));
    httpServer.createContext(LOGO_ENDPOINT, wrap(exchange -> handleStaticBinary(exchange, LOGO_RESOURCE, "image/png")));
    httpServer.createContext("/favicon.ico", wrap(DeveloperDashboardServer::handleFavicon));

        if (auth) {
            httpServer.createContext("/login", wrap(DeveloperDashboardServer::handleLoginPage));
            httpServer.createContext("/api/auth/login", wrap(DeveloperDashboardServer::handleLogin));
            httpServer.createContext("/api/auth/logout", wrap(DeveloperDashboardServer::handleLogout));
            httpServer.createContext("/setup", wrap(DeveloperDashboardServer::handleSetupPage));
            httpServer.createContext("/api/setup", wrap(DeveloperDashboardServer::handleSetup));
        }

        httpServer.createContext("/api/v1/health", wrap(DeveloperDashboardServer::handleHealth));
        httpServer.createContext("/api/v1/dashboard/state", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleDashboardState));
        httpServer.createContext("/api/v1/dashboard/timeline", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleTimeline));
        httpServer.createContext("/api/v1/dashboard/replay", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleReplay));
        httpServer.createContext("/api/v1/dashboard/toggles", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleToggleUpdate));
        httpServer.createContext("/api/v1/dashboard/history", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleHistoryExport));
        httpServer.createContext("/api/v1/dashboard/export", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleDiagnosticsExport));
        httpServer.createContext("/api/v1/mods", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleModRequest));
        httpServer.createContext("/api/v1/stress/clear-cache", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleStressClear));
        httpServer.createContext("/api/v1/stress/profile", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleStressProfile));
        httpServer.createContext("/api/v1/stress/run", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleStressRun));
        httpServer.createContext("/api/v1/resources", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleResourceOverview));
        httpServer.createContext("/api/v1/resources/flush", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleResourceFlush));
        httpServer.createContext("/api/v1/resources/disk", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleDiskManager));
        httpServer.createContext("/api/v1/mod-metrics", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleModMetrics));
        httpServer.createContext("/api/v1/config", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleConfigEndpoint));
    }

    private static HttpHandler wrap(CheckedHandler handler) {
        return exchange -> {
            try {
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleOptions(exchange);
                    return;
                }
                // Dashboard access requests are deliberately not sent to DeveloperOverlayManager.
                // The panel consumes that stream as Quantified runtime events; recording its own
                // polling requests there made remote dashboard traffic look like API activity.
                handler.handle(exchange);
            } catch (Exception ex) {
                LOGGER.log(Level.FINE, "Dashboard handler error", ex);
                sendError(exchange, 500, "Internal server error");
            }
        };
    }

    @FunctionalInterface
    private interface CheckedHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static void handleHealth(HttpExchange exchange) {
        sendJson(exchange, jsonObject(Map.of("status", "ok")));
    }

    private static void handleIndex(HttpExchange exchange) {
        if (!isDashboardConfigured()) {
            try {
                sendRedirect(exchange, "/setup");
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Failed to redirect to setup", e);
                sendError(exchange, 500, "Internal server error");
            }
            return;
        }
        handleStatic(exchange, RESOURCE_ROOT + "index.html", "text/html;charset=UTF-8");
    }

    private static void handleDashboardState(HttpExchange exchange) {
        DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics = DeveloperOverlayManager.diagnosticsView();
        JsonObject payload = buildStatePayload(diagnostics);
        if (LOGGER.isLoggable(Level.FINE)) {
            int queueDepth = diagnostics.snapshot().queueDepth();
            LOGGER.fine("Dashboard state request: queueDepth=" + queueDepth + ", execRate=" + diagnostics.snapshot().schedulerExecRate());
        }
        sendJson(exchange, payload);
    }

    private static void handleTimeline(HttpExchange exchange) {
        DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics = DeveloperOverlayManager.diagnosticsView();
        JsonObject payload = new JsonObject();
        payload.add("timeline", timelineArray(diagnostics.timeline()));
        JsonArray apiArray = new JsonArray();
        DeveloperOverlayManager.apiLogLines().forEach(line -> apiArray.add(line));
        payload.add("apiLogs", apiArray);
        sendJson(exchange, payload);
    }

    private static void handleReplay(HttpExchange exchange) {
        DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics = DeveloperOverlayManager.diagnosticsView();
        JsonObject payload = new JsonObject();
        payload.add("frames", replayArray(diagnostics.replayFrames()));
        sendJson(exchange, payload);
    }

    private static void handleModRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/mods".equals(path)) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            JsonObject payload = new JsonObject();
            payload.add("mods", describeConnectedMods());
            sendJson(exchange, payload);
            return;
        }
        if (path != null && path.startsWith("/api/v1/mods/") && path.endsWith("/stats")) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            String encodedId = path.substring("/api/v1/mods/".length(), path.length() - "/stats".length());
            String modId = URLDecoder.decode(encodedId, StandardCharsets.UTF_8);
            handleModStats(exchange, modId);
            return;
        }
        sendError(exchange, 404, "Not found");
    }

    private static JsonArray describeConnectedMods() {
        JsonArray mods = new JsonArray();
        long now = System.currentTimeMillis();
        for (ConnectedMod mod : QuantifiedAPI.getConnectedMods()) {
            ModStatistics stats = mod.getStatistics();
            JsonObject obj = new JsonObject();
            obj.addProperty("modId", mod.getModId());
            obj.addProperty("origModId", mod.getModId());
            obj.addProperty("displayName", mod.getDisplayName());
            obj.addProperty("version", mod.getVersion());
            if (stats != null) {
                Instant last = stats.getLastActivity();
                long lastValue = last != null ? last.toEpochMilli() : 0L;
                obj.addProperty("lastActivity", lastValue);
                boolean active = last != null && Math.abs(now - lastValue) < 25_000L;
                obj.addProperty("active", active);
                obj.addProperty("queueDepth", stats.getCurrentQueueDepth());
                obj.addProperty("tasksCompleted", stats.getTasksCompleted());
            } else {
                obj.addProperty("lastActivity", 0L);
                obj.addProperty("active", false);
                obj.addProperty("queueDepth", 0);
                obj.addProperty("tasksCompleted", 0);
            }
            mods.add(obj);
        }
        return mods;
    }

    private static void handleModStats(HttpExchange exchange, String modId) {
        ModStatistics stats = QuantifiedAPI.getModStatistics(modId);
        if (stats == null) {
            sendError(exchange, 404, "Mod not found");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("modId", stats.getModId());
        payload.addProperty("version", stats.getModVersion());
        payload.addProperty("tasksCompleted", stats.getTasksCompleted());
        payload.addProperty("tasksFailed", stats.getTasksFailed());
        payload.addProperty("totalTasksSubmitted", stats.getTotalTasksSubmitted());
        payload.addProperty("currentQueueDepth", stats.getCurrentQueueDepth());
        payload.addProperty("isThrottled", stats.isThrottled());
        payload.addProperty("throttleFactor", stats.getThrottleFactor());
        payload.addProperty("averageTaskTimeMs", stats.getAverageTaskTime().toMillis());
        payload.addProperty("maxTaskTimeMs", stats.getMaxTaskTime().toMillis());
        payload.addProperty("tasksPerSecond", stats.getTasksPerSecond());
        payload.addProperty("cacheHitRate", stats.getCacheHitRate());
        payload.addProperty("cacheSize", stats.getCacheSize());
        payload.addProperty("cacheMaxSize", stats.getCacheMaxSize());
        payload.addProperty("cacheEvictions", stats.getCacheEvictions());
        payload.addProperty("cacheMemoryUsage", stats.getCacheMemoryUsage());
        payload.addProperty("packetsSent", stats.getPacketsSent());
        payload.addProperty("packetsReceived", stats.getPacketsReceived());
        payload.addProperty("networkErrors", stats.getNetworkErrors());
        payload.addProperty("networkBytesTransferred", stats.getNetworkBytesTransferred());
        payload.addProperty("totalGPUTimeMs", stats.getTotalGPUTime().toMillis());
        payload.addProperty("peakVRAMUsage", stats.getPeakVRAMUsage());
        payload.addProperty("gpuUtilization", stats.getGPUUtilization());
        payload.addProperty("cpuFallbackRate", stats.getCPUFallbackRate());
        payload.addProperty("lastActivity", stats.getLastActivity() != null ? stats.getLastActivity().toEpochMilli() : 0L);
        sendJson(exchange, payload);
    }

    private static void handleHistoryExport(HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        JsonArray history = new JsonArray();
        synchronized (HISTORY_LOCK) {
            recentSnapshots.forEach(frame -> history.add(frame.deepCopy()));
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("count", history.size());
        payload.add("history", history);
        sendJson(exchange, payload);
    }

    private static void handleDiagnosticsExport(HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics = DeveloperOverlayManager.diagnosticsView();
        JsonObject payload = new JsonObject();
        payload.addProperty("exportedAt", System.currentTimeMillis());
        payload.add("state", buildStatePayload(diagnostics));
        JsonArray history = new JsonArray();
        synchronized (HISTORY_LOCK) {
            recentSnapshots.forEach(frame -> history.add(frame.deepCopy()));
        }
        payload.add("history", history);
        payload.add("mods", describeConnectedMods());
        sendJson(exchange, payload);
    }

    private static void handleStressClear(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        StressTestController.clearStressCache();
        JsonObject payload = new JsonObject();
        payload.addProperty("success", true);
        payload.addProperty("message", "Stress cache cleared");
        sendJson(exchange, payload);
    }

    private static void handleStressProfile(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        if (!DeveloperFeatures.isDeveloperModeEnabled()) {
            sendError(exchange, 409, "Developer mode disabled");
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject request = parseJsonObject(body);
            String profileKey = request.has("profile") ? request.get("profile").getAsString() : null;
            StressTestController.StressTestProfile profile = StressTestController.StressTestProfile.fromConfigKey(profileKey);
            DeveloperFeatures.setStressTestProfile(profile, true);
            JsonObject payload = new JsonObject();
            payload.addProperty("success", true);
            payload.addProperty("profile", profile.configKey());
            payload.addProperty("message", "Stress profile updated");
            sendJson(exchange, payload);
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, "Unknown stress profile");
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "Failed to update stress profile", ex);
            sendError(exchange, 500, "Failed to update stress profile");
        }
    }

    private static void handleStressRun(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        if (!DeveloperFeatures.isDeveloperModeEnabled()) {
            sendError(exchange, 409, "Developer mode disabled");
            return;
        }
        try {
            if (exchange.getRequestBody() != null) {
                // drain body to avoid client reset, even though we ignore payload
                exchange.getRequestBody().readAllBytes();
            }
            StressTestController.runOnce();
            JsonObject payload = new JsonObject();
            payload.addProperty("success", true);
            payload.addProperty("message", "Stress sweep dispatched");
            sendJson(exchange, payload);
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "Failed to run stress test cycle", ex);
            sendError(exchange, 500, "Failed to run stress test");
        }
    }

    private static void handleResourceOverview(HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics = DeveloperOverlayManager.diagnosticsView();
        JsonObject payload = buildResourcePayload(diagnostics);
        sendJson(exchange, payload);
    }

    private static void handleModMetrics(HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        sendJson(exchange, buildModMetricsPayload());
    }

    private static JsonObject buildModMetricsPayload() {
        long now = System.currentTimeMillis();
        long windowMs = 5 * 60 * 1000L;
        Map<String, ModActivityMetrics> aggregate = new LinkedHashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        Set<String> onlineMods = QuantifiedAPI.getConnectedMods().stream()
            .map(ConnectedMod::getModId)
            .collect(Collectors.toSet());
        for (ConnectedMod mod : QuantifiedAPI.getConnectedMods()) {
            displayNames.put(mod.getModId(), mod.getDisplayName());
            aggregate.computeIfAbsent(mod.getModId(), ModActivityMetrics::new).displayName = mod.getDisplayName();
        }

        TaskKindTelemetry.Snapshot taskSnapshot = TaskKindTelemetry.snapshot(windowMs, 200);
        JsonArray taskArray = new JsonArray();
        for (TaskKindTelemetry.KindStats entry : taskSnapshot.entries()) {
            ModActivityMetrics metrics = aggregate.computeIfAbsent(entry.modId, ModActivityMetrics::new);
            metrics.displayName = displayNames.getOrDefault(entry.modId, metrics.displayName);
            metrics.taskEvents += entry.count;
            metrics.batchCount += entry.batchCount;
            metrics.batchTotal += entry.batchTotal;
            metrics.batchMax = Math.max(metrics.batchMax, entry.batchMax);
            metrics.lastSeenMs = Math.max(metrics.lastSeenMs, entry.lastSeenMs);
            String route = entry.route == null ? "" : entry.route;
            if ("GPU Accel".equalsIgnoreCase(route)) {
                metrics.gpuEvents += entry.count;
            } else if ("Parallel".equalsIgnoreCase(route)) {
                metrics.parallelEvents += entry.count;
            } else if ("Multithreading".equalsIgnoreCase(route)) {
                metrics.multithreadingEvents += entry.count;
            } else {
                metrics.otherEvents += entry.count;
            }

            JsonObject task = new JsonObject();
            task.addProperty("modId", entry.modId);
            task.addProperty("displayName", displayNames.getOrDefault(entry.modId, entry.modId));
            task.addProperty("taskName", entry.taskName);
            task.addProperty("route", route);
            task.addProperty("count", entry.count);
            task.addProperty("lastSeenMs", entry.lastSeenMs);
            task.addProperty("batchCount", entry.batchCount);
            task.addProperty("batchTotal", entry.batchTotal);
            task.addProperty("batchMax", entry.batchMax);
            task.addProperty("batchAvg", entry.batchCount > 0 ? (double) entry.batchTotal / entry.batchCount : 0.0);
            taskArray.add(task);
        }

        DetailedInventory inventory = CacheManager.detailedInventory();
        inventory.caches().forEach((name, detail) -> {
            String modId = extractMetricModId(name);
            ModActivityMetrics metrics = aggregate.computeIfAbsent(modId, ModActivityMetrics::new);
            metrics.displayName = displayNames.getOrDefault(modId, metrics.displayName);
            metrics.cacheEntries += Math.max(0L, detail.entries());
            ThreadSafeCache.CacheStats stats = detail.stats();
            if (stats != null) {
                metrics.cacheHits += Math.max(0L, stats.hitCount());
                metrics.cacheMisses += Math.max(0L, stats.missCount());
                metrics.cacheEvictions += Math.max(0L, stats.evictionCount());
            }
        });

        Map<String, Long> diskUsage = DiskCacheManager.listCacheFiles().stream()
            .collect(Collectors.groupingBy(CacheFileDescriptor::modId, Collectors.summingLong(CacheFileDescriptor::sizeBytes)));
        for (Map.Entry<String, Long> entry : diskUsage.entrySet()) {
            ModActivityMetrics metrics = aggregate.computeIfAbsent(entry.getKey(), ModActivityMetrics::new);
            metrics.displayName = displayNames.getOrDefault(entry.getKey(), metrics.displayName);
            metrics.diskBytes = Math.max(0L, entry.getValue());
        }

        Map<String, ModStatistics> modStats = QuantifiedAPI.getAllModStatistics();
        for (Map.Entry<String, ModStatistics> entry : modStats.entrySet()) {
            String modId = entry.getKey();
            ModActivityMetrics metrics = aggregate.computeIfAbsent(modId, ModActivityMetrics::new);
            metrics.displayName = displayNames.getOrDefault(modId, modId);
            ModStatistics stats = entry.getValue();
            metrics.queueDepth = Math.max(metrics.queueDepth, stats.getCurrentQueueDepth());
            metrics.tasksPerSecond = Math.max(metrics.tasksPerSecond, stats.getTasksPerSecond());
            metrics.ramBytes = Math.max(metrics.ramBytes, Math.max(stats.getCacheMemoryUsage(), stats.getCacheSize() * 512L));
            metrics.peakVramBytes = Math.max(metrics.peakVramBytes, stats.getPeakVRAMUsage());
            Instant last = stats.getLastActivity();
            if (last != null) {
                metrics.lastSeenMs = Math.max(metrics.lastSeenMs, last.toEpochMilli());
            }
        }

        List<ModActivityMetrics> mods = new ArrayList<>(aggregate.values());
        mods.sort(Comparator
            .comparingLong(ModActivityMetrics::activityScore)
            .reversed()
            .thenComparing(metrics -> metrics.modId, String.CASE_INSENSITIVE_ORDER));

        JsonArray modArray = new JsonArray();
        long totalTaskEvents = 0L;
        long totalCacheRequests = 0L;
        long totalGpuEvents = 0L;
        for (ModActivityMetrics metrics : mods) {
            long cacheRequests = metrics.cacheHits + metrics.cacheMisses;
            totalTaskEvents += metrics.taskEvents;
            totalCacheRequests += cacheRequests;
            totalGpuEvents += metrics.gpuEvents;

            JsonObject mod = new JsonObject();
            mod.addProperty("modId", metrics.modId);
            mod.addProperty("displayName", metrics.displayName == null || metrics.displayName.isBlank() ? metrics.modId : metrics.displayName);
            mod.addProperty("online", onlineMods.contains(metrics.modId));
            mod.addProperty("active", metrics.lastSeenMs > 0L && Math.abs(now - metrics.lastSeenMs) < 25_000L);
            mod.addProperty("lastSeenMs", metrics.lastSeenMs);
            mod.addProperty("taskEvents", metrics.taskEvents);
            mod.addProperty("gpuEvents", metrics.gpuEvents);
            mod.addProperty("parallelEvents", metrics.parallelEvents);
            mod.addProperty("multithreadingEvents", metrics.multithreadingEvents);
            mod.addProperty("otherEvents", metrics.otherEvents);
            mod.addProperty("batchCount", metrics.batchCount);
            mod.addProperty("batchTotal", metrics.batchTotal);
            mod.addProperty("batchAvg", metrics.batchCount > 0L ? (double) metrics.batchTotal / metrics.batchCount : 0.0);
            mod.addProperty("batchMax", metrics.batchMax);
            mod.addProperty("cacheRequests", cacheRequests);
            mod.addProperty("cacheHits", metrics.cacheHits);
            mod.addProperty("cacheMisses", metrics.cacheMisses);
            mod.addProperty("cacheHitRate", cacheRequests > 0L ? (double) metrics.cacheHits / cacheRequests : 0.0);
            mod.addProperty("cacheEntries", metrics.cacheEntries);
            mod.addProperty("cacheEvictions", metrics.cacheEvictions);
            mod.addProperty("diskBytes", metrics.diskBytes);
            mod.addProperty("ramBytes", metrics.ramBytes);
            mod.addProperty("peakVramBytes", metrics.peakVramBytes);
            mod.addProperty("queueDepth", metrics.queueDepth);
            mod.addProperty("tasksPerSecond", metrics.tasksPerSecond);
            modArray.add(mod);
        }

        JsonObject summary = new JsonObject();
        summary.addProperty("modsTracked", mods.size());
        summary.addProperty("taskEvents", totalTaskEvents);
        summary.addProperty("gpuEvents", totalGpuEvents);
        summary.addProperty("cacheRequests", totalCacheRequests);
        summary.addProperty("gpuShare", totalTaskEvents > 0L ? (double) totalGpuEvents / totalTaskEvents : 0.0);

        JsonObject payload = new JsonObject();
        payload.addProperty("generatedAt", now);
        payload.addProperty("windowMs", taskSnapshot.windowMs());
        payload.add("summary", summary);
        payload.add("mods", modArray);
        payload.add("tasks", taskArray);
        return payload;
    }

    private static String extractMetricModId(String cacheName) {
        if (cacheName == null || cacheName.isBlank()) {
            return "quantified";
        }
        int dot = cacheName.indexOf('.');
        if (dot <= 0) {
            return "quantified";
        }
        String modId = cacheName.substring(0, dot).trim();
        return modId.isEmpty() || "unknown".equalsIgnoreCase(modId) ? "quantified" : modId;
    }

    private static JsonObject buildResourcePayload(DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics) {
        JsonObject payload = new JsonObject();
        payload.addProperty("generatedAt", System.currentTimeMillis());
        payload.addProperty("queueWarningThreshold", org.admany.quantified.core.common.parallel.config.ParallelConfig.queueLimit());

        CacheManager.CacheUsage usage = CacheManager.cacheUsageSnapshot();
        JsonObject summary = new JsonObject();
        summary.addProperty("queueDepth", diagnostics.snapshot().queueDepth());
        summary.addProperty("parallelActiveSlices", diagnostics.snapshot().parallelActiveSlices());
        summary.addProperty("totalWork", diagnostics.snapshot().totalWork());
        summary.addProperty("cacheEntryCount", usage.entryCount());
        summary.addProperty("cacheRamBytes", usage.heapBytes());
        summary.addProperty("cacheDiskBytes", usage.diskBytes());
        long vramTelemetryBytes = Math.max(0L, diagnostics.snapshot().gpuVramUsedBytes());
        long vramCacheBytes = Math.max(0L, OpenCLManager.cacheVramUsageBytes());
        long vramTaskBytes = Math.max(0L, OpenCLManager.activeTaskVramBytes())
            + Math.max(0L, VulkanExecutionSupport.activeTaskVramBytes());
        long vramContextBytes = Math.max(0L, vramTelemetryBytes - vramCacheBytes - vramTaskBytes);
        long vramEffectiveBytes = Math.max(vramTelemetryBytes, vramCacheBytes + vramTaskBytes);
        summary.addProperty("vramUsedBytes", vramEffectiveBytes);
        summary.addProperty("vramContextBytes", Math.max(0L, vramContextBytes));
        summary.addProperty("vramBudgetBytes", Math.max(0L, diagnostics.snapshot().gpuVramBudgetBytes()));
        summary.add("vulkanResidency", buildVulkanResidencyPayload());
        payload.add("summary", summary);

        List<CacheFileDescriptor> diskFiles = DiskCacheManager.listCacheFiles();
        Map<String, Long> diskUsage = diskFiles.stream()
            .collect(Collectors.groupingBy(CacheFileDescriptor::modId, Collectors.summingLong(CacheFileDescriptor::sizeBytes)));
        Set<String> onlineMods = QuantifiedAPI.getConnectedMods().stream()
            .map(ConnectedMod::getModId)
            .collect(Collectors.toSet());
        Map<String, String> displayNames = new HashMap<>();
        for (ConnectedMod mod : QuantifiedAPI.getConnectedMods()) {
            displayNames.put(mod.getModId(), mod.getDisplayName());
        }

        JsonArray diskArray = new JsonArray();
        for (CacheFileDescriptor descriptor : diskFiles) {
            JsonObject file = new JsonObject();
            file.addProperty("modId", descriptor.modId());
            file.addProperty("file", descriptor.fileName());
            file.addProperty("sizeBytes", descriptor.sizeBytes());
            file.addProperty("lastModified", descriptor.lastModifiedMillis());
            file.addProperty("modOnline", onlineMods.contains(descriptor.modId()));
            diskArray.add(file);
        }
        payload.add("diskFiles", diskArray);

        DetailedInventory inventory = CacheManager.detailedInventory();
        JsonArray cacheArray = new JsonArray();
        inventory.caches().forEach((name, detail) -> {
            JsonObject cache = new JsonObject();
            cache.addProperty("name", name);
            cache.addProperty("entries", detail.entries());
            ThreadSafeCache.CacheStats stats = detail.stats();
            if (stats != null) {
                cache.addProperty("hitRate", stats.hitRate());
                cache.addProperty("hitCount", stats.hitCount());
                cache.addProperty("missCount", stats.missCount());
                cache.addProperty("evictions", stats.evictionCount());
            }
            cacheArray.add(cache);
        });
        payload.add("caches", cacheArray);

        TaskKindTelemetry.Snapshot taskSnapshot = TaskKindTelemetry.snapshot();
        JsonArray taskKinds = new JsonArray();
        for (TaskKindTelemetry.KindStats entry : taskSnapshot.entries()) {
            JsonObject task = new JsonObject();
            task.addProperty("modId", entry.modId);
            task.addProperty("taskName", entry.taskName);
            task.addProperty("route", entry.route);
            task.addProperty("count", entry.count);
            task.addProperty("lastSeenMs", entry.lastSeenMs);
            task.addProperty("batchCount", entry.batchCount);
            double batchAvg = entry.batchCount > 0 ? (double) entry.batchTotal / entry.batchCount : 0.0;
            task.addProperty("batchAvg", batchAvg);
            task.addProperty("batchMax", entry.batchMax);
            taskKinds.add(task);
        }
        JsonObject taskKindsPayload = new JsonObject();
        taskKindsPayload.addProperty("windowMs", taskSnapshot.windowMs());
        taskKindsPayload.add("entries", taskKinds);
        payload.add("taskKinds", taskKindsPayload);

        Map<String, ModStatistics> modStats = QuantifiedAPI.getAllModStatistics();
        JsonArray modsArray = new JsonArray();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ModStatistics> entry : modStats.entrySet()) {
            String modId = entry.getKey();
            ModStatistics stats = entry.getValue();
            JsonObject modJson = new JsonObject();
            modJson.addProperty("modId", modId);
            modJson.addProperty("displayName", displayNames.getOrDefault(modId, modId));
            modJson.addProperty("queueDepth", stats.getCurrentQueueDepth());
            modJson.addProperty("tasksPerSecond", stats.getTasksPerSecond());
            modJson.addProperty("throttled", stats.isThrottled());
            long ramBytes = Math.max(stats.getCacheMemoryUsage(), stats.getCacheSize() * 512L);
            modJson.addProperty("ramBytes", ramBytes);
            modJson.addProperty("cacheEntries", stats.getCacheSize());
            modJson.addProperty("diskBytes", diskUsage.getOrDefault(modId, 0L));
            modJson.addProperty("peakVramBytes", stats.getPeakVRAMUsage());
            Instant last = stats.getLastActivity();
            long lastActivity = last != null ? last.toEpochMilli() : 0L;
            modJson.addProperty("lastActivity", lastActivity);
            modJson.addProperty("online", onlineMods.contains(modId));
            modJson.addProperty("active", last != null && Math.abs(now - lastActivity) < 25_000L);
            modsArray.add(modJson);
        }
        payload.add("mods", modsArray);
        return payload;
    }

    private static void handleResourceFlush(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = parseJsonObject(body);
        String target = request.has("target") ? request.get("target").getAsString() : "";
        JsonObject response = new JsonObject();
        switch (target.toLowerCase()) {
            case "ram" -> {
                CacheManager.clearAllCaches();
                StressTestController.clearStressCache();
                response.addProperty("message", "Cleared RAM caches");
            }
            case "vram" -> {
                try {
                    OpenCLTaskManager.handleVramSaturation("Manual dashboard flush");
                    VulkanExecutionSupport.trimInProcessResources("manual-dashboard-flush", true);
                    response.addProperty("message", "VRAM flush requested");
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "VRAM flush failed", t);
                    sendError(exchange, 500, "Failed to flush VRAM: " + t.getMessage());
                    return;
                }
            }
            default -> {
                sendError(exchange, 400, "Unknown resource target");
                return;
            }
        }
        response.addProperty("success", true);
        sendJson(exchange, response);
    }

    private static void handleDiskManager(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = parseJsonObject(body);
        int deleted = 0;
        if (request.has("delete") && request.get("delete").isJsonArray()) {
            for (JsonElement element : request.getAsJsonArray("delete")) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String modId = entry.has("modId") ? entry.get("modId").getAsString() : null;
                String file = entry.has("file") ? entry.get("file").getAsString() : null;
                if (modId == null || file == null) continue;
                if (DiskCacheManager.deleteCacheFile(modId, file)) {
                    deleted++;
                }
            }
        }
        if (request.has("purgeMod")) {
            String modId = request.get("purgeMod").getAsString();
            if (DiskCacheManager.deleteMod(modId)) {
                deleted++;
            }
        }
        if (request.has("clearAll") && request.get("clearAll").getAsBoolean()) {
            DiskCacheManager.clearAll();
        }
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("deleted", deleted);
        sendJson(exchange, response);
    }

    private static void handleConfigEndpoint(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            sendJson(exchange, buildConfigResponse());
            return;
        }
        if ("POST".equalsIgnoreCase(method)) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject request = parseJsonObject(body);
            try {
                applyConfigUpdates(request);
            } catch (IllegalArgumentException ex) {
                sendError(exchange, 400, ex.getMessage());
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("success", true);
            sendJson(exchange, payload);
            return;
        }
        sendError(exchange, 405, "Method not allowed");
    }

    private static JsonObject buildConfigResponse() {
        MultithreadingConfig.ConfigLayout layout = MultithreadingConfig.configLayout();
        JsonArray groups = new JsonArray();
        MultithreadingConfig.Config config = MultithreadingConfig.CONFIG;
        if (config == null) {
            JsonObject placeholder = new JsonObject();
            placeholder.addProperty("error", "Config not loaded");
            return placeholder;
        }
        JsonArray openclDeviceOptions = buildOpenClDeviceOptions();
        JsonArray vulkanDeviceOptions = buildVulkanDeviceOptions();
        JsonArray gpuBackendOptions = buildGpuBackendOptions();
        Map<String, Object> values = new LinkedHashMap<>();
        for (java.lang.reflect.Field field : MultithreadingConfig.Config.class.getFields()) {
            try {
                values.put(field.getName(), field.get(config));
            } catch (IllegalAccessException ignored) {
            }
        }
        layout.groups().forEach((groupName, keys) -> {
            JsonObject group = new JsonObject();
            group.addProperty("name", groupName);
            JsonArray fields = new JsonArray();
            for (String key : keys) {
                if (!values.containsKey(key)) continue;
                Object value = values.get(key);
                JsonObject field = new JsonObject();
                field.addProperty("key", key);
                field.addProperty("label", layout.displayNames().getOrDefault(key, key));
                field.addProperty("type", describeConfigType(value));
                field.addProperty("comment", layout.comments().getOrDefault(key, ""));
                field.add("value", GSON.toJsonTree(value));
                switch (key) {
                    case "preferredGpuBackend" -> {
                        field.addProperty("type", "select");
                        field.add("options", gpuBackendOptions);
                    }
                    case "vulkanDeviceId" -> {
                        field.addProperty("type", "select");
                        field.add("options", vulkanDeviceOptions);
                    }
                    case "openclDeviceId" -> {
                        field.addProperty("type", "select");
                        field.add("options", openclDeviceOptions);
                    }
                }
                fields.add(field);
            }
            group.add("fields", fields);
            groups.add(group);
        });
        JsonObject response = new JsonObject();
        response.add("groups", groups);
        response.addProperty("updatedAt", System.currentTimeMillis());
        return response;
    }

    private static String describeConfigType(Object value) {
        if (value == null) {
            return "string";
        }
        Class<?> type = value.getClass();
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            return "number";
        }
        if (value instanceof java.util.List) {
            return "list";
        }
        return "string";
    }

    private static JsonArray buildOpenClDeviceOptions() {
        JsonArray options = new JsonArray();
        JsonObject auto = new JsonObject();
        auto.addProperty("value", "auto");
        auto.addProperty("label", "Auto (fastest)");
        options.add(auto);
        for (GPUDetector.OpenCLDeviceInfo device : GPUDetector.listDevices()) {
            JsonObject option = new JsonObject();
            option.addProperty("value", device.id());
            option.addProperty("label", formatOpenClDeviceLabel(device));
            options.add(option);
        }
        return options;
    }

    private static JsonArray buildVulkanDeviceOptions() {
        JsonArray options = new JsonArray();
        JsonObject auto = new JsonObject();
        auto.addProperty("value", "auto");
        auto.addProperty("label", VulkanRuntime.hasBindings() ? "Auto (best Vulkan device)" : "Unavailable (Vulkan runtime missing)");
        options.add(auto);
        if (!VulkanRuntime.hasBindings()) {
            return options;
        }
        for (Object device : VulkanExecutionSupport.listInProcessDevices()) {
            JsonObject option = new JsonObject();
            option.addProperty("value", invokeVulkanDeviceString(device, "id", "unknown"));
            option.addProperty("label", formatVulkanDeviceLabel(device));
            options.add(option);
        }
        return options;
    }

    private static JsonArray buildGpuBackendOptions() {
        JsonArray options = new JsonArray();
        options.add(selectOption(GpuBackendPreference.VULKAN_PREFERRED.name(), GpuBackendPreference.VULKAN_PREFERRED.displayLabel()));
        options.add(selectOption(GpuBackendPreference.OPENCL_PREFERRED.name(), GpuBackendPreference.OPENCL_PREFERRED.displayLabel()));
        options.add(selectOption(GpuBackendPreference.VULKAN_REQUIRED.name(), GpuBackendPreference.VULKAN_REQUIRED.displayLabel()));
        options.add(selectOption(GpuBackendPreference.OPENCL_REQUIRED.name(), GpuBackendPreference.OPENCL_REQUIRED.displayLabel()));
        options.add(selectOption(GpuBackendPreference.CPU_ONLY.name(), GpuBackendPreference.CPU_ONLY.displayLabel()));
        return options;
    }

    private static String formatOpenClDeviceLabel(GPUDetector.OpenCLDeviceInfo device) {
        String type = device.type() != null ? device.type().name().toLowerCase(Locale.ROOT) : "gpu";
        String vram = device.vramBytes() > 0 ? formatBytes(device.vramBytes()) : "unknown VRAM";
        return device.name() + " (" + device.vendor() + " / " + type + " / " + vram + " / CU " + device.computeUnits() + ")";
    }

    private static String formatVulkanDeviceLabel(Object device) {
        int deviceType = invokeVulkanDeviceInt(device, "deviceType", 0);
        String type = switch (deviceType) {
            case 2 -> "discrete gpu";
            case 1 -> "integrated gpu";
            case 3 -> "virtual gpu";
            case 4 -> "cpu";
            default -> "gpu";
        };
        long localMemoryBytes = invokeVulkanDeviceLong(device, "localMemoryBytes", 0L);
        String vram = localMemoryBytes > 0 ? formatBytes(localMemoryBytes) : "unknown VRAM";
        String suffix = invokeVulkanDeviceBoolean(device, "softwareAdapter", false) ? " / software" : "";
        return invokeVulkanDeviceString(device, "name", "unknown") + " ("
            + invokeVulkanDeviceString(device, "vendor", "unknown") + " / " + type + " / " + vram + suffix + ")";
    }

    private static String invokeVulkanDeviceString(Object device, String methodName, String fallback) {
        Object value = invokeVulkanDeviceMethod(device, methodName);
        return value instanceof String string ? string : fallback;
    }

    private static JsonObject buildVulkanResidencyPayload() {
        Map<?, ?> residency = VulkanExecutionSupport.residencySnapshot();
        JsonObject payload = new JsonObject();
        payload.addProperty("available", !residency.isEmpty());
        if (residency.isEmpty()) {
            return payload;
        }
        payload.addProperty("reservedBytes", mapLong(residency, "reservedBytes"));
        payload.addProperty("localMemoryBytes", mapLong(residency, "localMemoryBytes"));
        payload.addProperty("softLimitBytes", mapLong(residency, "softLimitBytes"));
        payload.addProperty("hardLimitBytes", mapLong(residency, "hardLimitBytes"));
        payload.addProperty("slabCount", mapLong(residency, "slabCount"));
        payload.addProperty("workspacePoolCount", mapLong(residency, "workspacePoolCount"));
        payload.addProperty("workspacePoolBytes", mapLong(residency, "workspacePoolBytes"));
        payload.addProperty("cooldownActive", mapBoolean(residency, "cooldownActive"));
        payload.addProperty("cooldownRemainingMs", mapLong(residency, "cooldownRemainingMs"));
        payload.addProperty("trimEvents", mapLong(residency, "trimEvents"));
        payload.addProperty("trimmedBytes", mapLong(residency, "trimmedBytes"));
        payload.addProperty("trimmedWorkspacePools", mapLong(residency, "trimmedWorkspacePools"));
        payload.addProperty("trimmedWorkspaceBytes", mapLong(residency, "trimmedWorkspaceBytes"));
        payload.addProperty("trimmedSlabs", mapLong(residency, "trimmedSlabs"));
        payload.addProperty("trimmedSlabBytes", mapLong(residency, "trimmedSlabBytes"));
        payload.addProperty("pressureCooldowns", mapLong(residency, "pressureCooldowns"));
        payload.addProperty("pressureCooldownHits", mapLong(residency, "pressureCooldownHits"));
        payload.addProperty("pressureRejects", mapLong(residency, "pressureRejects"));
        return payload;
    }

    private static long mapLong(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static boolean mapBoolean(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Boolean bool && bool;
    }

    private static int invokeVulkanDeviceInt(Object device, String methodName, int fallback) {
        Object value = invokeVulkanDeviceMethod(device, methodName);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long invokeVulkanDeviceLong(Object device, String methodName, long fallback) {
        Object value = invokeVulkanDeviceMethod(device, methodName);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static boolean invokeVulkanDeviceBoolean(Object device, String methodName, boolean fallback) {
        Object value = invokeVulkanDeviceMethod(device, methodName);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static Object invokeVulkanDeviceMethod(Object device, String methodName) {
        if (device == null) {
            return null;
        }
        try {
            return device.getClass().getMethod(methodName).invoke(device);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static JsonObject selectOption(String value, String label) {
        JsonObject option = new JsonObject();
        option.addProperty("value", value);
        option.addProperty("label", label);
        return option;
    }

    private static void applyConfigUpdates(JsonObject request) {
        if (request == null || request.entrySet().isEmpty()) {
            throw new IllegalArgumentException("No config entries provided");
        }
        Map<String, JsonElement> updates = new LinkedHashMap<>();
        if (request.has("entries") && request.get("entries").isJsonArray()) {
            for (JsonElement element : request.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                if (!obj.has("key") || !obj.has("value")) continue;
                updates.put(obj.get("key").getAsString(), obj.get("value"));
            }
        } else {
            request.entrySet().forEach(entry -> updates.put(entry.getKey(), entry.getValue()));
        }
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("No config entries provided");
        }
        try {
            synchronized (MultithreadingConfig.class) {
                String previousVulkanDeviceId = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.vulkanDeviceId : null;
                String previousOpenclDeviceId = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.openclDeviceId : null;
                String previousGpuPreference = MultithreadingConfig.CONFIG != null ? MultithreadingConfig.CONFIG.preferredGpuBackend : null;
                String updatedVulkanDeviceId = previousVulkanDeviceId;
                String updatedOpenclDeviceId = previousOpenclDeviceId;
                String updatedGpuPreference = previousGpuPreference;
                boolean vulkanDeviceChanged = false;
                boolean openclDeviceChanged = false;
                boolean gpuPreferenceChanged = false;
                boolean gpuAccelerationChanged = false;
                for (Map.Entry<String, JsonElement> entry : updates.entrySet()) {
                    String key = entry.getKey();
                    java.lang.reflect.Field field = MultithreadingConfig.Config.class.getField(key);
                    Object coerced = coerceConfigValue(field.getType(), entry.getValue());
                    field.set(MultithreadingConfig.CONFIG, coerced);
                    if ("vulkanDeviceId".equals(key)) {
                        updatedVulkanDeviceId = coerced != null ? coerced.toString() : null;
                        vulkanDeviceChanged = !Objects.equals(previousVulkanDeviceId, updatedVulkanDeviceId);
                    }
                    if ("openclDeviceId".equals(key)) {
                        updatedOpenclDeviceId = coerced != null ? coerced.toString() : null;
                        openclDeviceChanged = !Objects.equals(previousOpenclDeviceId, updatedOpenclDeviceId);
                    }
                    if ("preferredGpuBackend".equals(key)) {
                        updatedGpuPreference = coerced != null ? coerced.toString() : null;
                        gpuPreferenceChanged = !Objects.equals(previousGpuPreference, updatedGpuPreference);
                    }
                    if ("enableGpuAcceleration".equals(key)) {
                        gpuAccelerationChanged = true;
                    }
                }
                MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
                boolean gpuEnabledNow = MultithreadingConfig.isGpuAccelerationEnabled();
                if (!gpuEnabledNow) {
                    VulkanProbeScheduler.reset();
                    AsyncProbeScheduler.reset();
                    OpenCLManager.shutdown();
                    VulkanExecutionSupport.shutdownInProcess();
                    DeveloperOverlayManager.recordApiLog("[Quantified] GPU acceleration disabled from config");
                    return;
                }
                if (vulkanDeviceChanged && VulkanRuntime.hasBindings()) {
                    VulkanExecutionSupport.setPreferredInProcessDevice(normalizeAutoDeviceValue(updatedVulkanDeviceId));
                }
                if (openclDeviceChanged) {
                    OpenCLManager.switchDevice(updatedOpenclDeviceId);
                }
                if ((gpuAccelerationChanged || gpuPreferenceChanged || vulkanDeviceChanged) && VulkanRuntime.hasBindings()) {
                    VulkanProbeScheduler.triggerProbe("dashboard-config");
                }
                if ((gpuAccelerationChanged || gpuPreferenceChanged || vulkanDeviceChanged) && VulkanRuntime.hasBindings()
                    && shouldWarmupVulkanAfterConfig(updatedGpuPreference)) {
                    VulkanExecutionSupport.warmupInProcessAsync("dashboard-config");
                }
                if (gpuAccelerationChanged || gpuPreferenceChanged || openclDeviceChanged) {
                    AsyncProbeScheduler.triggerProbe("dashboard-config");
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("Invalid config key provided", ex);
        }
    }

    private static String normalizeAutoDeviceValue(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return "auto".equalsIgnoreCase(deviceId.trim()) ? null : deviceId;
    }

    private static boolean shouldWarmupVulkanAfterConfig(String gpuPreference) {
        if (gpuPreference == null || gpuPreference.isBlank()) {
            return true;
        }
        try {
            GpuBackendPreference preference = GpuBackendPreference.valueOf(gpuPreference.trim());
            return preference == GpuBackendPreference.VULKAN_PREFERRED
                || preference == GpuBackendPreference.VULKAN_REQUIRED;
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    private static Object coerceConfigValue(Class<?> type, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (type == Boolean.TYPE || type == Boolean.class) {
            return value.getAsBoolean();
        }
        if (type == Integer.TYPE || type == Integer.class) {
            return value.getAsInt();
        }
        if (type == Long.TYPE || type == Long.class) {
            return value.getAsLong();
        }
        if (type == Double.TYPE || type == Double.class) {
            return value.getAsDouble();
        }
        if (type == String.class) {
            return value.getAsString();
        }
        if (java.util.List.class.isAssignableFrom(type)) {
            java.util.List<String> list = new java.util.ArrayList<>();
            if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonNull()) continue;
                    list.add(item.getAsString());
                }
            } else if (value.isJsonPrimitive()) {
                list.add(value.getAsString());
            }
            return list;
        }
        return GSON.fromJson(value, type);
    }

    private static void handleToggleUpdate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject request = parseJsonObject(body);

            // Update toggles
            if (request.has("developerMode")) {
                boolean developerMode = request.get("developerMode").getAsBoolean();
                DeveloperFeatures.setDeveloperMode(developerMode, true);
            }
            if (request.has("dashboardEnabled")) {
                boolean dashboard = request.get("dashboardEnabled").getAsBoolean();
                DeveloperFeatures.setDashboardEnabled(dashboard, true);
            }
            if (request.has("timelineEnabled")) {
                boolean timeline = request.get("timelineEnabled").getAsBoolean();
                DeveloperFeatures.setTimelineEnabled(timeline, true);
            }
            if (request.has("replayEnabled")) {
                boolean replay = request.get("replayEnabled").getAsBoolean();
                DeveloperFeatures.setReplayEnabled(replay, true);
            }
            if (request.has("stressTestEnabled")) {
                boolean stress = request.get("stressTestEnabled").getAsBoolean();
                DeveloperFeatures.setStressTestEnabled(stress, true);
            }
            if (request.has("modSpotlightEnabled")) {
                boolean spotlight = request.get("modSpotlightEnabled").getAsBoolean();
                DeveloperFeatures.setModSpotlightEnabled(spotlight, true);
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Toggles updated");
            sendJson(exchange, response);

        } catch (Exception e) {
            sendError(exchange, 400, "Invalid request");
        }
    }

    private static void handleStatic(HttpExchange exchange, String resourcePath, String contentType) {
        try {
            String body = readResource(resourcePath);
            if (body == null) {
                sendBytes(exchange, 404, "text/plain;charset=UTF-8", "Not Found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
            sendBytes(exchange, 200, contentType, body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ioException) {
            LOGGER.log(Level.FINE, "Failed to serve static asset " + resourcePath, ioException);
            sendError(exchange, 500, "Internal server error");
        }
    }

    private static void handleStaticBinary(HttpExchange exchange, String resourcePath, String contentType) {
        try {
            byte[] data = readBinaryResource(resourcePath);
            if (data == null) {
                sendBytes(exchange, 404, "text/plain;charset=UTF-8", "Not Found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
            sendBytes(exchange, 200, contentType, data);
        } catch (IOException ioException) {
            LOGGER.log(Level.FINE, "Failed to serve static asset " + resourcePath, ioException);
            sendError(exchange, 500, "Internal server error");
        }
    }

    private static void handleFavicon(HttpExchange exchange) {
        handleStaticBinary(exchange, LOGO_RESOURCE, "image/png");
    }

    private static void handleOptions(HttpExchange exchange) {
        sendBytes(exchange, 204, "text/plain", new byte[0]);
    }

    private static void applyCors(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, JsonElement payload) {
        sendJson(exchange, payload, 200);
    }

    private static void sendJson(HttpExchange exchange, JsonElement payload, int statusCode) {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        sendBytes(exchange, statusCode, "application/json;charset=UTF-8", bytes);
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        sendJson(exchange, error, statusCode);
    }

    private static JsonObject jsonObject(Map<String, String> map) {
        JsonObject obj = new JsonObject();
        map.forEach(obj::addProperty);
        return obj;
    }

    private static void sendBytes(HttpExchange exchange, int statusCode, String contentType, byte[] data) {
        try {
            Headers headers = exchange.getResponseHeaders();
            applyCors(headers);
            if (contentType != null) {
                headers.set("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(statusCode, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } catch (IOException ioe) {
            LOGGER.log(Level.FINE, "Failed to send response", ioe);
        } finally {
            exchange.close();
        }
    }

    private static JsonObject buildStatePayload(DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics) {
        JsonObject payload = new JsonObject();
        boolean gpuAccelerationEnabled = MultithreadingConfig.isGpuAccelerationEnabled();
        payload.addProperty("enableGpuAcceleration", gpuAccelerationEnabled);
        payload.addProperty("gpuConfigurationState", gpuAccelerationEnabled ? "ENABLED" : "DISABLED");
        payload.addProperty("developerMode", DeveloperFeatures.isDeveloperModeEnabled());
        payload.addProperty("dashboardEnabled", DeveloperFeatures.isDashboardEnabled());
        payload.addProperty("timelineEnabled", DeveloperFeatures.isTimelineEnabled());
        payload.addProperty("replayEnabled", DeveloperFeatures.isReplayEnabled());
        payload.addProperty("autoHintsEnabled", DeveloperFeatures.isAutoHintsEnabled());
        payload.addProperty("stressTestEnabled", DeveloperFeatures.isStressTestEnabled());
        payload.addProperty("modSpotlightEnabled", DeveloperFeatures.isModSpotlightEnabled());
        OpenCLManager.RuntimeStatus openclStatus = OpenCLManager.runtimeStatus();
        boolean vulkanBindingsPresent = VulkanRuntime.hasBindings();
        VulkanRuntime.RuntimeMode vulkanRuntimeMode = VulkanRuntime.runtimeMode();
        boolean vulkanProbeAvailable = VulkanRuntime.isAvailable() || vulkanRuntimeMode == VulkanRuntime.RuntimeMode.ISOLATED;
        boolean vulkanInitialized = VulkanExecutionSupport.isRuntimeReady();
        String vulkanFailureReason = vulkanBindingsPresent
            ? VulkanExecutionSupport.failureReason()
            : "Using isolated bundled Vulkan runtime for this Minecraft version";
        payload.addProperty("openclAvailable", openclStatus.isAvailable());
        if (openclStatus.failureReason() != null) {
            payload.addProperty("openclFailureReason", openclStatus.failureReason());
        }
        payload.addProperty("vulkanAvailable", vulkanProbeAvailable);
        payload.addProperty("vulkanInitialized", vulkanInitialized);
        payload.addProperty("vulkanRuntimeMode", vulkanRuntimeMode.name());
        payload.addProperty("vulkanRuntimeState", !gpuAccelerationEnabled
            ? "DISABLED_BY_CONFIG"
            : vulkanInitialized ? "READY"
            : vulkanProbeAvailable ? "PROBE_READY_RUNTIME_PENDING"
            : "UNAVAILABLE");
        payload.addProperty("openclRuntimeState", !gpuAccelerationEnabled
            ? "DISABLED_BY_CONFIG"
            : openclStatus.isAvailable() ? "READY"
            : "UNAVAILABLE");
        if (!vulkanProbeAvailable) {
            payload.addProperty("vulkanFailureReason", vulkanFailureReason);
        } else if (vulkanFailureReason != null) {
            payload.addProperty("vulkanFailureReason", "Probe succeeded; runtime initialization deferred until first use");
        }
        GPUMonitor.GPUStatus gpuStatus = OpenCLManager.getGPUStatus();
        payload.addProperty("openclDeviceName", gpuStatus != null ? gpuStatus.deviceName() : "");
        payload.addProperty("vulkanDeviceName", vulkanProbeAvailable ? VulkanExecutionSupport.deviceName() : "");
        payload.add("vulkanResidency", buildVulkanResidencyPayload());
        GpuBackendPreference configuredPreference = GpuBackendRouter.getDefaultPreference();
        payload.addProperty("configuredGpuBackendPreference", configuredPreference.name());
        GpuBackendType activeBackend = resolveDashboardGpuBackend(configuredPreference);
        payload.addProperty("activeGpuBackend", activeBackend.name());
        payload.addProperty("activeGpuDeviceName", resolveDashboardGpuDeviceName(activeBackend, gpuStatus));
        StressTestController.StressTestProfile profile = DeveloperFeatures.getStressTestProfile();
        if (profile != null) {
            payload.addProperty("stressTestProfile", profile.configKey());
            payload.addProperty("stressTestProfileLabel", profile.description());
        }
        JsonArray profileOptions = new JsonArray();
        for (StressTestController.StressTestProfile option : StressTestController.StressTestProfile.values()) {
            JsonObject optionJson = new JsonObject();
            optionJson.addProperty("key", option.configKey());
            optionJson.addProperty("label", option.description());
            profileOptions.add(optionJson);
        }
        payload.add("stressProfiles", profileOptions);
    payload.addProperty("stressTestCycleCount", StressTestController.cycleCount());
    payload.addProperty("gpuTestComputationCount", StressTestController.gpuTestComputationCount());
    payload.addProperty("cpuTestComputationCount", StressTestController.cpuTestComputationCount());
    payload.addProperty("stressTestLastRun", StressTestController.lastInjectionTimestamp());

    payload.addProperty("cpuChunkMs", StressTestController.getCpuChunkMs());
    payload.addProperty("stressPoolSize", StressTestController.getStressPoolSize());

    payload.addProperty("stressCacheSize", StressTestController.getStressCacheSize());
    payload.addProperty("totalCacheSize", StressTestController.getTotalCacheSize());

    payload.addProperty("stressTestPacketsSent", StressTestController.networkTestPacketsSent());
    payload.addProperty("stressTestPacketsReceived", StressTestController.networkTestPacketsReceived());
    payload.addProperty("stressTestBytesTransferred", StressTestController.networkTestBytesTransferred());
        long modsTasksSubmitted = 0L;
        long modsTasksCompleted = 0L;
        long modsTasksFailed = 0L;
        long modsPacketsSent = 0L;
        long modsPacketsReceived = 0L;
        long modsNetworkErrors = 0L;
        long modsNetworkBytes = 0L;
        long modsCacheSize = 0L;
        long modsCacheMaxSize = 0L;
        try {
            java.util.Map<String, ModStatistics> allStats = QuantifiedAPI.getAllModStatistics();
            for (ModStatistics stats : allStats.values()) {
                if (stats == null) {
                    continue;
                }
                modsTasksSubmitted += stats.getTotalTasksSubmitted();
                modsTasksCompleted += stats.getTasksCompleted();
                modsTasksFailed += stats.getTasksFailed();
                modsPacketsSent += stats.getPacketsSent();
                modsPacketsReceived += stats.getPacketsReceived();
                modsNetworkErrors += stats.getNetworkErrors();
                modsNetworkBytes += stats.getNetworkBytesTransferred();
                modsCacheSize += stats.getCacheSize();
                modsCacheMaxSize += stats.getCacheMaxSize();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Failed to aggregate Quantified statistics", t);
        }
        payload.addProperty("modsTasksSubmitted", modsTasksSubmitted);
        payload.addProperty("modsTasksCompleted", modsTasksCompleted);
        payload.addProperty("modsTasksFailed", modsTasksFailed);
        payload.addProperty("modsPacketsSent", modsPacketsSent);
        payload.addProperty("modsPacketsReceived", modsPacketsReceived);
        payload.addProperty("modsNetworkErrors", modsNetworkErrors);
        payload.addProperty("modsNetworkBytes", modsNetworkBytes);
        payload.addProperty("modsCacheSize", modsCacheSize);
        payload.addProperty("modsCacheMaxSize", modsCacheMaxSize);
        CacheManager.CacheUsage cacheUsage = CacheManager.cacheUsageSnapshot();
        payload.addProperty("cacheEntryCount", cacheUsage.entryCount());
        payload.addProperty("cacheRamBytes", cacheUsage.heapBytes());
        payload.addProperty("cacheDiskBytes", cacheUsage.diskBytes());
        payload.addProperty("diskCacheActive", cacheUsage.diskBytes() > 0);
        payload.addProperty("port", boundPort >= 0 ? boundPort : MultithreadingConfig.CONFIG.developerDashboardPort);
        payload.addProperty("timelineSize", diagnostics.timeline().size());
        payload.addProperty("replayFrameCount", diagnostics.replayFrames().size());
        String displayName = resolveDashboardDisplayName();
        if (!displayName.isEmpty()) {
            payload.addProperty("playerName", displayName);
            payload.addProperty("username", displayName);
        }

        payload.addProperty("openclForced", MultithreadingConfig.CONFIG.openclForced);

        TaskScheduler.SchedulingStats schedulerStats = TaskScheduler.getStats();
        payload.addProperty("schedulerTotalTasks", schedulerStats.totalTasks());
        payload.addProperty("schedulerCpuTasks", schedulerStats.cpuTasks());
        payload.addProperty("schedulerGpuTasks", schedulerStats.gpuTasks());
        payload.addProperty("schedulerGpuRatio", schedulerStats.gpuUtilizationRatio());
        // Snapshot JSON (also record into the export history buffer)
        JsonObject snapshotJson = toJson(diagnostics.snapshot());
        payload.add("snapshot", snapshotJson);
        long vramTelemetryBytes = Math.max(0L, diagnostics.snapshot().gpuVramUsedBytes());
        long vramCacheBytes = Math.max(0L, OpenCLManager.cacheVramUsageBytes());
        long vramTaskBytes = Math.max(0L, OpenCLManager.activeTaskVramBytes())
            + Math.max(0L, VulkanExecutionSupport.activeTaskVramBytes());
        long vramContextBytes = Math.max(0L, vramTelemetryBytes - vramCacheBytes - vramTaskBytes);
        long vramEffectiveBytes = Math.max(vramTelemetryBytes, vramCacheBytes + vramTaskBytes);
        payload.addProperty("gpuVramBudgetBytes", diagnostics.snapshot().gpuVramBudgetBytes());
        payload.addProperty("gpuVramUsedBytes", vramEffectiveBytes);
        payload.addProperty("gpuVramUsedTelemetryBytes", vramTelemetryBytes);
        payload.addProperty("gpuVramCacheBytes", vramCacheBytes);
        payload.addProperty("gpuVramTaskBytes", vramTaskBytes);
        payload.addProperty("gpuVramContextBytes", vramContextBytes);
        payload.addProperty("gpuSystemUsageRatio", diagnostics.snapshot().gpuSystemUsageRatio());
        try {
            synchronized (HISTORY_LOCK) {
                recentSnapshots.addLast(snapshotJson);
                while (recentSnapshots.size() > EXPORT_HISTORY_MAX) {
                    recentSnapshots.removeFirst();
                }
            }
        } catch (Throwable t) {

        }
        payload.add("hints", hintArray(diagnostics.hints()));
        payload.add("spotlight", spotlightArray(diagnostics.spotlight()));
        payload.add("systemInfo", getSystemInfo());
        // Include API logs in the main dashboard state for immediate display
        JsonArray apiArray = new JsonArray();
        DeveloperOverlayManager.apiLogLines().forEach(line -> apiArray.add(line));
        payload.add("apiLogs", apiArray);
        return payload;
    }

    private static GpuBackendType resolveDashboardGpuBackend(GpuBackendPreference configuredPreference) {
        if (MultithreadingConfig.CONFIG == null || !MultithreadingConfig.CONFIG.enableGpuAcceleration) {
            return GpuBackendType.CPU;
        }
        return GpuBackendRouter.selectBackend(
            "dashboard",
            configuredPreference,
            true,
            OpenCLManager.hasExecutableRuntime(),
            true,
            VulkanExecutionSupport.hasExecutableRuntime()
        ).backendType();
    }

    private static String resolveDashboardGpuDeviceName(GpuBackendType backendType, GPUMonitor.GPUStatus gpuStatus) {
        return switch (backendType) {
            case VULKAN -> VulkanExecutionSupport.hasExecutableRuntime() ? VulkanExecutionSupport.deviceName() : "";
            case OPENCL -> gpuStatus != null ? gpuStatus.deviceName() : "";
            case CPU -> "";
        };
    }

    private static String resolveDashboardDisplayName() {
        String playerName = resolveDashboardPlayerName();
        if (!playerName.isEmpty()) {
            return playerName;
        }
        if (MultithreadingConfig.CONFIG != null) {
            String configuredName = safeTrim(MultithreadingConfig.CONFIG.developerDashboardUsername);
            if (!configuredName.isEmpty()) {
                return configuredName;
            }
        }
        return "";
    }

    private static String resolveDashboardPlayerName() {
        String serverPlayerName = resolveServerPlayerName();
        if (!serverPlayerName.isEmpty()) {
            return serverPlayerName;
        }

        String clientPlayerName = resolveClientPlayerName();
        if (!clientPlayerName.isEmpty()) {
            return clientPlayerName;
        }

        return "";
    }

    private static String resolveServerPlayerName() {
        try {
            Class<?> hooksClass = Class.forName("net.minecraftforge.server.ServerLifecycleHooks");
            Object server = hooksClass.getMethod("getCurrentServer").invoke(null);
            if (server != null) {
                Object playerList = server.getClass().getMethod("getPlayerList").invoke(server);
                if (playerList != null) {
                    Object playersObj = playerList.getClass().getMethod("getPlayers").invoke(playerList);
                    if (playersObj instanceof List<?> players) {
                        for (Object player : players) {
                            String name = extractPlayerName(player);
                            if (!name.isEmpty()) {
                                return name;
                            }
                        }
                    }
                }
                String ownerName = extractServerOwnerName(server);
                if (!ownerName.isEmpty()) {
                    return ownerName;
                }
            }
        } catch (Throwable ignored) {
        }

        return "";
    }

    private static String resolveClientPlayerName() {
        if (!PhysicalEnvironment.isClient()) {
            return "";
        }
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
            if (minecraft != null) {
                String sessionName = extractMinecraftSessionName(minecraft);
                if (!sessionName.isEmpty()) {
                    return sessionName;
                }

                Object player = extractMinecraftPlayer(minecraftClass, minecraft);
                String name = extractPlayerName(player);
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }

        return "";
    }

    private static String extractServerOwnerName(Object server) {
        if (server == null) {
            return "";
        }
        try {
            Object profile = server.getClass().getMethod("getSingleplayerProfile").invoke(server);
            if (profile != null) {
                Object nameObj = profile.getClass().getMethod("getName").invoke(profile);
                String name = safeTrim(nameObj == null ? "" : String.valueOf(nameObj));
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object nameObj = server.getClass().getMethod("getSingleplayerName").invoke(server);
            String name = safeTrim(nameObj == null ? "" : String.valueOf(nameObj));
            if (!name.isEmpty()) {
                return name;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String extractMinecraftSessionName(Object minecraft) {
        if (minecraft == null) {
            return "";
        }
        try {
            Object user = minecraft.getClass().getMethod("getUser").invoke(minecraft);
            if (user != null) {
                Object nameObj = user.getClass().getMethod("getName").invoke(user);
                String name = safeTrim(nameObj == null ? "" : String.valueOf(nameObj));
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static Object extractMinecraftPlayer(Class<?> minecraftClass, Object minecraft) {
        if (minecraft == null) {
            return null;
        }
        try {
            return minecraftClass.getField("player").get(minecraft);
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field[] fields = minecraftClass.getFields();
            for (java.lang.reflect.Field field : fields) {
                Class<?> type = field.getType();
                if (type == null) {
                    continue;
                }
                String typeName = type.getName();
                if (typeName.equals("net.minecraft.client.player.LocalPlayer")
                    || typeName.endsWith(".LocalPlayer")) {
                    return field.get(minecraft);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String extractPlayerName(Object player) {
        if (player == null) {
            return "";
        }
        try {
            Object profile = player.getClass().getMethod("getGameProfile").invoke(player);
            if (profile != null) {
                Object nameObj = profile.getClass().getMethod("getName").invoke(profile);
                String name = safeTrim(nameObj == null ? "" : String.valueOf(nameObj));
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object component = player.getClass().getMethod("getName").invoke(player);
            if (component != null) {
                try {
                    Object text = component.getClass().getMethod("getString").invoke(component);
                    String fromComponent = safeTrim(text == null ? "" : String.valueOf(text));
                    if (!fromComponent.isEmpty()) {
                        return fromComponent;
                    }
                } catch (Throwable ignored) {
                    String fallback = safeTrim(String.valueOf(component));
                    if (!fallback.isEmpty()) {
                        return fallback;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static JsonObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        JsonElement element = JsonParser.parseString(raw);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Payload must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static String readResource(String resourcePath) throws IOException {
        try (InputStream stream = DeveloperDashboardServer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readBinaryResource(String resourcePath) throws IOException {
        try (InputStream stream = DeveloperDashboardServer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            return stream.readAllBytes();
        }
    }

    private static JsonObject toJson(DeveloperOverlayManager.OverlaySnapshot snapshot) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", snapshot.timestamp());
        json.addProperty("gpuAvailable", snapshot.gpuAvailable());
        json.addProperty("gpuMemoryUtil", snapshot.gpuMemoryUtil());
        json.addProperty("gpuComputeUtil", snapshot.gpuComputeUtil());
        json.addProperty("gpuTemperature", snapshot.gpuTemperature());
        json.addProperty("gpuVramBudgetBytes", snapshot.gpuVramBudgetBytes());
        json.addProperty("gpuVramUsedBytes", snapshot.gpuVramUsedBytes());
        json.addProperty("gpuSystemUsageRatio", snapshot.gpuSystemUsageRatio());

        json.addProperty("deviceName", snapshot.deviceName());
        json.addProperty("queueDepth", snapshot.queueDepth());
        json.addProperty("parallelActiveSlices", snapshot.parallelActiveSlices());
        json.addProperty("totalWork", snapshot.totalWork());
        json.addProperty("foregroundQueue", snapshot.foregroundQueue());
        json.addProperty("backgroundQueue", snapshot.backgroundQueue());
        json.addProperty("desiredForegroundWorkers", snapshot.desiredForegroundWorkers());
        json.addProperty("desiredBackgroundWorkers", snapshot.desiredBackgroundWorkers());
        json.addProperty("schedulerExecRate", snapshot.schedulerExecRate());
        json.addProperty("cpuSystemLoad", snapshot.cpuSystemLoad());
        json.addProperty("fallbackTotal", snapshot.fallbackTotal());
        json.addProperty("fallbackRecent", snapshot.fallbackRecent());
        return json;
    }

    private static JsonArray hintArray(Iterable<DeveloperOverlayManager.AutoTuningHint> hints) {
        JsonArray array = new JsonArray();
        hints.forEach(hint -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("severity", hint.severity().name());
            obj.addProperty("message", hint.message());
            array.add(obj);
        });
        return array;
    }

    private static JsonArray spotlightArray(Iterable<DeveloperOverlayManager.ModSpotlightEntry> entries) {
        JsonArray array = new JsonArray();
        entries.forEach(entry -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("modId", entry.modId());
            obj.addProperty("version", entry.version());
            obj.addProperty("tasksInFlight", entry.tasksInFlight());
            obj.addProperty("estimatedHeadroom", entry.estimatedHeadroom());
            obj.addProperty("cacheHitRate", entry.cacheHitRate());
            array.add(obj);
        });
        return array;
    }

    private static JsonArray timelineArray(Iterable<DeveloperOverlayManager.TimelineEvent> events) {
        JsonArray array = new JsonArray();
        events.forEach(event -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("timestamp", event.timestamp());
            obj.addProperty("type", event.type().name());
            obj.addProperty("message", event.message());
            obj.addProperty("queueDepth", event.queueDepth());
            obj.addProperty("gpuMemoryUtil", event.gpuMemoryUtil());
            obj.addProperty("gpuComputeUtil", event.gpuComputeUtil());
            obj.addProperty("gpuTemperature", event.gpuTemperature());
            obj.addProperty("modId", event.modId());
            array.add(obj);
        });
        return array;
    }

    private static JsonArray replayArray(Iterable<DeveloperOverlayManager.OverlaySnapshot> frames) {
        JsonArray array = new JsonArray();
        frames.forEach(frame -> array.add(toJson(frame)));
        return array;
    }

    private static JsonObject getSystemInfo() {
        JsonObject info = new JsonObject();
        SystemInfo system = getSystemInfoInstance();
        info.addProperty("os", describeOperatingSystem(system));
        if (system != null) {
            try {
                GlobalMemory memory = system.getHardware().getMemory();
                long phys = memory.getTotal();
                long available = memory.getAvailable();
                if (phys > 0) {
                    info.addProperty("ramTotalBytes", phys);
                    info.addProperty("ramAvailableBytes", available);
                    info.addProperty("ramUsedBytes", Math.max(0L, phys - available));
                    info.addProperty("ram", String.format("%.1f GB", phys / (1024.0 * 1024.0 * 1024.0)));
                }
            } catch (Throwable ignored) {
            }
        }
        if (!info.has("ram")) {
            long maxMemory = Runtime.getRuntime().maxMemory();
            info.addProperty("ram", maxMemory == Long.MAX_VALUE ? "Unlimited" : String.format("%.1f GB", maxMemory / (1024.0 * 1024.0 * 1024.0)));
        }
        int cores = Runtime.getRuntime().availableProcessors();
        String cpuModel = org.admany.quantified.core.common.opencl.gpu.probe.HardwareProbeService.getCPUNameFromSystem();
        info.addProperty("cpu", cpuModel + " (" + cores + " cores)");
        if (system != null) {
            try {
                CentralProcessor processor = system.getHardware().getProcessor();
                info.addProperty("cpuPhysicalCores", processor.getPhysicalProcessorCount());
                info.addProperty("cpuLogicalCores", processor.getLogicalProcessorCount());
                long freq = processor.getProcessorIdentifier().getVendorFreq();
                if (freq > 0) {
                    info.addProperty("cpuBaseClock", String.format("%.2f GHz", freq / 1_000_000_000.0));
                }
                info.addProperty("cpuIdentifier", processor.getProcessorIdentifier().getName());
            } catch (Throwable ignored) {
            }
        }
        // GPU: Use the device name from GPUMonitor (updated from OpenGL renderer)
        DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics = DeveloperOverlayManager.diagnosticsView();
        String gpu = diagnostics.snapshot().deviceName();
        if (gpu == null || gpu.isEmpty() || gpu.equals("Unknown GPU")) {
            gpu = "GPU (OpenGL renderer not yet available)";
        }
        info.addProperty("gpu", gpu);
        if (system != null) {
            try {
                JsonArray gpuList = new JsonArray();
                for (GraphicsCard card : system.getHardware().getGraphicsCards()) {
                    JsonObject gpuEntry = new JsonObject();
                    gpuEntry.addProperty("name", card.getName());
                    gpuEntry.addProperty("vendor", card.getVendor());
                    gpuEntry.addProperty("vramBytes", card.getVRam());
                    gpuEntry.addProperty("versionInfo", card.getVersionInfo());
                    gpuList.add(gpuEntry);
                }
                if (gpuList.size() > 0) {
                    info.add("gpuList", gpuList);
                }
            } catch (Throwable ignored) {
            }
        }
        double cpuTemperature = readCpuTemperature(system);
        if (!Double.isNaN(cpuTemperature) && cpuTemperature > 0.0) {
            info.addProperty("cpuTemperature", cpuTemperature);
        }
        double cpuUsage = readCpuLoad(system);
        if (!Double.isNaN(cpuUsage) && cpuUsage >= 0.0d) {
            info.addProperty("cpuUsage", cpuUsage);
        }
        double gpuTemperature = resolveGpuTemperature(system, diagnostics);
        if (!Double.isNaN(gpuTemperature) && gpuTemperature > 0.0d) {
            info.addProperty("gpuTemperature", gpuTemperature);
        }
        double gpuUsage = diagnostics.snapshot().gpuSystemUsageRatio();
        if (!Double.isNaN(gpuUsage) && gpuUsage >= 0.0d) {
            info.addProperty("gpuUsage", gpuUsage);
        }
        JsonArray storage = new JsonArray();
        List<JsonObject> drives = new ArrayList<>();
        try {
            for (java.io.File root : java.io.File.listRoots()) {
                JsonObject drive = new JsonObject();
                drive.addProperty("path", root.getAbsolutePath());
                drive.addProperty("total", formatBytes(root.getTotalSpace()));
                drive.addProperty("free", formatBytes(root.getFreeSpace()));
                drive.addProperty("type", " Drive");
                drives.add(drive);
            }
        } catch (Throwable t) {
        }
        drives.forEach(storage::add);
        info.add("storage", storage);
        if (system != null) {
            try {
                JsonArray physicalDisks = new JsonArray();
                for (HWDiskStore disk : system.getHardware().getDiskStores()) {
                    JsonObject diskJson = new JsonObject();
                    diskJson.addProperty("name", disk.getModel());
                    diskJson.addProperty("serial", disk.getSerial());
                    diskJson.addProperty("sizeBytes", disk.getSize());
                    diskJson.addProperty("reads", disk.getReads());
                    diskJson.addProperty("writes", disk.getWrites());
                    physicalDisks.add(diskJson);
                }
                if (physicalDisks.size() > 0) {
                    info.add("physicalDisks", physicalDisks);
                }
            } catch (Throwable ignored) {
            }
        }
        return info;
    }

    private static double readCpuTemperature(SystemInfo system) {
        double cached = LAST_CPU_SENSOR_VALUE.get();
        long lastQuery = LAST_CPU_SENSOR_QUERY.get();
        long now = System.currentTimeMillis();
        if (now - lastQuery < SENSOR_REFRESH_INTERVAL_MS) {
            return cached;
        }
        LAST_CPU_SENSOR_QUERY.set(now);
        if (system == null) {
            return cached;
        }
        boolean isWindows = normalizeOsFamily(system).contains("windows");
        if (isWindows) {
            CPU_TEMP_UNAVAILABLE.set(true);
            return cached;
        }
        try {
            Sensors sensors = system.getHardware().getSensors();
            if (sensors == null) {
                return cached;
            }
            double value = sensors.getCpuTemperature();
            if (!Double.isNaN(value) && value > 0.0d) {
                LAST_CPU_SENSOR_VALUE.set(value);
                return value;
            }
        } catch (Throwable ignored) {
        }
        return cached;
    }

    private static double readCpuLoad(SystemInfo system) {
        double cached = LAST_CPU_LOAD.get();
        long lastQuery = LAST_CPU_LOAD_QUERY.get();
        long now = System.currentTimeMillis();
        if (now - lastQuery < SENSOR_REFRESH_INTERVAL_MS) {
            return cached;
        }
        if (system == null) {
            return cached;
        }
        try {
            CentralProcessor processor = system.getHardware().getProcessor();
            long[] previous = LAST_CPU_TICKS.getAndSet(processor.getSystemCpuLoadTicks());
            LAST_CPU_LOAD_QUERY.set(now);
            if (previous == null) {
                return Double.NaN;
            }
            double load = processor.getSystemCpuLoadBetweenTicks(previous);
            LAST_CPU_LOAD.set(load);
            return load;
        } catch (Throwable ignored) {
        }
        return cached;
    }

    private static double resolveGpuTemperature(SystemInfo system,
                                                DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics) {
        GpuTelemetryService.TelemetrySample telemetrySample = GpuTelemetryService.getInstance().latestSample();
        if (telemetrySample != null && telemetrySample.temperatureC() > 0.0d) {
            return telemetrySample.temperatureC();
        }
        if (diagnostics != null) {
            double overlayTemp = diagnostics.snapshot().gpuTemperature();
            if (overlayTemp > 0.0d) {
                return overlayTemp;
            }
        }
        if (isLinux(system)) {
            double hwmonTemp = LinuxHwmonSensors.readGpuTemperature();
            if (!Double.isNaN(hwmonTemp) && hwmonTemp > 0.0d) {
                return hwmonTemp;
            }
        }
        return Double.NaN;
    }

    private static boolean isLinux(SystemInfo system) {
        return normalizeOsFamily(system).contains("linux");
    }

    private static String normalizeOsFamily(SystemInfo system) {
        if (system != null) {
            try {
                OperatingSystem os = system.getOperatingSystem();
                if (os != null && os.getFamily() != null) {
                    return os.getFamily().toLowerCase(Locale.ROOT);
                }
            } catch (Throwable ignored) {
            }
        }
        return safeTrim(System.getProperty("os.name", "")).toLowerCase(Locale.ROOT);
    }

    private static SystemInfo getSystemInfoInstance() {
        SystemInfo cached = SYSTEM_INFO.get();
        if (cached != null) {
            return cached;
        }
        try {
            SystemInfo created = new SystemInfo();
            return SYSTEM_INFO.compareAndSet(null, created) ? created : SYSTEM_INFO.get();
        } catch (Throwable t) {
            LOGGER.log(Level.FINEST, "Unable to initialize OSHI SystemInfo", t);
            return null;
        }
    }

    private static String describeOperatingSystem(SystemInfo system) {
        OperatingSystem os = null;
        if (system != null) {
            try {
                os = system.getOperatingSystem();
            } catch (Throwable t) {
                LOGGER.log(Level.FINEST, "Failed to query operating system via OSHI", t);
            }
        }
        if (os == null) {
            return fallbackOsDescription();
        }
        String family = safeTrim(os.getFamily());
        String details = describeVersionDetails(family, os.getVersionInfo());
        StringBuilder label = new StringBuilder();
        if (!family.isEmpty()) {
            label.append(family);
        }
        if (!details.isEmpty()) {
            if (label.length() > 0) {
                label.append(" ");
            }
            label.append(details);
        }
        int bitness = os.getBitness();
        if (bitness > 0) {
            if (label.length() > 0) {
                label.append(" ");
            }
            label.append("(").append(bitness).append("-bit").append(")");
        }
        return label.length() == 0 ? fallbackOsDescription() : label.toString();
    }

    private static String describeVersionDetails(String family, OperatingSystem.OSVersionInfo version) {
        if (version == null) {
            return "";
        }
        String lowerFamily = family == null ? "" : family.toLowerCase(Locale.ROOT);
        if (lowerFamily.contains("windows")) {
            String windows = describeWindowsVersion(version);
            if (!windows.isEmpty()) {
                return windows;
            }
        }
        StringBuilder sb = new StringBuilder();
        String ver = safeTrim(version.getVersion());
        String code = safeTrim(version.getCodeName());
        String build = safeTrim(version.getBuildNumber());
        if (!ver.isEmpty()) {
            sb.append(ver);
        }
        if (!code.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            if (lowerFamily.contains("mac")) {
                sb.append("(").append(code).append(")");
            } else {
                sb.append(code);
            }
        }
        if (!build.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append("Build ").append(build);
        }
        return sb.toString().trim();
    }

    private static String describeWindowsVersion(OperatingSystem.OSVersionInfo version) {
        String build = safeTrim(version.getBuildNumber());
        int buildNumber = parseIntSafe(build);
        if (buildNumber >= 22000) {
            return "11 (Build " + buildNumber + ")";
        }
        if (buildNumber >= 10240) {
            return "10 (Build " + (buildNumber > 0 ? buildNumber : build) + ")";
        }
        String ver = safeTrim(version.getVersion());
        if (!ver.isEmpty()) {
            return ver + (build.isEmpty() ? "" : " (Build " + build + ")");
        }
        String code = safeTrim(version.getCodeName());
        if (!code.isEmpty()) {
            return code + (build.isEmpty() ? "" : " (Build " + build + ")");
        }
        return build.isEmpty() ? "" : "Build " + build;
    }

    private static int parseIntSafe(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String fallbackOsDescription() {
        String name = safeTrim(System.getProperty("os.name", "Unknown OS"));
        String version = safeTrim(System.getProperty("os.version", ""));
        String arch = safeTrim(System.getProperty("os.arch", ""));
        StringBuilder sb = new StringBuilder(name.isEmpty() ? "Unknown OS" : name);
        if (!version.isEmpty()) {
            sb.append(" ").append(version);
        }
        if (!arch.isEmpty()) {
            sb.append(" (").append(arch).append(")");
        }
        return sb.toString();
    }

    private static final class LinuxHwmonSensors {
        private static final Path HWMON_ROOT = Paths.get("/sys/class/hwmon");
        private static final String[] GPU_HINTS = {"amdgpu", "nvidia", "radeon", "gpu"};

        private LinuxHwmonSensors() {
        }

        static double readGpuTemperature() {
            try {
                if (!Files.isDirectory(HWMON_ROOT)) {
                    return Double.NaN;
                }
                try (DirectoryStream<Path> dirs = Files.newDirectoryStream(HWMON_ROOT, "hwmon*")) {
                    for (Path dir : dirs) {
                        String name = readFirstLine(dir.resolve("name"));
                        if (!isGpuDevice(name)) {
                            continue;
                        }
                        double value = readFirstTemperature(dir);
                        if (!Double.isNaN(value) && value > 0.0d) {
                            return value;
                        }
                    }
                }
            } catch (IOException | SecurityException ignored) {
            }
            return Double.NaN;
        }

        private static boolean isGpuDevice(String name) {
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (String hint : GPU_HINTS) {
                if (lower.contains(hint)) {
                    return true;
                }
            }
            return false;
        }

        private static double readFirstTemperature(Path dir) throws IOException {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "temp*_input")) {
                for (Path file : files) {
                    double parsed = parseTemperature(readFirstLine(file));
                    if (!Double.isNaN(parsed)) {
                        return parsed;
                    }
                }
            }
            return Double.NaN;
        }

        private static double parseTemperature(String raw) {
            if (raw == null || raw.isBlank()) {
                return Double.NaN;
            }
            try {
                double value = Double.parseDouble(raw.trim());
                return value > 1000d ? value / 1000d : value;
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }

        private static String readFirstLine(Path file) {
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                return reader.readLine();
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private static void handleAuthCheck(HttpExchange exchange) throws IOException {

        if (!isDashboardConfigured()) {
            sendRedirect(exchange, "/setup");
            return;
        }
        if (isAuthenticated(exchange)) {
            handleIndex(exchange);
        } else {
            sendRedirect(exchange, "/login");
        }
    }

    private static void handleAuthRequired(HttpExchange exchange) throws IOException {
        if (!isAuthenticated(exchange)) {
            sendError(exchange, 401, "Authentication required");
            return;
        }
        dispatchDashboardRequest(exchange);
    }

    private static void dispatchDashboardRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/api/v1/dashboard/state" -> handleDashboardState(exchange);
            case "/api/v1/dashboard/timeline" -> handleTimeline(exchange);
            case "/api/v1/dashboard/replay" -> handleReplay(exchange);
            case "/api/v1/dashboard/toggles" -> handleToggleUpdate(exchange);
            case "/api/v1/dashboard/history" -> handleHistoryExport(exchange);
            case "/api/v1/dashboard/export" -> handleDiagnosticsExport(exchange);
            case "/api/v1/stress/clear-cache" -> handleStressClear(exchange);
            case "/api/v1/stress/profile" -> handleStressProfile(exchange);
            case "/api/v1/stress/run" -> handleStressRun(exchange);
            case "/api/v1/resources" -> handleResourceOverview(exchange);
            case "/api/v1/resources/flush" -> handleResourceFlush(exchange);
            case "/api/v1/resources/disk" -> handleDiskManager(exchange);
            case "/api/v1/mod-metrics" -> handleModMetrics(exchange);
            case "/api/v1/config" -> handleConfigEndpoint(exchange);
            case "/api/v1/mods" -> handleModRequest(exchange);
            default -> {
                if (path != null && path.startsWith("/api/v1/mods/")) {
                    handleModRequest(exchange);
                } else {
                    sendError(exchange, 404, "Not found");
                }
            }
        }
    }

    private static void handleLoginPage(HttpExchange exchange) throws IOException {
        if (!isDashboardConfigured()) {
            sendRedirect(exchange, "/setup");
            return;
        }
        if (isAuthenticated(exchange)) {
            sendRedirect(exchange, "/");
            return;
        }

        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Quantified API - Dashboard Login</title>
                <link rel="stylesheet" href="/dashboard.css">
                <link rel="icon" type="image/png" href="/dashboard-logo.png">
                <script>
                    (function() {
                        try {
                            const saved = localStorage.getItem('quantifiedThemeMode') || localStorage.getItem('quantifiedThemeOverride') || localStorage.getItem('quantifiedTheme');
                            const theme = saved === 'dark' || saved === 'light'
                                ? saved
                                : (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
                            document.documentElement.setAttribute('data-theme', theme);
                        } catch (ignored) {
                        }
                    })();
                </script>
                <style>
                    body { display:flex; align-items:center; justify-content:center; min-height:100vh; }
                    * {
                        box-sizing: border-box;
                    }

                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                        margin: 0;
                        padding: 0;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        background: linear-gradient(135deg, #3b1d6e 0%, #45237a 24%, #5f2f91 52%, #783aa7 76%, #9145be 100%);
                        background-size: 400% 400%;
                        animation: gradientShift 15s ease infinite;
                        color: #f7f6fb;
                        overflow-x: hidden;
                        position: relative;
                        /* Prevent FOUC - ensure dark background immediately */
                        background-color: #3b1d6e;
                    }

                    body::before {
                        content: '';
                        position: fixed;
                        top: 0;
                        left: 0;
                        right: 0;
                        bottom: 0;
                        background:
                            radial-gradient(circle at 20% 80%, rgba(139, 92, 246, 0.15) 0%, transparent 50%),
                            radial-gradient(circle at 80% 20%, rgba(168, 85, 247, 0.15) 0%, transparent 50%),
                            radial-gradient(circle at 40% 40%, rgba(192, 132, 252, 0.15) 0%, transparent 50%);
                        animation: float 20s ease-in-out infinite;
                        pointer-events: none;
                    }

                    @keyframes float {
                        0%, 100% { transform: translateY(0px) rotate(0deg); }
                        33% { transform: translateY(-20px) rotate(120deg); }
                        66% { transform: translateY(10px) rotate(240deg); }
                    }

                    .login-container {
                        width: min(720px, 92vw);
                        max-width: 720px;
                        margin: 40px auto;
                        padding: clamp(20px, 3vw, 44px);
                        background: rgba(18, 8, 36, 0.5);
                        border-radius: 16px;
                        box-shadow: 0 22px 60px rgba(7,5,20,0.6), 0 8px 24px rgba(7,5,20,0.3);
                        backdrop-filter: blur(14px) saturate(110%);
                        border: 1px solid rgba(255,255,255,0.06);
                        position: relative;
                        overflow: hidden;
                        animation: slideIn 0.8s cubic-bezier(0.16, 1, 0.3, 1);
                        transform: translateY(0);
                    }

                    .login-container::before {
                        content: '';
                        position: absolute;
                        top: -20%;
                        left: -30%;
                        width: 160%;
                        height: 140%;
                        transform: rotate(18deg);
                        background: linear-gradient(115deg, rgba(255,255,255,0.04) 0%, rgba(255,255,255,0.12) 36%, rgba(255,255,255,0.02) 100%);
                        filter: blur(28px);
                        opacity: 0.9;
                        animation: swoosh 6s ease-in-out infinite;
                    }

                    @keyframes slideIn {
                        from {
                            opacity: 0;
                            transform: translateY(30px) scale(0.95);
                        }
                        to {
                            opacity: 1;
                            transform: translateY(0) scale(1);
                        }
                    }

                    @keyframes swoosh {
                        0% { transform: translateX(-12%) rotate(14deg); opacity: 0.75 }
                        50% { transform: translateX(6%) rotate(16deg); opacity: 0.95 }
                        100% { transform: translateX(-12%) rotate(14deg); opacity: 0.75 }
                    }

                    .login-header {
                        text-align: center;
                        margin-bottom: 35px;
                        position: relative;
                    }

                    .login-header img {
                        width: 72px;
                        height: 72px;
                        border-radius: 20px;
                        overflow: hidden;
                        display: inline-block;
                        margin-bottom: 20px;
                        box-shadow: 0 20px 40px rgba(17, 8, 35, 0.35);
                        backdrop-filter: blur(14px);
                        opacity: 0;
                        animation: fadeInUp 0.6s 0.2s ease-out forwards;
                    }

                    .login-header h1 {
                        color: #f7f6fb;
                        margin-bottom: 8px;
                        font-size: 28px;
                        font-weight: 700;
                        letter-spacing: -0.5px;
                        background: linear-gradient(135deg, #8B5CF6, #A855F7);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        background-clip: text;
                        animation: textGlow 2s ease-in-out infinite alternate;
                    }

                    @keyframes textGlow {
                        from { filter: brightness(1); }
                        to { filter: brightness(1.1); }
                    }

                    .login-header p {
                        color: #6b7280;
                        font-size: 16px;
                        font-weight: 400;
                        margin: 0;
                        opacity: 0;
                        animation: fadeInUp 0.6s 0.3s ease-out forwards;
                    }

                    @keyframes fadeInUp {
                        from {
                            opacity: 0;
                            transform: translateY(10px);
                        }
                        to {
                            opacity: 1;
                            transform: translateY(0);
                        }
                    }

                    .login-form {
                        display: flex;
                        flex-direction: column;
                        gap: 24px;
                    }

                    .form-group {
                        display: flex;
                        flex-direction: column;
                        position: relative;
                    }

                    .form-group label {
                        font-weight: 600;
                        margin-bottom: 8px;
                        color: #f7f6fb;
                        font-size: 14px;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                        opacity: 0;
                        animation: fadeInUp 0.6s 0.4s ease-out forwards;
                    }

                    .form-group input {
                        padding: 16px 20px;
                        border: 2px solid rgba(255, 255, 255, 0.08);
                        border-radius: 12px;
                        font-size: 16px;
                        font-weight: 400;
                        transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
                        background: rgba(17, 8, 35, 0.4);
                        backdrop-filter: blur(10px);
                        color: #f7f6fb;
                        position: relative;
                        opacity: 0;
                        animation: fadeInUp 0.6s 0.5s ease-out forwards;
                    }

                    .form-group input:hover {
                        border-color: rgba(255, 255, 255, 0.15);
                        transform: translateY(-1px);
                        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
                    }

                    .form-group input:focus {
                        outline: none;
                        border-color: #8B5CF6;
                        box-shadow:
                            0 0 0 3px rgba(139, 92, 246, 0.1),
                            0 8px 25px rgba(139, 92, 246, 0.15);
                        transform: translateY(-2px);
                        background: rgba(17, 8, 35, 0.6);
                    }

                    .form-group input::placeholder {
                        color: #9ca3af;
                        transition: color 0.2s;
                    }

                    .form-group input:focus::placeholder {
                        color: #d1d5db;
                    }

                    .login-btn {
                        background: linear-gradient(135deg, #8B5CF6 0%, #A855F7 100%);
                        color: white;
                        border: none;
                        padding: 16px 24px;
                        border-radius: 12px;
                        font-size: 16px;
                        font-weight: 600;
                        cursor: pointer;
                        transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
                        position: relative;
                        overflow: hidden;
                        margin-top: 8px;
                        opacity: 0;
                        animation: fadeInUp 0.6s 0.6s ease-out forwards;
                        box-shadow: 0 4px 15px rgba(139, 92, 246, 0.3);
                    }

                    .login-btn::before {
                        content: '';
                        position: absolute;
                        top: 0;
                        left: -100%;
                        width: 100%;
                        height: 100%;
                        background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.2), transparent);
                        transition: left 0.5s;
                    }

                    .login-btn:hover {
                        transform: translateY(-2px);
                        box-shadow: 0 8px 25px rgba(139, 92, 246, 0.4);
                    }

                    .login-btn:hover::before {
                        left: 100%;
                    }

                    .login-btn:active {
                        transform: translateY(0);
                        box-shadow: 0 2px 10px rgba(139, 92, 246, 0.3);
                    }

                    .login-btn:disabled {
                        opacity: 0.7;
                        cursor: not-allowed;
                        transform: none;
                        box-shadow: 0 2px 8px rgba(139, 92, 246, 0.2);
                    }

                    .login-btn:disabled::before {
                        display: none;
                    }

                    .login-btn.loading {
                        pointer-events: none;
                    }

                    .login-btn.loading::after {
                        content: '';
                        position: absolute;
                        width: 20px;
                        height: 20px;
                        margin: auto;
                        border: 2px solid transparent;
                        border-top-color: #ffffff;
                        border-radius: 50%;
                        animation: spin 1s linear infinite;
                    }

                    @keyframes spin {
                        0% { transform: rotate(0deg); }
                        100% { transform: rotate(360deg); }
                    }

                    .error-message {
                        color: #dc2626;
                        background: linear-gradient(135deg, #fef2f2, #fee2e2);
                        border: 1px solid #fecaca;
                        padding: 16px 20px;
                        border-radius: 12px;
                        margin-bottom: 24px;
                        font-size: 14px;
                        font-weight: 500;
                        animation: shake 0.5s ease-in-out;
                        box-shadow: 0 4px 12px rgba(220, 38, 38, 0.1);
                        opacity: 0;
                        animation: fadeInUp 0.6s 0.7s ease-out forwards, shake 0.5s 0.7s ease-in-out;
                    }

                    @keyframes shake {
                        0%, 100% { transform: translateX(0); }
                        25% { transform: translateX(-5px); }
                        75% { transform: translateX(5px); }
                    }

                    .setup-link {
                        text-align: center;
                        margin-top: 24px;
                        color: #9ca3af;
                        font-size: 14px;
                        opacity: 0;
                        animation: fadeInUp 0.6s 0.8s ease-out forwards;
                    }

                    .setup-link a {
                        color: #8B5CF6;
                        text-decoration: none;
                        font-weight: 500;
                        transition: all 0.2s;
                        position: relative;
                    }

                    .setup-link a::after {
                        content: '';
                        position: absolute;
                        bottom: -2px;
                        left: 0;
                        width: 0;
                        height: 2px;
                        background: linear-gradient(135deg, #8B5CF6, #A855F7);
                        transition: width 0.3s;
                    }

                    .setup-link a:hover::after {
                        width: 100%;
                    }

                    .setup-link a:hover {
                        color: #A855F7;
                        transform: translateY(-1px);
                    }

                    body {
                        align-items: stretch;
                        justify-content: flex-start;
                        background:
                            radial-gradient(circle at top left, rgba(95, 111, 138, 0.14), transparent 34rem),
                            linear-gradient(135deg, var(--bg), var(--surface-strong));
                        background-color: var(--bg);
                        background-size: auto;
                        animation: none;
                        color: var(--text-primary);
                        padding: clamp(14px, 2.4vw, 26px);
                        position: relative;
                        isolation: isolate;
                    }

                    body::before {
                        display: none;
                    }

                    .dashboard-bg-stage {
                        position: fixed;
                        inset: 0;
                        z-index: 0;
                        overflow: hidden;
                        pointer-events: none;
                        background:
                            radial-gradient(circle at 17% 14%, rgba(198, 94, 69, 0.12), transparent 30rem),
                            radial-gradient(circle at 82% 10%, rgba(47, 118, 96, 0.12), transparent 34rem),
                            linear-gradient(140deg, var(--bg) 0%, var(--surface-strong) 55%, var(--bg) 100%);
                    }

                    .dashboard-bg-grid,
                    .dashboard-bg-grid::before,
                    .dashboard-bg-glow,
                    .dashboard-bg-sweep,
                    .dashboard-bg-aurora,
                    .dashboard-bg-noise {
                        position: absolute;
                        inset: 0;
                    }

                    .dashboard-bg-grid {
                        opacity: 0.45;
                        background-image:
                            linear-gradient(rgba(95, 111, 138, 0.16) 1px, transparent 1px),
                            linear-gradient(90deg, rgba(95, 111, 138, 0.14) 1px, transparent 1px);
                        background-size: 54px 54px;
                        mask-image: radial-gradient(circle at center, black 0%, transparent 72%);
                        animation: dashboard-grid-drift 26s linear infinite;
                    }

                    .dashboard-bg-grid::before {
                        content: "";
                        opacity: 0.42;
                        background:
                            linear-gradient(115deg, transparent 0%, rgba(198, 94, 69, 0.18) 38%, transparent 58%),
                            linear-gradient(245deg, transparent 4%, rgba(47, 118, 96, 0.14) 42%, transparent 64%);
                        filter: blur(14px);
                        transform: translateX(-18%);
                        animation: dashboard-grid-wind 12s ease-in-out infinite alternate;
                    }

                    .dashboard-bg-glow {
                        width: 32rem;
                        height: 32rem;
                        border-radius: 999px;
                        inset: auto;
                        filter: blur(28px);
                        opacity: 0.22;
                        mix-blend-mode: multiply;
                    }

                    .dashboard-bg-glow-a {
                        left: -8rem;
                        top: 10%;
                        background: rgba(198, 94, 69, 0.5);
                        animation: dashboard-orb-a 20s ease-in-out infinite alternate;
                    }

                    .dashboard-bg-glow-b {
                        right: -9rem;
                        bottom: 6%;
                        background: rgba(47, 118, 96, 0.45);
                        animation: dashboard-orb-b 24s ease-in-out infinite alternate;
                    }

                    .dashboard-bg-sweep {
                        opacity: 0.32;
                        background: linear-gradient(110deg, transparent 8%, rgba(255, 255, 255, 0.34) 46%, transparent 70%);
                        transform: translateX(-78%) skewX(-12deg);
                        animation: dashboard-bg-sweep 15s ease-in-out infinite;
                    }

                    .dashboard-bg-aurora {
                        opacity: 0.18;
                        background:
                            conic-gradient(from 180deg at 54% 44%, transparent, rgba(198, 94, 69, 0.22), transparent, rgba(47, 118, 96, 0.2), transparent);
                        filter: blur(48px);
                        animation: dashboard-aurora-shift 18s ease-in-out infinite alternate;
                    }

                    .dashboard-bg-noise {
                        opacity: 0.035;
                        background-image:
                            radial-gradient(circle at 20% 20%, #111 0 1px, transparent 1px),
                            radial-gradient(circle at 80% 30%, #111 0 1px, transparent 1px),
                            radial-gradient(circle at 40% 70%, #111 0 1px, transparent 1px);
                        background-size: 180px 180px;
                    }

                    :root[data-theme="dark"] .dashboard-bg-stage {
                        background:
                            radial-gradient(circle at 17% 14%, rgba(217, 111, 82, 0.22), transparent 31rem),
                            radial-gradient(circle at 82% 10%, rgba(69, 153, 126, 0.18), transparent 34rem),
                            linear-gradient(140deg, var(--bg) 0%, var(--surface-strong) 55%, var(--bg) 100%);
                    }

                    :root[data-theme="dark"] .dashboard-bg-grid {
                        opacity: 0.62;
                        background-image:
                            linear-gradient(rgba(224, 229, 238, 0.12) 1px, transparent 1px),
                            linear-gradient(90deg, rgba(224, 229, 238, 0.1) 1px, transparent 1px);
                    }

                    :root[data-theme="dark"] .dashboard-bg-glow {
                        opacity: 0.32;
                        mix-blend-mode: screen;
                    }

                    :root[data-theme="dark"] .dashboard-bg-sweep {
                        opacity: 0.22;
                    }

                    :root[data-theme="dark"] .dashboard-bg-noise {
                        opacity: 0.055;
                        filter: invert(1);
                    }

                    .login-shell {
                        width: min(1120px, 100%);
                        min-height: calc(100vh - clamp(28px, 4.8vw, 52px));
                        margin: 0 auto;
                        display: grid;
                        grid-template-columns: minmax(0, 1fr);
                        align-items: stretch;
                        position: relative;
                        z-index: 1;
                    }

                    .login-rail,
                    .login-overview,
                    .login-card {
                        background: var(--surface);
                        border: 1px solid var(--border-soft);
                        border-radius: var(--radius-card);
                        box-shadow: var(--shadow-soft-1);
                    }

                    .login-rail-inner {
                        position: sticky;
                        top: 24px;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        gap: var(--space-6);
                        padding: var(--space-5) var(--space-3);
                    }

                    .login-brand,
                    .login-header img {
                        background: var(--surface-contrast);
                        display: grid;
                        place-items: center;
                    }

                    .login-brand {
                        width: 36px;
                        height: 36px;
                        border-radius: 12px;
                        padding: 6px;
                    }

                    .login-brand img {
                        width: 100%;
                        height: 100%;
                        object-fit: contain;
                    }

                    .rail-dot {
                        width: 44px;
                        height: 44px;
                        border-radius: 14px;
                        display: grid;
                        place-items: center;
                        background: var(--surface-strong);
                        border: 1px solid var(--border-strong);
                        color: var(--text-primary);
                        font-size: 0.78rem;
                        font-weight: 700;
                    }

                    .login-main {
                        min-width: 0;
                        display: grid;
                        grid-template-columns: minmax(0, 1.15fr) minmax(360px, 430px);
                        gap: var(--space-6);
                        align-items: center;
                    }

                    .login-overview {
                        min-height: 540px;
                        padding: clamp(26px, 5vw, 56px);
                        display: flex;
                        flex-direction: column;
                        justify-content: center;
                        gap: var(--space-5);
                        position: relative;
                        overflow: hidden;
                    }

                    .login-overview::after {
                        content: "";
                        position: absolute;
                        inset: auto -18% -26% auto;
                        width: 340px;
                        height: 340px;
                        border-radius: 50%;
                        background: radial-gradient(circle, rgba(111, 126, 104, 0.16), transparent 68%);
                        pointer-events: none;
                    }

                    .login-eyebrow {
                        display: inline-flex;
                        width: fit-content;
                        align-items: center;
                        gap: 8px;
                        border-radius: var(--radius-pill);
                        border: 1px solid var(--border-soft);
                        background: var(--surface-strong);
                        color: var(--text-secondary);
                        padding: 8px 12px;
                        font-size: 0.78rem;
                        font-weight: 700;
                        text-transform: uppercase;
                        letter-spacing: 0.07em;
                    }

                    .status-light {
                        width: 8px;
                        height: 8px;
                        border-radius: 50%;
                        background: var(--success);
                        box-shadow: 0 0 0 4px rgba(44, 143, 93, 0.12);
                    }

                    .login-copy {
                        max-width: 620px;
                        display: flex;
                        flex-direction: column;
                        gap: var(--space-5);
                    }

                    .login-copy h1 {
                        margin: 0;
                        color: var(--text-primary);
                        font-size: clamp(2.1rem, 5vw, 4.6rem);
                        font-weight: 780;
                        line-height: 0.94;
                        text-wrap: balance;
                    }

                    .login-copy p {
                        max-width: 58ch;
                        margin: 0;
                        color: var(--text-primary);
                        font-size: clamp(1rem, 1.7vw, 1.2rem);
                        line-height: 1.65;
                    }

                    .login-card {
                        padding: clamp(22px, 3vw, 32px);
                        align-self: center;
                    }

                    .login-header {
                        display: flex;
                        align-items: center;
                        gap: var(--space-4);
                        margin-bottom: var(--space-7);
                        text-align: left;
                    }

                    .login-header img {
                        width: 48px;
                        height: 48px;
                        border-radius: 14px;
                        padding: 8px;
                        object-fit: contain;
                        margin: 0;
                        opacity: 1;
                        animation: none;
                        box-shadow: none;
                    }

                    .login-header h1 {
                        margin: 0;
                        color: var(--text-primary);
                        background: none;
                        -webkit-text-fill-color: currentColor;
                        font-size: 1.15rem;
                        font-weight: 760;
                        letter-spacing: 0;
                        animation: none;
                    }

                    .login-header p {
                        margin: 3px 0 0;
                        color: var(--text-muted);
                        font-size: 0.86rem;
                        opacity: 1;
                        animation: none;
                    }

                    .login-form {
                        gap: var(--space-5);
                    }

                    .form-group {
                        gap: 8px;
                    }

                    .form-group label {
                        color: var(--text-secondary);
                        font-size: 0.78rem;
                        font-weight: 720;
                        letter-spacing: 0.07em;
                        margin: 0;
                        opacity: 1;
                        animation: none;
                    }

                    .form-group input {
                        background: var(--surface);
                        color: var(--text-primary);
                        border: 1px solid var(--border-soft);
                        padding: 12px 14px;
                        font-size: 0.94rem;
                        opacity: 1;
                        animation: none;
                        transform: none;
                    }

                    .form-group input:hover {
                        border-color: var(--border-strong);
                        transform: none;
                        box-shadow: none;
                    }

                    .form-group input:focus {
                        outline: 2px solid rgba(95, 111, 138, 0.2);
                        border-color: var(--accent-1);
                        background: var(--surface);
                        box-shadow: none;
                        transform: none;
                    }

                    .login-btn {
                        width: 100%;
                        min-height: 46px;
                        border: 1px solid var(--surface-contrast);
                        background: var(--surface-contrast);
                        color: #ffffff;
                        padding: 12px 16px;
                        font-size: 0.94rem;
                        font-weight: 740;
                        box-shadow: var(--shadow-soft-2);
                        opacity: 1;
                        animation: none;
                    }

                    .login-btn:hover {
                        transform: translateY(-1px);
                        box-shadow: var(--shadow-soft-3);
                    }

                    .login-btn.loading::after {
                        inset: 0;
                        border-top-color: currentColor;
                    }

                    .error-message {
                        color: var(--danger);
                        background: color-mix(in srgb, var(--danger) 10%, var(--surface));
                        border: 1px solid color-mix(in srgb, var(--danger) 28%, var(--border-soft));
                        padding: 12px 14px;
                        margin-bottom: var(--space-5);
                        font-size: 0.88rem;
                        font-weight: 650;
                        box-shadow: none;
                        animation: none;
                        opacity: 1;
                    }

                    .setup-link {
                        margin-top: var(--space-5);
                        color: var(--text-muted);
                        font-size: 0.85rem;
                        opacity: 1;
                        animation: none;
                    }

                    .setup-link a {
                        color: var(--text-primary);
                        font-weight: 680;
                    }

                    .setup-link a:hover {
                        color: var(--text-primary);
                        text-decoration: underline;
                        transform: none;
                    }

                    @media (max-width: 860px) {
                        .login-shell {
                            grid-template-columns: 1fr;
                            min-height: auto;
                        }

                        .login-main {
                            grid-template-columns: 1fr;
                        }

                        .login-overview {
                            min-height: auto;
                        }
                    }

                    @media (max-width: 560px) {
                        body {
                            padding: 12px;
                        }

                        .login-main {
                            gap: var(--space-4);
                        }

                        .login-card {
                            padding: 20px;
                        }
                    }

                    @media (prefers-reduced-motion: reduce) {
                        *, *::before, *::after {
                            animation-duration: 0.01ms !important;
                            animation-iteration-count: 1 !important;
                            transition-duration: 0.01ms !important;
                        }
                    }

                    @keyframes dashboard-grid-drift {
                        from { background-position: 0 0, 0 0; }
                        to { background-position: 54px 54px, 54px 54px; }
                    }

                    @keyframes dashboard-grid-wind {
                        0% { transform: translateX(-18%) translateY(-3%) skewX(-8deg); }
                        100% { transform: translateX(16%) translateY(5%) skewX(8deg); }
                    }

                    @keyframes dashboard-orb-a {
                        0% { transform: translate3d(0, 0, 0) scale(1); }
                        100% { transform: translate3d(18vw, 7vh, 0) scale(1.18); }
                    }

                    @keyframes dashboard-orb-b {
                        0% { transform: translate3d(0, 0, 0) scale(1); }
                        100% { transform: translate3d(-14vw, -10vh, 0) scale(1.12); }
                    }

                    @keyframes dashboard-bg-sweep {
                        0%, 35% { transform: translateX(-82%) skewX(-12deg); }
                        70%, 100% { transform: translateX(82%) skewX(-12deg); }
                    }

                    @keyframes dashboard-aurora-shift {
                        0% { transform: translateY(-3%) rotate(0deg) scale(1); }
                        100% { transform: translateY(5%) rotate(10deg) scale(1.08); }
                    }
                </style>
            </head>
            <body>
                <div class="dashboard-bg-stage" aria-hidden="true">
                    <div class="dashboard-bg-grid"></div>
                    <div class="dashboard-bg-glow dashboard-bg-glow-a"></div>
                    <div class="dashboard-bg-glow dashboard-bg-glow-b"></div>
                    <div class="dashboard-bg-sweep"></div>
                    <div class="dashboard-bg-aurora"></div>
                    <div class="dashboard-bg-noise"></div>
                </div>
                <div class="theme-toggle" aria-label="Theme">
                    <button class="theme-toggle__btn" type="button" data-theme-choice="light" aria-label="Use light theme">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"></circle><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="m4.93 4.93 1.41 1.41"></path><path d="m17.66 17.66 1.41 1.41"></path><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="m6.34 17.66-1.41 1.41"></path><path d="m19.07 4.93-1.41 1.41"></path></svg>
                    </button>
                    <button class="theme-toggle__btn" type="button" data-theme-choice="dark" aria-label="Use dark theme">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5 8.5 8.5 0 1 0 20.5 14.5Z"></path></svg>
                    </button>
                    <button class="theme-toggle__btn" type="button" data-theme-choice="system" aria-label="Use system theme">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="12" rx="2"></rect><path d="M8 20h8"></path><path d="M12 16v4"></path></svg>
                    </button>
                </div>
                <main class="login-shell">
                    <section class="login-main">
                        <div class="login-overview">
                            <div class="login-copy">
                                <h1>Quantified API webpanel</h1>
                                <p>Next-gen independent powerhouse built to run your code with maximum performance and scalability, giving you full source transparency, designed with love at the Anti-Corpo BlackRift Studios :]</p>
                            </div>
                        </div>
                        <div class="login-card">
                            <div class="login-header">
                                <img src="/dashboard-logo.png" alt="Quantified API logo">
                                <div>
                                    <h1>Sign in</h1>
                                </div>
                            </div>
                            <div id="error-message" class="error-message" style="display: none;"></div>
                            <form class="login-form" id="login-form">
                                <div class="form-group">
                                    <label for="username">Username</label>
                                    <input type="text" id="username" name="username" placeholder="Dashboard username" autocomplete="username" required>
                                </div>
                                <div class="form-group">
                                    <label for="password">Password</label>
                                    <input type="password" id="password" name="password" placeholder="Dashboard password" autocomplete="current-password" required>
                                </div>
                                <button type="submit" class="login-btn" id="login-btn">
                                    <span id="btn-text">Log In</span>
                                </button>
                            </form>
                            <div class="setup-link">
                                Project source: <a href="https://github.com/Admany/Quantified-API/" target="_blank" rel="noopener noreferrer">GitHub</a>
                            </div>
                        </div>
                    </section>
                </main>
                <script>
                    const loginForm = document.getElementById('login-form');
                    const loginButton = document.getElementById('login-btn');
                    const buttonText = document.getElementById('btn-text');
                    const errorMessage = document.getElementById('error-message');
                    const media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
                    const themeButtons = Array.from(document.querySelectorAll('[data-theme-choice]'));

                    function effectiveTheme(mode) {
                        if (mode === 'light' || mode === 'dark') return mode;
                        return media && media.matches ? 'dark' : 'light';
                    }

                    function currentThemeMode() {
                        try {
                            const saved = localStorage.getItem('quantifiedThemeMode') || localStorage.getItem('quantifiedThemeOverride') || localStorage.getItem('quantifiedTheme');
                            return saved === 'light' || saved === 'dark' ? saved : 'system';
                        } catch (ignored) {
                            return 'system';
                        }
                    }

                    function applyThemeMode(mode) {
                        const nextMode = mode === 'light' || mode === 'dark' ? mode : 'system';
                        document.documentElement.setAttribute('data-theme', effectiveTheme(nextMode));
                        themeButtons.forEach(button => button.classList.toggle('active', button.dataset.themeChoice === nextMode));
                    }

                    themeButtons.forEach(button => {
                        button.addEventListener('click', () => {
                            const mode = button.dataset.themeChoice;
                            try {
                                if (mode === 'system') {
                                    localStorage.removeItem('quantifiedThemeMode');
                                    localStorage.removeItem('quantifiedThemeOverride');
                                    localStorage.removeItem('quantifiedTheme');
                                } else {
                                    localStorage.setItem('quantifiedThemeMode', mode);
                                    localStorage.setItem('quantifiedThemeOverride', mode);
                                    localStorage.setItem('quantifiedTheme', mode);
                                }
                            } catch (ignored) {
                            }
                            applyThemeMode(mode);
                        });
                    });

                    if (media) {
                        const refreshSystemTheme = () => {
                            if (currentThemeMode() === 'system') applyThemeMode('system');
                        };
                        if (media.addEventListener) media.addEventListener('change', refreshSystemTheme);
                        else if (media.addListener) media.addListener(refreshSystemTheme);
                    }

                    applyThemeMode(currentThemeMode());

                    loginForm.addEventListener('submit', async (e) => {
                        e.preventDefault();
                        loginButton.classList.add('loading');
                        loginButton.disabled = true;
                        buttonText.textContent = 'Checking credentials';
                        errorMessage.style.display = 'none';
                        let loginSucceeded = false;

                        try {
                            const formData = new FormData(e.target);
                            const response = await fetch('/api/auth/login', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                },
                                body: JSON.stringify({
                                    username: formData.get('username'),
                                    password: formData.get('password')
                                })
                            });

                            if (response.ok) {
                                loginSucceeded = true;
                                buttonText.textContent = 'Opening';
                                window.location.replace('/');
                                return;
                            }

                            const data = await response.json().catch(() => ({}));
                            errorMessage.textContent = (data && data.error) ? data.error : 'Invalid credentials';
                            errorMessage.style.display = 'block';
                        } catch (error) {
                            errorMessage.textContent = 'Connection failed. Please try again.';
                            errorMessage.style.display = 'block';
                        } finally {
                            if (loginSucceeded) {
                                return;
                            }
                            loginButton.classList.remove('loading');
                            loginButton.disabled = false;
                            buttonText.textContent = 'Log In';
                        }
                    });
                </script>
            </body>
            </html>
            """;
        sendHtml(exchange, html);
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject request = parseJsonObject(body);
            String username = request.get("username").getAsString();
            String password = request.get("password").getAsString();

            if (MultithreadingConfig.CONFIG.developerDashboardUsername.equals(username) &&
                MultithreadingConfig.CONFIG.developerDashboardPassword.equals(password)) {

                String sessionId = generateSessionId();
                synchronized (SESSIONS_LOCK) {
                    sessions.put(sessionId, System.currentTimeMillis());
                }

                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("sessionId", sessionId);

                // Set session cookie before sending the response so the browser receives it.
                Headers headers = exchange.getResponseHeaders();
                headers.set("Set-Cookie", "session=" + sessionId + "; Path=/; HttpOnly; Max-Age=86400");
                sendJson(exchange, response);
            } else {
                sendError(exchange, 401, "Invalid credentials");
            }
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid request");
        }
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        String sessionId = getSessionIdFromCookie(exchange);
        if (sessionId != null) {
            synchronized (SESSIONS_LOCK) {
                sessions.remove(sessionId);
            }
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Set-Cookie", "session=; Path=/; HttpOnly; Max-Age=0");
        sendRedirect(exchange, "/login");
    }

    private static void handleSetupPage(HttpExchange exchange) throws IOException {
        if (isDashboardConfigured()) {
            sendRedirect(exchange, "/login");
            return;
        }

        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Quantified API - Setup</title>
                <link rel="stylesheet" href="/dashboard.css">
                <link rel="icon" type="image/png" href="/dashboard-logo.png">
                <style>
                    :root {
                        --bg-primary: radial-gradient(120% 120% at 15% 10%, rgba(99, 102, 241, 0.55) 0%, rgba(15, 23, 42, 0.75) 45%, rgba(8, 11, 20, 0.95) 100%);
                        --surface: rgba(17, 24, 39, 0.78);
                        --surface-soft: rgba(59, 130, 246, 0.12);
                        --border: rgba(148, 163, 184, 0.28);
                        --border-strong: rgba(99, 102, 241, 0.42);
                        --text-main: #e2e8f0;
                        --text-subtle: #94a3b8;
                        --accent: #6366f1;
                        --accent-strong: #8b5cf6;
                        --shadow: 0 40px 100px rgba(15, 23, 42, 0.65);
                    }
                    * {
                        box-sizing: border-box;
                    }
                    body {
                        margin: 0;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: clamp(28px, 5vh, 60px) clamp(18px, 5vw, 70px);
                        font-family: "Inter", "Segoe UI", sans-serif;
                        background: var(--bg-primary);
                        color: var(--text-main);
                        position: relative;
                        overflow-y: auto;
                    }
                    body::before,
                    body::after {
                        content: "";
                        position: fixed;
                        inset: 0;
                        pointer-events: none;
                        background: radial-gradient(45% 50% at 80% 15%, rgba(14, 165, 233, 0.18), transparent),
                                    radial-gradient(40% 45% at 15% 75%, rgba(125, 211, 252, 0.12), transparent);
                        mix-blend-mode: screen;
                        filter: blur(40px);
                        animation: auroraDrift 26s ease-in-out infinite;
                    }
                    main.setup-shell {
                        width: min(1060px, 100%);
                        display: grid;
                        grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
                        gap: clamp(28px, 4vw, 48px);
                        padding: clamp(32px, 6vw, 64px);
                        border-radius: 28px;
                        background: linear-gradient(135deg, rgba(15, 23, 42, 0.85), rgba(30, 41, 59, 0.78));
                        backdrop-filter: blur(22px) saturate(140%);
                        border: 1px solid rgba(99, 102, 241, 0.26);
                        box-shadow: var(--shadow);
                        position: relative;
                        overflow: hidden;
                        z-index: 1;
                    }
                    main.setup-shell::before {
                        content: "";
                        position: absolute;
                        inset: -60% -40%;
                        background: linear-gradient(160deg, transparent 0%, rgba(255, 255, 255, 0.05) 40%, rgba(255, 255, 255, 0.12) 50%, rgba(255, 255, 255, 0.05) 60%, transparent 100%);
                        mix-blend-mode: screen;
                        opacity: 0.9;
                        transform: translateY(-140%);
                        animation: glassSheen 9s ease-in-out infinite;
                        pointer-events: none;
                    }
                    .setup-copy {
                        display: flex;
                        flex-direction: column;
                        gap: clamp(20px, 4vh, 32px);
                    }

                    .setup-logo {
                        width: 80px;
                        height: 80px;
                        border-radius: 24px;
                        overflow: hidden;
                        display: inline-block;
                        box-shadow: 0 24px 60px rgba(8, 11, 20, 0.45);
                        align-self: flex-start;
                        opacity: 0;
                        animation: fadeInUp 0.6s 0.1s ease-out forwards;
                    }
                    .pill {
                        align-self: flex-start;
                        padding: 8px 16px;
                        border-radius: 999px;
                        background: rgba(99, 102, 241, 0.18);
                        border: 1px solid rgba(148, 163, 184, 0.32);
                        text-transform: uppercase;
                        letter-spacing: 0.08em;
                        font-size: 11px;
                        font-weight: 600;
                        color: #c7d2fe;
                    }
                    .setup-copy h1 {
                        margin: 0;
                        font-size: clamp(28px, 4vw, 40px);
                        font-weight: 700;
                        line-height: 1.2;
                    }
                    .setup-copy p {
                        margin: 0;
                        font-size: 16px;
                        line-height: 1.6;
                        color: var(--text-subtle);
                        max-width: 38ch;
                    }
                    .callouts {
                        display: grid;
                        gap: 16px;
                    }
                    .callout {
                        padding: 16px 18px;
                        border-radius: 16px;
                        background: rgba(148, 163, 184, 0.08);
                        border: 1px solid rgba(148, 163, 184, 0.22);
                        display: flex;
                        gap: 14px;
                        align-items: flex-start;
                        color: var(--text-subtle);
                        font-size: 14px;
                        line-height: 1.5;
                    }
                    .callout .icon {
                        width: 36px;
                        height: 36px;
                        border-radius: 12px;
                        background: rgba(59, 130, 246, 0.18);
                        border: 1px solid rgba(99, 102, 241, 0.3);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: #c7d2fe;
                        font-size: 18px;
                        flex-shrink: 0;
                    }
                    .setup-card {
                        padding: clamp(22px, 3vw, 32px);
                        border-radius: 22px;
                        background: var(--surface);
                        border: 1px solid var(--border);
                        box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
                        display: flex;
                        flex-direction: column;
                        gap: 24px;
                    }
                    .setup-card header {
                        display: flex;
                        flex-direction: column;
                        gap: 10px;
                    }
                    .setup-card header h2 {
                        margin: 0;
                        font-size: 24px;
                        color: #c7d2fe;
                    }
                    .setup-card header p {
                        margin: 0;
                        font-size: 14px;
                        line-height: 1.6;
                        color: var(--text-subtle);
                    }
                    #error-message {
                        display: none;
                        padding: 12px 14px;
                        border-radius: 12px;
                        background: rgba(248, 113, 113, 0.12);
                        border: 1px solid rgba(248, 113, 113, 0.35);
                        color: #fecaca;
                        font-size: 14px;
                    }
                    form.setup-form {
                        display: flex;
                        flex-direction: column;
                        gap: 20px;
                    }
                    .field-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                        gap: 18px;
                    }
                    .field-group {
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                    }
                    .auth-credentials {
                        transition: opacity 0.2s ease, filter 0.2s ease;
                    }
                    .auth-credentials.is-disabled {
                        opacity: 0.45;
                        filter: saturate(65%);
                        pointer-events: none;
                    }
                    .auth-credentials.is-disabled input {
                        cursor: not-allowed;
                    }
                    label {
                        font-size: 13px;
                        letter-spacing: 0.01em;
                        text-transform: uppercase;
                        color: #cbd5f5;
                        font-weight: 600;
                    }
                    input[type="text"],
                    input[type="number"],
                    input[type="password"],
                    select {
                        padding: 12px 14px;
                        border-radius: 12px;
                        border: 1px solid rgba(148, 163, 184, 0.3);
                        background: rgba(15, 23, 42, 0.6);
                        color: var(--text-main);
                        font-size: 15px;
                        transition: border 0.18s ease, box-shadow 0.18s ease;
                    }
                    input:focus,
                    select:focus {
                        outline: none;
                        border-color: var(--accent-strong);
                        box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.18);
                    }
                    ::placeholder {
                        color: rgba(148, 163, 184, 0.5);
                    }
                    .checkbox-line {
                        display: flex;
                        gap: 14px;
                        align-items: center;
                        padding: 12px 14px;
                        border-radius: 14px;
                        background: rgba(79, 70, 229, 0.12);
                        border: 1px solid rgba(99, 102, 241, 0.26);
                        color: var(--text-subtle);
                        font-size: 14px;
                    }
                    .checkbox-line input[type="checkbox"] {
                        appearance: none;
                        width: 18px;
                        height: 18px;
                        border-radius: 6px;
                        border: 1px solid rgba(165, 180, 252, 0.6);
                        background: rgba(99, 102, 241, 0.22);
                        box-shadow: inset 0 1px 2px rgba(15, 23, 42, 0.45);
                        display: grid;
                        place-items: center;
                        transition: border 0.2s ease, background 0.2s ease, box-shadow 0.2s ease;
                    }
                    .checkbox-line input[type="checkbox"]::after {
                        content: "";
                        width: 8px;
                        height: 8px;
                        border-radius: 3px;
                        background: transparent;
                        transition: transform 0.2s ease, background 0.2s ease, box-shadow 0.2s ease;
                        transform: scale(0);
                    }
                    .checkbox-line input[type="checkbox"]:checked {
                        background: linear-gradient(135deg, rgba(99, 102, 241, 0.6), rgba(139, 92, 246, 0.5));
                        border-color: rgba(165, 180, 252, 0.9);
                        box-shadow: 0 0 12px rgba(129, 140, 248, 0.35);
                    }
                    .checkbox-line input[type="checkbox"]:checked::after {
                        background: #ede9fe;
                        box-shadow: 0 0 10px rgba(192, 132, 252, 0.5);
                        transform: scale(1);
                    }
                    .checkbox-line input[type="checkbox"]:focus-visible {
                        outline: none;
                        border-color: rgba(129, 140, 248, 1);
                        box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.3);
                    }
                    .pw-strength {
                        display: flex;
                        flex-direction: column;
                        gap: 10px;
                    }
                    .pw-strength .meter {
                        width: 100%;
                        height: 10px;
                        border-radius: 999px;
                        background: rgba(148, 163, 184, 0.18);
                        overflow: hidden;
                        border: 1px solid rgba(99, 102, 241, 0.25);
                    }
                    .pw-strength .meter .bar {
                        height: 100%;
                        width: 0%;
                        background: linear-gradient(90deg, #ef4444, #f97316);
                        transition: width 0.2s ease, background 0.2s ease;
                    }
                    .pw-hints {
                        display: flex;
                        flex-wrap: wrap;
                        gap: 10px;
                        font-size: 12px;
                    }
                    .pw-hints .hint {
                        padding: 6px 10px;
                        border-radius: 999px;
                        background: rgba(99, 102, 241, 0.16);
                        border: 1px solid rgba(129, 140, 248, 0.4);
                        color: rgba(226, 232, 240, 0.8);
                        transition: background 0.2s ease, border 0.2s ease, color 0.2s ease;
                    }
                    .pw-hints .hint.ok {
                        background: rgba(34, 197, 94, 0.16);
                        border-color: rgba(16, 185, 129, 0.4);
                        color: rgba(209, 250, 229, 0.9);
                    }
                    .setup-btn {
                        margin-top: 8px;
                        padding: 14px 20px;
                        border-radius: 14px;
                        border: none;
                        background: linear-gradient(135deg, var(--accent), var(--accent-strong));
                        color: #f8fafc;
                        font-size: 16px;
                        font-weight: 600;
                        letter-spacing: 0.02em;
                        cursor: pointer;
                        transition: transform 0.2s ease, box-shadow 0.2s ease, filter 0.2s ease;
                        box-shadow: 0 18px 40px rgba(99, 102, 241, 0.35);
                    }
                    .setup-btn:hover {
                        transform: translateY(-2px);
                        filter: brightness(1.05);
                    }
                    .setup-btn:disabled {
                        opacity: 0.5;
                        cursor: not-allowed;
                        transform: none;
                        box-shadow: none;
                    }
                    @keyframes auroraDrift {
                        0% { transform: translate3d(-6%, -4%, 0) scale(1); opacity: 0.92; }
                        50% { transform: translate3d(8%, 6%, 0) scale(1.08); opacity: 1; }
                        100% { transform: translate3d(-4%, 12%, 0) scale(1.02); opacity: 0.95; }
                    }
                    @keyframes glassSheen {
                        0% { transform: translateY(-150%); }
                        50% { transform: translateY(120%); }
                        100% { transform: translateY(200%); }
                    }
                    @media (prefers-reduced-motion: reduce) {
                        body::before,
                        body::after,
                        main.setup-shell::before {
                            animation: none;
                        }
                    }
                    @media (max-width: 900px) {
                        main.setup-shell {
                            grid-template-columns: 1fr;
                        }
                        .setup-copy {
                            order: 2;
                        }
                        .setup-card {
                            order: 1;
                        }
                    }
                    @media (max-width: 640px) {
                        body {
                            padding: 24px;
                        }
                        main.setup-shell {
                            padding: 24px;
                            border-radius: 20px;
                        }
                        .setup-card {
                            padding: 20px;
                        }
                        .callout {
                            flex-direction: column;
                        }
                    }
                </style>
            </head>
            <body>
                <div class="aurora" aria-hidden="true"></div>
                <main class="setup-shell">
                    <section class="setup-copy">
                        <img src="/dashboard-logo.png" alt="Quantified API logo" class="setup-logo">
                        <span class="pill">First Time Setup</span>
                        <h1>Prepare the Quantified webpanel for secure access.</h1>
                        <p>Lock the dashboard behind strong credentials, choose the bind address and port, and opt into HTTPS when deploying beyond your LAN.</p>
                        <div class="callouts">
                            <div class="callout">
                                <div class="icon">!</div>
                                <div>Setup won't move forward until your password meets every security requirement. That way, all development data stays locked behind strong authentication right from the start.</div>
                            </div>
                            <div class="callout">
                                <div class="icon">i</div>
                                <div>Turn on HTTPS once you have your keystore ready. You can always change these settings later in the Quantified config file.</div>
                            </div>
                        </div>
                    </section>
                    <section class="setup-card">
                        <header>
                            <h2>Configuration</h2>
                            <p>Everything here writes to the Quantified dashboard config. You can adjust the values at any time through the config file.</p>
                        </header>
                        <div id="error-message"></div>
                        <form class="setup-form" id="setup-form" novalidate>
                            <div class="field-grid">
                                <div class="field-group">
                                    <label for="host">Host / IP</label>
                                    <input type="text" id="host" name="host" value="0.0.0.0" autocomplete="off" required>
                                </div>
                                <div class="field-group">
                                    <label for="port">Port</label>
                                    <input type="number" id="port" name="port" value="8765" min="1024" max="65535" required>
                                </div>
                            </div>
                            <div class="checkbox-line">
                                <input type="checkbox" id="https" name="https">
                                <label for="https">Enable HTTPS (requires a configured keystore)</label>
                            </div>
                            <div class="checkbox-line">
                                <input type="checkbox" id="auth" name="auth" checked>
                                <label for="auth">Require authentication to open the dashboard</label>
                            </div>
                            <div class="field-group auth-credentials" data-auth-block>
                                <label for="username">Username</label>
                                <input type="text" id="username" name="username" autocomplete="username" required>
                            </div>
                            <div class="field-group auth-credentials" data-auth-block>
                                <label for="password">Password</label>
                                <input type="password" id="password" name="password" autocomplete="new-password">
                                <div id="pw-strength" class="pw-strength" aria-hidden="true">
                                    <div class="meter"><div id="meter-bar" class="bar"></div></div>
                                    <div id="pw-hints" class="pw-hints">
                                        <div class="hint" id="hint-length">Minimum 8 characters</div>
                                        <div class="hint" id="hint-lower">Lowercase</div>
                                        <div class="hint" id="hint-upper">Uppercase</div>
                                        <div class="hint" id="hint-digit">Digit</div>
                                        <div class="hint" id="hint-symbol">Symbol</div>
                                    </div>
                                </div>
                            </div>
                            <div class="field-group auth-credentials" data-auth-block>
                                <label for="confirm-password">Confirm Password</label>
                                <input type="password" id="confirm-password" name="confirm-password" autocomplete="new-password">
                            </div>
                            <button type="submit" class="setup-btn" id="setup-btn">Complete Setup</button>
                        </form>
                    </section>
                </main>
                <script>
                    document.getElementById('setup-form').addEventListener('submit', async (e) => {
                        e.preventDefault();
                        const btn = document.getElementById('setup-btn');
                        const errorDiv = document.getElementById('error-message');

                        const password = document.getElementById('password').value;
                        const confirmPassword = document.getElementById('confirm-password').value;

                        // Password equality check
                        if (password !== confirmPassword) {
                            errorDiv.textContent = 'Passwords do not match';
                            errorDiv.style.display = 'block';
                            return;
                        }

                        // Complexity check: min 8, must include lower, upper, digit and symbol
                        const complexity = /(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}/;
                        if (!complexity.test(password)) {
                            errorDiv.textContent = 'Password must be at least 8 characters and include upper, lower, digits and a symbol.';
                            errorDiv.style.display = 'block';
                            return;
                        }

                        btn.disabled = true;
                        btn.textContent = 'Setting up...';
                        errorDiv.style.display = 'none';

                        try {
                            const formData = new FormData(e.target);
                            const response = await fetch('/api/setup', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                },
                                body: JSON.stringify({
                                    host: formData.get('host'),
                                    port: parseInt(formData.get('port')),
                                    https: formData.has('https'),
                                    auth: formData.has('auth'),
                                    username: formData.get('username'),
                                    password: formData.get('password'),
                                    confirmPassword: formData.get('confirm-password')
                                })
                            });

                            if (response.ok) {
                                alert('Setup complete! The webpanel will restart with your new configuration.');
                                window.location.href = '/login';
                            } else {
                                const data = await response.json();
                                errorDiv.textContent = data.error || 'Setup failed';
                                errorDiv.style.display = 'block';
                            }
                        } catch (error) {
                            errorDiv.textContent = 'Network error. Please try again.';
                            errorDiv.style.display = 'block';
                        } finally {
                            btn.disabled = false;
                            btn.textContent = 'Complete Setup';
                        }
                    });

                    // Password strength + dynamic form state for setup
                    (function() {
                        const pwd = document.getElementById('password');
                        const meterBar = document.getElementById('meter-bar');
                        const confirm = document.getElementById('confirm-password');
                        const authBox = document.getElementById('auth');
                        const setupBtn = document.getElementById('setup-btn');
                        const usernameInput = document.getElementById('username');
                        const hints = {
                            length: document.getElementById('hint-length'),
                            lower: document.getElementById('hint-lower'),
                            upper: document.getElementById('hint-upper'),
                            digit: document.getElementById('hint-digit'),
                            symbol: document.getElementById('hint-symbol')
                        };

                        function scorePassword(value) {
                            let score = 0;
                            if (value.length >= 8) score += 1;
                            if (/[a-z]/.test(value)) score += 1;
                            if (/[A-Z]/.test(value)) score += 1;
                            if (/\\d/.test(value)) score += 1;
                            if (/[^A-Za-z0-9]/.test(value)) score += 1;
                            return score; // 0..5
                        }

                        function updateFormState() {
                            const authRequired = authBox && authBox.checked;
                            const val = pwd ? pwd.value || '' : '';
                            const score = scorePassword(val);
                            const complexEnough = score === 5; // require all checks for server validation
                            const confirmVal = confirm ? confirm.value : '';
                            const usernameOk = usernameInput ? usernameInput.value && usernameInput.value.trim().length > 0 : true;

                            if (authRequired) {
                                if (pwd) pwd.required = true;
                                if (confirm) confirm.required = true;
                                if (setupBtn) setupBtn.disabled = !(usernameOk && complexEnough && val === confirmVal);
                            } else {
                                if (pwd) pwd.required = false;
                                if (confirm) confirm.required = false;
                                if (setupBtn) setupBtn.disabled = false; // no auth requested -> allow
                            }
                        }

                        function updateMeter() {
                            const val = pwd ? pwd.value || '' : '';
                            const score = scorePassword(val);
                            const pct = Math.round((score / 5) * 100);
                            if (meterBar) {
                                meterBar.style.width = pct + '%';
                                let bg = 'linear-gradient(90deg, #ef4444, #f97316)';
                                if (score >= 4) bg = 'linear-gradient(90deg,#10b981,#059669)';
                                else if (score >= 3) bg = 'linear-gradient(90deg,#f59e0b,#f97316)';
                                meterBar.style.background = bg;
                            }
                            if (hints.length) hints.length.classList.toggle('ok', val.length >= 8);
                            if (hints.lower) hints.lower.classList.toggle('ok', /[a-z]/.test(val));
                            if (hints.upper) hints.upper.classList.toggle('ok', /[A-Z]/.test(val));
                            if (hints.digit) hints.digit.classList.toggle('ok', /\\d/.test(val));
                            if (hints.symbol) hints.symbol.classList.toggle('ok', /[^A-Za-z0-9]/.test(val));
                            updateFormState();
                        }

                        if (pwd) pwd.addEventListener('input', updateMeter);
                        if (confirm) confirm.addEventListener('input', updateFormState);
                        if (authBox) authBox.addEventListener('change', updateFormState);
                        if (usernameInput) usernameInput.addEventListener('input', updateFormState);
                        updateMeter();
                        updateFormState();
                    }());
                </script>
            </body>
            </html>
            """;
        sendHtml(exchange, html);
    }

    private static void handleSetup(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject request = parseJsonObject(body);

            String host = request.get("host").getAsString();
            int port = request.get("port").getAsInt();
            boolean https = request.has("https") && request.get("https").getAsBoolean();
            boolean auth = request.has("auth") && request.get("auth").getAsBoolean();
            String username = request.get("username").getAsString();
            String password = request.get("password").getAsString();
            String confirm = request.has("confirmPassword") ? request.get("confirmPassword").getAsString() : null;

            // Validate input
            if (port < 1024 || port > 65535) {
                sendError(exchange, 400, "Port must be between 1024 and 65535");
                return;
            }

            if (auth) {
                if (username == null || username.trim().isEmpty()) {
                    sendError(exchange, 400, "Username cannot be empty");
                    return;
                }
                if (password == null || confirm == null || !password.equals(confirm)) {
                    sendError(exchange, 400, "Passwords do not match");
                    return;
                }
                if (!isPasswordComplex(password)) {
                    sendError(exchange, 400, "Password does not meet complexity requirements: min 8 chars, include upper+lower+digit+symbol");
                    return;
                }
            }

            MultithreadingConfig.CONFIG.developerDashboardHost = host;
            MultithreadingConfig.CONFIG.developerDashboardPort = port;
            MultithreadingConfig.CONFIG.developerDashboardHttps = https;
            MultithreadingConfig.CONFIG.developerDashboardAuth = auth;
            if (auth) {
                MultithreadingConfig.CONFIG.developerDashboardUsername = username.trim();
                MultithreadingConfig.CONFIG.developerDashboardPassword = password;
            }
            MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);

            DeveloperFeatures.setDashboardEnabled(true, true);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Setup complete. Server restarting...");
            sendJson(exchange, response);

        } catch (Exception e) {
            sendError(exchange, 400, "Invalid request");
        }
    }

    private static boolean isDashboardConfigured() {
        try {
            MultithreadingConfig.Config config = MultithreadingConfig.CONFIG;
            if (config == null) {
                return false;
            }

            if (!config.developerDashboardAuth) {
                return true;
            }

            String u = config.developerDashboardUsername;
            String p = config.developerDashboardPassword;
            return u != null && !u.isBlank() && p != null && !p.isBlank();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPasswordComplex(String pw) {
        if (pw == null) return false;
        if (pw.length() < 8) return false;
        boolean hasLower = pw.chars().anyMatch(Character::isLowerCase);
        boolean hasUpper = pw.chars().anyMatch(Character::isUpperCase);
        boolean hasDigit = pw.chars().anyMatch(Character::isDigit);
        boolean hasSymbol = pw.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        return hasLower && hasUpper && hasDigit && hasSymbol;
    }

    private static boolean isAuthenticated(HttpExchange exchange) {
        String sessionId = getSessionIdFromCookie(exchange);
        if (sessionId == null) return false;

        synchronized (SESSIONS_LOCK) {
            Long timestamp = sessions.get(sessionId);
            if (timestamp == null) return false;

            if (System.currentTimeMillis() - timestamp > 24 * 60 * 60 * 1000) {
                sessions.remove(sessionId);
                return false;
            }

            sessions.put(sessionId, System.currentTimeMillis());
            return true;
        }
    }

    private static String getSessionIdFromCookie(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        List<String> cookies = headers.get("Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                String[] parts = cookie.split(";");
                for (String part : parts) {
                    part = part.trim();
                    if (part.startsWith("session=")) {
                        return part.substring("session=".length());
                    }
                }
            }
        }
        return null;
    }

    private static String generateSessionId() {
        return java.util.UUID.randomUUID().toString();
    }

    private static void sendRedirect(HttpExchange exchange, String location) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        applyCors(headers);
        headers.set("Content-Type", "text/html;charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }

    private static final class ModActivityMetrics {
        final String modId;
        String displayName;
        long taskEvents;
        long gpuEvents;
        long parallelEvents;
        long multithreadingEvents;
        long otherEvents;
        long batchCount;
        long batchTotal;
        int batchMax;
        long cacheHits;
        long cacheMisses;
        long cacheEntries;
        long cacheEvictions;
        long diskBytes;
        long ramBytes;
        long peakVramBytes;
        int queueDepth;
        double tasksPerSecond;
        long lastSeenMs;

        ModActivityMetrics(String modId) {
            this.modId = modId == null || modId.isBlank() ? "unknown-mod" : modId;
            this.displayName = this.modId;
        }

        long activityScore() {
            return taskEvents + cacheHits + cacheMisses + batchTotal + queueDepth;
        }
    }
}

