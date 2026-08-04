package org.admany.quantified.core.common.cache;

import org.admany.quantified.core.common.cache.impl.CaffeineThreadSafeCache;
import org.admany.quantified.core.common.cache.impl.PersistentCache;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentCacheTest {

    @TempDir
    Path tempDir;

    private ThreadSafeCache<String, String> cache;

    @BeforeEach
    void setUp() {
        // Create a Caffeine cache for testing
        CaffeineThreadSafeCache<String, String> caffeineCache = CaffeineThreadSafeCache.create(
            new CaffeineThreadSafeCache.CacheBuilderSpec(100, Duration.ofMinutes(5), false, 16)
        );

        // Use temp directory for isolated testing
        Path testCacheDir = tempDir.resolve("test-cache");

        // Wrap it with PersistentCache
        cache = new PersistentCache<String, String>(caffeineCache, "testmod", "testcache", true, testCacheDir);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void testBasicPutAndGet() {
        // Put a value
        cache.put("key1", "value1");

        // Get it back
        String result = cache.getIfPresent("key1");
        assertThat(result).isEqualTo("value1");
    }

    @Test
    void testGetWithLoader() {
        // Get with loader (should compute and cache)
        String result = cache.get("key2", key -> "computed_value");
        assertThat(result).isEqualTo("computed_value");

        // Get again (should return cached value)
        String cachedResult = cache.getIfPresent("key2");
        assertThat(cachedResult).isEqualTo("computed_value");
    }

    @Test
    void testLoadedValueIsPersisted() {
        assertThat(cache.get("loaded_key", key -> "loaded_value")).isEqualTo("loaded_value");
        cache.close();

        CaffeineThreadSafeCache<String, String> replacementDelegate = CaffeineThreadSafeCache.create(
            new CaffeineThreadSafeCache.CacheBuilderSpec(100, Duration.ofMinutes(5), false, 16)
        );
        ThreadSafeCache<String, String> replacement = new PersistentCache<>(
            replacementDelegate,
            "testmod",
            "testcache",
            true,
            tempDir.resolve("test-cache")
        );
        try {
            assertThat(replacement.getIfPresent("loaded_key")).isEqualTo("loaded_value");
        } finally {
            replacement.close();
        }
    }

    @Test
    void testPersistence() {
        // Put some data
        cache.put("persistent_key", "persistent_value");
        cache.put("another_key", "another_value");

        // Close the cache (should save to disk)
        cache.close();

        // Create a new cache instance (should load from disk)
        CaffeineThreadSafeCache<String, String> newCaffeineCache = CaffeineThreadSafeCache.create(
            new CaffeineThreadSafeCache.CacheBuilderSpec(100, Duration.ofMinutes(5), false, 16)
        );
        Path testCacheDir = tempDir.resolve("test-cache");
        ThreadSafeCache<String, String> newCache = new PersistentCache<String, String>(newCaffeineCache, "testmod", "testcache", true, testCacheDir);

        // Check that data was loaded
        String loadedValue = newCache.getIfPresent("persistent_key");
        assertThat(loadedValue).isEqualTo("persistent_value");

        String anotherValue = newCache.getIfPresent("another_key");
        assertThat(anotherValue).isEqualTo("another_value");

        newCache.close();
    }

    @Test
    void testInvalidate() {
        cache.put("key", "value");
        assertThat(cache.getIfPresent("key")).isEqualTo("value");

        cache.invalidate("key");
        assertThat(cache.getIfPresent("key")).isNull();
    }

    @Test
    void testInvalidateAll() {
        cache.put("key1", "value1");
        cache.put("key2", "value2");

        cache.invalidateAll();

        assertThat(cache.getIfPresent("key1")).isNull();
        assertThat(cache.getIfPresent("key2")).isNull();
    }

    @Test
    void testSize() {
        assertThat(cache.size()).isEqualTo(0);

        cache.put("key1", "value1");
        assertThat(cache.size()).isEqualTo(1);

        cache.put("key2", "value2");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void testSnapshot() {
        cache.put("key1", "value1");
        cache.put("key2", "value2");

        Map<String, String> snapshot = cache.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get("key1")).isEqualTo("value1");
        assertThat(snapshot.get("key2")).isEqualTo("value2");
    }
}
