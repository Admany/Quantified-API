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

    private OpenCLIsolatedExecutor() {
    }

    public static boolean canExecute() {
        OpenCLRuntime.ProbeSnapshot snapshot = OpenCLRuntime.cachedProbeSnapshot();
        return snapshot != null && snapshot.success() && !snapshot.devices().isEmpty();
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
        ChildFirstPackageClassLoader loader = new ChildFirstPackageClassLoader(
            urls.toArray(URL[]::new),
            OpenCLIsolatedExecutor.class.getClassLoader(),
            Set.of("org.lwjgl.opencl.", "org.admany.quantified.core.common.opencl.")
        );
        Class<?> bridgeClass = Class.forName(BRIDGE_CLASS, true, loader);
        java.lang.reflect.Method isAvailable = bridgeClass.getMethod("isAvailable");
        java.lang.reflect.Method executeApiTask = bridgeClass.getMethod("executeApiTask", Object.class);
        return new BridgeHandle(loader, bridgeClass, isAvailable, executeApiTask);
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
        java.lang.reflect.Method executeApiTask
    ) {
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
