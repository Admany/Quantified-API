package org.admany.quantified.core.common.opencl.core;

import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.admany.quantified.core.common.util.QuantifiedPaths;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;

public final class OpenCLIsolatedExecutor {

    private static final String PROBE_ROOT_RESOURCE = "quantified/embedded/openclProbe";
    private static final String PROBE_INDEX_RESOURCE = PROBE_ROOT_RESOURCE + "/classpath.index";
    private static final String PROBE_RESOURCE_SUFFIX = ".bin";
    private static final String BRIDGE_CLASS = "org.admany.quantified.core.common.opencl.core.OpenCLIsolatedBridge";
    private static final long EXECUTION_FAILURE_COOLDOWN_MS = 300_000L;
    private static final String EXTRACT_PATH_PROPERTY = "quantified.opencl.isolated.extractPath";
    private static final AtomicReference<BridgeHandle> HANDLE = new AtomicReference<>();
    private static final AtomicReference<CompletableFuture<Boolean>> WARMUP = new AtomicReference<>();
    private static final AtomicBoolean RUNTIME_READY = new AtomicBoolean(false);
    private static final AtomicLong DISABLED_UNTIL_MS = new AtomicLong();
    private static final AtomicReference<String> FAILURE_REASON = new AtomicReference<>();
    private static final ThreadLocal<Boolean> ON_RUNTIME_THREAD = ThreadLocal.withInitial(() -> false);
    private static final ExecutorService RUNTIME_EXECUTOR = Executors.newSingleThreadExecutor(runnable ->
        LwjglRuntimeTuning.newDaemonThread(runnable, "Quantified-OpenCL-Isolated", LwjglRuntimeTuning.gpuThreadStackSizeKb()));

    private OpenCLIsolatedExecutor() {
    }

    public static boolean canExecute() {
        return RUNTIME_READY.get() && !isCoolingDown();
    }

    public static boolean warmup() {
        try {
            return warmupAsync().get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            FAILURE_REASON.set("OpenCL isolated runtime warmup interrupted");
            return false;
        } catch (Throwable throwable) {
            FAILURE_REASON.set(describeFailure(throwable));
            return false;
        }
    }

