package org.admany.quantified.core.common.cache.interfaces;

import java.time.Duration;

/**
 * Specialised cache that exposes TTL semantics on top of {@link ThreadSafeCache}.
 */
public interface TTLCache<K, V> extends ThreadSafeCache<K, V> {

    Duration ttl();

    boolean refreshOnAccess();
}
