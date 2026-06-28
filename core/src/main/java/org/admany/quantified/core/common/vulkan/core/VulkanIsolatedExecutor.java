package org.admany.quantified.core.common.vulkan.core;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.util.QuantifiedPaths;

public final class VulkanIsolatedExecutor {
    private static final String PROBE_ROOT_RESOURCE = "quantified/embedded/vulkanProbe";
    private static final String PROBE_INDEX_RESOURCE = "quantified/embedded/vulkanProbe/classpath.index";
    private static final String PROBE_RESOURCE_SUFFIX = ".bin";
    private static final String BRIDGE_CLASS = "org.admany.quantified.core.common.vulkan.core.VulkanIsolatedBridge";
    private static final long EXECUTION_FAILURE_COOLDOWN_MS = 300000L;
    private static final AtomicReference<BridgeHandle> HANDLE = new AtomicReference();
    private static final AtomicLong DISABLED_UNTIL_MS = new AtomicLong();
    private static final ThreadLocal<Boolean> ON_RUNTIME_THREAD = ThreadLocal.withInitial(() -> false);
    private static final ExecutorService RUNTIME_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> LwjglRuntimeTuning.newDaemonThread(
        runnable,
        "Quantified-Vulkan-Isolated",
        LwjglRuntimeTuning.gpuThreadStackSizeKb()
    ));

    private VulkanIsolatedExecutor() {
    }

    public static boolean canExecute() {
        if (VulkanIsolatedExecutor.isCoolingDown()) {
            return false;
        }
        VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.cachedSnapshot();
        return VulkanRuntime.runtimeMode() == VulkanRuntime.RuntimeMode.ISOLATED && snapshot != null && !snapshot.devices().isEmpty();
    }

    public static <T> T executeApiTask(QuantifiedVulkan.ApiVulkanTask<T> apiTask) {
        try {
            VulkanRuntimeActivityTracker.TaskSample sample = VulkanRuntimeActivityTracker.beginTask(
                apiTask.estimatedVramBytes(),
                apiTask.estimatedComputeUnits()
            );
            try {
            T result = VulkanIsolatedExecutor.runOnRuntimeThread(() -> {
                BridgeHandle handle = VulkanIsolatedExecutor.handle();
                return (T)handle.executeApiTask().invoke(null, apiTask);
            });
            VulkanIsolatedExecutor.clearCooldown();
            return result;
            } finally {
                VulkanRuntimeActivityTracker.endTask(sample);
            }
        }
        catch (RuntimeException runtimeException) {
            VulkanIsolatedExecutor.recordFailure(runtimeException);
            throw runtimeException;
        }
        catch (Throwable throwable) {
            VulkanIsolatedExecutor.recordFailure(throwable);
            throw new RuntimeException("Isolated Vulkan execution failed", throwable);
        }
    }

    public static Object[] executeApiTasks(List<? extends QuantifiedVulkan.ApiVulkanTask<?>> apiTasks) {
        try {
            long estimatedVramBytes = 0L;
            int estimatedComputeUnits = 0;
            for (QuantifiedVulkan.ApiVulkanTask<?> apiTask : apiTasks) {
                if (apiTask == null) {
                    continue;
                }
                estimatedVramBytes += Math.max(0L, apiTask.estimatedVramBytes());
                estimatedComputeUnits += Math.max(0, apiTask.estimatedComputeUnits());
            }
            VulkanRuntimeActivityTracker.TaskSample sample = VulkanRuntimeActivityTracker.beginTask(
                estimatedVramBytes,
                estimatedComputeUnits
            );
            try {
            Object[] results = VulkanIsolatedExecutor.runOnRuntimeThread(() -> {
                BridgeHandle handle = VulkanIsolatedExecutor.handle();
                return (Object[])handle.executeApiTasks().invoke(null, apiTasks);
            });
            boolean hasFailure = false;
            for (Object result : results) {
                if (!(result instanceof Throwable)) continue;
                VulkanIsolatedExecutor.recordFailure((Throwable)result);
                hasFailure = true;
                break;
            }
            if (!hasFailure) {
                VulkanIsolatedExecutor.clearCooldown();
            }
            return results;
            } finally {
                VulkanRuntimeActivityTracker.endTask(sample);
            }
        }
        catch (RuntimeException runtimeException) {
            VulkanIsolatedExecutor.recordFailure(runtimeException);
            throw runtimeException;
        }
        catch (Throwable throwable) {
            VulkanIsolatedExecutor.recordFailure(throwable);
            throw new RuntimeException("Isolated Vulkan batch execution failed", throwable);
        }
    }

    public static Map<?, ?> residencySnapshot() {
        if (!VulkanIsolatedExecutor.canExecute()) {
            return Map.of();
        }
        try {
            return VulkanIsolatedExecutor.runOnRuntimeThread(() -> {
                BridgeHandle handle = VulkanIsolatedExecutor.handle();
                Object value = handle.residencySnapshot().invoke(null);
                return value instanceof Map<?, ?> map ? map : Map.of();
            });
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    private static <T> T runOnRuntimeThread(Callable<T> callable) throws Exception {
        if (ON_RUNTIME_THREAD.get()) {
            return callable.call();
        }
        Future<T> future = RUNTIME_EXECUTOR.submit(() -> {
            ON_RUNTIME_THREAD.set(true);
            try {
                LwjglRuntimeTuning.ensureConfigured();
                LwjglRuntimeTuning.ensureThreadLocalStack();
                return callable.call();
            } finally {
                ON_RUNTIME_THREAD.remove();
            }
        });
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
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

    private static void clearCooldown() {
        DISABLED_UNTIL_MS.set(0L);
    }

    private static void recordFailure(Throwable throwable) {
        DISABLED_UNTIL_MS.set(System.currentTimeMillis() + EXECUTION_FAILURE_COOLDOWN_MS);
    }

    private static BridgeHandle handle() throws Exception {
        BridgeHandle cached = HANDLE.get();
        if (cached != null) {
            return cached;
        }
        BridgeHandle created = VulkanIsolatedExecutor.createHandle();
        if (HANDLE.compareAndSet(null, created)) {
            return created;
        }
        return HANDLE.get();
    }

    private static BridgeHandle createHandle() throws Exception {
        Path bundleRoot = QuantifiedPaths.getCacheDir().resolve("tools").resolve("vulkanIsolated");
        Files.createDirectories(bundleRoot, new FileAttribute[0]);
        List<String> relativeEntries = VulkanIsolatedExecutor.readClasspathIndex();
        if (relativeEntries.isEmpty()) {
            throw new IOException("Embedded Vulkan runtime bundle missing classpath index: quantified/embedded/vulkanProbe/classpath.index");
        }
        ArrayList<URL> urls = new ArrayList<URL>();
        urls.add(VulkanIsolatedExecutor.resolveCurrentCodeSource());
        for (String relativeEntry : relativeEntries) {
            Path destination = bundleRoot.resolve(relativeEntry.replace('/', File.separatorChar));
            Files.createDirectories(destination.getParent(), new FileAttribute[0]);
            String resourcePath = "quantified/embedded/vulkanProbe/" + relativeEntry + PROBE_RESOURCE_SUFFIX;
            try (InputStream stream = VulkanIsolatedExecutor.class.getClassLoader().getResourceAsStream(resourcePath);){
                if (stream == null) {
                    throw new IOException("Embedded Vulkan runtime resource missing: " + resourcePath);
                }
                VulkanIsolatedExecutor.extractEmbeddedBinary(stream, destination);
            }
            urls.add(destination.toUri().toURL());
        }
        ChildFirstPackageClassLoader loader = new ChildFirstPackageClassLoader((URL[])urls.toArray(URL[]::new), VulkanIsolatedExecutor.class.getClassLoader(), Set.of("org.lwjgl.vulkan.", "org.admany.quantified.core.common.vulkan.", "org.admany.quantified.core.common.util."));
        Class<?> bridgeClass = Class.forName(BRIDGE_CLASS, true, loader);
        Method isAvailable = bridgeClass.getMethod("isAvailable", new Class[0]);
        Method executeApiTask = bridgeClass.getMethod("executeApiTask", Object.class);
        Method executeApiTasks = bridgeClass.getMethod("executeApiTasks", List.class);
        Method residencySnapshot = bridgeClass.getMethod("residencySnapshot", new Class[0]);
        return new BridgeHandle(loader, bridgeClass, isAvailable, executeApiTask, executeApiTasks, residencySnapshot);
    }

    private static URL resolveCurrentCodeSource() throws IOException {
        CodeSource source = VulkanIsolatedExecutor.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("Unable to resolve current Quantified code source for isolated Vulkan runtime");
        }
        return source.getLocation();
    }

    private static List<String> readClasspathIndex() throws IOException {
        try (InputStream stream = VulkanIsolatedExecutor.class.getClassLoader().getResourceAsStream(PROBE_INDEX_RESOURCE);){
            if (stream == null) {
                List<String> list = List.of();
                return list;
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            ArrayList<String> entries = new ArrayList<String>();
            for (String line : content.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                entries.add(trimmed);
            }
            ArrayList<String> arrayList = entries;
            return arrayList;
        }
    }

    private static void extractEmbeddedBinary(InputStream stream, Path destination) throws IOException {
        byte[] payload = stream.readAllBytes();
        if (payload.length >= 2 && (payload[0] & 0xFF) == 31 && (payload[1] & 0xFF) == 139) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload));){
                Files.copy(gzip, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        Files.write(destination, payload, new OpenOption[0]);
    }

    private record BridgeHandle(ClassLoader loader,
                                Class<?> bridgeClass,
                                Method isAvailable,
                                Method executeApiTask,
                                Method executeApiTasks,
                                Method residencySnapshot) {
    }

    private static final class ChildFirstPackageClassLoader
    extends URLClassLoader {
        private final Set<String> childFirstPrefixes;

        private ChildFirstPackageClassLoader(URL[] urls, ClassLoader parent, Set<String> childFirstPrefixes) {
            super(urls, parent);
            this.childFirstPrefixes = new LinkedHashSet<String>(childFirstPrefixes);
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Object object = this.getClassLoadingLock(name);
            synchronized (object) {
                Class<?> loaded = this.findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                if (this.isChildFirst(name)) {
                    try {
                        Class<?> found = this.findClass(name);
                        if (resolve) {
                            this.resolveClass(found);
                        }
                        return found;
                    }
                    catch (ClassNotFoundException classNotFoundException) {
                        // empty catch block
                    }
                }
                return super.loadClass(name, resolve);
            }
        }

        private boolean isChildFirst(String className) {
            for (String prefix : this.childFirstPrefixes) {
                if (!className.startsWith(prefix)) continue;
                return true;
            }
            return false;
        }
    }
}