    public static CompletableFuture<Boolean> warmupAsync() {
        if (canExecute()) {
            return CompletableFuture.completedFuture(true);
        }
        if (isCoolingDown()) {
            return CompletableFuture.completedFuture(false);
        }
        OpenCLRuntime.ProbeSnapshot snapshot = OpenCLRuntime.cachedProbeSnapshot();
        if (snapshot == null || !snapshot.success() || snapshot.devices().isEmpty()) {
            FAILURE_REASON.set(snapshot != null ? snapshot.failureReason() : "OpenCL probe has not succeeded");
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> current = WARMUP.get();
        if (current != null && !current.isDone()) {
            return current;
        }
        CompletableFuture<Boolean> created = new CompletableFuture<>();
        if (!WARMUP.compareAndSet(current, created)) {
            return WARMUP.get();
        }
        RUNTIME_EXECUTOR.execute(() -> {
            ON_RUNTIME_THREAD.set(true);
            try {
                BridgeHandle bridge = handle();
                boolean available = Boolean.TRUE.equals(bridge.isAvailable().invoke(null));
                if (!available) {
                    Object reason = bridge.failureReason().invoke(null);
                    throw new IllegalStateException(reason == null
                        ? "Isolated OpenCL context creation failed"
                        : String.valueOf(reason));
                }
                RUNTIME_READY.set(true);
                DISABLED_UNTIL_MS.set(0L);
                FAILURE_REASON.set(null);
                created.complete(true);
            } catch (Throwable throwable) {
                recordFailure(throwable);
                created.complete(false);
            } finally {
                ON_RUNTIME_THREAD.remove();
            }
        });
        return created;
    }

    public static String failureReason() {
        return FAILURE_REASON.get();
    }

    @SuppressWarnings("unchecked")
    public static <T> T executeApiTask(QuantifiedOpenCL.ApiOpenClTask<T> apiTask) {
        try {
            return runOnRuntimeThread(() -> {
                if (!canExecute()) {
                    throw new IllegalStateException("Isolated OpenCL runtime is unavailable: " + failureReason());
                }
                BridgeHandle handle = handle();
                T result = (T) handle.executeApiTask().invoke(null, apiTask);
                RUNTIME_READY.set(true);
                return result;
            });
        } catch (RuntimeException runtimeException) {
            recordExecutionFailure(runtimeException);
            throw runtimeException;
        } catch (Throwable throwable) {
            recordExecutionFailure(throwable);
            throw new RuntimeException("Isolated OpenCL execution failed", throwable);
        }
    }

    public static Object[] executeApiTasks(List<? extends QuantifiedOpenCL.ApiOpenClTask<?>> apiTasks) {
        try {
            return runOnRuntimeThread(() -> {
                if (!canExecute()) {
                    throw new IllegalStateException("Isolated OpenCL runtime is unavailable: " + failureReason());
                }
                return (Object[]) handle().executeApiTasks().invoke(null, apiTasks);
            });
        } catch (RuntimeException runtimeException) {
            recordExecutionFailure(runtimeException);
            throw runtimeException;
        } catch (Throwable throwable) {
            recordExecutionFailure(throwable);
            throw new RuntimeException("Isolated OpenCL batch execution failed", throwable);
        }
    }

    public static <T> CompletableFuture<T> executeApiTaskAsync(QuantifiedOpenCL.ApiOpenClTask<T> apiTask) {
        CompletableFuture<T> result = new CompletableFuture<>();
        RUNTIME_EXECUTOR.execute(() -> {
            ON_RUNTIME_THREAD.set(true);
            try {
                if (!canExecute()) {
                    throw new IllegalStateException("Isolated OpenCL runtime is unavailable: " + failureReason());
                }
                @SuppressWarnings("unchecked")
                T value = (T) handle().executeApiTask().invoke(null, apiTask);
                RUNTIME_READY.set(true);
                result.complete(value);
            } catch (Throwable throwable) {
                recordExecutionFailure(throwable);
                result.completeExceptionally(throwable);
            } finally {
                ON_RUNTIME_THREAD.remove();
            }
        });
        return result;
    }

    private static BridgeHandle handle() throws Exception {
        BridgeHandle cached = HANDLE.get();
        if (cached != null) {
            return cached;
        }
        BridgeHandle created = createHandle();
        if (HANDLE.compareAndSet(null, created)) {
            return created;
        }
        return HANDLE.get();
    }

    private static <T> T runOnRuntimeThread(Callable<T> callable) throws Exception {
        if (ON_RUNTIME_THREAD.get()) {
            return callable.call();
        }
        Future<T> future = RUNTIME_EXECUTOR.submit(() -> {
            ON_RUNTIME_THREAD.set(true);
            try {
                return callable.call();
            } finally {
                ON_RUNTIME_THREAD.remove();
            }
        });
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private static BridgeHandle createHandle() throws Exception {
        Path bundleRoot = QuantifiedPaths.getCacheDir().resolve("tools").resolve("openclIsolated");
        Files.createDirectories(bundleRoot);
        Path nativeRoot = bundleRoot.resolve("native-runtime");
        Files.createDirectories(nativeRoot);
        List<String> relativeEntries = readClasspathIndex();
        if (relativeEntries.isEmpty()) {
            throw new IOException("Embedded OpenCL runtime bundle missing classpath index: " + PROBE_INDEX_RESOURCE);
        }
        List<URL> urls = new ArrayList<>();
        urls.add(resolveCurrentCodeSource());
        for (String relativeEntry : relativeEntries) {
            Path destination = bundleRoot.resolve(relativeEntry.replace('/', java.io.File.separatorChar));
            Files.createDirectories(destination.getParent());
            String resourcePath = PROBE_ROOT_RESOURCE + "/" + relativeEntry + PROBE_RESOURCE_SUFFIX;
            try (InputStream stream = OpenCLIsolatedExecutor.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    throw new IOException("Embedded OpenCL runtime resource missing: " + resourcePath);
                }
                extractEmbeddedBinary(stream, destination);
            }
            urls.add(destination.toUri().toURL());
        }
        Set<String> childFirstPackages = new LinkedHashSet<>();
        // Do not mix the game's LWJGL classes with the embedded OpenCL
        // binding. The parent may already own a different lwjgl.dll, and
        // LWJGL refuses to load that JNI library from another class loader.
        childFirstPackages.add("org.lwjgl.");
        childFirstPackages.add("org.admany.quantified.core.common.opencl.");
        String previousExtractPath = System.getProperty(EXTRACT_PATH_PROPERTY);
        System.setProperty(EXTRACT_PATH_PROPERTY, nativeRoot.toAbsolutePath().toString());
        ChildFirstPackageClassLoader loader = new ChildFirstPackageClassLoader(
            urls.toArray(URL[]::new),
            OpenCLIsolatedExecutor.class.getClassLoader(),
            childFirstPackages
        );
        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS, true, loader);
            java.lang.reflect.Method isAvailable = bridgeClass.getMethod("isAvailable");
            java.lang.reflect.Method failureReason = bridgeClass.getMethod("failureReason");
            java.lang.reflect.Method executeApiTask = bridgeClass.getMethod("executeApiTask", Object.class);
            java.lang.reflect.Method executeApiTasks = bridgeClass.getMethod("executeApiTasks", List.class);
            return new BridgeHandle(loader, bridgeClass, isAvailable, failureReason, executeApiTask, executeApiTasks);
        } finally {
            if (previousExtractPath == null) {
                System.clearProperty(EXTRACT_PATH_PROPERTY);
            } else {
                System.setProperty(EXTRACT_PATH_PROPERTY, previousExtractPath);
            }
        }
    }

    private static URL resolveCurrentCodeSource() throws IOException {
        CodeSource source = OpenCLIsolatedExecutor.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("Unable to resolve current Quantified code source for isolated OpenCL runtime");
        }
        return source.getLocation();
    }

    private static List<String> readClasspathIndex() throws IOException {
        try (InputStream stream = OpenCLIsolatedExecutor.class.getClassLoader().getResourceAsStream(PROBE_INDEX_RESOURCE)) {
            if (stream == null) {
                return List.of();
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            List<String> entries = new ArrayList<>();
            for (String line : content.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
            return entries;
        }
    }

    private static void extractEmbeddedBinary(InputStream stream, Path destination) throws IOException {
        byte[] payload = stream.readAllBytes();
        if (payload.length >= 2 && (payload[0] & 0xFF) == 0x1F && (payload[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(payload))) {
                Files.copy(gzip, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        Files.write(destination, payload);
    }

    private record BridgeHandle(
        ClassLoader loader,
        Class<?> bridgeClass,
        java.lang.reflect.Method isAvailable,
        java.lang.reflect.Method failureReason,
        java.lang.reflect.Method executeApiTask,
        java.lang.reflect.Method executeApiTasks
    ) {
    }

    private static boolean mustUseBundledLwjglCore() {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (osName.contains("linux")) {
            return true;
        }
        return !parentHasUsableLwjglCore();
    }

    private static boolean parentHasUsableLwjglCore() {
        try {
            Class.forName("org.lwjgl.system.MemoryUtil", true, OpenCLIsolatedExecutor.class.getClassLoader());
            Class.forName("org.lwjgl.PointerBuffer", true, OpenCLIsolatedExecutor.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isCoolingDown() {
        long blockedUntil = DISABLED_UNTIL_MS.get();
        if (blockedUntil <= 0L) {
            return false;
        }
        if (System.currentTimeMillis() >= blockedUntil) {
            DISABLED_UNTIL_MS.compareAndSet(blockedUntil, 0L);
            return false;
        }
        return true;
    }

    private static void recordFailure(Throwable throwable) {
        RUNTIME_READY.set(false);
        FAILURE_REASON.set(describeFailure(throwable));
        if (isPermanentNativeFailure(throwable)) {
            DISABLED_UNTIL_MS.set(Long.MAX_VALUE);
        } else {
            DISABLED_UNTIL_MS.set(System.currentTimeMillis() + EXECUTION_FAILURE_COOLDOWN_MS);
        }
    }

    /**
     * A failed API workload is not a failed OpenCL runtime.  Keep the child
     * context hot for the next task and only trip the cooldown when the
     * bridge or native runtime itself has broken.
     */
    private static void recordExecutionFailure(Throwable throwable) {
        if (isRuntimeFailure(throwable)) {
            recordFailure(throwable);
        }
    }

    private static boolean isRuntimeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof LinkageError || current instanceof ExceptionInInitializerError) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("opencl context")
                    || lower.contains("opencl runtime")
                    || lower.contains("lwjgl")
                    || lower.contains("native library")
                    || lower.contains("no opencl")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isPermanentNativeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof UnsatisfiedLinkError
                || current instanceof ExceptionInInitializerError
                || current instanceof NoClassDefFoundError) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("already loaded in another classloader")
                || message.contains("already loaded in another ClassLoader"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String describeFailure(Throwable throwable) {
        Throwable root = throwable;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        if (root == null) {
            return "Unknown isolated OpenCL runtime failure";
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
            ? root.getClass().getSimpleName()
            : root.getClass().getSimpleName() + ": " + message;
    }

    private static final class ChildFirstPackageClassLoader extends URLClassLoader {
        private final Set<String> childFirstPrefixes;

        private ChildFirstPackageClassLoader(URL[] urls, ClassLoader parent, Set<String> childFirstPrefixes) {
            super(urls, parent);
            this.childFirstPrefixes = new LinkedHashSet<>(childFirstPrefixes);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                if (isChildFirst(name)) {
                    try {
                        Class<?> found = findClass(name);
                        if (resolve) {
                            resolveClass(found);
                        }
                        return found;
                    } catch (ClassNotFoundException ignored) {
                    }
                }
                return super.loadClass(name, resolve);
            }
        }

        @Override
        public URL getResource(String name) {
            if (isChildFirstResource(name)) {
                URL found = findResource(name);
                if (found != null) {
                    return found;
                }
            }
            return super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (!isChildFirstResource(name)) {
                return super.getResources(name);
            }
            List<URL> ordered = new ArrayList<>();
            Enumeration<URL> own = findResources(name);
            while (own.hasMoreElements()) {
                ordered.add(own.nextElement());
            }
            Enumeration<URL> parentResources = getParent().getResources(name);
            while (parentResources.hasMoreElements()) {
                ordered.add(parentResources.nextElement());
            }
            return java.util.Collections.enumeration(ordered);
        }

        private boolean isChildFirst(String className) {
            for (String prefix : childFirstPrefixes) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isChildFirstResource(String resourceName) {
            String normalized = resourceName.replace('/', '.');
            return isChildFirst(normalized);
        }
    }
}
