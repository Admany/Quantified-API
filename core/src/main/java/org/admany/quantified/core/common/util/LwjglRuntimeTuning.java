package org.admany.quantified.core.common.util;

import org.admany.quantified.core.common.threading.core.WorkerClassLoaderContext;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
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
    private static final AtomicBoolean THREAD_STACK_REFLECTION_WARNING_LOGGED = new AtomicBoolean(false);
    private static volatile ThreadLocal<MemoryStack> memoryStackThreadLocal;

    static {
        prefetchStackSizeProperties();
    }

    private LwjglRuntimeTuning() {
    }

    private static void prefetchStackSizeProperties() {
        int stackSizeBytes = maxBytes(
            DEFAULT_STACK_SIZE_BYTES,
            parseBytesProperty("quantified.lwjgl.stackSizeBytes"),
            parseKilobytesProperty("quantified.lwjgl.stackSizeKb"),
            parseKilobytesProperty("org.lwjgl.system.stackSize")
        );
        int stackSizeKb = bytesToKb(stackSizeBytes);
        System.setProperty("quantified.lwjgl.stackSizeBytes", Integer.toString(stackSizeBytes));
        System.setProperty("quantified.lwjgl.stackSizeKb", Integer.toString(stackSizeKb));
        System.setProperty("org.lwjgl.system.stackSize", Integer.toString(stackSizeKb));
    }

    public static MemoryStack pushMemoryStack() {
        ensureConfigured();
        ensureThreadLocalStack();
        return MemoryStack.stackPush();
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

    public static void ensureThreadLocalStack() {
        int desiredBytes = ensureConfigured();
        try {
            ThreadLocal<MemoryStack> threadLocal = memoryStackThreadLocal();
            MemoryStack current = threadLocal.get();
            if (current != null && current.getSize() >= desiredBytes) {
                return;
            }
            if (current != null && current.getFrameIndex() != 0) {
                if (THREAD_STACK_REFLECTION_WARNING_LOGGED.compareAndSet(false, true)) {
                    LOGGER.warning("[LwjglRuntimeTuning] Cannot replace active LWJGL MemoryStack on thread '"
                        + Thread.currentThread().getName() + "'; current=" + current.getSize()
                        + " bytes, desired=" + desiredBytes + " bytes");
                }
                return;
            }
            threadLocal.set(MemoryStack.create(desiredBytes));
            LOGGER.info("[LwjglRuntimeTuning] Installed thread-local LWJGL MemoryStack for '"
                + Thread.currentThread().getName() + "' size=" + (desiredBytes / MIB) + " MiB");
        } catch (Throwable throwable) {
            if (THREAD_STACK_REFLECTION_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warning("[LwjglRuntimeTuning] Failed to install thread-local LWJGL MemoryStack: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<MemoryStack> memoryStackThreadLocal() throws ReflectiveOperationException {
        ThreadLocal<MemoryStack> cached = memoryStackThreadLocal;
        if (cached != null) {
            return cached;
        }
        Field field = MemoryStack.class.getDeclaredField("TLS");
        field.setAccessible(true);
        ThreadLocal<MemoryStack> resolved = (ThreadLocal<MemoryStack>) field.get(null);
        memoryStackThreadLocal = resolved;
        return resolved;
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
        ClassLoader classLoader = WorkerClassLoaderContext.get();
        if (classLoader != null) {
            thread.setContextClassLoader(classLoader);
        }
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

    public static void addModernJvmCompatArgs(java.util.List<String> command) {
        int feature = Runtime.version().feature();
        if (feature >= 22) {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
        if (feature >= 24) {
            command.add("--sun-misc-unsafe-memory-access=allow");
        }
    }

    /**
     * Keeps probe subprocesses on the embedded LWJGL jars instead of inheriting the game's
     * {@code java.library.path}, which on MC 26.x points at a different LWJGL version.
     */
    public static void addIsolatedProbeNativeExtractPath(java.util.List<String> command, Path extractRoot) {
        Path nativesDir = extractRoot.resolve("natives");
        command.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativesDir.toAbsolutePath());
        // Some stripped dedicated-server launchers expose LWJGL classes but do
        // not let the isolated JVM discover native jars on its classpath. The
        // Linux probe explicitly extracts liblwjgl.so below, so give LWJGL its
        // exact directory instead of relying on automatic jar scanning.
        if (isLinux()) {
            command.add("-Dorg.lwjgl.librarypath=" + nativesDir.toAbsolutePath());
        }
    }

    /**
     * Extracts the bundled LWJGL core native that an isolated Linux probe must
     * load before it can reach Vulkan or OpenCL. This is deliberately scoped to
     * probe subprocesses: Windows keeps Minecraft's parent-owned lwjgl.dll.
     */
    public static void extractIsolatedLinuxCoreNative(List<Path> extractedClasspath, Path extractRoot) throws IOException {
        if (!isLinux()) {
            return;
        }
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String resource = architecture.contains("aarch64") || architecture.contains("arm64")
            ? "linux/arm64/org/lwjgl/liblwjgl.so"
            : "linux/x64/org/lwjgl/liblwjgl.so";
        Path nativeJar = extractedClasspath.stream()
            .filter(path -> path.getFileName().toString().startsWith("lwjgl-")
                && path.getFileName().toString().contains("natives-linux")
                && (resource.contains("arm64") == path.getFileName().toString().contains("arm64")))
            .findFirst()
            .orElseThrow(() -> new IOException("Embedded LWJGL Linux native jar is missing for " + resource));
        Path target = extractRoot.resolve("natives").resolve("liblwjgl.so");
        Files.createDirectories(target.getParent());
        try (ZipFile archive = new ZipFile(nativeJar.toFile())) {
            java.util.zip.ZipEntry entry = archive.getEntry(resource);
            if (entry == null) {
                throw new IOException("Embedded LWJGL native is missing: " + resource + " in " + nativeJar.getFileName());
            }
            try (InputStream input = archive.getInputStream(entry)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
