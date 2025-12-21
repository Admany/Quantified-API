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
import org.admany.quantified.api.QuantifiedAPI;
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
import org.admany.quantified.core.common.opencl.gpu.probe.GpuTelemetryService;
import org.admany.quantified.core.common.opencl.task.OpenCLTaskManager;
import org.admany.quantified.core.common.util.TaskScheduler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final long SENSOR_REFRESH_INTERVAL_MS = 10_000L;
    private static final AtomicLong LAST_CPU_SENSOR_QUERY = new AtomicLong(0L);
    private static final AtomicReference<Double> LAST_CPU_SENSOR_VALUE = new AtomicReference<>(Double.NaN);
    private static final AtomicBoolean CPU_TEMP_QUERY_DISABLED = new AtomicBoolean(false);
    private static final AtomicBoolean CPU_TEMP_WARNING_LOGGED = new AtomicBoolean(false);
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
    private static volatile boolean isHttps = false;

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
    }

    public static void applyConfiguration(boolean enabled, int port, String host, boolean https, boolean auth) {
        LOGGER.fine("DeveloperDashboardServer.applyConfiguration called: enabled=" + enabled + ", port=" + port + ", host=" + host + ", https=" + https + ", auth=" + auth);
        synchronized (LOCK) {
            if (!enabled) {
                stopInternal();
                return;
            }
            if (server != null && boundPort == port && isHttps == https) {
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

    private static void startInternal(int port, String host, boolean https, boolean auth) {
        try {
            LOGGER.fine("Starting developer dashboard server on " + host + ":" + port + " (https=" + https + ", auth=" + auth + ")");
            if (https) {
                try {
                    server = createHttpsServer(host, port);
                    isHttps = true;
                } catch (Exception httpsEx) {
                    LOGGER.warning("Failed to create HTTPS server, falling back to HTTP: " + httpsEx.getMessage());
                    server = HttpServer.create(new InetSocketAddress(host, port), 0);
                    isHttps = false;
                }
            } else {
                server = HttpServer.create(new InetSocketAddress(host, port), 0);
                isHttps = false;
            }
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
            String protocol = https ? "https" : "http";
            LOGGER.fine(() -> "Developer dashboard listening on " + protocol + "://" + host + ":" + port);
        } catch (Exception ex) {
            boundPort = -1;
            isHttps = false;
            LOGGER.log(Level.WARNING, "Failed to start developer dashboard on " + host + ":" + port, ex);
        }
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
                isHttps = false;
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
        httpServer.createContext("/api/v1/config", wrap(auth ? DeveloperDashboardServer::handleAuthRequired : DeveloperDashboardServer::handleConfigEndpoint));
    }

    private static HttpHandler wrap(CheckedHandler handler) {
        return exchange -> {
            try {
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleOptions(exchange);
                    return;
                }
                try {
                    String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "";
                    boolean isStaticDashboardAsset = false;
                    if (path != null) {
                        if (path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico")
                            || path.equals("/dashboard.js") || path.equals("/dashboard.css")
                            || path.equals(LOGO_ENDPOINT)
                            || path.startsWith("/dashboard/")) {
                            isStaticDashboardAsset = true;
                        }
                    }
                    if (!isStaticDashboardAsset) {
                        boolean isDashboardApi = path != null && path.startsWith("/api/v1/");
                        boolean isLoopback = false;
                        if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
                            isLoopback = exchange.getRemoteAddress().getAddress().isLoopbackAddress();
                        }
                        if (!(isDashboardApi && isLoopback)) {
                            String requestLine = exchange.getRequestMethod() + " " + exchange.getRequestURI().toString() + " from " + exchange.getRemoteAddress();
                            String ts = new java.text.SimpleDateFormat("MMM dd, yyyy hh:mm:ss a").format(new java.util.Date());
                            String loggerName = DeveloperDashboardServer.class.getName() + " handle";
                            String formatted = ts + " " + loggerName + System.lineSeparator() + "INFO: " + requestLine;
                            DeveloperOverlayManager.recordApiLog(formatted);
                            LOGGER.fine(() -> "HTTP " + requestLine);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "Failed to record API log", t);
                }
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

    private static JsonObject buildResourcePayload(DeveloperOverlayManager.DeveloperDiagnosticsView diagnostics) {
        JsonObject payload = new JsonObject();
        payload.addProperty("generatedAt", System.currentTimeMillis());
        payload.addProperty("queueWarningThreshold", 2000);

        CacheManager.CacheUsage usage = CacheManager.cacheUsageSnapshot();
        JsonObject summary = new JsonObject();
        summary.addProperty("queueDepth", diagnostics.snapshot().queueDepth());
        summary.addProperty("cacheEntryCount", usage.entryCount());
        summary.addProperty("cacheRamBytes", usage.heapBytes());
        summary.addProperty("cacheDiskBytes", usage.diskBytes());
        summary.addProperty("vramUsedBytes", Math.max(0L, diagnostics.snapshot().gpuVramUsedBytes()));
        summary.addProperty("vramBudgetBytes", Math.max(0L, diagnostics.snapshot().gpuVramBudgetBytes()));
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
                for (Map.Entry<String, JsonElement> entry : updates.entrySet()) {
                    String key = entry.getKey();
                    java.lang.reflect.Field field = MultithreadingConfig.Config.class.getField(key);
                    Object coerced = coerceConfigValue(field.getType(), entry.getValue());
                    field.set(MultithreadingConfig.CONFIG, coerced);
                }
                MultithreadingConfig.writePrettyJsonConfig(MultithreadingConfig.CONFIG);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("Invalid config key provided", ex);
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
            if (request.has("overlayEnabled")) {
                boolean overlay = request.get("overlayEnabled").getAsBoolean();
                DeveloperFeatures.setOverlayEnabled(overlay, true);
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
        payload.addProperty("developerMode", DeveloperFeatures.isDeveloperModeEnabled());
        payload.addProperty("dashboardEnabled", DeveloperFeatures.isDashboardEnabled());
        payload.addProperty("overlayEnabled", DeveloperFeatures.isOverlayEnabled());
        payload.addProperty("timelineEnabled", DeveloperFeatures.isTimelineEnabled());
        payload.addProperty("replayEnabled", DeveloperFeatures.isReplayEnabled());
        payload.addProperty("autoHintsEnabled", DeveloperFeatures.isAutoHintsEnabled());
        payload.addProperty("stressTestEnabled", DeveloperFeatures.isStressTestEnabled());
        payload.addProperty("modSpotlightEnabled", DeveloperFeatures.isModSpotlightEnabled());
        payload.addProperty("overlaySamplingActive", DeveloperFeatures.isOverlaySamplingActive());
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

    payload.addProperty("openclForced", MultithreadingConfig.CONFIG.openclForced);

        TaskScheduler.SchedulingStats schedulerStats = TaskScheduler.getStats();
        payload.addProperty("schedulerTotalTasks", schedulerStats.totalTasks());
        payload.addProperty("schedulerCpuTasks", schedulerStats.cpuTasks());
        payload.addProperty("schedulerGpuTasks", schedulerStats.gpuTasks());
        payload.addProperty("schedulerGpuRatio", schedulerStats.gpuUtilizationRatio());
        // Snapshot JSON (also record into the export history buffer)
        JsonObject snapshotJson = toJson(diagnostics.snapshot());
        payload.add("snapshot", snapshotJson);
        payload.addProperty("gpuVramBudgetBytes", diagnostics.snapshot().gpuVramBudgetBytes());
        payload.addProperty("gpuVramUsedBytes", diagnostics.snapshot().gpuVramUsedBytes());
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
                drive.addProperty("type", "Drive");
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
        if (isWindows && CPU_TEMP_QUERY_DISABLED.get()) {
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
            if (isWindows) {
                disableCpuTemperaturePolling();
            }
        } catch (Throwable ignored) {
            if (isWindows) {
                disableCpuTemperaturePolling();
            }
        }
        return cached;
    }

    private static void disableCpuTemperaturePolling() {
        if (CPU_TEMP_QUERY_DISABLED.compareAndSet(false, true)) {
            LAST_CPU_SENSOR_VALUE.set(Double.NaN);
            if (CPU_TEMP_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.fine("Disabling Windows CPU temperature polling after repeated WMI failures; dashboard will omit CPU thermals.");
            }
        }
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
                <title>Quantified API - Login</title>
                <link rel="stylesheet" href="/dashboard.css">
                <link rel="icon" type="image/png" href="/dashboard-logo.png">
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

                    @media (max-width: 480px) {
                        .login-container {
                            margin: 40px 20px;
                            padding: 30px 24px;
                        }

                        .login-header h1 {
                            font-size: 24px;
                        }
                    }

                    @media (prefers-reduced-motion: reduce) {
                        *, *::before, *::after {
                            animation-duration: 0.01ms !important;
                            animation-iteration-count: 1 !important;
                            transition-duration: 0.01ms !important;
                        }
                    }
                </style>
            </head>
            <body>
                <div class="login-container">
                    <div class="login-header">
                        <img src="/dashboard-logo.png" alt="Quantified API logo">
                        <h1>Quantified API</h1>
                        <p>Webpanel Login</p>
                    </div>
                    <div id="error-message" class="error-message" style="display: none;"></div>
                    <form class="login-form" id="login-form">
                        <div class="form-group">
                            <label for="username">Username</label>
                            <input type="text" id="username" name="username" placeholder="Enter your username" required>
                        </div>
                        <div class="form-group">
                            <label for="password">Password</label>
                            <input type="password" id="password" name="password" placeholder="Enter your password" required>
                        </div>
                        <button type="submit" class="login-btn" id="login-btn">
                            <span id="btn-text">Login</span>
                        </button>
                    </form>
                    <div class="setup-link">
                        Interested in the project? <a href="https://github.com/Admany/Quantified-API/" target="_blank" rel="noopener noreferrer">Check it out!</a>
                    </div>
                </div>
                <script>
                    document.getElementById('login-form').addEventListener('submit', async (e) => {
                        e.preventDefault();
                        const btn = document.getElementById('login-btn');
                        const btnText = document.getElementById('btn-text');
                        const errorDiv = document.getElementById('error-message');

                        btn.classList.add('loading');
                        btn.disabled = true;
                        btnText.textContent = 'Authenticating...';
                        errorDiv.style.display = 'none';

                        // Add subtle animation to the container during login
                        const container = document.querySelector('.login-container');
                        container.style.transform = 'scale(0.98)';
                        container.style.transition = 'transform 0.2s ease';

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
                                // Success animation
                                container.style.transform = 'scale(1.02)';
                                container.style.boxShadow = '0 30px 60px rgba(0, 0, 0, 0.2)';
                                setTimeout(() => {
                                    window.location.href = '/';
                                }, 300);
                            } else {
                                // Error animation
                                container.style.transform = 'scale(1)';
                                container.style.animation = 'shake 0.5s ease-in-out';
                                setTimeout(() => {
                                    container.style.animation = '';
                                }, 500);

                                const data = await response.json().catch(() => ({}));
                                errorDiv.textContent = (data && data.error) ? data.error : 'Login failed';
                                errorDiv.style.display = 'block';
                            }
                        } catch (error) {
                            container.style.transform = 'scale(1)';
                            errorDiv.textContent = 'Network error. Please try again.';
                            errorDiv.style.display = 'block';
                        } finally {
                            btn.classList.remove('loading');
                            btn.disabled = false;
                            btnText.textContent = 'Login';
                        }
                    });

                    

                    // Add focus effects to inputs
                    document.querySelectorAll('input').forEach(input => {
                        input.addEventListener('focus', () => {
                            input.parentElement.style.transform = 'scale(1.02)';
                            input.parentElement.style.transition = 'transform 0.2s ease';
                        });

                        input.addEventListener('blur', () => {
                            input.parentElement.style.transform = 'scale(1)';
                        });
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
                                <div class="icon">🔒</div>
                                <div>Setup won’t move forward until your password meets every security requirement. That way, all development data stays locked behind strong authentication right from the start.</div>
                            </div>
                            <div class="callout">
                                <div class="icon">🌐</div>
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
}
