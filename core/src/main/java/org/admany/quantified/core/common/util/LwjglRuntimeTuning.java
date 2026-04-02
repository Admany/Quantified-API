package org.admany.quantified.core.common.util;

import org.lwjgl.system.Configuration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class LwjglRuntimeTuning {

    private static final Logger LOGGER = Logger.getLogger(LwjglRuntimeTuning.class.getName());
    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;
    private static final int DEFAULT_STACK_SIZE_BYTES = 64 * MIB;
    private static final int DEFAULT_GPU_THREAD_STACK_BYTES = 64 * MIB;
    private static final int DEFAULT_PROBE_THREAD_STACK_BYTES = 64 * MIB;
    private static final AtomicBoolean CONFIGURED = new AtomicBoolean(false);

    private LwjglRuntimeTuning() {
    }

    public static int ensureConfigured() {
        int stackSizeBytes = maxBytes(
            DEFAULT_STACK_SIZE_BYTES,
            parseBytesProperty("quantified.lwjgl.stackSizeBytes"),
            parseKilobytesProperty("quantified.lwjgl.stackSizeKb"),
            parseKilobytesProperty("org.lwjgl.system.stackSize")
        );
        if (!CONFIGURED.compareAndSet(false, true)) {
            return stackSizeBytes;
        }
        int stackSizeKb = bytesToKb(stackSizeBytes);
        System.setProperty("quantified.lwjgl.stackSizeBytes", Integer.toString(stackSizeBytes));
        System.setProperty("quantified.lwjgl.stackSizeKb", Integer.toString(stackSizeKb));
        System.setProperty("org.lwjgl.system.stackSize", Integer.toString(stackSizeKb));
        try {
            Configuration.STACK_SIZE.set(stackSizeKb);
        } catch (Throwable ignored) {
        }
        LOGGER.info("[LwjglRuntimeTuning] Configured LWJGL stack size to "
            + (stackSizeBytes / MIB) + " MiB (" + stackSizeKb + " KiB)");
        return stackSizeBytes;
    }

    public static int gpuThreadStackSizeKb() {
        return maxBytes(
            DEFAULT_GPU_THREAD_STACK_BYTES,
            parseBytesProperty("quantified.gpuThreadStackBytes"),
            parsePossiblyLegacyStackSize("quantified.gpuThreadStackKb"),
            ensureConfigured()
        );
    }

    public static int probeThreadStackSizeKb() {
        return maxBytes(
            DEFAULT_PROBE_THREAD_STACK_BYTES,
            parseBytesProperty("quantified.probeThreadStackBytes"),
            parsePossiblyLegacyStackSize("quantified.probeThreadStackKb"),
            gpuThreadStackSizeKb()
        );
    }

    public static Thread newDaemonThread(Runnable runnable, String name, int stackSizeBytes) {
        long resolvedStackSizeBytes = Math.max(256L * 1024L, Math.max((long) ensureConfigured(), stackSizeBytes));
        LOGGER.info("[LwjglRuntimeTuning] Creating daemon thread '" + name + "' stack="
            + (resolvedStackSizeBytes / MIB) + " MiB");
        Thread thread = new Thread(null, runnable, name, resolvedStackSizeBytes);
        thread.setDaemon(true);
        return thread;
    }

    private static int maxBytes(int baseline, Integer... candidates) {
        int value = baseline;
        if (candidates != null) {
            for (Integer candidate : candidates) {
                if (candidate != null) {
                    value = Math.max(value, candidate);
                }
            }
        }
        return value;
    }

    private static Integer parseBytesProperty(String key) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseKilobytesProperty(String key) {
        Integer parsed = parseBytesProperty(key);
        if (parsed == null) {
            return null;
        }
        return Math.max(parsed, 1) * KIB;
    }

    private static Integer parsePossiblyLegacyStackSize(String key) {
        Integer parsed = parseBytesProperty(key);
        if (parsed == null) {
            return null;
        }
        if (parsed < MIB) {
            return parsed * KIB;
        }
        return parsed;
    }

    public static int bytesToKb(int bytes) {
        long safeBytes = Math.max(bytes, KIB);
        long rounded = (safeBytes + (KIB - 1L)) / KIB;
        return (int) Math.min(Integer.MAX_VALUE, rounded);
    }
}
