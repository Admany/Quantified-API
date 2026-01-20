package org.admany.quantified.core.common.opencl.core;

import java.nio.ByteBuffer;

public interface CacheableOpenCLTask<T> {
    String cacheKey();
    ByteBuffer encodeResult(T result);
    T decodeResult(ByteBuffer data);
}
