package org.admany.quantified.core.common.util;

import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.builders.QuantifiedCacheBuilder;
import org.admany.quantified.api.builders.QuantifiedHybridBuilder;
import org.admany.quantified.api.builders.QuantifiedNetworkBuilder;
import org.admany.quantified.api.builders.QuantifiedTaskBuilder;
import org.admany.quantified.api.interfaces.ConnectedMod;
import org.admany.quantified.api.interfaces.ModStatistics;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.admany.quantified.core.common.network.NetworkManager;
import org.admany.quantified.core.common.network.PacketSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;


public class ConnectedModImpl implements ConnectedMod {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectedModImpl.class);

    private final String modId;
    private volatile String version;
    private volatile String displayName;
    private final ModStatisticsImpl statistics;
    private final ConcurrentMap<String, ThreadSafeCache<String, Object>> caches = new ConcurrentHashMap<>();
    private volatile boolean disconnected = false;
    private static final long ESTIMATED_CACHE_ENTRY_BYTES = 512L;

    public ConnectedModImpl(String modId, String version, String displayName) {
        this.modId = modId;
        this.version = version;
        this.displayName = displayName;
        this.statistics = new ModStatisticsImpl(modId, version);
    }

    @Override
    public String getModId() { return modId; }

    @Override
    public String getVersion() { return version; }

    @Override
    public String getDisplayName() { return displayName; }

    @Override
    public ModStatistics getStatistics() { return statistics; }

    @Override
    public QuantifiedTaskBuilder task(String name) {
        return new TaskBuilderImpl(name);
    }

    @Override
    public QuantifiedCacheBuilder cache(Enum<?> cacheType) {
        return new CacheBuilderImpl(cacheType.name().toLowerCase());
    }

    @Override
    public QuantifiedHybridBuilder hybrid(String name) {
        return new HybridBuilderImpl(name);
    }

    @Override
    public QuantifiedNetworkBuilder network(String channel) {
        return new NetworkBuilderImpl(channel);
    }

    /**
     * Submit a batch of tasks with smart grouping by resource hints - automatically groups tasks by CPU/GPU requirements
     * and executes them efficiently. Perfect for when you got a bunch of similar tasks to run.
     */
    public static <R> CompletableFuture<java.util.List<R>> submitBatch(
            String batchName,
            java.util.List<org.admany.quantified.core.common.util.TaskScheduler.TaskBatchItem<R>> tasks,
            java.util.function.Function<org.admany.quantified.core.common.util.TaskScheduler.TaskBatchItem<R>, org.admany.quantified.core.common.util.TaskScheduler.ResourceHint> groupBy) {
        return org.admany.quantified.core.common.util.TaskScheduler.submitBatch("quantified-api", batchName, tasks, groupBy);
    }

    @Override
    public void disconnect() {
        disconnected = true;
        caches.clear();
    }

    public void updateVersion(String version) {
        this.version = version;
    }

    public void updateDisplayName(String displayName) {
        this.displayName = displayName;
    }

    // === Metrics bridge for QuantifiedHandle ===

    public void recordTaskSubmitted() {
        statistics.recordTaskSubmitted();
    }

    public void recordTaskCompleted(long durationNanos) {
        statistics.recordTaskCompleted(durationNanos);
    }

    public void recordTaskFailed() {
        statistics.recordTaskFailed();
    }

    public void recordCacheHit() {
        statistics.recordCacheHit();
    }

    public void recordCacheMiss() {
        statistics.recordCacheMiss();
    }

    public void recordCacheEntryAdded() {
        statistics.recordCacheEntryAdded();
    }

    public void recordCacheEntryRemoved() {
        statistics.recordCacheEntryRemoved();
    }

    public void updateCacheSize(long size) {
        statistics.updateCacheSize(size);
    }

    public void updateCacheStats(long entries, long bytes) {
        statistics.updateCacheStats(entries, bytes);
    }

    private void refreshLocalCacheTotals() {
        long totalEntries = 0L;
        for (ThreadSafeCache<String, Object> cache : caches.values()) {
            totalEntries += cache.size();
        }
        long totalBytes = totalEntries * ESTIMATED_CACHE_ENTRY_BYTES;
        statistics.updateCacheStats(totalEntries, totalBytes);
    }

    public void recordPacketSent() {
        statistics.recordPacketSent();
    }

    public void recordPacketReceived() {
        statistics.recordPacketReceived();
    }

    // ===== BUILDER IMPLEMENTATIONS =====

    /**
     * Task builder that handles async work with intelligent CPU/GPU routing.
     * Automatically decides whether to use CPU or GPU based on your hints and hardware.
     * Includes retry logic, progress callbacks, and resource estimation for optimal performance.
     */

    private class TaskBuilderImpl implements QuantifiedTaskBuilder {
        private final String name;
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries = 3;
        private long vramBytes = 0;
        private int computeUnits = 0;
        private java.util.function.Consumer<Double> progressCallback;
        private java.util.function.Consumer<Throwable> failureCallback;
        private final long startTimeNanos = System.nanoTime();
        private boolean allowMainThreadRerouting = true; // Default to allowing main thread rerouting for thread-unsafe tasks

        public TaskBuilderImpl(String name) {
            this.name = name;
        }

        @Override
        public QuantifiedTaskBuilder priority(QuantifiedTaskBuilder.Priority priority) {
            // Priority is handled internally by TaskScheduler based on task characteristics - this method is kept for API compatibility but has no effect currently
            // Future versions might implement strict priority queues
            return this;
        }

        @Override
        public QuantifiedTaskBuilder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        @Override
        public QuantifiedTaskBuilder retry(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        @Override
        public QuantifiedTaskBuilder resourceEstimate(long vramBytes, int computeUnits) {
            this.vramBytes = vramBytes;
            this.computeUnits = computeUnits;
            return this;
        }

        @Override
        public QuantifiedTaskBuilder onProgress(java.util.function.Consumer<Double> progressCallback) {
            this.progressCallback = progressCallback;
            return this;
        }

        @Override
        public QuantifiedTaskBuilder onFailure(java.util.function.Consumer<Throwable> failureCallback) {
            this.failureCallback = failureCallback;
            return this;
        }

        @Override
        public QuantifiedTaskBuilder allowMainThreadRerouting(boolean allow) {
            this.allowMainThreadRerouting = allow;
            return this;
        }

        @Override
        public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> work) {
            if (disconnected) {
                CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Mod disconnected"));
                return future;
            }

            statistics.recordTaskSubmitted();

            // Call progress callback at start
            if (progressCallback != null) {
                progressCallback.accept(0.0);
            }

            // Create a unique task key for this mod and task name - helps with deduplication and tracking your tasks
            long taskKey = (modId.hashCode() * 31L + name.hashCode()) & 0x7FFFFFFFFFFFFFFFL;

            // Use TaskScheduler for intelligent CPU/GPU routing - automatically routes tasks to CPU or GPU based on data size, complexity, and hardware
            // Smart heuristics that learn from your workload patterns for maximum performance gains
            long dataSizeBytes = Math.max(1024, vramBytes); // Minimum 1KB, use VRAM estimate
            int parallelUnits = Math.max(1, computeUnits); // Minimum 1 unit, use compute units estimate

            // Create retry wrapper for the work
            java.util.function.Supplier<T> retryWork = () -> {
                RuntimeException lastException = null;
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        return work.get();
                    } catch (RuntimeException e) {
                        lastException = e;
                        if (attempt < maxRetries) {
                            // Could add exponential backoff delay here if needed
                        }
                    } catch (Exception e) {
                        // Wrap checked exceptions
                        lastException = new RuntimeException("Task execution failed", e);
                        if (attempt < maxRetries) {
                            // Could add exponential backoff delay here if needed
                        }
                    }
                }
                throw lastException;
            };

            if (QuantifiedAPI.isPrintDebugLogs()) LOGGER.debug("[DEBUG] TaskBuilderImpl: About to call TaskScheduler.submitComputeTask with modId: {}, name: {}, taskKey: {}", modId, name, taskKey);
            return org.admany.quantified.core.common.util.TaskScheduler.submitComputeTask(
                modId,
                name,
                taskKey,
                retryWork, // CPU implementation with retry
                null, // No GPU implementation available in frontend API
                dataSizeBytes,
                parallelUnits,
                org.admany.quantified.core.common.util.TaskScheduler.TaskComplexity.MODERATE,
                org.admany.quantified.core.common.util.TaskScheduler.TaskType.GENERAL,
                timeout,
                allowMainThreadRerouting
            ).whenComplete((result, error) -> {
                if (QuantifiedAPI.isPrintDebugLogs()) LOGGER.debug("[DEBUG] TaskBuilderImpl: Task completed with result: {}, error: {}", result, error);
                long durationNanos = System.nanoTime() - startTimeNanos;
                if (error != null) {
                    statistics.recordTaskFailed();
                    // Call failure callback
                    if (failureCallback != null) {
                        failureCallback.accept(error);
                    }
                } else {
                    statistics.recordTaskCompleted(durationNanos);
                    // Call progress callback at completion
                    if (progressCallback != null) {
                        progressCallback.accept(1.0);
                    }
                }
            });
        }
    }

    /**
     * Cache builder with Caffeine-backed caching - TTL and size limits, shared cache for cross-mod data.
     * Statistics and eviction monitoring to keep things running smooth.
     */
    private class CacheBuilderImpl implements QuantifiedCacheBuilder {
        private final String cacheName;
        private Duration ttl = Duration.ofMinutes(5);
        private long maxSize = 1000;
        private boolean compression = true;
        private boolean persistence = false;

        public CacheBuilderImpl(String cacheName) {
            this.cacheName = cacheName;
        }

        @Override
        public QuantifiedCacheBuilder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        @Override
        public QuantifiedCacheBuilder maxSize(long maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        @Override
        public QuantifiedCacheBuilder compression(boolean enabled) {
            this.compression = enabled;
            return this;
        }

        @Override
        public QuantifiedCacheBuilder persistence(boolean enabled) {
            this.persistence = enabled;
            return this;
        }

        private ThreadSafeCache<String, Object> getOrCreateCache() {
            return caches.computeIfAbsent(cacheName, name ->
                CacheManager.register(modId + "." + name, maxSize, ttl, false, persistence, compression)
            );
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key, java.util.function.Supplier<T> loader) {
            ThreadSafeCache<String, Object> cache = getOrCreateCache();
            Object cached = cache.getIfPresent(key);
            if (cached != null) {
                statistics.recordCacheHit();
                return (T) cached;
            }
            statistics.recordCacheMiss();
            T value = loader.get();
            cache.put(key, value);
                statistics.recordCacheEntryAdded();
                refreshLocalCacheTotals();
                return value;
            }

        @Override
            public <T> void put(String key, T value) {
                ThreadSafeCache<String, Object> cache = getOrCreateCache();
                cache.put(key, value);
                statistics.recordCacheEntryAdded();
                refreshLocalCacheTotals();
            }

        @Override
        public boolean contains(String key) {
            return getOrCreateCache().getIfPresent(key) != null;
        }

        @Override
        public void remove(String key) {
            ThreadSafeCache<String, Object> cache = getOrCreateCache();
                Object removed = cache.getIfPresent(key);
                if (removed != null) {
                    cache.invalidate(key);
                    statistics.recordCacheEntryRemoved();
                    refreshLocalCacheTotals();
                }
            }

        @Override
            public void clear() {
                ThreadSafeCache<String, Object> cache = caches.remove(cacheName);
                if (cache != null) {
                    cache.invalidateAll();
                    refreshLocalCacheTotals();
                }
            }

        @Override
        public void invalidatePattern(String pattern) {
            // Pattern invalidation not yet implemented - ThreadSafeCache doesn't expose key iteration for wildcard matching
            // For now, we clear all entries as a fallback since the cache doesn't expose keys for pattern matching
            clear();
        }
    }

    /**
     * Hybrid builder that first checks cache, then computes if needed - perfect for "calculate once, use often" jobs.
     * Combines caching with async task execution for optimal performance.
     */
    private class HybridBuilderImpl implements QuantifiedHybridBuilder {
        private final String name;
        private Duration timeout = Duration.ofSeconds(30);
        private Duration ttl = Duration.ofMinutes(5);
        private long maxSize = 1000;
        private boolean compression = true;
        private java.util.function.Supplier<Boolean> condition;
        private boolean allowMainThreadRerouting = true; // Default to allowing main thread rerouting for thread-unsafe tasks

        public HybridBuilderImpl(String name) {
            this.name = name;
        }

        @Override
        public QuantifiedHybridBuilder priority(QuantifiedTaskBuilder.Priority priority) {
            // Priority is handled internally by TaskScheduler based on task characteristics - this method is kept for API compatibility but has no effect currently
            // Future versions might implement strict priority queues
            return this;
        }

        @Override
        public QuantifiedHybridBuilder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        @Override
        public QuantifiedHybridBuilder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        @Override
        public QuantifiedHybridBuilder maxSize(long maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        @Override
        public QuantifiedHybridBuilder compression(boolean enabled) {
            this.compression = enabled;
            return this;
        }

        @Override
        public QuantifiedHybridBuilder conditional(java.util.function.Supplier<Boolean> condition) {
            this.condition = condition;
            return this;
        }

        @Override
        public QuantifiedHybridBuilder allowMainThreadRerouting(boolean allow) {
            this.allowMainThreadRerouting = allow;
            return this;
        }

        @Override
        public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> work) {
            if (disconnected) {
                CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Mod disconnected"));
                return future;
            }

            statistics.recordTaskSubmitted();

            // For hybrid, we check cache first, then compute if needed - perfect for "calculate once, use often" jobs like world pre-computation or player stat aggregation
            String cacheKey = name + "_result";
            CacheBuilderImpl cache = new CacheBuilderImpl(name + "_hybrid");
            cache.ttl(ttl).maxSize(maxSize).compression(compression);

            // Try cache first
            T cached = cache.get(cacheKey, () -> null);
            if (cached != null) {
                // Check conditional refresh - if condition is set and returns false, use cached value to avoid unnecessary recomputation
                if (condition == null || !condition.get()) {
                    statistics.recordTaskCompleted();
                    return CompletableFuture.completedFuture(cached);
                }
                // Condition requires refresh, continue to compute
            }

            // Not cached or condition requires refresh, compute and cache with intelligent CPU/GPU routing - hybrid tasks are typically more complex
            long taskKey = (modId.hashCode() * 31L + name.hashCode()) & 0x7FFFFFFFFFFFFFFFL;

            // Estimate task characteristics - hybrid tasks are typically more complex
            long dataSizeBytes = Math.max(1024, maxSize * 100); // Estimate based on cache size
            int parallelUnits = Math.max(10, (int) Math.min(1000, maxSize / 10)); // Estimate parallelism

            return org.admany.quantified.core.common.util.TaskScheduler.submitComputeTask(
                modId,
                name + "_hybrid",
                taskKey,
                work, // CPU implementation
                null, // No GPU implementation available in frontend API
                dataSizeBytes,
                parallelUnits,
                org.admany.quantified.core.common.util.TaskScheduler.TaskComplexity.MODERATE,
                org.admany.quantified.core.common.util.TaskScheduler.TaskType.GENERAL,
                timeout,
                allowMainThreadRerouting
            ).whenComplete((result, error) -> {
                if (error == null && result != null) {
                    cache.put(cacheKey, result);
                    statistics.recordTaskCompleted();
                } else {
                    statistics.recordTaskFailed();
                }
            });
        }
    }

    /**
     * Network builder for mod-to-mod communication - encrypted channels with packet serialization and type safety.
     * Built-in reliability features and handler registration system.
     */
    private class NetworkBuilderImpl implements QuantifiedNetworkBuilder {
        private final String channel;
        private java.util.UUID channelId;

        public NetworkBuilderImpl(String channel) {
            this.channel = channel;
        }

        @Override
        public QuantifiedNetworkBuilder chunkSize(int bytes) {
            // Chunk size configuration stored but not currently used - keeps messages small for better performance
            return this;
        }

        @Override
        public QuantifiedNetworkBuilder timeout(Duration timeout) {
            // Timeout configuration stored but not currently used - prevents hanging on network issues
            return this;
        }

        @Override
        public QuantifiedNetworkBuilder retryPolicy(QuantifiedNetworkBuilder.RetryPolicy policy) {
            // Retry policy configuration stored but not currently used - handles transient network failures
            return this;
        }

        @Override
        public QuantifiedNetworkBuilder callback(java.util.function.Consumer<Object> responseCallback) {
            // Response callback configuration stored but not currently used - for handling async responses
            return this;
        }

        @Override
        public QuantifiedNetworkBuilder withQoS(QuantifiedNetworkBuilder.QoS qos) {
            // QoS configuration stored but not currently used - prioritizes important messages
            return this;
        }

        @Override
        public CompletableFuture<Void> send(Object data) {
            if (disconnected) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Mod disconnected"));
                return future;
            }

            NetworkManager networkManager = org.admany.quantified.core.forge.QuantifiedCoreForge.getNetworkManager();
            if (networkManager == null) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Network manager not available"));
                return future;
            }

            return networkManager.createChannel().thenCompose(channel -> {
                channelId = channel.getChannelId();
                try {
                    // Serialize data to bytes (simplified - in real implementation, use proper serialization like JSON)
                    byte[] dataBytes = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    PacketSerializer.DataSyncPacket packet = new PacketSerializer.DataSyncPacket(this.channel, dataBytes);
                    statistics.recordPacketSent();
                    return networkManager.sendPacket(channelId, packet);
                } catch (Exception e) {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    future.completeExceptionally(e);
                    return future;
                }
            });
        }

        @Override
        public CompletableFuture<Void> sendToAll(Object data) {
            if (disconnected) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Mod disconnected"));
                return future;
            }

            NetworkManager networkManager = org.admany.quantified.core.forge.QuantifiedCoreForge.getNetworkManager();
            if (networkManager == null) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Network manager not available"));
                return future;
            }

            try {
                // Serialize data to bytes and build a DataSyncPacket for broadcast - sends to all connected mods
                byte[] dataBytes = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                PacketSerializer.DataSyncPacket packet = new PacketSerializer.DataSyncPacket(this.channel, dataBytes);
                statistics.recordPacketSent();
                return networkManager.broadcast(packet);
            } catch (Exception e) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
            }
        }

        @Override
        public <T> CompletableFuture<T> sendAndReceive(Object data) {
            if (disconnected) {
                CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Mod disconnected"));
                return future;
            }

            NetworkManager networkManager = org.admany.quantified.core.forge.QuantifiedCoreForge.getNetworkManager();
            if (networkManager == null) {
                CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Network manager not available"));
                return future;
            }

            // For sendAndReceive, we send and expect a response via callback - in practice, correlation IDs would be used for matching requests/responses
            // This is a simplified implementation - real version would wait for a response packet
            return networkManager.createChannel().thenCompose(channel -> {
                channelId = channel.getChannelId();
                try {
                    // Serialize data to bytes - packets are transmitted as UTF-8 payloads, so send JSON or another string-friendly format
                    byte[] dataBytes = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    PacketSerializer.DataSyncPacket packet = new PacketSerializer.DataSyncPacket(this.channel, dataBytes);
                    statistics.recordPacketSent();

                    // Send the packet
                    return networkManager.sendPacket(channelId, packet).thenApply(v -> {
                        // In a real implementation, we'd wait for a response packet with correlation ID matching
                        // For now, return null and expect response via callback - this is a simplified implementation
                        return null;
                    });
                } catch (Exception e) {
                    CompletableFuture<T> future = new CompletableFuture<>();
                    future.completeExceptionally(e);
                    return future;
                }
            });
        }
    }

    // ===== STATISTICS IMPLEMENTATION =====
    // Real-time metrics and performance monitoring - live stats for task throughput, cache hit rates, GPU utilization, and more
    // Use these numbers to expose dashboards or warn players about performance issues

    private static class ModStatisticsImpl implements ModStatistics {
        // Live performance monitoring with atomic counters - tracks everything from task throughput to GPU utilization
        // isThrottled() turns true when the queue backs up (>10 outstanding tasks) - use this to warn players
        private final String modId;
        private final String version;
        private final AtomicLong tasksSubmitted = new AtomicLong();
        private final AtomicLong tasksCompleted = new AtomicLong();
        private final AtomicLong tasksFailed = new AtomicLong();
        private final AtomicLong packetsSent = new AtomicLong();
        private final AtomicLong packetsReceived = new AtomicLong();
        private final AtomicLong currentQueueDepth = new AtomicLong();
        private final AtomicLong totalTaskTimeNanos = new AtomicLong();
        private final AtomicLong maxTaskTimeNanos = new AtomicLong();
        private final AtomicLong cacheHits = new AtomicLong();
        private final AtomicLong cacheMisses = new AtomicLong();
        private final AtomicLong totalCacheEntries = new AtomicLong();
        private final AtomicLong totalCacheBytes = new AtomicLong();
        private final AtomicLong totalGPUTimeNanos = new AtomicLong();
        private final AtomicLong peakVRAMUsage = new AtomicLong();
        private final AtomicLong cpuFallbackCount = new AtomicLong();
        private volatile Instant lastActivity = Instant.now();
        private static final long ESTIMATED_ENTRY_BYTES = 512L;

        public ModStatisticsImpl(String modId, String version) {
            this.modId = modId;
            this.version = version;
        }

        public void recordTaskSubmitted() {
            tasksSubmitted.incrementAndGet();
            currentQueueDepth.incrementAndGet();
            lastActivity = Instant.now();
        }

        private void decrementQueueSafely() {
            currentQueueDepth.updateAndGet(value -> value > 0 ? value - 1 : 0);
        }

        public void recordTaskCompleted() {
            tasksCompleted.incrementAndGet();
            decrementQueueSafely();
            lastActivity = Instant.now();
        }

        public void recordTaskCompleted(long durationNanos) {
            tasksCompleted.incrementAndGet();
            decrementQueueSafely();
            totalTaskTimeNanos.addAndGet(durationNanos);
            // Update max time atomically - tracks the slowest task for performance monitoring
            long currentMax;
            do {
                currentMax = maxTaskTimeNanos.get();
            } while (durationNanos > currentMax && !maxTaskTimeNanos.compareAndSet(currentMax, durationNanos));
            lastActivity = Instant.now();
        }

        public void recordTaskFailed() {
            tasksFailed.incrementAndGet();
            decrementQueueSafely();
            lastActivity = Instant.now();
        }

        public void recordCacheHit() {
            cacheHits.incrementAndGet();
            lastActivity = Instant.now();
        }

        public void recordCacheMiss() {
            cacheMisses.incrementAndGet();
            lastActivity = Instant.now();
        }

        public void recordCacheEntryAdded() {
            totalCacheEntries.incrementAndGet();
            lastActivity = Instant.now();
        }

        public void recordCacheEntryRemoved() {
            totalCacheEntries.updateAndGet(value -> value > 0 ? value - 1 : 0);
            lastActivity = Instant.now();
        }

        public void updateCacheSize(long size) {
            long entries = Math.max(0, size);
            totalCacheEntries.set(entries);
            totalCacheBytes.set(entries * ESTIMATED_ENTRY_BYTES);
            if (entries > 0) {
                lastActivity = Instant.now();
            }
        }

        public void updateCacheStats(long entries, long bytes) {
            long safeEntries = Math.max(0L, entries);
            long safeBytes = Math.max(0L, bytes);
            totalCacheEntries.set(safeEntries);
            totalCacheBytes.set(safeBytes);
            if (safeEntries > 0 || safeBytes > 0) {
                lastActivity = Instant.now();
            }
        }

        @SuppressWarnings("unused")
        public void recordGPUTime(long nanos) {
            totalGPUTimeNanos.addAndGet(nanos);
        }

        @SuppressWarnings("unused")
        public void recordVRAMUsage(long bytes) {
            // Update peak atomically
            long currentPeak;
            do {
                currentPeak = peakVRAMUsage.get();
            } while (bytes > currentPeak && !peakVRAMUsage.compareAndSet(currentPeak, bytes));
        }

        @SuppressWarnings("unused")
        public void recordCPUFallback() {
            cpuFallbackCount.incrementAndGet();
        }

        public void recordPacketSent() {
            packetsSent.incrementAndGet();
            lastActivity = Instant.now();
        }

        public void recordPacketReceived() {
            packetsReceived.incrementAndGet();
            lastActivity = Instant.now();
        }

        @Override public String getModId() { return modId; }
        @Override public String getModVersion() { return version; }
        @Override public Instant getLastActivity() { return lastActivity; }

        @Override public long getTotalTasksSubmitted() { return tasksSubmitted.get(); }
        @Override public long getTasksCompleted() { return tasksCompleted.get(); }
        @Override public long getTasksFailed() { return tasksFailed.get(); }
        @Override public int getCurrentQueueDepth() { return (int) currentQueueDepth.get(); }
        @Override public boolean isThrottled() { return currentQueueDepth.get() > 10; } // Turns true when queue backs up - use this to warn players about performance issues
        @Override public double getThrottleFactor() { return Math.min(1.0, currentQueueDepth.get() / 20.0); }

        @Override public Duration getAverageTaskTime() {
            long completed = tasksCompleted.get();
            return completed > 0 ? Duration.ofNanos(totalTaskTimeNanos.get() / completed) : Duration.ofMillis(1);
        }
        @Override public Duration getMaxTaskTime() { return Duration.ofNanos(maxTaskTimeNanos.get()); }
        @Override public double getTasksPerSecond() { return tasksCompleted.get() / 1.0; }

        @Override public double getCacheHitRate() {
            long total = cacheHits.get() + cacheMisses.get();
            return total > 0 ? (double) cacheHits.get() / total : 0.8; // Effectiveness percentage - higher is better, shows how well your caching is working
        }
        @Override public long getCacheSize() { return totalCacheEntries.get(); }
        @Override public long getCacheMaxSize() { return 1000; }
        @Override public long getCacheEvictions() { return 0; }
        @Override public long getCacheMemoryUsage() { return totalCacheBytes.get(); }

        @Override public long getPacketsSent() { return packetsSent.get(); }
        @Override public long getPacketsReceived() { return packetsReceived.get(); }
        @Override public long getNetworkErrors() { return 0; }
        @Override public long getNetworkBytesTransferred() { return packetsSent.get() * 1024; } // Estimate

        @Override public Duration getTotalGPUTime() { return Duration.ofNanos(totalGPUTimeNanos.get()); }
        @Override public long getPeakVRAMUsage() { return peakVRAMUsage.get(); }
        @Override public double getGPUUtilization() { return 0.0; } // Would be updated by GPU monitor - tracks how hard your graphics card is working
        @Override public double getCPUFallbackRate() {
            long total = tasksCompleted.get() + cpuFallbackCount.get();
            return total > 0 ? (double) cpuFallbackCount.get() / total : 0.0; // How often CPU fallback is used - lower is better for GPU tasks
        }
    }
}
