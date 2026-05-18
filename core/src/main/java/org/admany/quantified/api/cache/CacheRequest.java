package org.admany.quantified.api;

import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.admany.quantified.core.common.async.task.PriorityTaskType;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class CacheRequest {
    private final String modId;
    private final String cacheName;
    private Duration ttl;
    private long maxEntries;
    private boolean persistence;
    private boolean compression;
    private boolean refreshOnAccess;

    CacheRequest(String modId, String cacheName) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.cacheName = Objects.requireNonNull(cacheName, "cacheName");
    }

    public CacheRequest ttl(Duration ttl) {
        this.ttl = ttl;
        return this;
    }

    public CacheRequest maxEntries(long maxEntries) {
        this.maxEntries = maxEntries;
        return this;
    }

    public CacheRequest persistent() {
        this.persistence = true;
        return this;
    }

    public CacheRequest diskPreferred() {
        return persistent();
    }

    public CacheRequest ephemeral() {
        this.persistence = false;
        return this;
    }

    public CacheRequest memoryOnly() {
        return ephemeral();
    }

    public CacheRequest compression(boolean compression) {
        this.compression = compression;
        return this;
    }

    public CacheRequest compressed() {
        this.compression = true;
        return this;
    }

    public CacheRequest refreshOnAccess() {
        this.refreshOnAccess = true;
        return this;
    }

    public CacheRequest fixedTtl() {
        this.refreshOnAccess = false;
        return this;
    }

    public <T> T get(String key, Supplier<T> loader) {
        return QuantifiedAPI.resolveHandleForApi(modId)
            .cacheGet(cacheName, key, loader, ttl, maxEntries, persistence, compression, refreshOnAccess);
    }

    public <T> CompletableFuture<T> getAsync(String key, Supplier<T> loader) {
        return QuantifiedAPI.resolveHandleForApi(modId)
            .cacheGetAsync(cacheName, key, loader, ttl, maxEntries, persistence, compression, refreshOnAccess);
    }

    public <T> CompletableFuture<T> prefetch(String key, Supplier<T> loader) {
        return getAsync(key, loader);
    }

    public <T> T refresh(String key, Supplier<T> loader) {
        remove(key);
        return get(key, loader);
    }

    public <T> CompletableFuture<T> refreshAsync(String key, Supplier<T> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        QuantifiedHandle handle = QuantifiedAPI.resolveHandleForApi(modId);
        ThreadSafeCache<String, Object> cache = cache();
        return handle.submitRuntimeTask(
            "cache-refresh:" + cacheName,
            PriorityTaskType.CACHE,
            false,
            true,
            null,
            cacheName + ":" + key,
            () -> {
                cache.invalidate(key);
                T refreshed = loader.get();
                if (refreshed != null) {
                    cache.put(key, refreshed);
                }
                return refreshed;
            }
        );
    }

    public <T> CacheRequest put(String key, T value) {
        if (value != null) {
            cache().put(key, value);
        }
        return this;
    }

    public boolean contains(String key) {
        return cache().getIfPresent(key) != null;
    }

    public CacheRequest remove(String key) {
        cache().invalidate(key);
        return this;
    }

    public CacheRequest clear() {
        cache().invalidateAll();
        return this;
    }

    private ThreadSafeCache<String, Object> cache() {
        return QuantifiedAPI.resolveHandleForApi(modId)
            .cache(cacheName, maxEntries, ttl, persistence, compression, refreshOnAccess);
    }
}
