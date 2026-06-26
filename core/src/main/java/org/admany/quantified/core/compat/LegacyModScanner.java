package org.admany.quantified.core.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Detects mods that still target Quantified API v1.x by scanning their jars and dependency metadata.
 */
public final class LegacyModScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyModScanner.class);
    private static final String QUANTIFIED_MOD_ID = "quantified";

    private static final String[] LEGACY_BYTECODE_MARKERS = {
        "org/admany/quantified/api/TaskGraphExecutor",
        "org/admany/quantified/api/vulkan/McDensityProgram",
        "Lorg/admany/quantified/api/TaskGraphExecutor;",
        "Lorg/admany/quantified/api/vulkan/McDensityProgram;"
    };

    private LegacyModScanner() {
    }

    public static void scanLoadedMods() {
        List<ModCandidate> candidates = collectModCandidates();
        for (ModCandidate candidate : candidates) {
            if (QUANTIFIED_MOD_ID.equals(candidate.modId())) {
                continue;
            }
            if (LegacyApiRegistry.snapshot().containsKey(candidate.modId())
                && LegacyApiRegistry.snapshot().get(candidate.modId()).binding() == LegacyApiBinding.V2) {
                continue;
            }

            String reason = detectLegacyReason(candidate);
            if (reason != null) {
                LegacyApiRegistry.markDetected(candidate.modId(), candidate.displayName(), reason);
            }
        }
        LegacyApiRegistry.logSummary();
    }

    private static String detectLegacyReason(ModCandidate candidate) {
        if (candidate.declaresLegacyQuantifiedDependency()) {
            return "depends on Quantified API < 2.0.0";
        }
        if (candidate.jarPath() != null && jarContainsLegacyMarkers(candidate.jarPath())) {
            return "bytecode references removed v1.x API types";
        }
        if (candidate.jarPath() != null && jarReferencesQuantifiedApi(candidate.jarPath())) {
            return "references Quantified API without v2 registration";
        }
        return null;
    }

    private static boolean jarContainsLegacyMarkers(Path jarPath) {
        return scanJar(jarPath, LegacyModScanner::entryHasLegacyMarker);
    }

    private static boolean jarReferencesQuantifiedApi(Path jarPath) {
        return scanJar(jarPath, bytes -> {
            if (!containsAscii(bytes, "org/admany/quantified/api")) {
                return false;
            }
            return containsAscii(bytes, "QuantifiedAPI")
                || containsAscii(bytes, "QuantifiedHandle");
        });
    }

    private static boolean scanJar(Path jarPath, EntryPredicate predicate) {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return false;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    if (predicate.test(bytes)) {
                        return true;
                    }
                }
            }
        } catch (IOException ex) {
            LOGGER.debug("Legacy scan skipped unreadable jar {}: {}", jarPath, ex.toString());
        }
        return false;
    }

    private static boolean entryHasLegacyMarker(byte[] bytes) {
        for (String marker : LEGACY_BYTECODE_MARKERS) {
            if (containsAscii(bytes, marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAscii(byte[] bytes, String needle) {
        if (bytes == null || bytes.length == 0 || needle == null || needle.isEmpty()) {
            return false;
        }
        byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static List<ModCandidate> collectModCandidates() {
        List<ModCandidate> out = new ArrayList<>();
        out.addAll(collectForgeLikeMods("net.minecraftforge.fml.ModList"));
        out.addAll(collectForgeLikeMods("net.neoforged.fml.ModList"));
        out.addAll(collectFabricMods());
        return out;
    }

    private static List<ModCandidate> collectForgeLikeMods(String modListClassName) {
        List<ModCandidate> out = new ArrayList<>();
        try {
            Class<?> modListClass = Class.forName(modListClassName);
            Object modList = modListClass.getMethod("get").invoke(null);
            if (modList == null) {
                return out;
            }
            Iterable<?> mods = (Iterable<?>) modListClass.getMethod("getMods").invoke(modList);
            for (Object modInfo : mods) {
                String modId = stringValue(invoke(modInfo, "getModId"));
                String displayName = stringValue(invoke(modInfo, "getDisplayName"));
                Path jarPath = extractForgeJarPath(modInfo);
                boolean legacyDep = declaresLegacyQuantifiedDependency(modInfo);
                if (modId != null && !modId.isBlank()) {
                    out.add(new ModCandidate(modId, displayName, jarPath, legacyDep));
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return out;
    }

    private static List<ModCandidate> collectFabricMods() {
        List<ModCandidate> out = new ArrayList<>();
        try {
            Object loader = Class.forName("net.fabricmc.loader.api.FabricLoader").getMethod("getInstance").invoke(null);
            Iterable<?> mods = (Iterable<?>) loader.getClass().getMethod("getAllMods").invoke(loader);
            for (Object container : mods) {
                Object metadata = invoke(container, "getMetadata");
                String modId = stringValue(invoke(metadata, "getId"));
                String displayName = stringValue(invoke(metadata, "getName"));
                Path jarPath = extractFabricJarPath(container);
                boolean legacyDep = fabricDeclaresLegacyQuantifiedDependency(metadata);
                if (modId != null && !modId.isBlank()) {
                    out.add(new ModCandidate(modId, displayName, jarPath, legacyDep));
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return out;
    }

    private static boolean declaresLegacyQuantifiedDependency(Object modInfo) {
        try {
            Object deps = invoke(modInfo, "getDependencies");
            if (!(deps instanceof Iterable<?> iterable)) {
                return false;
            }
            for (Object dep : iterable) {
                String modId = stringValue(invoke(dep, "getModId"));
                if (!QUANTIFIED_MOD_ID.equals(modId)) {
                    continue;
                }
                String version = stringValue(invoke(dep, "getVersionRange"));
                if (version != null && (version.contains("[2") || version.contains("2."))) {
                    return false;
                }
                return true;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    private static boolean fabricDeclaresLegacyQuantifiedDependency(Object metadata) {
        try {
            Object deps = invoke(metadata, "getDependencies");
            if (!(deps instanceof Iterable<?> iterable)) {
                return false;
            }
            for (Object dep : iterable) {
                String modId = stringValue(invoke(dep, "getModId"));
                if (!QUANTIFIED_MOD_ID.equals(modId)) {
                    continue;
                }
                String version = stringValue(invoke(dep, "getVersion"));
                if (version != null && !version.isBlank() && !version.startsWith("2")) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    private static Path extractForgeJarPath(Object modInfo) {
        try {
            Object owningFile = invoke(modInfo, "getOwningFile");
            if (owningFile == null) {
                return null;
            }
            Path path = toPath(invoke(owningFile, "getFilePath"));
            if (path != null) {
                return path;
            }
            Object file = invoke(owningFile, "getFile");
            path = toPath(invoke(file, "getFilePath"));
            if (path != null) {
                return path;
            }
            Object secureJar = invoke(file, "getSecureJar");
            path = toPath(invoke(secureJar, "getPrimaryPath"));
            return path;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Path extractFabricJarPath(Object container) {
        try {
            Iterable<?> roots = (Iterable<?>) container.getClass().getMethod("getRootPaths").invoke(container);
            for (Object root : roots) {
                if (root instanceof Path path) {
                    Path normalized = path.toAbsolutePath().normalize();
                    if (Files.isRegularFile(normalized)) {
                        return normalized;
                    }
                    Path parent = normalized.getParent();
                    if (parent != null) {
                        try (ZipFile zip = openZipIfPresent(parent)) {
                            if (zip != null) {
                                return parent;
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static ZipFile openZipIfPresent(Path path) throws IOException {
        if (path != null && Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
            return new ZipFile(path.toFile());
        }
        return null;
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        return target.getClass().getMethod(method).invoke(target);
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Path toPath(Object value) {
        if (value instanceof Path path) {
            return path;
        }
        if (value instanceof java.io.File file) {
            return file.toPath();
        }
        if (value != null) {
            try {
                return Path.of(value.toString());
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private record ModCandidate(String modId, String displayName, Path jarPath, boolean declaresLegacyQuantifiedDependency) {}

    @FunctionalInterface
    private interface EntryPredicate {
        boolean test(byte[] bytes);
    }
}
