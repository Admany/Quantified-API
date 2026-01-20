package org.admany.quantified.core.common.cache.disk;

import org.admany.quantified.core.common.util.QuantifiedPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class DiskCacheManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiskCacheManager.class);
    private static final ScheduledExecutorService MAINTENANCE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quantified-disk-cache-maintenance");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, ModDiskCache> MOD_CACHES = new ConcurrentHashMap<>();
    private static Path baseCachePath;
    private static boolean initialized = false;

    private DiskCacheManager() {}

    public static synchronized void initialize() {
        if (initialized) return;

        try {
            QuantifiedPaths.ensureCacheLayout(LOGGER);
            baseCachePath = QuantifiedPaths.getCacheDir();
            Files.createDirectories(baseCachePath);

            LOGGER.info("Initialized disk cache at: {}", baseCachePath);

            MAINTENANCE_EXECUTOR.scheduleAtFixedRate(DiskCacheManager::performMaintenance, 5, 60, TimeUnit.MINUTES);

            initialized = true;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize disk cache", e);
        }
    }

    public static ModDiskCache getModCache(String modId) {
        if (!initialized) return null;

        return MOD_CACHES.computeIfAbsent(modId, id -> {
            try {
                Path modPath = baseCachePath.resolve(id);
                Files.createDirectories(modPath);
                return new ModDiskCache(modPath, id);
            } catch (Exception e) {
                LOGGER.error("Failed to create disk cache for mod: {}", id, e);
                return null;
            }
        });
    }

    private static void performMaintenance() {
        if (!initialized) return;

        try {
            long maxBytes = (long) 500 * 1024 * 1024; // 500MB max
            long targetBytes = (long) 100 * 1024 * 1024; // 100MB target

            AtomicLong totalSize = new AtomicLong();
            List<FileInfo> files = new ArrayList<>();

            Files.walkFileTree(baseCachePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    long size = attrs.size();
                    totalSize.addAndGet(size);
                    files.add(new FileInfo(file, attrs.lastModifiedTime().toMillis(), size));
                    return FileVisitResult.CONTINUE;
                }
            });

            if (totalSize.get() > maxBytes) {
                files.sort(Comparator.comparingLong(f -> f.lastModified));

                for (FileInfo file : files) {
                    if (totalSize.get() <= targetBytes) break;
                    try {
                        Files.delete(file.path);
                        totalSize.addAndGet(-file.size);
                        LOGGER.debug("Evicted disk cache file: {}", file.path);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to evict disk cache file: {}", file.path, e);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Disk cache maintenance failed", e);
        }
    }

    public static void shutdown() {
        MAINTENANCE_EXECUTOR.shutdown();
        try {
            MAINTENANCE_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static synchronized void clearAll() {
        if (!initialized || baseCachePath == null) {
            return;
        }
        try {
            if (Files.exists(baseCachePath)) {
                Files.walk(baseCachePath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                Files.createDirectories(baseCachePath);
            }
            MOD_CACHES.clear();
            LOGGER.info("Disk caches cleared for all mods");
        } catch (Exception e) {
            LOGGER.warn("Failed to clear disk caches", e);
        }
    }

    public static List<CacheFileDescriptor> listCacheFiles() {
        if (!initialized || baseCachePath == null) {
            return java.util.Collections.emptyList();
        }

        List<CacheFileDescriptor> files = new ArrayList<>();
        try {
            Files.walkFileTree(baseCachePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = baseCachePath.relativize(file);
                    if (relative.getNameCount() == 0) {
                        return FileVisitResult.CONTINUE;
                    }
                    String modId = relative.getName(0).toString();
                    String fileName = relative.getNameCount() > 1
                        ? relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/')
                        : relative.getFileName().toString();
                    files.add(new CacheFileDescriptor(modId, fileName, attrs.size(), attrs.lastModifiedTime().toMillis()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Failed to enumerate disk caches", e);
        }
        return files;
    }

    public static synchronized boolean deleteCacheFile(String modId, String relativePath) {
        if (!initialized || baseCachePath == null || modId == null || modId.isBlank() || relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            Path modDir = baseCachePath.resolve(modId).normalize();
            Path target = modDir.resolve(relativePath).normalize();
            if (!target.startsWith(modDir)) {
                return false;
            }
            if (Files.isDirectory(target)) {
                Files.walk(target)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                return true;
            }
            return Files.deleteIfExists(target);
        } catch (Exception e) {
            LOGGER.warn("Failed to delete disk cache file {}/{}", modId, relativePath, e);
            return false;
        }
    }

    public static synchronized boolean deleteMod(String modId) {
        if (!initialized || baseCachePath == null || modId == null || modId.isBlank()) {
            return false;
        }
        try {
            Path modDir = baseCachePath.resolve(modId).normalize();
            if (!modDir.startsWith(baseCachePath) || !Files.exists(modDir)) {
                return false;
            }
            Files.walk(modDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to purge disk cache for {}", modId, e);
            return false;
        }
    }

    private static class FileInfo {
        final Path path;
        final long lastModified;
        final long size;

        FileInfo(Path path, long lastModified, long size) {
            this.path = path;
            this.lastModified = lastModified;
            this.size = size;
        }
    }

    public static class ModDiskCache {
        private final Path modPath;
        private final String modId;

        ModDiskCache(Path modPath, String modId) {
            this.modPath = modPath;
            this.modId = modId;
        }

        public <T> void put(String key, T value) {
            if (value == null) return;

            Path filePath = modPath.resolve(sanitizeKey(key) + ".cache.gz");
            try (ObjectOutputStream oos = new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(filePath)))) {
                oos.writeObject(value);
            } catch (Exception e) {
                LOGGER.warn("Failed to write disk cache for mod {} key {}", modId, key, e);
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String key, Class<T> type) {
            Path filePath = modPath.resolve(sanitizeKey(key) + ".cache.gz");
            if (!Files.exists(filePath)) return null;

            try (ObjectInputStream ois = new ObjectInputStream(new GZIPInputStream(Files.newInputStream(filePath)))) {
                Object obj = ois.readObject();
                if (type.isInstance(obj)) {
                    Files.setLastModifiedTime(filePath, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                    return (T) obj;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to read disk cache for mod {} key {}", modId, key, e);
                try {
                    Files.deleteIfExists(filePath);
                } catch (Exception ignored) {}
            }
            return null;
        }

        public boolean contains(String key) {
            Path filePath = modPath.resolve(sanitizeKey(key) + ".cache.gz");
            return Files.exists(filePath);
        }

        public void remove(String key) {
            Path filePath = modPath.resolve(sanitizeKey(key) + ".cache.gz");
            try {
                Files.deleteIfExists(filePath);
            } catch (Exception e) {
                LOGGER.warn("Failed to remove disk cache for mod {} key {}", modId, key, e);
            }
        }

        private String sanitizeKey(String key) {
            return key.replaceAll("[^a-zA-Z0-9_-]", "_");
        }
    }

    public record CacheFileDescriptor(String modId, String fileName, long sizeBytes, long lastModifiedMillis) {
    }
}
