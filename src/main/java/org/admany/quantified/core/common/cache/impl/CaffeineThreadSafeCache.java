package org.admany.quantified.core.common.cache.impl;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import org.admany.quantified.core.common.cache.interfaces.TTLCache;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;

public class CaffeineThreadSafeCache<K, V> implements TTLCache<K, V> {

    private static final int EVICTION_SAMPLE_SIZE = Math.max(8,
        Integer.getInteger("quantified.cache.evictSample", 64));
    private static final int EVICTION_MAX_REMOVALS = Math.max(1,
        Integer.getInteger("quantified.cache.evictMax", 64));
    private static final long EVICTION_MIN_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(Math.max(1,
        Integer.getInteger("quantified.cache.evictMinIntervalMs", 5)));
    private static final ExecutorService EVICTION_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "quantified-cache-evictor");
        thread.setDaemon(true);
        return thread;
    });

    private final ConcurrentMap<K, CacheEntry<V>> storage;
    private final long maximumSize;
    private final long ttlNanos;
    private final boolean refreshOnAccess;
    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder evictionCount = new LongAdder();
    private final AtomicBoolean evictionScheduled = new AtomicBoolean(false);
    private volatile long lastEvictionNanos;

    private CaffeineThreadSafeCache(CacheBuilderSpec spec) {
    this.ttlNanos = spec.ttl() == null ? -1L : spec.ttl().toNanos();
        this.refreshOnAccess = spec.refreshOnAccess();
    this.maximumSize = spec.maximumSize();
        int capacity = spec.initialCapacity() > 0 ? spec.initialCapacity() : 16;
        this.storage = new ConcurrentHashMap<>(capacity);
    }

    public static <K, V> CaffeineThreadSafeCache<K, V> create(CacheBuilderSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return new CaffeineThreadSafeCache<>(spec);
    }

    @Override
    public V getIfPresent(K key) {
        CacheEntry<V> entry = storage.get(key);
        if (entry == null) {
            missCount.increment();
            return null;
        }
        long now = System.nanoTime();
        if (isExpired(entry, now)) {
            storage.remove(key, entry);
            evictionCount.increment();
            missCount.increment();
            return null;
        }
        if (refreshOnAccess && ttlNanos > 0) {
            entry.expiryNanos = expiryTimestamp(now);
        }
        entry.recordAccess(now);
        hitCount.increment();
        return entry.value;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction, "mappingFunction");
        final Holder<V> holder = new Holder<>();
        storage.compute(key, (k, existing) -> {
            long now = System.nanoTime();
            if (existing != null) {
                if (isExpired(existing, now)) {
                    evictionCount.increment();
                } else {
                    if (refreshOnAccess && ttlNanos > 0) {
                        existing.expiryNanos = expiryTimestamp(now);
                    }
                    existing.recordAccess(now);
                    hitCount.increment();
                    holder.value = existing.value;
                    return existing;
                }
            }

            V computed = mappingFunction.apply(k);
            holder.value = computed;
            if (computed == null) {
                missCount.increment();
                return null;
            }

            missCount.increment();
            return new CacheEntry<>(computed, ttlNanos > 0 ? expiryTimestamp(now) : Long.MAX_VALUE, now);
        });
        enforceMaximumSize();
        return holder.value;
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        long now = System.nanoTime();
        storage.put(key, new CacheEntry<>(value, ttlNanos > 0 ? expiryTimestamp(now) : Long.MAX_VALUE, now));
        enforceMaximumSize();
    }

    @Override
    public void invalidate(K key) {
        if (storage.remove(key) != null) {
            evictionCount.increment();
        }
    }

    @Override
    public void invalidateAll() {
        int removed = storage.size();
        storage.clear();
        if (removed > 0) {
            evictionCount.add(removed);
        }
    }

    @Override
    public long size() {
        cleanupExpired();
        return storage.size();
    }

    @Override
    public Map<K, V> snapshot() {
        cleanupExpired();
        Map<K, V> snapshot = new HashMap<>();
        storage.forEach((key, entry) -> snapshot.put(key, entry.value));
        return Collections.unmodifiableMap(snapshot);
    }

    @Override
    public Optional<ThreadSafeCache.CacheStats> stats() {
        long hits = hitCount.sum();
        long misses = missCount.sum();
        long evictions = evictionCount.sum();
        long total = hits + misses;
        double hitRate = total == 0 ? 1.0 : (double) hits / total;
        return Optional.of(new ThreadSafeCache.CacheStats(hits, misses, hitRate, evictions));
    }

    @Override
    public Duration ttl() {
        return ttlNanos > 0 ? Duration.ofNanos(ttlNanos) : null;
    }

    @Override
    public boolean refreshOnAccess() {
        return refreshOnAccess;
    }

    @Override
    public void close() {
        invalidateAll();
    }

    private boolean isExpired(CacheEntry<V> entry, long now) {
        return ttlNanos > 0 && now >= entry.expiryNanos;
    }

    private long expiryTimestamp(long now) {
        return now + ttlNanos;
    }

    private void cleanupExpired() {
        if (ttlNanos <= 0) {
            return;
        }
        long now = System.nanoTime();
        storage.forEach((key, entry) -> {
            if (isExpired(entry, now)) {
                storage.remove(key, entry);
                evictionCount.increment();
            }
        });
    }

    private void enforceMaximumSize() {
        if (maximumSize <= 0) {
            return;
        }
        if (storage.size() <= maximumSize) {
            return;
        }
        scheduleEviction();
    }

    private void scheduleEviction() {
        long now = System.nanoTime();
        if ((now - lastEvictionNanos) < EVICTION_MIN_INTERVAL_NS) {
            return;
        }
        if (!evictionScheduled.compareAndSet(false, true)) {
            return;
        }
        EVICTION_EXECUTOR.execute(this::runEviction);
    }

    private void runEviction() {
        try {
            int removals = 0;
            while (removals < EVICTION_MAX_REMOVALS && storage.size() > maximumSize) {
                K candidate = pickEvictionCandidate();
                if (candidate == null) {
                    break;
                }
                if (storage.remove(candidate) != null) {
                    evictionCount.increment();
                    removals++;
                }
            }
        } finally {
            lastEvictionNanos = System.nanoTime();
            evictionScheduled.set(false);
            if (storage.size() > maximumSize) {
                scheduleEviction();
            }
        }
    }

    private K pickEvictionCandidate() {
        K oldestKey = null;
        long oldestAccess = Long.MAX_VALUE;
        int sampled = 0;
        for (Map.Entry<K, CacheEntry<V>> entry : storage.entrySet()) {
            long access = entry.getValue().lastAccessNanos();
            if (access < oldestAccess) {
                oldestAccess = access;
                oldestKey = entry.getKey();
            }
            sampled++;
            if (sampled >= EVICTION_SAMPLE_SIZE) {
                break;
            }
        }
        return oldestKey;
    }

    @Override
    public void pruneIdleEntries(Duration idleThreshold) {
        if (idleThreshold == null || idleThreshold.isZero() || idleThreshold.isNegative()) {
            return;
        }
        long idleNanos = idleThreshold.toNanos();
        long now = System.nanoTime();
        storage.forEach((key, entry) -> {
            if (now - entry.lastAccessNanos() >= idleNanos) {
                if (storage.remove(key, entry)) {
                    evictionCount.increment();
                }
            }
        });
    }

    private static final class CacheEntry<V> {
        final V value;
        volatile long expiryNanos;
        volatile long lastAccessNanos;

        CacheEntry(V value, long expiryNanos, long accessNanos) {
            this.value = Objects.requireNonNull(value, "value");
            this.expiryNanos = expiryNanos;
            this.lastAccessNanos = accessNanos;
        }

        void recordAccess(long now) {
            this.lastAccessNanos = now;
        }

        long lastAccessNanos() {
            return lastAccessNanos;
        }
    }

    private static final class Holder<V> {
        V value;
    }

    public record CacheBuilderSpec(long maximumSize,
                                   Duration ttl,
                                   boolean refreshOnAccess,
                                   int initialCapacity) {
        public CacheBuilderSpec {
            if (maximumSize <= 0) {
                throw new IllegalArgumentException("maximumSize must be positive");
            }
        }

        public static CacheBuilderSpec of(long maximumSize, Duration ttl) {
            return new CacheBuilderSpec(maximumSize, ttl, false, 0);
        }
    }
}
