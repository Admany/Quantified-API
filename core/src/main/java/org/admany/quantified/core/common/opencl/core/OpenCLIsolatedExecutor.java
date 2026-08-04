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
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

public final class OpenCLIsolatedExecutor {

    private static final String PROBE_ROOT_RESOURCE = "quantified/embedded/openclProbe";
    private static final String PROBE_INDEX_RESOURCE = PROBE_ROOT_RESOURCE + "/classpath.index";
    private static final String PROBE_RESOURCE_SUFFIX = ".bin";
    private static final String BRIDGE_CLASS = "org.admany.quantified.core.common.opencl.core.OpenCLIsolatedBridge";
    private static final AtomicReference<BridgeHandle> HANDLE = new AtomicReference<>();
    private static final AtomicReference<Boolean> RUNTIME_READY = new AtomicReference<>(false);
    private static final AtomicReference<String> FAILURE_REASON = new AtomicReference<>();

    private OpenCLIsolatedExecutor() {
    }

    public static boolean canExecute() {
        return Boolean.TRUE.equals(RUNTIME_READY.get());
    }

    public static boolean warmup() {
        if (canExecute()) {
            return true;
        }
        OpenCLRuntime.ProbeSnapshot snapshot = OpenCLRuntime.cachedProbeSnapshot();
        if (snapshot == null || !snapshot.success() || snapshot.devices().isEmpty()) {
            FAILURE_REASON.set(snapshot != null ? snapshot.failureReason() : "OpenCL probe has not succeeded");
            return false;
        }
        try {
            BridgeHandle handle = handle();
            boolean available = Boolean.TRUE.equals(handle.isAvailable().invoke(null));
            if (available) {
                RUNTIME_READY.set(true);
                FAILURE_REASON.set(null);
                return true;
            }
            Object reason = handle.failureReason().invoke(null);
            FAILURE_REASON.set(reason == null ? "Isolated OpenCL context creation failed" : String.valueOf(reason));
            return false;
        } catch (Throwable throwable) {
            FAILURE_REASON.set(describeFailure(throwable));
            return false;
        }
    }

    public static String failureReason() {
        return FAILURE_REASON.get();
    }

    @SuppressWarnings("unchecked")
    public static <T> T executeApiTask(QuantifiedOpenCL.ApiOpenClTask<T> apiTask) {
        try {
            BridgeHandle handle = handle();
            return (T) handle.executeApiTask().invoke(null, apiTask);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new RuntimeException("Isolated OpenCL execution failed", throwable);
        }
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

    private static BridgeHandle createHandle() throws Exception {
        Path bundleRoot = QuantifiedPaths.getCacheDir().resolve("tools").resolve("openclIsolated");
        Files.createDirectories(bundleRoot);
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
        childFirstPackages.add("org.lwjgl.opencl.");
        childFirstPackages.add("org.admany.quantified.core.common.opencl.");
        // A dedicated Linux server can expose LWJGL Java classes through the
        // loader while shipping no liblwjgl.so at all.  Reusing that partial
        // parent runtime makes LWJGL search the server classpath and prevents
        // the embedded native jars from ever being considered.  On Linux the
        // isolated runtime must own both its LWJGL classes and bundled natives.
        // Windows keeps the shared parent path to avoid loading lwjgl.dll twice.
        if (mustUseBundledLwjglCore()) {
            childFirstPackages.add("org.lwjgl.");
        }
        ChildFirstPackageClassLoader loader = new ChildFirstPackageClassLoader(
            urls.toArray(URL[]::new),
            OpenCLIsolatedExecutor.class.getClassLoader(),
            childFirstPackages
        );
        Class<?> bridgeClass = Class.forName(BRIDGE_CLASS, true, loader);
        java.lang.reflect.Method isAvailable = bridgeClass.getMethod("isAvailable");
        java.lang.reflect.Method failureReason = bridgeClass.getMethod("failureReason");
        java.lang.reflect.Method executeApiTask = bridgeClass.getMethod("executeApiTask", Object.class);
        return new BridgeHandle(loader, bridgeClass, isAvailable, failureReason, executeApiTask);
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
        java.lang.reflect.Method executeApiTask
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
