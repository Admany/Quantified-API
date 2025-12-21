package org.admany.quantified.api.builders;

import java.time.Duration;
import java.util.function.Supplier;

public interface QuantifiedCacheBuilder {
    QuantifiedCacheBuilder ttl(Duration ttl);
    QuantifiedCacheBuilder maxSize(long maxSize);
    QuantifiedCacheBuilder compression(boolean enabled);
    QuantifiedCacheBuilder persistence(boolean enabled);

    <T> T get(String key, Supplier<T> loader);
    <T> void put(String key, T value);
    boolean contains(String key);
    void remove(String key);
    void clear();
    void invalidatePattern(String pattern);
}