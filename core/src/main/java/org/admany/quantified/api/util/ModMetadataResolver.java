package org.admany.quantified.api.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ModMetadataResolver {

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ConcurrentMap<String, ResolvedMod> MOD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Optional<ResolvedMod>> CALLER_CACHE = new ConcurrentHashMap<>();
    private static final String INTERNAL_PACKAGE = "org.admany.quantified.";

    private ModMetadataResolver() {}

    public static ResolvedMod resolveByModId(String modId) {
        if (modId == null || modId.isBlank()) {
            return null;
        }
        return MOD_CACHE.computeIfAbsent(modId, ModMetadataResolver::resolveByModIdUncached);
    }

    public static ResolvedMod resolveCallerMod() {
        Class<?> caller = STACK_WALKER.walk(stream -> stream
            .map(StackWalker.StackFrame::getDeclaringClass)
            .filter(Objects::nonNull)
            .filter(ModMetadataResolver::isExternalCaller)
            .findFirst()
            .orElse(null));
        if (caller == null) {
            return null;
        }
        return CALLER_CACHE.computeIfAbsent(caller, ModMetadataResolver::resolveByCallerClass).orElse(null);
    }

    private static Optional<ResolvedMod> resolveByCallerClass(Class<?> callerClass) {
        ResolvedMod fabric = resolveFabricBySource(callerClass);
        if (fabric != null) {
            MOD_CACHE.putIfAbsent(fabric.modId(), fabric);
            return Optional.of(fabric);
        }

        ResolvedMod forge = resolveForgeLikeBySource(callerClass, "net.minecraftforge.fml.ModList");
        if (forge != null) {
            MOD_CACHE.putIfAbsent(forge.modId(), forge);
            return Optional.of(forge);
        }

        ResolvedMod neoForge = resolveForgeLikeBySource(callerClass, "net.neoforged.fml.ModList");
        if (neoForge != null) {
            MOD_CACHE.putIfAbsent(neoForge.modId(), neoForge);
            return Optional.of(neoForge);
        }

        return Optional.empty();
    }

    private static ResolvedMod resolveByModIdUncached(String modId) {
        ResolvedMod fabric = resolveFabricByModId(modId);
        if (fabric != null) {
            return fabric;
        }

        ResolvedMod forge = resolveForgeLikeByModId(modId, "net.minecraftforge.fml.ModList");
        if (forge != null) {
            return forge;
        }

        ResolvedMod neoForge = resolveForgeLikeByModId(modId, "net.neoforged.fml.ModList");
        if (neoForge != null) {
            return neoForge;
        }

        return new ResolvedMod(modId, fallbackDisplayName(modId), "unknown");
    }

    private static ResolvedMod resolveFabricByModId(String modId) {
        try {
            Object loader = Class.forName("net.fabricmc.loader.api.FabricLoader").getMethod("getInstance").invoke(null);
            Object optional = loader.getClass().getMethod("getModContainer", String.class).invoke(loader, modId);
            if (!(boolean) optional.getClass().getMethod("isPresent").invoke(optional)) {
                return null;
            }
            Object container = optional.getClass().getMethod("get").invoke(optional);
            return toResolvedMod(readFabricMetadata(container, modId));
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static ResolvedMod resolveFabricBySource(Class<?> callerClass) {
        Path callerPath = codeSourcePath(callerClass);
        if (callerPath == null) {
            return null;
        }
        try {
            Object loader = Class.forName("net.fabricmc.loader.api.FabricLoader").getMethod("getInstance").invoke(null);
            Iterable<?> mods = (Iterable<?>) loader.getClass().getMethod("getAllMods").invoke(loader);
            for (Object container : mods) {
                Iterable<?> roots = (Iterable<?>) container.getClass().getMethod("getRootPaths").invoke(container);
                for (Object root : roots) {
                    Path rootPath = normalizePath(root instanceof Path p ? p : null);
                    if (pathMatches(rootPath, callerPath)) {
                        return toResolvedMod(readFabricMetadata(container, null));
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static MetadataRecord readFabricMetadata(Object container, String fallbackModId) throws ReflectiveOperationException {
        Object metadata = container.getClass().getMethod("getMetadata").invoke(container);
        String modId = stringValue(invokeNoArg(metadata, "getId"));
        if (modId == null || modId.isBlank()) {
            modId = fallbackModId;
        }
        String displayName = stringValue(invokeNoArg(metadata, "getName"));
        String version = versionString(invokeNoArg(metadata, "getVersion"));
        return new MetadataRecord(modId, displayName, version);
    }

    private static ResolvedMod resolveForgeLikeByModId(String modId, String modListClassName) {
        try {
            Object modInfo = getForgeLikeModInfoById(modId, modListClassName);
            if (modInfo == null) {
                return null;
            }
            return toResolvedMod(readForgeLikeMetadata(modInfo, modId));
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static ResolvedMod resolveForgeLikeBySource(Class<?> callerClass, String modListClassName) {
        Path callerPath = codeSourcePath(callerClass);
        if (callerPath == null) {
            return null;
        }
        try {
            Class<?> modListClass = Class.forName(modListClassName);
            Object modList = modListClass.getMethod("get").invoke(null);
            if (modList == null) {
                return null;
            }
            Iterable<?> mods = (Iterable<?>) modListClass.getMethod("getMods").invoke(modList);
            for (Object modInfo : mods) {
                Path modPath = extractForgeLikePath(modInfo);
                if (pathMatches(modPath, callerPath)) {
                    return toResolvedMod(readForgeLikeMetadata(modInfo, null));
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Object getForgeLikeModInfoById(String modId, String modListClassName) throws ReflectiveOperationException {
        Class<?> modListClass = Class.forName(modListClassName);
        Object modList = modListClass.getMethod("get").invoke(null);
        if (modList == null) {
            return null;
        }
        Object containerOptional = modListClass.getMethod("getModContainerById", String.class).invoke(modList, modId);
        if (!(boolean) containerOptional.getClass().getMethod("isPresent").invoke(containerOptional)) {
            return null;
        }
        Object container = containerOptional.getClass().getMethod("get").invoke(containerOptional);
        return container.getClass().getMethod("getModInfo").invoke(container);
    }

    private static MetadataRecord readForgeLikeMetadata(Object modInfo, String fallbackModId) throws ReflectiveOperationException {
        String modId = stringValue(invokeNoArg(modInfo, "getModId"));
        if (modId == null || modId.isBlank()) {
            modId = fallbackModId;
        }
        String displayName = stringValue(invokeNoArg(modInfo, "getDisplayName"));
        String version = versionString(invokeNoArg(modInfo, "getVersion"));
        return new MetadataRecord(modId, displayName, version);
    }

    private static Path extractForgeLikePath(Object modInfo) {
        try {
            Object owningFile = invokeNoArg(modInfo, "getOwningFile");
            if (owningFile == null) {
                return null;
            }

            Path directPath = normalizePath(pathValue(invokeNoArg(owningFile, "getFilePath")));
            if (directPath != null) {
                return directPath;
            }

            Object file = invokeNoArg(owningFile, "getFile");
            Path filePath = normalizePath(pathValue(invokeNoArg(file, "getFilePath")));
            if (filePath != null) {
                return filePath;
            }

            Object secureJar = invokeNoArg(file, "getSecureJar");
            Path rootPath = normalizePath(pathValue(invokeNoArg(secureJar, "getRootPath")));
            if (rootPath != null) {
                return rootPath;
            }

            return normalizePath(pathValue(invokeNoArg(secureJar, "getPrimaryPath")));
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isExternalCaller(Class<?> type) {
        String name = type.getName();
        return !name.startsWith(INTERNAL_PACKAGE)
            && !name.startsWith("java.")
            && !name.startsWith("javax.")
            && !name.startsWith("jdk.")
            && !name.startsWith("sun.");
    }

    private static ResolvedMod toResolvedMod(MetadataRecord metadata) {
        if (metadata == null || metadata.modId == null || metadata.modId.isBlank()) {
            return null;
        }
        String displayName = metadata.displayName == null || metadata.displayName.isBlank()
            ? fallbackDisplayName(metadata.modId)
            : metadata.displayName;
        String version = metadata.version == null || metadata.version.isBlank()
            ? "unknown"
            : metadata.version;
        return new ResolvedMod(metadata.modId, displayName, version);
    }

    private static Object invokeNoArg(Object target, String method) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Path codeSourcePath(Class<?> callerClass) {
        try {
            URL location = callerClass.getProtectionDomain() != null
                && callerClass.getProtectionDomain().getCodeSource() != null
                ? callerClass.getProtectionDomain().getCodeSource().getLocation()
                : null;
            if (location == null) {
                return null;
            }
            return normalizePath(Paths.get(toUri(location)));
        } catch (URISyntaxException | RuntimeException ignored) {
            return null;
        }
    }

    private static URI toUri(URL url) throws URISyntaxException {
        return url.toURI();
    }

    private static Path pathValue(Object value) {
        if (value instanceof Path path) {
            return path;
        }
        if (value instanceof java.io.File file) {
            return file.toPath();
        }
        if (value != null) {
            try {
                return Paths.get(value.toString());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Path normalizePath(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return path.toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return path.normalize();
        }
    }

    private static boolean pathMatches(Path modPath, Path callerPath) {
        if (modPath == null || callerPath == null) {
            return false;
        }
        Path left = normalizePath(modPath);
        Path right = normalizePath(callerPath);
        if (left == null || right == null) {
            return false;
        }
        return right.startsWith(left)
            || left.startsWith(right)
            || left.equals(right)
            || left.toString().equalsIgnoreCase(right.toString());
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String versionString(Object versionObject) {
        if (versionObject == null) {
            return null;
        }
        try {
            Object friendly = invokeNoArg(versionObject, "getFriendlyString");
            if (friendly != null) {
                return friendly.toString();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return versionObject.toString();
    }

    private static String fallbackDisplayName(String modId) {
        if (modId == null || modId.isBlank()) {
            return "unknown";
        }
        return modId.substring(0, 1).toUpperCase() + modId.substring(1).replace('_', ' ');
    }

    private record MetadataRecord(String modId, String displayName, String version) {}

    public record ResolvedMod(String modId, String displayName, String version) {}
}
