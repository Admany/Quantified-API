package org.admany.quantified.core.common.commands;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperFeatures;
import org.admany.quantified.core.common.dev.web.DeveloperDashboardServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantifiedCommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCommandService.class);
    private static final int DEFAULT_DASHBOARD_PORT = 8765;
    private static final String DEFAULT_DASHBOARD_HOST = "127.0.0.1";

    private QuantifiedCommandService() {
    }

    public static DashboardResult enableDashboard() {
        MultithreadingConfig.Config config = ensureConfig();
        sanitizeDashboardConfig(config);

        config.developerMode = true;
        config.developerDashboard = true;
        DeveloperFeatures.setDeveloperMode(true, false);
        DeveloperFeatures.setDashboardEnabled(true, false);

        if (!DeveloperDashboardServer.isRunning()) {
            DeveloperDashboardServer.applyConfiguration(true, config.developerDashboardPort, config.developerDashboardHost,
                config.developerDashboardHttps, config.developerDashboardAuth);
        }

        if (!DeveloperDashboardServer.isRunning()) {
            String reason = DeveloperDashboardServer.lastStartFailure();
            if (reason.isBlank()) {
                reason = "dashboard server did not report a bound socket";
            }
            LOGGER.warn("Developer dashboard command failed to start server: {}", reason);
            return DashboardResult.failure("Developer dashboard failed to start: " + reason);
        }

        persist(config);
        return DashboardResult.success("Developer dashboard online", dashboardUrl());
    }

    public static DashboardResult enableDashboardForCommand() {
        MultithreadingConfig.Config config = ensureConfig();
        sanitizeDashboardConfig(config);

        config.developerDashboardHost = DEFAULT_DASHBOARD_HOST;
        config.developerDashboardHttps = false;
        config.developerDashboardAuth = false;
        config.developerMode = true;
        config.developerDashboard = true;

        LOGGER.info("Dashboard command startup requested: host={}, port={}, https={}, auth={}",
            config.developerDashboardHost, config.developerDashboardPort, config.developerDashboardHttps, config.developerDashboardAuth);

        DeveloperFeatures.setDeveloperMode(true, false);
        DeveloperFeatures.setDashboardEnabled(true, false);

        LOGGER.info("Dashboard command forcing interactive startup on {}:{} over HTTP without auth",
            config.developerDashboardHost, config.developerDashboardPort);
        DeveloperDashboardServer.applyConfiguration(false, config.developerDashboardPort, config.developerDashboardHost,
            false, false);
        DeveloperDashboardServer.applyConfiguration(true, config.developerDashboardPort, config.developerDashboardHost,
            false, false);

        if (!DeveloperDashboardServer.isRunning()) {
            String reason = DeveloperDashboardServer.lastStartFailure();
            if (reason.isBlank()) {
                reason = "dashboard server did not report a bound socket";
            }
            LOGGER.warn("Developer dashboard command failed to start server: {}", reason);
            return DashboardResult.failure("Developer dashboard failed to start: " + reason);
        }

        LOGGER.info("Dashboard command startup succeeded: boundHost={}, boundPort={}, url={}",
            DeveloperDashboardServer.boundHost(), DeveloperDashboardServer.boundPort(), dashboardUrl());

        persist(config);
        return DashboardResult.success("Developer dashboard online", dashboardUrl());
    }

    public static DashboardResult disableDashboard() {
        MultithreadingConfig.Config config = ensureConfig();
        config.developerDashboard = false;
        config.developerMode = false;

        DeveloperFeatures.setDashboardEnabled(false, false);
        DeveloperFeatures.setDeveloperMode(false, false);
        DeveloperDashboardServer.applyConfiguration(false, config.developerDashboardPort, config.developerDashboardHost,
            config.developerDashboardHttps, config.developerDashboardAuth);

        persist(config);
        return DashboardResult.success("Developer dashboard disabled", "");
    }

    public static String dashboardUrl() {
        String protocol = MultithreadingConfig.CONFIG != null && MultithreadingConfig.CONFIG.developerDashboardHttps
            ? "https"
            : "http";
        String host = normalizeDashboardLinkHost(DeveloperDashboardServer.boundHost());
        int port = DeveloperDashboardServer.boundPort();
        if (port < 0 && MultithreadingConfig.CONFIG != null) {
            port = MultithreadingConfig.CONFIG.developerDashboardPort;
        }
        return protocol + "://" + host + ":" + port + "/";
    }

    private static MultithreadingConfig.Config ensureConfig() {
        if (MultithreadingConfig.CONFIG == null) {
            MultithreadingConfig.CONFIG = new MultithreadingConfig.Config();
        }
        return MultithreadingConfig.CONFIG;
    }

    private static void sanitizeDashboardConfig(MultithreadingConfig.Config config) {
        if (config.developerDashboardPort <= 0 || config.developerDashboardPort > 65535) {
            config.developerDashboardPort = DEFAULT_DASHBOARD_PORT;
        }
        if (config.developerDashboardHost == null || config.developerDashboardHost.isBlank()) {
            config.developerDashboardHost = DEFAULT_DASHBOARD_HOST;
        }
    }

    private static String normalizeDashboardLinkHost(String host) {
        if (host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("::")) {
            return DEFAULT_DASHBOARD_HOST;
        }
        return host;
    }

    private static void persist(MultithreadingConfig.Config config) {
        try {
            MultithreadingConfig.writePrettyJsonConfig(config);
        } catch (Throwable t) {
            LOGGER.warn("Failed to persist Quantified command config change", t);
        }
    }

    public record DashboardResult(boolean success, String message, String url) {
        private static DashboardResult success(String message, String url) {
            return new DashboardResult(true, message, url == null ? "" : url);
        }

        private static DashboardResult failure(String message) {
            return new DashboardResult(false, message, "");
        }
    }
}
