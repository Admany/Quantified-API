package org.admany.quantified.core.common.opencl.core;

import org.admany.quantified.api.opencl.QuantifiedOpenCL;

public final class ApiOpenClTaskWrapper<T> extends OpenCLTask<T> {
    private final QuantifiedOpenCL.ApiOpenClTask<T> apiTask;

    public ApiOpenClTaskWrapper(QuantifiedOpenCL.ApiOpenClTask<T> apiTask) {
        super(apiTask.modId(), apiTask.name(), apiTask.taskKey(), apiTask.cpuFallback(), apiTask.timeout().orElse(null));
        this.apiTask = apiTask;
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
}