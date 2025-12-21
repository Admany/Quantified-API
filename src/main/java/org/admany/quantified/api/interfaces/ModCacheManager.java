package org.admany.quantified.api.interfaces;

import java.util.Map;
import java.util.Set;

public interface ModCacheManager {

    long getTotalCacheSizeMB();

    int getTotalCacheEntryCount();

    void clearAllCaches();

    long clearOldCaches(long maxAgeMs);

    Set<String> getCacheNames();

    long getCacheSizeMB(String cacheName);

    int getCacheEntryCount(String cacheName);

    void clearCache(String cacheName);

    long clearOldCacheEntries(String cacheName, long maxAgeMs);

    void setMemoryLimitMB(long maxMB);

    long getMemoryLimitMB();

    boolean isMemoryPressureHigh();

    void triggerMemoryPressureCleanup();

    Map<String, CacheStats> getAllCacheStats();

    CacheStats getCacheStats(String cacheName);

    interface CacheStats {
        long sizeMB();
        int entryCount();
        long oldestEntryAgeMs();
        long newestEntryAgeMs();
        String cacheType();
    }
}