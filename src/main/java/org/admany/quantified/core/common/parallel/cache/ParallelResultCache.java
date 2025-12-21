package org.admany.quantified.core.common.parallel.cache;

import org.admany.quantified.api.model.ParallelTaskSpec;
import org.admany.quantified.api.parallel.ParallelSliceCachePolicy;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.admany.quantified.core.common.parallel.metrics.ParallelMetrics;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ParallelResultCache {
    private static final ConcurrentMap<String, ThreadSafeCache<String, byte[]>> REGISTRY = new ConcurrentHashMap<>();
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(30);

    private ParallelResultCache() {
    }

    public static <S, R, O> R tryLoad(ParallelTaskSpec<S, R, O> spec,
                                      ParallelSliceCachePolicy<S, R> policy,
                                      S slice) {
        try {
            ThreadSafeCache<String, byte[]> cache = ensureCache(spec.modId(), policy);
            String key = Objects.requireNonNull(policy.keyFunction().apply(slice), "cache key");
            byte[] payload = cache.getIfPresent(key);
            if (payload == null || payload.length == 0) {
                ParallelMetrics.recordCacheMiss();
                return null;
            }
            byte[] copy = Arrays.copyOf(payload, payload.length);
            R restored = policy.deserializer().apply(copy);
            if (restored != null) {
                ParallelMetrics.recordCacheHit();
            } else {
                ParallelMetrics.recordCacheMiss();
            }
            return restored;
        } catch (Throwable throwable) {
            ParallelMetrics.recordCacheMiss();
            return null;
        }
    }

    public static <S, R, O> void store(ParallelTaskSpec<S, R, O> spec,
                                       ParallelSliceCachePolicy<S, R> policy,
                                       S slice,
                                       R result) {
        if (result == null) {
            return;
        }
        try {
            ThreadSafeCache<String, byte[]> cache = ensureCache(spec.modId(), policy);
            String key = Objects.requireNonNull(policy.keyFunction().apply(slice), "cache key");
            byte[] payload = policy.serializer().apply(result);
            if (payload == null || payload.length == 0) {
                return;
            }
            cache.put(key, Arrays.copyOf(payload, payload.length));
        } catch (Throwable ignored) {
        }
    }

    private static ThreadSafeCache<String, byte[]> ensureCache(String modId,
                                                              ParallelSliceCachePolicy<?, ?> policy) {
        String normalized = modId + ".parallel." + policy.cacheName();
        return REGISTRY.computeIfAbsent(normalized, name -> {
            Duration ttl = policy.ttl();
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                ttl = FALLBACK_TTL;
            }
            return CacheManager.register(
                name,
                policy.maxEntries(),
                ttl,
                true,
                policy.persistent(),
                policy.compression()
            );
        });
    }
}
