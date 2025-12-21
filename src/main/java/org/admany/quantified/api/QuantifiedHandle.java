package org.admany.quantified.api;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.PriorityScheduler;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.admany.quantified.core.common.network.NetworkManager;
import org.admany.quantified.core.common.network.SecureChannel;
import org.admany.quantified.core.common.telemetry.Metrics;
import org.admany.quantified.core.forge.QuantifiedCoreForge;
import org.admany.quantified.core.common.util.ConnectedModImpl;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.admany.quantified.api.interfaces.ModCacheManager;
import org.admany.quantified.api.model.QuantifiedHybrid;
import org.admany.quantified.api.model.QuantifiedPacket;
import org.admany.quantified.api.model.QuantifiedStats;
import org.admany.quantified.api.model.QuantifiedTask;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class QuantifiedHandle {

    public static final String CACHE_PREFIX = "quantified.api.cache";

    final String modId;
    private volatile String version;
    private final Supplier<NetworkManager> networkAccessor;

    private final AtomicLong tasksSubmitted = new AtomicLong();
    private final AtomicLong tasksSucceeded = new AtomicLong();
    private final AtomicLong tasksFailed = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    final ConcurrentMap<String, ThreadSafeCache<String, Object>> caches = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<java.util.UUID>> channels = new ConcurrentHashMap<>();

    QuantifiedHandle(String modId, String version, Supplier<NetworkManager> networkAccessor) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.version = version;
        this.networkAccessor = Objects.requireNonNull(networkAccessor, "networkAccessor");
    }

    public QuantifiedHandle(String modId, String version) {
        this(modId, version, () -> null);
    }

    String modId() {
        return modId;
    }

    String version() {
        return version;
    }

    void updateVersion(String version) {
        if (version != null && !version.isBlank()) {
            this.version = version;
        }
    }

    private static final double MISS_RATE_BASELINE = 0.45d;
    private static final double MISS_RATE_RANGE = 0.3d;

    private static final double ADAPTIVE_THRESHOLD_MAX = 0.9d;
    private static final double QUEUE_PRESSURE_FACTOR = 0.2d;
    private static final double HIGH_QUEUE_PRESSURE_THRESHOLD = 1.5d;
    private static final double LOW_QUEUE_PRESSURE_THRESHOLD = 0.25d;
    private static final long HIGH_INFLIGHT_MULTIPLIER = 16L;
    private static final long LOW_INFLIGHT_MULTIPLIER = 4L;
    private static final long MIN_CACHE_SAMPLES = 16L;

    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

    <T> CompletableFuture<T> submitTask(QuantifiedTask<T> task) {
        Objects.requireNonNull(task, "task");
        ensureReady();
        final ConnectedModImpl modMetrics = QuantifiedAPI.lookupConnectedMod(modId);
        tasksSubmitted.incrementAndGet();
        if (modMetrics != null) {
            modMetrics.recordTaskSubmitted();
        }
        QuantifiedCoreForge.touchMod(modId);
        long key = ThreadLocalRandom.current().nextLong();
        Duration timeout = task.timeout().orElse(null);
        PriorityTaskType resolvedPriority = resolvePriority(task);
        double score = resolvedPriority.defaultScore();
        final long startTimeNanos = System.nanoTime();
        TaskScheduler.recordExternalCpuTask();
        TaskMetadata.Builder metadataBuilder = TaskMetadata.builder();
        if (task.gpuPreferred()) {
            metadataBuilder.gpuPreferred(true);
        }
        if (task.gpuRequired()) {
            metadataBuilder.gpuRequired(true);
        }
        TaskMetadata metadata = metadataBuilder.build();
        CompletableFuture<T> future = AsyncManager.submitSync(
            key,
            resolvedPriority,
            score,
            task.work(),
            timeout,
            task.threadSafe(),
            modId,
            metadata
        );
        return future.whenComplete((result, throwable) -> {
            long durationNanos = System.nanoTime() - startTimeNanos;
            QuantifiedCoreForge.touchMod(modId);
            if (throwable == null) {
                tasksSucceeded.incrementAndGet();
                Metrics.increment("quantified_api_tasks_success");
                if (modMetrics != null) {
                    modMetrics.recordTaskCompleted(durationNanos);
                }
            } else {
                tasksFailed.incrementAndGet();
                Metrics.increment("quantified_api_tasks_failed");
                if (modMetrics != null) {
                    modMetrics.recordTaskFailed();
                }
            }
        });
    }

    <T> CompletableFuture<T> submitHybrid(QuantifiedHybrid<T> hybrid) {
        Objects.requireNonNull(hybrid, "hybrid");
        ensureReady();
        ThreadSafeCache<String, Object> cache = cacheFor(hybrid.cacheName(), hybrid.maximumSize(), hybrid.ttl());
        String cacheKey = hybrid.cacheKey();
        final ConnectedModImpl modMetrics = QuantifiedAPI.lookupConnectedMod(modId);
        if (cacheKey != null) {
            @SuppressWarnings("unchecked")
            T cached = (T) cache.getIfPresent(cacheKey);
            if (cached != null) {
                cacheHits.incrementAndGet();
                if (modMetrics != null) {
                    modMetrics.recordCacheHit();
                }
                QuantifiedCoreForge.touchMod(modId);
                return CompletableFuture.completedFuture(cached);
            }
            cacheMisses.incrementAndGet();
            if (modMetrics != null) {
                modMetrics.recordCacheMiss();
            }
        }
        return submitTask(hybrid.toTask()).whenComplete((result, throwable) -> {
            if (throwable == null && cacheKey != null && result != null) {
                cache.put(cacheKey, result);
                if (modMetrics != null) {
                    modMetrics.recordCacheEntryAdded();
                    modMetrics.updateCacheSize(cache.size());
                }
            }
        });
    }

    <T> T cacheGet(String cacheName, String key, Supplier<T> loader, Duration ttl, long maximumSize, boolean persistence) {
        Objects.requireNonNull(cacheName, "cacheName");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        ensureReady();
        ThreadSafeCache<String, Object> cache = cacheFor(cacheName, maximumSize, ttl, persistence);
        final ConnectedModImpl modMetrics = QuantifiedAPI.lookupConnectedMod(modId);
        @SuppressWarnings("unchecked")
        T cached = (T) cache.getIfPresent(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            if (modMetrics != null) {
                modMetrics.recordCacheHit();
            }
            QuantifiedCoreForge.touchMod(modId);
            return cached;
        }
        cacheMisses.incrementAndGet();
        if (modMetrics != null) {
            modMetrics.recordCacheMiss();
        }
        T computed = loader.get();
        if (computed == null) {
            QuantifiedCoreForge.touchMod(modId);
            return null;
        }
        cache.put(key, computed);
        if (modMetrics != null) {
            modMetrics.recordCacheEntryAdded();
            modMetrics.updateCacheSize(cache.size());
        }
        QuantifiedCoreForge.touchMod(modId);
        return computed;
    }

    CompletableFuture<Void> sendPacket(String channelName, QuantifiedPacket packet) {
        Objects.requireNonNull(channelName, "channelName");
        Objects.requireNonNull(packet, "packet");
        ensureReady();
        NetworkManager network = networkAccessor.get();
        if (network == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Networking is disabled in Quantified configuration"));
        }
        final ConnectedModImpl modMetrics = QuantifiedAPI.lookupConnectedMod(modId);
        CompletableFuture<java.util.UUID> channelFuture = channels.computeIfAbsent(channelName, name ->
            network.createChannel().thenApply(SecureChannel::getChannelId)
        );
        return channelFuture.thenCompose(channelId ->
            network.sendPacket(channelId, packet.toPacket(modId, channelName))
                .whenComplete((ignored, throwable) -> {
                    if (throwable == null && modMetrics != null) {
                        modMetrics.recordPacketSent();
                    }
                })
        );
    }

    QuantifiedStats.ModStats snapshotStats() {
        long submitted = tasksSubmitted.get();
        long succeeded = tasksSucceeded.get();
        long failed = tasksFailed.get();
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        return new QuantifiedStats.ModStats(
            modId,
            version,
            submitted,
            succeeded,
            failed,
            hits,
            misses,
            Optional.ofNullable(QuantifiedCoreForge.getModInfo(modId)).map(info -> info.lastActivity).orElse(0L)
        );
    }

    Map<String, ThreadSafeCache<String, Object>> snapshotCaches() {
        return Map.copyOf(caches);
    }

    ModCacheManager getCacheManager() {
        return new ModCacheManagerImpl(this);
    }

    void closeChannels() {
        CompletableFuture.allOf(channels.values().toArray(CompletableFuture[]::new)).thenAccept(ignored -> channels.clear());
    }

    private void ensureReady() {
        if (!AsyncManager.isInitialised()) {
            throw new IllegalStateException("Quantified core not initialised yet");
        }
    }

    private PriorityTaskType resolvePriority(QuantifiedTask<?> task) {
        if (!task.autoPriority()) {
            return task.priority();
        }
        try {
            PriorityScheduler.SchedulerSnapshot snapshot = AsyncManager.schedulerSnapshot();
            long inFlight = inFlightTasks();
            int desiredWorkers = Math.max(1, snapshot.desiredForegroundWorkers() + snapshot.desiredBackgroundWorkers());
            int queued = snapshot.foregroundQueue() + snapshot.backgroundQueue();
            double queuePressure = queued / (double) Math.max(1, desiredWorkers);
            double missRate = cacheMissRate();
            long cacheSamples = cacheHits.get() + cacheMisses.get();

            double adaptiveThreshold = Math.min(ADAPTIVE_THRESHOLD_MAX, MISS_RATE_BASELINE + Math.min(MISS_RATE_RANGE, queuePressure * QUEUE_PRESSURE_FACTOR));
            if (cacheSamples >= MIN_CACHE_SAMPLES && missRate > adaptiveThreshold) {
                return PriorityTaskType.CACHE;
            }
            if (queuePressure > HIGH_QUEUE_PRESSURE_THRESHOLD || inFlight > desiredWorkers * HIGH_INFLIGHT_MULTIPLIER) {
                return PriorityTaskType.BACKGROUND;
            }
            if (queuePressure < LOW_QUEUE_PRESSURE_THRESHOLD && inFlight < desiredWorkers * LOW_INFLIGHT_MULTIPLIER) {
                return PriorityTaskType.FOREGROUND;
            }
            return PriorityTaskType.OTHER;
        } catch (IllegalStateException ignored) {
            return PriorityTaskType.OTHER;
        }
    }

    private long inFlightTasks() {
        long submitted = tasksSubmitted.get();
        long completed = tasksSucceeded.get() + tasksFailed.get();
        long inFlight = submitted - completed;
        return inFlight < 0L ? 0L : inFlight;
    }

    private double cacheMissRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        return total == 0L ? 0.0d : (double) misses / total;
    }

    private ThreadSafeCache<String, Object> cacheFor(String cacheName, long maximumSize, Duration ttl) {
        return cacheFor(cacheName, maximumSize, ttl, false); // Default to no persistence
    }

    private ThreadSafeCache<String, Object> cacheFor(String cacheName, long maximumSize, Duration ttl, boolean persistence) {
        String normalized = CACHE_PREFIX + "." + modId + "." + cacheName;
        return caches.computeIfAbsent(normalized, name -> {
            ThreadSafeCache<String, Object> existing = CacheManager.lookup(name);
            if (existing != null) {
                return existing;
            }
            long effectiveSize = maximumSize > 0 ? maximumSize : 10000; // Default cache size
            Duration effectiveTtl = ttl == null || ttl.isNegative() || ttl.isZero()
                ? DEFAULT_CACHE_TTL
                : ttl;
            return CacheManager.register(name, effectiveSize, effectiveTtl, false, persistence);
        });
    }
}

