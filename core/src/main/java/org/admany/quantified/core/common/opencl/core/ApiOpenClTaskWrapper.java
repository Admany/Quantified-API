package org.admany.quantified.core.common.opencl.core;

import org.admany.quantified.api.opencl.QuantifiedOpenCL;

public final class ApiOpenClTaskWrapper<T> extends OpenCLTask<T> implements CacheableOpenCLTask<T> {
    private final QuantifiedOpenCL.ApiOpenClTask<T> apiTask;

    public ApiOpenClTaskWrapper(QuantifiedOpenCL.ApiOpenClTask<T> apiTask) {
        super(apiTask.modId(), apiTask.name(), apiTask.taskKey(), apiTask.cpuFallback(), apiTask.timeout().orElse(null));
        this.apiTask = apiTask;
    }

    public QuantifiedOpenCL.ApiOpenClTask<T> apiTask() {
        return apiTask;
    }

    @Override
    public long estimatedVramBytes() {
        return apiTask.estimatedVramBytes();
    }

    @Override
    public int estimatedComputeUnits() {
        return apiTask.estimatedComputeUnits();
    }

    @Override
    public T executeOnGPU(OpenCLContext context) {
        try {
            return apiTask.executeOnGPU(context);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("OpenCL workload failed", e);
        }
    }

    @Override
    public String cacheKey() {
        String key = apiTask.cacheKey();
        return key == null || key.isBlank() ? null : key;
    }

    @Override
    public java.nio.ByteBuffer encodeResult(T result) {
        QuantifiedOpenCL.CacheCodec<T> codec = apiTask.cacheCodec();
        return codec == null || result == null ? null : codec.encode(result);
    }

    @Override
    public T decodeResult(java.nio.ByteBuffer data) {
        QuantifiedOpenCL.CacheCodec<T> codec = apiTask.cacheCodec();
        return codec == null || data == null ? null : codec.decode(data);
    }
}
