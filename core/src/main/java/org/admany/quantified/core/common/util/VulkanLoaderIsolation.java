package org.admany.quantified.core.common.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class VulkanLoaderIsolation {

    private static final Object LOCK = new Object();
    private static final String DISABLE_KEY = "VK_LOADER_LAYERS_DISABLE";
    private static final String DISABLE_IMPLICIT_VALUE = "~implicit~";
    private static final String[] CLEAR_KEYS = {
        "VK_INSTANCE_LAYERS",
        "VK_LOADER_LAYERS_ENABLE",
        "VK_LOADER_LAYERS_ALLOW"
    };

    private VulkanLoaderIsolation() {
    }

    public static <T> T runWithImplicitLayersDisabled(Logger logger, String reason, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (!isSupportedPlatform()) {
            return action.get();
        }
        synchronized (LOCK) {
            Map<String, String> previous = new LinkedHashMap<>();
            boolean applied = false;
            try {
                previous.put(DISABLE_KEY, System.getenv(DISABLE_KEY));
                String disableValue = mergeDisableValue(previous.get(DISABLE_KEY));
                if (!setEnv(DISABLE_KEY, disableValue)) {
                    if (logger != null) {
                        logger.warn("[Vulkan] Failed to set {} for {}", DISABLE_KEY, reason);
                    }
                    return action.get();
                }
                for (String key : CLEAR_KEYS) {
                    previous.put(key, System.getenv(key));
                    if (!clearEnv(key) && logger != null) {
                        logger.warn("[Vulkan] Failed to clear {} for {}", key, reason);
                    }
                }
                applied = true;
                if (logger != null) {
                    logger.info("[Vulkan] Suppressing implicit Vulkan layers for {} using {}={}",
                        reason, DISABLE_KEY, disableValue);
                }
                return action.get();
            } finally {
                if (applied) {
                    restore(previous, logger, reason);
                }
            }
        }
    }

    private static void restore(Map<String, String> previous, Logger logger, String reason) {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            boolean restored = entry.getValue() == null
                ? clearEnv(entry.getKey())
                : setEnv(entry.getKey(), entry.getValue());
            if (!restored && logger != null) {
                logger.warn("[Vulkan] Failed to restore {} after {}", entry.getKey(), reason);
            }
        }
    }

    private static String mergeDisableValue(String existing) {
        if (existing == null || existing.isBlank()) {
            return DISABLE_IMPLICIT_VALUE;
        }
        String trimmed = existing.trim();
        if (trimmed.contains(DISABLE_IMPLICIT_VALUE)) {
            return trimmed;
        }
        return DISABLE_IMPLICIT_VALUE + "," + trimmed;
    }

    private static boolean isSupportedPlatform() {
        return Platform.isWindows() || Platform.isLinux() || Platform.isMac();
    }

    private static boolean setEnv(String key, String value) {
        if (Platform.isWindows()) {
            return WindowsKernel32.INSTANCE.SetEnvironmentVariableW(key, value);
        }
        return PosixLib.INSTANCE.setenv(key, value, 1) == 0;
    }

    private static boolean clearEnv(String key) {
        if (Platform.isWindows()) {
            return WindowsKernel32.INSTANCE.SetEnvironmentVariableW(key, null);
        }
        return PosixLib.INSTANCE.unsetenv(key) == 0;
    }

    private interface WindowsKernel32 extends StdCallLibrary {
        WindowsKernel32 INSTANCE = Native.load("kernel32", WindowsKernel32.class);

        boolean SetEnvironmentVariableW(String lpName, String lpValue);
    }

    private interface PosixLib extends Library {
        PosixLib INSTANCE = Native.load(Platform.C_LIBRARY_NAME, PosixLib.class);

        int setenv(String name, String value, int overwrite);

        int unsetenv(String name);
    }
}
