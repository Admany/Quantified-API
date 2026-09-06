package org.admany.quantified.core.common.config;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.LogManager;

/** Applies the console logging settings shared by all Quantified loaders. */
public final class QuantifiedLogging {
    private static final String LOGGER_NAMESPACE = "org.admany.quantified";
    private static final java.util.regex.Pattern CONSOLE_PATTERN =
        java.util.regex.Pattern.compile("(?m)^\\s*\"logToConsole\"\\s*:\\s*(true|false)\\s*,?\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern LEVEL_PATTERN =
        java.util.regex.Pattern.compile("(?m)^\\s*\"logLevel\"\\s*:\\s*\"([^\"]+)\"\\s*,?\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private QuantifiedLogging() {
    }

    /** Apply values already loaded into the shared QAPI config. */
    public static void configure(Logger bootstrapLogger, MultithreadingConfig.Config config) {
        boolean enabled = config == null || config.logToConsole;
        String level = config == null ? "INFO" : config.logLevel;
        apply(bootstrapLogger, enabled, level);
    }

    /** Apply the saved file before bootstrap emits its first status line. */
    public static void configureFromFile(Path configRoot) {
        if (configRoot == null) {
            return;
        }
        Path[] candidates = {
            configRoot.resolve("QuantifiedAPI").resolve("quantified_config.json"),
            configRoot.resolve("quantified").resolve("quantified_config.json"),
            configRoot.getParent() == null ? null
                : configRoot.getParent().resolve("QuantifiedAPI").resolve("quantified_config.json")
        };
        for (Path candidate : candidates) {
            if (candidate == null || !Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                String text = Files.readString(candidate, StandardCharsets.UTF_8);
                java.util.regex.Matcher console = CONSOLE_PATTERN.matcher(text);
                java.util.regex.Matcher level = LEVEL_PATTERN.matcher(text);
                boolean enabled = !console.find() || Boolean.parseBoolean(console.group(1));
                String configuredLevel = level.find() ? level.group(1) : "INFO";
                apply(null, enabled, configuredLevel);
            } catch (IOException ignored) {
                // The normal config loader will report a malformed/unreadable file.
            }
            return;
        }
    }

    private static void apply(Logger bootstrapLogger, boolean enabled, String configuredLevel) {
        String levelName = normalize(configuredLevel);
        java.util.logging.Level julLevel = julLevel(levelName, enabled);
        configureJul(julLevel);
        configureLog4j(enabled ? levelName : "OFF");
        if (bootstrapLogger != null && bootstrapLogger.isDebugEnabled()) {
            bootstrapLogger.debug("Quantified logging set to {} ({})", enabled ? "on" : "off", levelName);
        }
    }

    private static String normalize(String configuredLevel) {
        if (configuredLevel == null) {
            return "INFO";
        }
        return switch (configuredLevel.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE" -> "TRACE";
            case "DEBUG" -> "DEBUG";
            case "WARN", "WARNING" -> "WARN";
            case "ERROR" -> "ERROR";
            default -> "INFO";
        };
    }

    private static java.util.logging.Level julLevel(String levelName, boolean enabled) {
        if (!enabled) {
            return java.util.logging.Level.OFF;
        }
        return switch (levelName) {
            case "TRACE" -> java.util.logging.Level.FINEST;
            case "DEBUG" -> java.util.logging.Level.FINE;
            case "WARN" -> java.util.logging.Level.WARNING;
            case "ERROR" -> java.util.logging.Level.SEVERE;
            default -> java.util.logging.Level.INFO;
        };
    }

    private static void configureJul(java.util.logging.Level level) {
        java.util.logging.Logger namespace = java.util.logging.Logger.getLogger(LOGGER_NAMESPACE);
        namespace.setLevel(level);
        Enumeration<String> names = LogManager.getLogManager().getLoggerNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null || (!name.equals(LOGGER_NAMESPACE) && !name.startsWith(LOGGER_NAMESPACE + "."))) {
                continue;
            }
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger(name);
            logger.setLevel(level);
            for (Handler handler : logger.getHandlers()) {
                handler.setLevel(level);
            }
        }
    }

    private static void configureLog4j(String levelName) {
        try {
            Class<?> configurator = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Object level = levelClass.getField(levelName).get(null);
            configurator.getMethod("setLevel", String.class, levelClass)
                .invoke(null, LOGGER_NAMESPACE, level);
        } catch (Throwable ignored) {
            // Log4j core is optional in the common module; JUL still follows the setting.
        }
    }
}
