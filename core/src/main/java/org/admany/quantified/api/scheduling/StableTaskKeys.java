package org.admany.quantified.api;

import java.nio.charset.StandardCharsets;

public final class StableTaskKeys {
    private static final long FNV64_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV64_PRIME = 0x100000001b3L;

    private StableTaskKeys() {
    }

    public static long of(String namespace, String modId, String name) {
        return of(namespace, modId, name, "");
    }

    public static long of(String namespace, String modId, String name, String qualifier) {
        long hash = FNV64_OFFSET;
        hash = mix(hash, namespace);
        hash = mix(hash, modId);
        hash = mix(hash, name);
        hash = mix(hash, qualifier);
        return hash;
    }

    public static long named(String namespace, String modId, String name, String explicitKey) {
        return of(namespace, modId, name, explicitKey == null ? "" : explicitKey);
    }

    private static long mix(long current, String value) {
        long hash = current;
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= FNV64_PRIME;
        }
        hash ^= 0xff;
        hash *= FNV64_PRIME;
        return hash;
    }
}
