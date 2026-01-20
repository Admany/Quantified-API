package org.admany.quantified.core.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuantifiedPaths {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedPaths.class);
    private static final String ROOT_FOLDER = "QuantifiedAPI";
    private static final String LEGACY_ROOT_LOWER = "quantified";
    private static final String LEGACY_ROOT_SPACED = "Quantified API";
    private static final String CACHE_FOLDER = "cache";
    private static final String CONFIG_FILE = "quantified_config.json";
    private static final AtomicBoolean MIGRATED = new AtomicBoolean(false);
    private static volatile PathProvider PATH_PROVIDER;

    private QuantifiedPaths() {
    }

    public interface PathProvider {
        Path getGameDir();
        Path getConfigDir();
    }

    public static void setPathProvider(PathProvider provider) {
        PATH_PROVIDER = provider;
    }

    public static Path getConfigDir() {
        return getConfigRootDir().resolve(ROOT_FOLDER);
    }

    public static Path getCacheDir() {
        return getConfigDir().resolve(CACHE_FOLDER);
    }

    public static Path getConfigFile() {
        return getConfigDir().resolve(CONFIG_FILE);
    }

    public static void ensureCacheLayout() {
        ensureCacheLayout(LOGGER);
    }

    public static void ensureCacheLayout(Logger logger) {
        boolean initialRun = MIGRATED.compareAndSet(false, true);
        boolean legacyPresent = hasLegacyRoots();
        if (!initialRun && !legacyPresent) {
            return;
        }
        Path cacheDir = getCacheDir();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            if (logger != null) {
                logger.debug("Failed to create Quantified cache directory {}", cacheDir, e);
            }
        }
        migrateLegacyCache(cacheDir, logger);
        if (legacyPresent && hasLegacyRoots()) {
            MIGRATED.set(false);
        }
    }

    public static void migrateLegacyConfig(Path legacyConfigPath) {
        Path newConfig = getConfigFile();
        if (legacyConfigPath == null || Files.exists(newConfig)) {
            return;
        }
        if (!Files.exists(legacyConfigPath)) {
            return;
        }
        try {
            Files.createDirectories(newConfig.getParent());
            Files.move(legacyConfigPath, newConfig, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.debug("Failed to migrate legacy Quantified config {}", legacyConfigPath, e);
        }
    }

    private static void migrateLegacyCache(Path cacheDir, Logger logger) {
        Path configRoot = getConfigDir();
        for (Path legacyRoot : legacyRoots()) {
            if (legacyRoot.equals(configRoot)) {
                continue;
            }
            migrateLegacyRoot(legacyRoot, cacheDir, configRoot, logger);
        }
    }

    private static void migrateLegacyRoot(Path legacyRoot, Path cacheDir, Path configRoot, Logger logger) {
        if (!Files.exists(legacyRoot) || !Files.isDirectory(legacyRoot)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacyRoot)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (CONFIG_FILE.equals(name)) {
                    migrateLegacyConfig(entry);
                    continue;
                }
                if (CACHE_FOLDER.equals(name) && Files.isDirectory(entry)) {
                    migrateLegacyCacheFolder(entry, cacheDir, logger);
                    continue;
                }
                Path target = shouldMoveToConfigRoot(entry)
                    ? configRoot.resolve(entry.getFileName())
                    : cacheDir.resolve(entry.getFileName());
                moveEntry(entry, target, logger);
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.debug("Failed to migrate legacy Quantified cache {}", legacyRoot, e);
            }
        }
        try {
            deleteIfEmpty(legacyRoot);
        } catch (IOException e) {
            if (logger != null) {
                logger.debug("Failed to remove legacy Quantified cache root {}", legacyRoot, e);
            }
        }
    }

    private static void migrateLegacyCacheFolder(Path legacyCache, Path cacheDir, Logger logger) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacyCache)) {
            for (Path entry : stream) {
                Path target = cacheDir.resolve(entry.getFileName());
                moveEntry(entry, target, logger);
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.debug("Failed to migrate legacy Quantified cache folder {}", legacyCache, e);
            }
        }
        try {
            deleteTree(legacyCache);
        } catch (IOException e) {
            if (logger != null) {
                logger.debug("Failed to remove legacy Quantified cache folder {}", legacyCache, e);
            }
        }
    }

    private static void moveEntry(Path source, Path target, Logger logger) {
        try {
            if (!Files.exists(target)) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            if (Files.isDirectory(source)) {
                mergeDirectory(source, target);
                deleteTree(source);
            } else {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.debug("Failed to move legacy Quantified cache entry {} -> {}", source, target, e);
            }
        }
    }

    private static void mergeDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path dest = target.resolve(relative);
                Files.createDirectories(dest.getParent());
                Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            if (stream.iterator().hasNext()) {
                return;
            }
        }
        Files.deleteIfExists(directory);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<Path> legacyRoots() {
        List<Path> roots = new ArrayList<>();
        Path gameDir = getGameDir();
        roots.add(gameDir.resolve(ROOT_FOLDER));
        roots.add(gameDir.resolve(LEGACY_ROOT_LOWER));
        roots.add(gameDir.resolve(LEGACY_ROOT_SPACED));
        Path configDir = getConfigRootDir();
        roots.add(configDir.resolve(LEGACY_ROOT_LOWER));
        roots.add(configDir.resolve(LEGACY_ROOT_SPACED));
        return roots;
    }

    private static boolean hasLegacyRoots() {
        Path configRoot = getConfigDir();
        for (Path root : legacyRoots()) {
            if (root.equals(configRoot)) {
                continue;
            }
            if (Files.exists(root)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldMoveToConfigRoot(Path entry) throws IOException {
        if (!Files.isDirectory(entry)) {
            return false;
        }
        String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("metrics")
            || name.equals("logs")
            || name.equals("telemetry")
            || name.equals("debug")
            || name.equals("reports")
            || name.equals("dashboard");
    }

    private static Path getGameDir() {
        PathProvider provider = PATH_PROVIDER;
        if (provider != null) {
            Path gameDir = provider.getGameDir();
            if (gameDir != null) {
                return gameDir;
            }
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    private static Path getConfigRootDir() {
        PathProvider provider = PATH_PROVIDER;
        if (provider != null) {
            Path configDir = provider.getConfigDir();
            if (configDir != null) {
                return configDir;
            }
        }
        return getGameDir().resolve("config");
    }
}
