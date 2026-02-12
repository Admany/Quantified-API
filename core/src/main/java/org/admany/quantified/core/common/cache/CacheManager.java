package org.admany.quantified.core.common.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.admany.quantified.core.common.cache.impl.CaffeineThreadSafeCache;
import org.admany.quantified.core.common.cache.impl.PersistentCache;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CacheManager {

    private static final Logger LOGGER = Logger.getLogger(CacheManager.class.getName());

    private static final ConcurrentMap<String, ThreadSafeCache<?, ?>> REGISTRY = new ConcurrentHashMap<>();
    private static final long ESTIMATED_ENTRY_BYTES = 512L;
    private static final AtomicBoolean MAINTENANCE_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean DISK_SCAN_IN_FLIGHT = new AtomicBoolean(false);
    private static ScheduledExecutorService maintenanceExecutor;
    private static ScheduledExecutorService diskUsageExecutor;
    private static ScheduledFuture<?> maintenanceFuture;
    private static volatile Duration maintenanceIdleThreshold = Duration.ofMinutes(5);
    private static volatile long lastDiskUsageBytes;
    private static volatile long lastDiskScanTimeMs;
    private static final long DISK_USAGE_REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

    private CacheManager() {
    }

    public static <K, V> ThreadSafeCache<K, V> register(String name, Supplier<CaffeineThreadSafeCache.CacheBuilderSpec> specSupplier) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(specSupplier, "specSupplier");
        return getOrCreate(name, () -> CaffeineThreadSafeCache.create(specSupplier.get()));
    }

    public static <K, V> ThreadSafeCache<K, V> register(String name, long maximumSize, Duration ttl, boolean refreshOnAccess) {
        return register(name, () -> new CaffeineThreadSafeCache.CacheBuilderSpec(maximumSize, ttl, refreshOnAccess, 0));
    }

    public static <K, V> ThreadSafeCache<K, V> register(String name, long maximumSize, Duration ttl, boolean refreshOnAccess, boolean persistence) {
        return register(name, () -> new CaffeineThreadSafeCache.CacheBuilderSpec(maximumSize, ttl, refreshOnAccess, 0), persistence, true);
    }

    public static <K, V> ThreadSafeCache<K, V> register(String name, long maximumSize, Duration ttl, boolean refreshOnAccess, boolean persistence, boolean compression) {
        return register(name, () -> new CaffeineThreadSafeCache.CacheBuilderSpec(maximumSize, ttl, refreshOnAccess, 0), persistence, compression);
    }

    public static <K, V> ThreadSafeCache<K, V> register(String name, Supplier<CaffeineThreadSafeCache.CacheBuilderSpec> specSupplier, boolean persistence) {
        return register(name, specSupplier, persistence, true);
    }

    public static <K, V> ThreadSafeCache<K, V> register(String name, Supplier<CaffeineThreadSafeCache.CacheBuilderSpec> specSupplier, boolean persistence, boolean compression) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(specSupplier, "specSupplier");

        return getOrCreate(name, () -> {
            ThreadSafeCache<K, V> cache = CaffeineThreadSafeCache.create(specSupplier.get());
            if (persistence) {
                String modId = extractModId(name);
                String cacheName = extractCacheName(name);
                cache = new PersistentCache<>(cache, modId, cacheName, compression);
            }
            return cache;
        });
    }

    private static String extractModId(String fullName) {
        int dotIndex = fullName.indexOf('.');
        return dotIndex > 0 ? fullName.substring(0, dotIndex) : "unknown";
    }

    private static String extractCacheName(String fullName) {
        int dotIndex = fullName.indexOf('.');
        return dotIndex > 0 ? fullName.substring(dotIndex + 1) : fullName;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> ThreadSafeCache<K, V> lookup(String name) {
        return (ThreadSafeCache<K, V>) REGISTRY.get(name);
    }

    public static void shutdownAll() {
        stopMaintenance();
        for (Map.Entry<String, ThreadSafeCache<?, ?>> entry : REGISTRY.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Cache shutdown failure for {0}", entry.getKey());
            }
        }
        REGISTRY.clear();
    }

    public static void clearAllCaches() {
        for (Map.Entry<String, ThreadSafeCache<?, ?>> entry : REGISTRY.entrySet()) {
            try {
                entry.getValue().invalidateAll();
                LOGGER.log(Level.FINE, "Cleared cache {0}", entry.getKey());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Cache clear failure for {0}: {1}", new Object[]{entry.getKey(), e.getMessage()});
            }
        }
    }

    public static void startMaintenance(Duration interval, Duration idleThreshold) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            LOGGER.fine("Cache maintenance start skipped due to invalid interval");
            return;
        }

        Duration sanitizedIdle = (idleThreshold == null || idleThreshold.isZero() || idleThreshold.isNegative())
            ? interval
            : idleThreshold;
        maintenanceIdleThreshold = sanitizedIdle;

        if (MAINTENANCE_STARTED.compareAndSet(false, true)) {
            maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "quantified-cache-maintainer");
                thread.setDaemon(true);
                return thread;
            });
            maintenanceFuture = maintenanceExecutor.scheduleAtFixedRate(
                CacheManager::runMaintenanceSafely,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
            );
            LOGGER.log(Level.FINE, "Cache maintenance started (interval={0}s, idleThreshold={1}s)",
                new Object[]{interval.toSeconds(), sanitizedIdle.toSeconds()});
        }
    }

    public static CacheInventory inventory() {
        ConcurrentHashMap<String, ThreadSafeCache.CacheStats> stats = new ConcurrentHashMap<>();
        REGISTRY.forEach((name, cache) -> cache.stats().ifPresent(stat -> stats.put(name, stat)));
        return new CacheInventory(stats);
    }

    public static DetailedInventory detailedInventory() {
        ConcurrentHashMap<String, CacheDetail> details = new ConcurrentHashMap<>();
        REGISTRY.forEach((name, cache) -> {
            long entries = safeSize(cache);
            ThreadSafeCache.CacheStats stats = cache.stats().orElse(null);
            details.put(name, new CacheDetail(entries, stats));
        });
        return new DetailedInventory(details);
    }

    public static long getTotalCacheSize() {
        return REGISTRY.values().stream().mapToLong(ThreadSafeCache::size).sum();
    }

    public static CacheUsage cacheUsageSnapshot() {
        long totalEntries = 0L;
        long estimatedHeapBytes = 0L;

        for (ThreadSafeCache<?, ?> cache : REGISTRY.values()) {
            long entries = safeSize(cache);
            totalEntries += entries;
            estimatedHeapBytes += estimateHeapBytes(entries);
        }

        long diskBytes = refreshDiskUsageIfNeeded();
        return new CacheUsage(totalEntries, estimatedHeapBytes, diskBytes);
    }

    private static long refreshDiskUsageIfNeeded() {
        long now = System.currentTimeMillis();
        long age = now - lastDiskScanTimeMs;
        if (lastDiskScanTimeMs == 0L) {
            // Prime the cache usage snapshot synchronously on first call to avoid prolonged zero values.
            try {
                lastDiskUsageBytes = computeDiskUsageBytes();
                lastDiskScanTimeMs = now;
            } catch (Exception ex) {
                LOGGER.log(Level.FINER, "Failed to prime disk cache usage", ex);
            }
            return lastDiskUsageBytes;
        }
        if (age > DISK_USAGE_REFRESH_INTERVAL_MS && DISK_SCAN_IN_FLIGHT.compareAndSet(false, true)) {
            ScheduledExecutorService executor = diskUsageExecutor;
            if (executor == null) {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "quantified-cache-disk-usage");
                    thread.setDaemon(true);
                    return thread;
                });
                diskUsageExecutor = executor;
            }
            executor.execute(() -> {
                try {
                    long computed = computeDiskUsageBytes();
                    lastDiskUsageBytes = computed;
                    lastDiskScanTimeMs = System.currentTimeMillis();
                } catch (Exception ex) {
                    LOGGER.log(Level.FINER, "Failed to refresh disk cache usage", ex);
                } finally {
                    DISK_SCAN_IN_FLIGHT.set(false);
                }
            });
        }
        return lastDiskUsageBytes;
    }

    private static long safeSize(ThreadSafeCache<?, ?> cache) {
        try {
            return Math.max(0L, cache.size());
        } catch (Exception ex) {
            LOGGER.log(Level.FINER, "Failed to query cache size", ex);
            return 0L;
        }
    }

    private static long estimateHeapBytes(long entryCount) {
        if (entryCount <= 0L) {
            return 0L;
        }
        long estimate = entryCount * ESTIMATED_ENTRY_BYTES;
        return estimate < 0L ? Long.MAX_VALUE : estimate;
    }

    private static long computeDiskUsageBytes() {
        Path root = PersistentCache.cacheRootDirectory();
        if (root == null) {
            return 0L;
        }
        try {
            if (!Files.exists(root)) {
                return 0L;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ex) {
                            LOGGER.log(Level.FINER, "Failed to read cache file size", ex);
                            return 0L;
                        }
                    })
                    .sum();
            }
        } catch (IOException ex) {
            LOGGER.log(Level.FINER, "Failed to enumerate cache disk usage", ex);
            return 0L;
        }
    }

    private static <K, V> ThreadSafeCache<K, V> getOrCreate(String name, Supplier<ThreadSafeCache<K, V>> factory) {
        ThreadSafeCache<?, ?> cache = REGISTRY.computeIfAbsent(name, key -> {
            ThreadSafeCache<K, V> created = factory.get();
            LOGGER.log(Level.FINE, "Registered cache " + name);
            return created;
        });
        @SuppressWarnings("unchecked")
        ThreadSafeCache<K, V> typed = (ThreadSafeCache<K, V>) cache;
        return typed;
    }

    private static void runMaintenanceSafely() {
        try {
            REGISTRY.forEach((name, cache) -> {
                try {
                    cache.pruneIdleEntries(maintenanceIdleThreshold);
                    cache.size();
                } catch (Exception cacheError) {
                    LOGGER.log(Level.FINEST, "Cache maintenance failed for {0}: {1}",
                        new Object[]{name, cacheError.getMessage()});
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.FINER, "Cache maintenance cycle failed", e);
        }
    }

    private static void stopMaintenance() {
        if (maintenanceFuture != null) {
            maintenanceFuture.cancel(false);
            maintenanceFuture = null;
        }
        if (maintenanceExecutor != null) {
            maintenanceExecutor.shutdownNow();
            maintenanceExecutor = null;
        }
        if (diskUsageExecutor != null) {
            diskUsageExecutor.shutdownNow();
            diskUsageExecutor = null;
        }
        MAINTENANCE_STARTED.set(false);
    }

    public record CacheInventory(Map<String, ThreadSafeCache.CacheStats> statsByName) {
        public Optional<ThreadSafeCache.CacheStats> statsFor(String name) {
            return Optional.ofNullable(statsByName.get(name));
        }
    }

    public record DetailedInventory(Map<String, CacheDetail> caches) {
        public Optional<CacheDetail> detailFor(String name) {
            return Optional.ofNullable(caches.get(name));
        }
    }

    public record CacheDetail(long entries, ThreadSafeCache.CacheStats stats) {
    }

    public record CacheUsage(long entryCount, long heapBytes, long diskBytes) {
    }
}
