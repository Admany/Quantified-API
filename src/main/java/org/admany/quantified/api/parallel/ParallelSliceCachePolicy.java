package org.admany.quantified.api.parallel;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

public final class ParallelSliceCachePolicy<S, R> {
    private final String cacheName;
    private final Function<S, String> keyFunction;
    private final Function<R, byte[]> serializer;
    private final Function<byte[], R> deserializer;
    private final Duration ttl;
    private final long maxEntries;
    private final boolean persistent;
    private final boolean compression;
    private final boolean copyOnWrite;

    public ParallelSliceCachePolicy(String cacheName,
                                    Function<S, String> keyFunction,
                                    Function<R, byte[]> serializer,
                                    Function<byte[], R> deserializer,
                                    Duration ttl,
                                    long maxEntries,
                                    boolean persistent,
                                    boolean compression) {
        this(cacheName, keyFunction, serializer, deserializer, ttl, maxEntries, persistent, compression, false);
    }

    public ParallelSliceCachePolicy(String cacheName,
                                    Function<S, String> keyFunction,
                                    Function<R, byte[]> serializer,
                                    Function<byte[], R> deserializer,
                                    Duration ttl,
                                    long maxEntries,
                                    boolean persistent,
                                    boolean compression,
                                    boolean copyOnWrite) {
        this.cacheName = Objects.requireNonNull(cacheName, "cacheName");
        this.keyFunction = Objects.requireNonNull(keyFunction, "keyFunction");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
        this.ttl = ttl;
        this.maxEntries = Math.max(64L, maxEntries);
        this.persistent = persistent;
        this.compression = compression;
        this.copyOnWrite = copyOnWrite;
    }

    public String cacheName() {
        return cacheName;
    }

    public Function<S, String> keyFunction() {
        return keyFunction;
    }

    public Function<R, byte[]> serializer() {
        return serializer;
    }

    public Function<byte[], R> deserializer() {
        return deserializer;
    }

    public Duration ttl() {
        return ttl;
    }

    public long maxEntries() {
        return maxEntries;
    }

    public boolean persistent() {
        return persistent;
    }

    public boolean compression() {
        return compression;
    }

    public boolean copyOnWrite() {
        return copyOnWrite;
    }
}
