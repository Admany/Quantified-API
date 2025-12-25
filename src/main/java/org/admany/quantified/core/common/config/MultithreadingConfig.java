package org.admany.quantified.core.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.*;

public class MultithreadingConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static MultithreadingConfig.Config CONFIG;
    public static org.slf4j.Logger LOGGER;
    private static final ConfigSchema CONFIG_SCHEMA = buildConfigSchema();


    public static boolean isDedicatedServerEnv() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            return true;
        }
        try {
            return ServerLifecycleHooks.getCurrentServer() != null &&
                   !ServerLifecycleHooks.getCurrentServer().isSingleplayer();
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static class Config {
        // === General Settings ===
        public boolean logToConsole = true; // Log API actions to console
        public String logLevel = "INFO"; // Logging level (TRACE, DEBUG, INFO, WARN, ERROR)

        // === Parallel Execution ===
        public int parallelMaxThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
        public int parallelQueueLimit = 4096;
        public int parallelMaxSlicesPerMod = 512;
        public String parallelFailurePolicy = "FAIL_FAST";

        // === Networking Configuration ===
        public boolean enableNetworking = true; // Enable network communication features
        public int networkTimeoutMs = 30000; // Network operation timeout in milliseconds (default: 30 seconds)

        // === GPU/OpenCL Configuration ===
        public boolean enableGpuAcceleration = true; // Enable GPU acceleration when available
        public boolean openclForced = false; // Force OpenCL usage even for small tasks
        public boolean gpuDebugLogging = false; // Enable detailed GPU operation logging
        public String openclDeviceId = "auto"; // OpenCL device selection ("auto" picks the fastest detected GPU)

        // Task Processing Configuration
        public int taskQueueSize = 1000; // Maximum queued tasks before rejection
        public long taskTimeoutMs = 60000; // Task execution timeout in milliseconds (default: 1 minute)

        // === Developer Features (Disabled by default on servers) ===
        public boolean developerMode = true; // Enable developer mode features
        public boolean developerTimeline = true; // Record operation timeline for debugging
        public boolean developerReplay = true; // Enable replay functionality for diagnostics
        public boolean developerAutoHints = true; // Show automatic performance hints
        public boolean developerStressTest = false; // Enable stress testing features
        public boolean developerModSpotlight = true; // Highlight mod performance in diagnostics

        // === Developer Dashboard Configuration ===
        public boolean developerDashboard = false; // Enable developer dashboard (disabled by default on servers)
        public int developerDashboardPort = 8765; // Dashboard server port
        public String developerDashboardHost = "127.0.0.1"; // Dashboard bind address (127.0.0.1 for local, 0.0.0.0 for network)
        public boolean developerDashboardHttps = false; // Enable HTTPS for dashboard
        public boolean developerDashboardAuth = false; // Require authentication for dashboard access
        public String developerDashboardUsername = ""; // Dashboard username (leave empty to disable auth)
        public String developerDashboardPassword = ""; // Dashboard password (leave empty to disable auth)
        public String developerDashboardKeystorePath = ""; // Path to keystore (JKS or PKCS12) for HTTPS
        public String developerDashboardKeystorePassword = ""; // Password for keystore (if any)
        public int dashboardSessionTimeoutMinutes = 30; // Dashboard session timeout in minutes

        // === Stress Testing Configuration ===
        public String developerStressProfile = "balanced"; // Default stress test profile
        public int stressTestCpuChunkMs = 50; // CPU stress test chunk size in milliseconds

        // === Monitoring and Diagnostics ===
        public boolean enableMetrics = true; // Enable performance metrics collection
        public int metricsIntervalSeconds = 30; // Metrics collection interval in seconds
        public boolean exportMetricsToFile = false; // Export metrics to JSON file
        public String metricsExportPath = "config/quantified/metrics/"; // Path for metrics export
        public int maxMetricsHistoryHours = 24; // Maximum metrics history retention in hours

        // === Security and Safety ===
        public boolean enableSecurityChecks = true; // Enable security validation checks
        public boolean validateTaskInputs = true; // Validate task inputs for safety
        public boolean sandboxExternalCalls = true; // Sandbox external process calls

        // === Fixes ===
        public boolean enableGcHints = true; // Provide garbage collection optimization hints

        // === Debug Settings ===
        public boolean debug = false; // Enable detailed debug logging
        public boolean debugShowTimings = false; // Show timing information for operations
        public boolean debugShowThreading = false; // Show thread creation/destruction logs
        public boolean debugSaveToFile = false; // Save debug output to file
        public String debugLogFile = "config/quantified/debug.log"; // Debug log file path
        public int debugMaxLogSizeMb = 10; // Maximum debug log file size in MB

        // === Experimental Features ===
        public boolean enableExperimentalFeatures = false; // Enable experimental/unstable features
        public boolean experimentalAsyncBatching = false; // Experimental async task batching
        public boolean experimentalMemoryPooling = false; // Experimental memory pooling for tasks
        public boolean experimentalPredictiveScaling = false; // Experimental predictive thread scaling
    }

    /**
     * Handles loading or creating the configuration file.
     * If a config file already exists, it attempts to load and parse it.
     * If the file is missing, corrupted, or unparseable, it generates a new one with sensible defaults,
     * ensuring the system always has a valid configuration to operate with.
     */
    public static Config loadOrCreateConfig(Logger logger) {
        String path = "config/quantified/quantified_config.json";
        String legacyPath = "config/quantified.json";
        // Treat as server if we positively detect a dedicated server, even if Dist is misreported
        boolean physIsServer = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
        boolean detectedServer = isDedicatedServerEnv();
        // Always load/create the config file so server operators and singleplayer hosts can edit toggles
        if (!detectedServer) {
            // Singleplayer/integrated: enable some developer features by default
            // Server: disable developer features by default for security
        }

        // Use fallback logger if null
        Logger log = (logger != null) ? logger : org.slf4j.LoggerFactory.getLogger(MultithreadingConfig.class);
        if (!physIsServer && detectedServer) {
            log.warn("Physical side is CLIENT but detected server environment - config may have unexpected defaults");
        }

        try {
            // Try to load existing config
            File configFile = new File(path);
            if (configFile.exists()) {
                try (FileReader reader = new FileReader(configFile)) {
                    // Read the entire file content
                    StringBuilder content = new StringBuilder();
                    char[] buffer = new char[1024];
                    int bytesRead;
                    while ((bytesRead = reader.read(buffer)) != -1) {
                        content.append(buffer, 0, bytesRead);
                    }

                    // Strip the ASCII header if present
                    String contentStr = content.toString();
                    if (contentStr.startsWith("/*")) {
                        int headerEnd = contentStr.indexOf("*/");
                        if (headerEnd != -1) {
                            contentStr = contentStr.substring(headerEnd + 2).trim();
                            // Remove leading whitespace and potential opening brace
                            if (contentStr.startsWith("\n")) {
                                contentStr = contentStr.substring(1);
                            }
                        }
                    }

                    StringBuilder cleanJson = new StringBuilder();
                    String[] lines = contentStr.split("\n");
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("//") && !trimmed.isEmpty()) {
                            cleanJson.append(line).append("\n");
                        }
                    }
                    contentStr = cleanJson.toString().trim();

                    // Parse the JSON content
                    com.google.gson.JsonObject jsonData = GSON.fromJson(contentStr, com.google.gson.JsonObject.class);
                    if (jsonData != null) {
                        // Try ADM-style flat config first
                        CONFIG = parseFlatConfig(jsonData);
                        if (CONFIG != null) {
                            // Validate and set defaults for missing fields
                            validateAndSetDefaults(CONFIG, detectedServer);
                            log.info("Loaded flat configuration from " + path);
                            return CONFIG;
                        }
                        CONFIG = parseBoxedConfig(jsonData);
                        if (CONFIG != null) {
                            // Validate and set defaults for missing fields
                            validateAndSetDefaults(CONFIG, detectedServer);
                            log.info("Loaded boxed configuration from " + path);
                            return CONFIG;
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to load config from " + path + ", creating backup and using defaults", e);
                    backupConfigFile(path);
                }
            }

            // Try legacy config migration
            migrateLegacyConfigIfNeeded(legacyPath, path);

            // Create new config with defaults
            CONFIG = new Config();
            validateAndSetDefaults(CONFIG, detectedServer);
            writePrettyJsonConfig(CONFIG);
            log.info("Created new configuration file at " + path);

        } catch (Exception e) {
            log.error("Failed to load/create config, using defaults", e);
            CONFIG = new Config();
            validateAndSetDefaults(CONFIG, detectedServer);
        }

        log.info("Final config loaded: developerDashboard=" + CONFIG.developerDashboard + ", developerMode=" + CONFIG.developerMode + ", port=" + CONFIG.developerDashboardPort + ", host=" + CONFIG.developerDashboardHost);
        return CONFIG;
    }

    private static void validateAndSetDefaults(Config config, boolean isServer) {

        // Ensure lists are initialized
        if (isServer && config.developerStressTest) {
            config.developerStressTest = false;
        }
    }

    private static void migrateLegacyConfigIfNeeded(String legacyPath, String newPath) {
        try {
            File legacyFile = new File(legacyPath);
            if (legacyFile.exists()) {
                LOGGER.info("Migrating legacy config from " + legacyPath + " to " + newPath);
                // Load legacy config
                try (FileReader reader = new FileReader(legacyFile)) {
                    // Simple migration - load as generic JSON and save as new format
                    com.google.gson.JsonObject legacyData = GSON.fromJson(reader, com.google.gson.JsonObject.class);
                    if (legacyData != null) {
                        // Create new config and merge legacy values
                        Config newConfig = new Config();

                        // Handle both flat and nested legacy formats
                        if (legacyData.has("cacheMaxSize")) {
                            // Flat format migration
                            if (legacyData.has("enableNetworking")) newConfig.enableNetworking = legacyData.get("enableNetworking").getAsBoolean();
                            if (legacyData.has("developerMode")) newConfig.developerMode = legacyData.get("developerMode").getAsBoolean();
                            if (legacyData.has("developerDashboard")) newConfig.developerDashboard = legacyData.get("developerDashboard").getAsBoolean();
                            if (legacyData.has("developerDashboardPort")) newConfig.developerDashboardPort = legacyData.get("developerDashboardPort").getAsInt();
                            if (legacyData.has("developerDashboardHost")) newConfig.developerDashboardHost = legacyData.get("developerDashboardHost").getAsString();
                            if (legacyData.has("developerDashboardHttps")) newConfig.developerDashboardHttps = legacyData.get("developerDashboardHttps").getAsBoolean();
                            if (legacyData.has("developerDashboardAuth")) newConfig.developerDashboardAuth = legacyData.get("developerDashboardAuth").getAsBoolean();
                            if (legacyData.has("developerDashboardUsername")) newConfig.developerDashboardUsername = legacyData.get("developerDashboardUsername").getAsString();
                            if (legacyData.has("developerDashboardPassword")) newConfig.developerDashboardPassword = legacyData.get("developerDashboardPassword").getAsString();
                            if (legacyData.has("developerStressProfile")) newConfig.developerStressProfile = legacyData.get("developerStressProfile").getAsString();
                            if (legacyData.has("openclForced")) newConfig.openclForced = legacyData.get("openclForced").getAsBoolean();
                        } else {
                            // Nested format migration (from older nested versions)
                            if (legacyData.has("networking") && legacyData.get("networking").isJsonObject()) {
                                com.google.gson.JsonObject networking = legacyData.get("networking").getAsJsonObject();
                                if (networking.has("enableNetworking")) newConfig.enableNetworking = networking.get("enableNetworking").getAsBoolean();
                            }
                            if (legacyData.has("developerFeatures") && legacyData.get("developerFeatures").isJsonObject()) {
                                com.google.gson.JsonObject devFeatures = legacyData.get("developerFeatures").getAsJsonObject();
                                if (devFeatures.has("developerMode")) newConfig.developerMode = devFeatures.get("developerMode").getAsBoolean();
                            }
                            if (legacyData.has("developerDashboard") && legacyData.get("developerDashboard").isJsonObject()) {
                                com.google.gson.JsonObject dashboard = legacyData.get("developerDashboard").getAsJsonObject();
                                if (dashboard.has("developerDashboard")) newConfig.developerDashboard = dashboard.get("developerDashboard").getAsBoolean();
                                if (dashboard.has("developerDashboardPort")) newConfig.developerDashboardPort = dashboard.get("developerDashboardPort").getAsInt();
                                if (dashboard.has("developerDashboardHost")) newConfig.developerDashboardHost = dashboard.get("developerDashboardHost").getAsString();
                                if (dashboard.has("developerDashboardHttps")) newConfig.developerDashboardHttps = dashboard.get("developerDashboardHttps").getAsBoolean();
                                if (dashboard.has("developerDashboardAuth")) newConfig.developerDashboardAuth = dashboard.get("developerDashboardAuth").getAsBoolean();
                                if (dashboard.has("developerDashboardUsername")) newConfig.developerDashboardUsername = dashboard.get("developerDashboardUsername").getAsString();
                                if (dashboard.has("developerDashboardPassword")) newConfig.developerDashboardPassword = dashboard.get("developerDashboardPassword").getAsString();
                            }
                            if (legacyData.has("stressTesting") && legacyData.get("stressTesting").isJsonObject()) {
                                com.google.gson.JsonObject stress = legacyData.get("stressTesting").getAsJsonObject();
                                if (stress.has("developerStressProfile")) newConfig.developerStressProfile = stress.get("developerStressProfile").getAsString();
                            }
                            if (legacyData.has("gpu") && legacyData.get("gpu").isJsonObject()) {
                                com.google.gson.JsonObject gpu = legacyData.get("gpu").getAsJsonObject();
                                if (gpu.has("openclForced")) newConfig.openclForced = gpu.get("openclForced").getAsBoolean();
                            }
                        }

                        // Save new config
                        writePrettyJsonConfig(newConfig);

                        // Backup legacy file
                        File backupFile = new File(legacyPath + ".backup");
                        try (FileInputStream fis = new FileInputStream(legacyFile);
                             FileOutputStream fos = new FileOutputStream(backupFile)) {
                            fis.transferTo(fos);
                        }
                        LOGGER.info("Legacy config backed up to " + backupFile.getPath());
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to migrate legacy config", e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error during config migration", e);
        }
    }

    /**
     * Generates a pretty ADM-style JSON config file with an ASCII header,
     * grouped commented sections, and standard JSON fields.
     * This approach makes the config file self-documenting and easy for users to edit.
     */
    public static void writePrettyJsonConfig(Config config) {
        String path = "config/quantified/quantified_config.json";
        File configFile = new File(path);
        File parentDir = configFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        ConfigSchema schema = CONFIG_SCHEMA;
        java.util.Map<String, String> comments = schema.comments();
        java.util.LinkedHashMap<String, String[]> groups = schema.groups();

        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            // ASCII header
            w.println("/*");
            w.println("==============================================");
            w.println("|      Quantified API - Configuration        |");
            w.println("|      Author: Admany                        |");
            w.println("|      All Rights Reserved                   |");
            w.println("==============================================");
            w.println("|  This config file auto-updates itself if   |");
            w.println("|  there are config changes. You do NOT need |");
            w.println("|  to delete it for new features or updates! |");
            w.println("==============================================");
            w.println("*/");
            w.println();
            w.println("{");
            w.println("  \"_comment\": \"Edit this file to configure Quantified API. For documentation, visit: https://github.com/Admany/Quantified-API\",");
            w.println();

            // Build merged map from the provided config instance
            java.lang.reflect.Field[] fields = Config.class.getFields();
            java.util.Map<String, Object> mergedMap = new java.util.LinkedHashMap<>();
            for (java.lang.reflect.Field field : fields) {
                mergedMap.put(field.getName(), field.get(config));
            }

            java.util.List<String> jsonFieldLines = new java.util.ArrayList<>();
            java.util.List<String> outputLines = new java.util.ArrayList<>();

            for (String group : groups.keySet()) {
                outputLines.add("");
                outputLines.add(String.format("  // === %s ===", group));
                for (String name : groups.get(group)) {
                    if (!mergedMap.containsKey(name)) continue;
                    Object value = mergedMap.get(name);
                    String comment = comments.get(name);
                    if (comment != null) outputLines.add(String.format("  // %s", comment));
                    String valueStr = GSON.toJson(value);
                    String jsonLine = String.format("  \"%s\": %s", name, valueStr);
                    outputLines.add(jsonLine);
                    jsonFieldLines.add(jsonLine);
                }
            }

            // Experimental notice
            String experimentalLine = "  \"_experimental\": \"Some features are experimental. Use with caution!\"";
            outputLines.add("");
            outputLines.add(experimentalLine);
            jsonFieldLines.add(experimentalLine);

            // Write lines, adding commas only between JSON fields
            int jsonFieldIdx = 0;
            int jsonFieldCount = jsonFieldLines.size();
            java.util.Set<String> jsonFieldSet = new java.util.HashSet<>(jsonFieldLines);
            for (String line : outputLines) {
                if (jsonFieldSet.contains(line)) {
                    jsonFieldIdx++;
                    if (jsonFieldIdx < jsonFieldCount) w.println(line + ","); else w.println(line);
                } else {
                    w.println(line);
                }
            }

            w.println("}");

        } catch (Exception e) {
            LOGGER.error("Failed to write pretty config (ADM style)", e);
            // Fallback: write minimal config using gson
            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(config, writer);
            } catch (Exception ex) {
                LOGGER.error("Failed to write fallback config", ex);
            }
        }
    }

    public static void backupConfigFile(String path) {
        try {
            File configFile = new File(path);
            if (configFile.exists()) {
                File backupFile = new File(path + ".backup");
                try (FileInputStream fis = new FileInputStream(configFile);
                     FileOutputStream fos = new FileOutputStream(backupFile)) {
                    fis.transferTo(fos);
                }
                LOGGER.info("Config file backed up to " + backupFile.getPath());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to backup config file", e);
        }
    }

    public static void initializeGlobals(org.slf4j.Logger logger) {
        LOGGER = logger;
        CONFIG = loadOrCreateConfig(logger);
    }

    private static Config parseFlatConfig(com.google.gson.JsonObject jsonData) {
        Config config = new Config();

        try {
            // Parse all fields directly from the root JSON object (ADM-style flat format)
            config.logToConsole = extractBoxedBoolean(jsonData, "logToConsole", config.logToConsole);
            config.logLevel = extractBoxedString(jsonData, "logLevel", config.logLevel);

            config.enableNetworking = extractBoxedBoolean(jsonData, "enableNetworking", config.enableNetworking);
            config.networkTimeoutMs = extractBoxedInt(jsonData, "networkTimeoutMs", config.networkTimeoutMs);

            config.enableGpuAcceleration = extractBoxedBoolean(jsonData, "enableGpuAcceleration", config.enableGpuAcceleration);
            config.openclForced = extractBoxedBoolean(jsonData, "openclForced", config.openclForced);
            config.gpuDebugLogging = extractBoxedBoolean(jsonData, "gpuDebugLogging", config.gpuDebugLogging);
            config.openclDeviceId = extractBoxedString(jsonData, "openclDeviceId", config.openclDeviceId);

            config.taskQueueSize = extractBoxedInt(jsonData, "taskQueueSize", config.taskQueueSize);
            config.taskTimeoutMs = extractBoxedLong(jsonData, "taskTimeoutMs", config.taskTimeoutMs);

            config.developerMode = extractBoxedBoolean(jsonData, "developerMode", config.developerMode);
            config.developerTimeline = extractBoxedBoolean(jsonData, "developerTimeline", config.developerTimeline);
            config.developerReplay = extractBoxedBoolean(jsonData, "developerReplay", config.developerReplay);
            config.developerAutoHints = extractBoxedBoolean(jsonData, "developerAutoHints", config.developerAutoHints);
            config.developerStressTest = extractBoxedBoolean(jsonData, "developerStressTest", config.developerStressTest);
            config.developerModSpotlight = extractBoxedBoolean(jsonData, "developerModSpotlight", config.developerModSpotlight);

            config.developerDashboard = extractBoxedBoolean(jsonData, "developerDashboard", config.developerDashboard);
            config.developerDashboardPort = extractBoxedInt(jsonData, "developerDashboardPort", config.developerDashboardPort);
            config.developerDashboardHost = extractBoxedString(jsonData, "developerDashboardHost", config.developerDashboardHost);
            config.developerDashboardHttps = extractBoxedBoolean(jsonData, "developerDashboardHttps", config.developerDashboardHttps);
            config.developerDashboardAuth = extractBoxedBoolean(jsonData, "developerDashboardAuth", config.developerDashboardAuth);
            config.developerDashboardUsername = extractBoxedString(jsonData, "developerDashboardUsername", config.developerDashboardUsername);
            config.developerDashboardPassword = extractBoxedString(jsonData, "developerDashboardPassword", config.developerDashboardPassword);
            config.developerDashboardKeystorePath = extractBoxedString(jsonData, "developerDashboardKeystorePath", config.developerDashboardKeystorePath);
            config.developerDashboardKeystorePassword = extractBoxedString(jsonData, "developerDashboardKeystorePassword", config.developerDashboardKeystorePassword);
            config.dashboardSessionTimeoutMinutes = extractBoxedInt(jsonData, "dashboardSessionTimeoutMinutes", config.dashboardSessionTimeoutMinutes);

            config.developerStressProfile = extractBoxedString(jsonData, "developerStressProfile", config.developerStressProfile);
            config.stressTestCpuChunkMs = extractBoxedInt(jsonData, "stressTestCpuChunkMs", config.stressTestCpuChunkMs);

            config.enableMetrics = extractBoxedBoolean(jsonData, "enableMetrics", config.enableMetrics);
            config.metricsIntervalSeconds = extractBoxedInt(jsonData, "metricsIntervalSeconds", config.metricsIntervalSeconds);
            config.exportMetricsToFile = extractBoxedBoolean(jsonData, "exportMetricsToFile", config.exportMetricsToFile);
            config.metricsExportPath = extractBoxedString(jsonData, "metricsExportPath", config.metricsExportPath);
            config.maxMetricsHistoryHours = extractBoxedInt(jsonData, "maxMetricsHistoryHours", config.maxMetricsHistoryHours);

            config.enableSecurityChecks = extractBoxedBoolean(jsonData, "enableSecurityChecks", config.enableSecurityChecks);
            config.validateTaskInputs = extractBoxedBoolean(jsonData, "validateTaskInputs", config.validateTaskInputs);
            config.sandboxExternalCalls = extractBoxedBoolean(jsonData, "sandboxExternalCalls", config.sandboxExternalCalls);

            config.enableGcHints = extractBoxedBoolean(jsonData, "enableGcHints", config.enableGcHints);

            config.debug = extractBoxedBoolean(jsonData, "debug", config.debug);
            config.debugShowTimings = extractBoxedBoolean(jsonData, "debugShowTimings", config.debugShowTimings);
            config.debugShowThreading = extractBoxedBoolean(jsonData, "debugShowThreading", config.debugShowThreading);
            config.debugSaveToFile = extractBoxedBoolean(jsonData, "debugSaveToFile", config.debugSaveToFile);
            config.debugLogFile = extractBoxedString(jsonData, "debugLogFile", config.debugLogFile);
            config.debugMaxLogSizeMb = extractBoxedInt(jsonData, "debugMaxLogSizeMb", config.debugMaxLogSizeMb);

            config.enableExperimentalFeatures = extractBoxedBoolean(jsonData, "enableExperimentalFeatures", config.enableExperimentalFeatures);

        } catch (Exception e) {
            LOGGER.error("Failed to parse flat ADM-style config format", e);
            return null;
        }

        return config;
    }

    private static Config parseBoxedConfig(com.google.gson.JsonObject jsonData) {
        Config config = new Config();

        try {
            // Parse general settings
            if (jsonData.has("general") && jsonData.get("general").isJsonObject()) {
                com.google.gson.JsonObject general = jsonData.get("general").getAsJsonObject();
                config.logToConsole = extractBoxedBoolean(general, "logToConsole", config.logToConsole);
                config.logLevel = extractBoxedString(general, "logLevel", config.logLevel);
            }

            // Parse networking settings
            if (jsonData.has("networking") && jsonData.get("networking").isJsonObject()) {
                com.google.gson.JsonObject networking = jsonData.get("networking").getAsJsonObject();
                config.enableNetworking = extractBoxedBoolean(networking, "enableNetworking", config.enableNetworking);
                config.networkTimeoutMs = extractBoxedInt(networking, "networkTimeoutMs", config.networkTimeoutMs);
            }

            // Parse GPU settings
            if (jsonData.has("gpu") && jsonData.get("gpu").isJsonObject()) {
                com.google.gson.JsonObject gpu = jsonData.get("gpu").getAsJsonObject();
                config.enableGpuAcceleration = extractBoxedBoolean(gpu, "enableGpuAcceleration", config.enableGpuAcceleration);
                config.openclForced = extractBoxedBoolean(gpu, "openclForced", config.openclForced);
                config.gpuDebugLogging = extractBoxedBoolean(gpu, "gpuDebugLogging", config.gpuDebugLogging);
                config.openclDeviceId = extractBoxedString(gpu, "openclDeviceId", config.openclDeviceId);
            }

            // Parse task processing settings
            if (jsonData.has("taskProcessing") && jsonData.get("taskProcessing").isJsonObject()) {
                com.google.gson.JsonObject taskProcessing = jsonData.get("taskProcessing").getAsJsonObject();
                config.taskQueueSize = extractBoxedInt(taskProcessing, "taskQueueSize", config.taskQueueSize);
                config.taskTimeoutMs = extractBoxedLong(taskProcessing, "taskTimeoutMs", config.taskTimeoutMs);
            }

            // Parse developer features
            if (jsonData.has("developerFeatures") && jsonData.get("developerFeatures").isJsonObject()) {
                com.google.gson.JsonObject developerFeatures = jsonData.get("developerFeatures").getAsJsonObject();
                config.developerMode = extractBoxedBoolean(developerFeatures, "developerMode", config.developerMode);
                config.developerTimeline = extractBoxedBoolean(developerFeatures, "developerTimeline", config.developerTimeline);
                config.developerReplay = extractBoxedBoolean(developerFeatures, "developerReplay", config.developerReplay);
                config.developerAutoHints = extractBoxedBoolean(developerFeatures, "developerAutoHints", config.developerAutoHints);
                config.developerStressTest = extractBoxedBoolean(developerFeatures, "developerStressTest", config.developerStressTest);
                config.developerModSpotlight = extractBoxedBoolean(developerFeatures, "developerModSpotlight", config.developerModSpotlight);
            }

            // Parse developer dashboard
            if (jsonData.has("developerDashboard") && jsonData.get("developerDashboard").isJsonObject()) {
                com.google.gson.JsonObject developerDashboard = jsonData.get("developerDashboard").getAsJsonObject();
                config.developerDashboard = extractBoxedBoolean(developerDashboard, "developerDashboard", config.developerDashboard);
                config.developerDashboardPort = extractBoxedInt(developerDashboard, "developerDashboardPort", config.developerDashboardPort);
                config.developerDashboardHost = extractBoxedString(developerDashboard, "developerDashboardHost", config.developerDashboardHost);
                config.developerDashboardHttps = extractBoxedBoolean(developerDashboard, "developerDashboardHttps", config.developerDashboardHttps);
                config.developerDashboardAuth = extractBoxedBoolean(developerDashboard, "developerDashboardAuth", config.developerDashboardAuth);
                config.developerDashboardUsername = extractBoxedString(developerDashboard, "developerDashboardUsername", config.developerDashboardUsername);
                config.developerDashboardPassword = extractBoxedString(developerDashboard, "developerDashboardPassword", config.developerDashboardPassword);
                config.developerDashboardKeystorePath = extractBoxedString(developerDashboard, "developerDashboardKeystorePath", config.developerDashboardKeystorePath);
                config.developerDashboardKeystorePassword = extractBoxedString(developerDashboard, "developerDashboardKeystorePassword", config.developerDashboardKeystorePassword);
                config.dashboardSessionTimeoutMinutes = extractBoxedInt(developerDashboard, "dashboardSessionTimeoutMinutes", config.dashboardSessionTimeoutMinutes);
            }

            // Parse stress testing
            if (jsonData.has("stressTesting") && jsonData.get("stressTesting").isJsonObject()) {
                com.google.gson.JsonObject stressTesting = jsonData.get("stressTesting").getAsJsonObject();
                config.developerStressProfile = extractBoxedString(stressTesting, "developerStressProfile", config.developerStressProfile);
                config.stressTestCpuChunkMs = extractBoxedInt(stressTesting, "stressTestCpuChunkMs", config.stressTestCpuChunkMs);
            }

            // Parse monitoring
            if (jsonData.has("monitoring") && jsonData.get("monitoring").isJsonObject()) {
                com.google.gson.JsonObject monitoring = jsonData.get("monitoring").getAsJsonObject();
                config.enableMetrics = extractBoxedBoolean(monitoring, "enableMetrics", config.enableMetrics);
                config.metricsIntervalSeconds = extractBoxedInt(monitoring, "metricsIntervalSeconds", config.metricsIntervalSeconds);
                config.exportMetricsToFile = extractBoxedBoolean(monitoring, "exportMetricsToFile", config.exportMetricsToFile);
                config.metricsExportPath = extractBoxedString(monitoring, "metricsExportPath", config.metricsExportPath);
                config.maxMetricsHistoryHours = extractBoxedInt(monitoring, "maxMetricsHistoryHours", config.maxMetricsHistoryHours);
            }

            // Parse security
            if (jsonData.has("security") && jsonData.get("security").isJsonObject()) {
                com.google.gson.JsonObject security = jsonData.get("security").getAsJsonObject();
                config.enableSecurityChecks = extractBoxedBoolean(security, "enableSecurityChecks", config.enableSecurityChecks);
                config.validateTaskInputs = extractBoxedBoolean(security, "validateTaskInputs", config.validateTaskInputs);
                config.sandboxExternalCalls = extractBoxedBoolean(security, "sandboxExternalCalls", config.sandboxExternalCalls);
            }

            // Parse debug settings
            if (jsonData.has("debug") && jsonData.get("debug").isJsonObject()) {
                com.google.gson.JsonObject debug = jsonData.get("debug").getAsJsonObject();
                config.debug = extractBoxedBoolean(debug, "debug", config.debug);
                config.debugShowTimings = extractBoxedBoolean(debug, "debugShowTimings", config.debugShowTimings);
                config.debugShowThreading = extractBoxedBoolean(debug, "debugShowThreading", config.debugShowThreading);
                config.debugSaveToFile = extractBoxedBoolean(debug, "debugSaveToFile", config.debugSaveToFile);
                config.debugLogFile = extractBoxedString(debug, "debugLogFile", config.debugLogFile);
                config.debugMaxLogSizeMb = extractBoxedInt(debug, "debugMaxLogSizeMb", config.debugMaxLogSizeMb);
            }

            // Parse fixes
            if (jsonData.has("fixes") && jsonData.get("fixes").isJsonObject()) {
                com.google.gson.JsonObject fixes = jsonData.get("fixes").getAsJsonObject();
                config.enableGcHints = extractBoxedBoolean(fixes, "enableGcHints", config.enableGcHints);
            }

            // Parse experimental features
            if (jsonData.has("experimental") && jsonData.get("experimental").isJsonObject()) {
                com.google.gson.JsonObject experimental = jsonData.get("experimental").getAsJsonObject();
                config.enableExperimentalFeatures = extractBoxedBoolean(experimental, "enableExperimentalFeatures", config.enableExperimentalFeatures);
                config.experimentalAsyncBatching = extractBoxedBoolean(experimental, "experimentalAsyncBatching", config.experimentalAsyncBatching);
                config.experimentalMemoryPooling = extractBoxedBoolean(experimental, "experimentalMemoryPooling", config.experimentalMemoryPooling);
                config.experimentalPredictiveScaling = extractBoxedBoolean(experimental, "experimentalPredictiveScaling", config.experimentalPredictiveScaling);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to parse boxed config format", e);
            return null;
        }

        return config;
    }

    private static String extractBoxedString(com.google.gson.JsonObject parent, String key, String defaultValue) {
        if (parent.has(key)) {
            com.google.gson.JsonElement element = parent.get(key);
            if (element.isJsonObject()) {
                com.google.gson.JsonObject box = element.getAsJsonObject();
                if (box.has("value")) {
                    return box.get("value").getAsString();
                }
            } else if (element.isJsonPrimitive()) {
                return element.getAsString();
            }
        }
        return defaultValue;
    }

    private static boolean extractBoxedBoolean(com.google.gson.JsonObject parent, String key, boolean defaultValue) {
        if (parent.has(key)) {
            com.google.gson.JsonElement element = parent.get(key);
            if (element.isJsonObject()) {
                com.google.gson.JsonObject box = element.getAsJsonObject();
                if (box.has("value")) {
                    return box.get("value").getAsBoolean();
                }
            } else if (element.isJsonPrimitive()) {
                return element.getAsBoolean();
            }
        }
        return defaultValue;
    }

    private static int extractBoxedInt(com.google.gson.JsonObject parent, String key, int defaultValue) {
        if (parent.has(key)) {
            com.google.gson.JsonElement element = parent.get(key);
            if (element.isJsonObject()) {
                com.google.gson.JsonObject box = element.getAsJsonObject();
                if (box.has("value")) {
                    return box.get("value").getAsInt();
                }
            } else if (element.isJsonPrimitive()) {
                return element.getAsInt();
            }
        }
        return defaultValue;
    }

    private static long extractBoxedLong(com.google.gson.JsonObject parent, String key, long defaultValue) {
        if (parent.has(key)) {
            com.google.gson.JsonElement element = parent.get(key);
            if (element.isJsonObject()) {
                com.google.gson.JsonObject box = element.getAsJsonObject();
                if (box.has("value")) {
                    return box.get("value").getAsLong();
                }
            } else if (element.isJsonPrimitive()) {
                return element.getAsLong();
            }
        }
        return defaultValue;
    }

    public static ConfigLayout configLayout() {
        java.util.Map<String, String> commentsCopy = new java.util.LinkedHashMap<>(CONFIG_SCHEMA.comments());
        java.util.Map<String, String> labelsCopy = new java.util.LinkedHashMap<>(CONFIG_SCHEMA.displayNames());
        java.util.LinkedHashMap<String, String[]> groupsCopy = new java.util.LinkedHashMap<>();
        CONFIG_SCHEMA.groups().forEach((key, value) -> groupsCopy.put(key, value.clone()));
        return new ConfigLayout(
            java.util.Collections.unmodifiableMap(commentsCopy),
            java.util.Collections.unmodifiableMap(groupsCopy),
            java.util.Collections.unmodifiableMap(labelsCopy)
        );
    }

    private static ConfigSchema buildConfigSchema() {
        java.util.Map<String, String> comments = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        describeField(comments, labels, "logToConsole", "Console Logging", "Write Quantified API activity into the Minecraft log / console so you can follow along.");
        describeField(comments, labels, "logLevel", "Log Level", "Choose how chatty the logger should be (TRACE, DEBUG, INFO, WARN, ERROR).");
        describeField(comments, labels, "enableNetworking", "Networking Support", "Allow the API to talk to remote services or other mods.");
        describeField(comments, labels, "networkTimeoutMs", "Network Timeout", "How long to wait (in ms) before a network request is considered dead.");
        describeField(comments, labels, "enableGpuAcceleration", "GPU Acceleration", "Let Quantified push compatible workloads onto the GPU whenever possible.");
        describeField(comments, labels, "openclForced", "Force OpenCL", "Always route compute through OpenCL even when the CPU would normally be chosen.");
        describeField(comments, labels, "gpuDebugLogging", "GPU Debug Logging", "Emit detailed OpenCL/GPU traces to help troubleshoot rendering or compute issues.");
        describeField(comments, labels, "openclDeviceId", "OpenCL Device", "Select which OpenCL device to use. Use auto to let Quantified pick the fastest GPU.");
        describeField(comments, labels, "parallelMaxThreads", "Parallel Threads", "Upper bound for threads dedicated to the parallel compute pool.");
        describeField(comments, labels, "parallelQueueLimit", "Parallel Queue Limit", "Maximum number of queued parallel slices across all mods.");
        describeField(comments, labels, "parallelMaxSlicesPerMod", "Parallel Slices Per Mod", "Per-mod ceiling for simultaneously running slices.");
        describeField(comments, labels, "parallelFailurePolicy", "Parallel Failure Policy", "Default failure policy for parallel batches (FAIL_FAST or BEST_EFFORT).");
        describeField(comments, labels, "taskQueueSize", "Task Queue Size", "Maximum number of queued tasks before new ones are rejected.");
        describeField(comments, labels, "taskTimeoutMs", "Task Timeout", "Upper limit (in ms) for how long a single task may run.");
        describeField(comments, labels, "developerMode", "Developer Mode", "Unlock developer-only UI elements and tooling.");
        describeField(comments, labels, "developerTimeline", "Timeline Capture", "Record execution timeline samples so you can scrub through performance history.");
        describeField(comments, labels, "developerReplay", "Replay Recorder", "Keep telemetry frames so you can replay them later for diagnostics.");
        describeField(comments, labels, "developerStressTest", "Stress Tester", "Expose the stress test harness that hammers the scheduler.");
        describeField(comments, labels, "developerModSpotlight", "Mod Spotlight", "Highlight verbose or busy mods directly in diagnostics.");
        describeField(comments, labels, "developerDashboard", "Web Dashboard", "Serve the browser-based dashboard for realtime control.");
        describeField(comments, labels, "developerDashboardPort", "Dashboard Port", "TCP port that the dashboard server binds to.");
        describeField(comments, labels, "developerDashboardHost", "Dashboard Host", "Interface/IP address the dashboard listens on (127.0.0.1 keeps it local).");
        describeField(comments, labels, "developerDashboardHttps", "Dashboard HTTPS", "Serve the dashboard over HTTPS using your supplied keystore.");
        describeField(comments, labels, "developerDashboardAuth", "Dashboard Login", "Require credentials before anyone can access the dashboard.");
        describeField(comments, labels, "developerDashboardUsername", "Dashboard Username", "Username used to authenticate when dashboard login is enabled.");
        describeField(comments, labels, "developerDashboardPassword", "Dashboard Password", "Password paired with the username above.");
        describeField(comments, labels, "developerDashboardKeystorePath", "Dashboard Keystore", "Path to the JKS/PKCS12 keystore used to enable HTTPS.");
        describeField(comments, labels, "developerDashboardKeystorePassword", "Keystore Password", "Password required to unlock the HTTPS keystore.");
        describeField(comments, labels, "dashboardSessionTimeoutMinutes", "Dashboard Session Timeout", "Idle minutes before an authenticated dashboard session expires.");
        describeField(comments, labels, "developerStressProfile", "Stress Profile", "Preset used whenever the stress tester spins up.");
        describeField(comments, labels, "stressTestCpuChunkMs", "CPU Chunk Duration", "How long each CPU stress pass should run (milliseconds).");
        describeField(comments, labels, "enableMetrics", "Metrics Collection", "Track runtime metrics so you can review history later.");
        describeField(comments, labels, "metricsIntervalSeconds", "Metrics Interval", "Seconds between automatic metric samples.");
        describeField(comments, labels, "exportMetricsToFile", "Export Metrics", "Write collected metrics to JSON files on disk.");
        describeField(comments, labels, "metricsExportPath", "Metrics Folder", "Where exported metric files should be saved.");
        describeField(comments, labels, "maxMetricsHistoryHours", "Metrics History", "How many hours of metrics the game should remember.");
        describeField(comments, labels, "enableSecurityChecks", "Security Checks", "Run extra validation to avoid unsafe or malicious workloads.");
        describeField(comments, labels, "validateTaskInputs", "Validate Task Inputs", "Double-check each task payload before it runs.");
        describeField(comments, labels, "sandboxExternalCalls", "Sandbox External Calls", "Wrap risky system or process calls in a safer sandbox.");
        describeField(comments, labels, "enableGcHints", "GC Hints", "Log JVM garbage-collection hints to help with tuning.");
        describeField(comments, labels, "debug", "Debug Logging", "Enable verbose debug logging globally.");
        describeField(comments, labels, "debugShowTimings", "Show Timings", "Dump timing breakdowns for core operations.");
        describeField(comments, labels, "debugShowThreading", "Show Threading", "Log thread creation/destruction details.");
        describeField(comments, labels, "debugSaveToFile", "Debug File Output", "Mirror debug output to disk.");
        describeField(comments, labels, "debugLogFile", "Debug Log File", "Path to the debug log file.");
        describeField(comments, labels, "debugMaxLogSizeMb", "Debug Log Size", "Max size (in MB) before the debug log rolls.");
        describeField(comments, labels, "enableExperimentalFeatures", "Experimental Features", "Unlock bleeding-edge features that may change or break.");

        java.util.LinkedHashMap<String, String[]> groups = new java.util.LinkedHashMap<>();
        groups.put("General Settings", new String[]{"logToConsole", "logLevel"});
        groups.put("Networking Configuration", new String[]{"enableNetworking", "networkTimeoutMs"});
        groups.put("GPU / OpenCL", new String[]{"enableGpuAcceleration", "openclForced", "gpuDebugLogging", "openclDeviceId"});
        groups.put("Parallel Execution", new String[]{"parallelMaxThreads", "parallelQueueLimit", "parallelMaxSlicesPerMod", "parallelFailurePolicy"});
        groups.put("Task Processing", new String[]{"taskQueueSize", "taskTimeoutMs"});
        groups.put("Developer Features", new String[]{"developerMode", "developerTimeline", "developerReplay", "developerStressTest", "developerModSpotlight"});
        groups.put("Developer Dashboard", new String[]{"developerDashboard", "developerDashboardPort", "developerDashboardHost", "developerDashboardHttps", "developerDashboardAuth", "developerDashboardUsername", "developerDashboardPassword", "developerDashboardKeystorePath", "developerDashboardKeystorePassword", "dashboardSessionTimeoutMinutes"});
        groups.put("Stress Testing", new String[]{"developerStressProfile", "stressTestCpuChunkMs"});
        groups.put("Monitoring & Metrics", new String[]{"enableMetrics", "metricsIntervalSeconds", "exportMetricsToFile", "metricsExportPath", "maxMetricsHistoryHours"});
        groups.put("Security & Safety", new String[]{"enableSecurityChecks", "validateTaskInputs", "sandboxExternalCalls"});
        groups.put("Fixes", new String[]{"enableGcHints"});
        groups.put("Debug Settings", new String[]{"debug", "debugShowTimings", "debugShowThreading", "debugSaveToFile", "debugLogFile", "debugMaxLogSizeMb"});
        groups.put("Experimental Features", new String[]{"enableExperimentalFeatures"});
        return new ConfigSchema(
            java.util.Collections.unmodifiableMap(comments),
            groups,
            java.util.Collections.unmodifiableMap(labels)
        );
    }

    private static void describeField(
        java.util.Map<String, String> comments,
        java.util.Map<String, String> labels,
        String key,
        String label,
        String description
    ) {
        comments.put(key, description);
        labels.put(key, label);
    }

    private record ConfigSchema(
        java.util.Map<String, String> comments,
        java.util.LinkedHashMap<String, String[]> groups,
        java.util.Map<String, String> displayNames
    ) {
    }

    public record ConfigLayout(
        java.util.Map<String, String> comments,
        java.util.Map<String, String[]> groups,
        java.util.Map<String, String> displayNames
    ) {
    }
}
