package org.admany.quantified.core.common.util;

import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuantifiedPaths {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedPaths.class);
    private static final String ROOT_FOLDER = "QuantifiedAPI";
    private static final String CACHE_FOLDER = "cache";
    private static final String CONFIG_FILE = "quantified_config.json";
    private static final AtomicBoolean MIGRATED = new AtomicBoolean(false);

    private QuantifiedPaths() {
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get().resolve(ROOT_FOLDER);
    }

    public static Path getCacheDir() {
        return getConfigDir().resolve(CACHE_FOLDER);
    }

    public static Path getConfigFile() {
        return getConfigDir().resolve(CONFIG_FILE);
    }

    private static Path getLegacyCacheDir() {
        return FMLPaths.GAMEDIR.get().resolve(ROOT_FOLDER);
    }

    public static void ensureCacheLayout() {
        ensureCacheLayout(LOGGER);
    }

    public static void ensureCacheLayout(Logger logger) {
        if (!MIGRATED.compareAndSet(false, true)) {
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
        Path legacyRoot = getLegacyCacheDir();
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
                Path target = cacheDir.resolve(entry.getFileName());
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
}
