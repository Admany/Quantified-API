package org.admany.quantified.core.common.cache.interfaces;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Represents a thread-safe cache abstraction that hides the underlying cache
 * implementation (Caffeine). All caches in the core must implement this
 * interface to guarantee concurrency guarantees across the mod.
 */
public interface ThreadSafeCache<K, V> extends AutoCloseable {

    V getIfPresent(K key);

    V get(K key, Function<? super K, ? extends V> mappingFunction);

    void put(K key, V value);

    void invalidate(K key);

    void invalidateAll();

    long size();

    Map<K, V> snapshot();

    Optional<CacheStats> stats();

    @Override
    default void close() {
        // default no-op
    }

    /**
     * Optional idle eviction hook used by cache maintenance tasks to prune data that has not been accessed within a threshold.
     * Implementations that cannot support idle eviction can safely ignore this call.
     */
    default void pruneIdleEntries(Duration idleThreshold) {
        // default no-op
    }

    record CacheStats(long hitCount, long missCount, double hitRate, long evictionCount) {
    }
}