class ModCacheManagerImpl implements ModCacheManager {

    private final QuantifiedHandle handle;
    private volatile long memoryLimitMB = 512; // Default 512MB per mod

    ModCacheManagerImpl(QuantifiedHandle handle) {
        this.handle = handle;
    }

    @Override
    public long getTotalCacheSizeMB() {
        long totalBytes = 0;
        for (ThreadSafeCache<String, Object> cache : handle.caches.values()) {
            totalBytes += cache.size() * 256; 
        }
        return totalBytes / (1024 * 1024);
    }

    @Override
    public int getTotalCacheEntryCount() {
        long total = 0;
        for (ThreadSafeCache<String, Object> cache : handle.caches.values()) {
            total += cache.size();
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    @Override
    public void clearAllCaches() {
        for (ThreadSafeCache<String, Object> cache : handle.caches.values()) {
            cache.invalidateAll();
        }
        handle.caches.clear();
    }

    @Override
    public long clearOldCaches(long maxAgeMs) {
        long freedBytes = 0;


        for (ThreadSafeCache<String, Object> cache : handle.caches.values()) {
            long originalSize = cache.size();
            if (originalSize > 0) {
                cache.invalidateAll();
                freedBytes += originalSize * 256; 
            }
        }
        return freedBytes / (1024 * 1024);
    }

    @Override
    public Set<String> getCacheNames() {
        Set<String> names = new HashSet<>();
        for (String fullName : handle.caches.keySet()) {
            if (fullName.startsWith(QuantifiedHandle.CACHE_PREFIX + "." + handle.modId + ".")) {
                names.add(fullName.substring((QuantifiedHandle.CACHE_PREFIX + "." + handle.modId + ".").length()));
            }
        }
        return names;
    }

    @Override
    public long getCacheSizeMB(String cacheName) {
        ThreadSafeCache<String, Object> cache = getCache(cacheName);
        if (cache == null) return 0;
        return (cache.size() * 256L) / (1024 * 1024); 
    }

    @Override
    public int getCacheEntryCount(String cacheName) {
        ThreadSafeCache<String, Object> cache = getCache(cacheName);
        return cache != null ? (int) Math.min(cache.size(), Integer.MAX_VALUE) : 0;
    }

    @Override
    public void clearCache(String cacheName) {
        ThreadSafeCache<String, Object> cache = getCache(cacheName);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    @Override
    public long clearOldCacheEntries(String cacheName, long maxAgeMs) {
        ThreadSafeCache<String, Object> cache = getCache(cacheName);
        if (cache == null) return 0;

        int originalSize = (int) Math.min(cache.size(), Integer.MAX_VALUE);
        cache.invalidateAll();
        return (originalSize * 256L) / (1024 * 1024);
    }

    @Override
    public void setMemoryLimitMB(long maxMB) {
        this.memoryLimitMB = Math.max(1, maxMB);
    }

    @Override
    public long getMemoryLimitMB() {
        return this.memoryLimitMB;
    }

    @Override
    public boolean isMemoryPressureHigh() {
        return getTotalCacheSizeMB() > memoryLimitMB;
    }

    @Override
    public void triggerMemoryPressureCleanup() {
        if (isMemoryPressureHigh()) {
            clearOldCaches(300_000); 
        }
    }

    @Override
    public Map<String, CacheStats> getAllCacheStats() {
        Map<String, CacheStats> stats = new HashMap<>();
        for (String cacheName : getCacheNames()) {
            CacheStats cacheStats = getCacheStats(cacheName);
            if (cacheStats != null) {
                stats.put(cacheName, cacheStats);
            }
        }
        return stats;
    }

    @Override
    public CacheStats getCacheStats(String cacheName) {
        ThreadSafeCache<String, Object> cache = getCache(cacheName);
        if (cache == null) return null;

        return new CacheStats() {
            @Override
            public long sizeMB() {
                return getCacheSizeMB(cacheName);
            }

            @Override
            public int entryCount() {
                return (int) Math.min(cache.size(), Integer.MAX_VALUE);
            }

            @Override
            public long oldestEntryAgeMs() {
                return 0;
            }

            @Override
            public long newestEntryAgeMs() {
                return 0;
            }

            @Override
            public String cacheType() {
                return "ThreadSafeCache";
            }
        };
    }

    private ThreadSafeCache<String, Object> getCache(String cacheName) {
        String fullName = QuantifiedHandle.CACHE_PREFIX + "." + handle.modId + "." + cacheName;
        return handle.caches.get(fullName);
    }
}
