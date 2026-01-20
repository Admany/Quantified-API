package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Supplier;

public final class VectorReductionTask extends OpenCLTask<Double> {

    private final double[] values;
    private final DoubleBinaryOperator reducer;
    private final double identity;

    private VectorReductionTask(Builder builder) {
        super(builder);
        this.values = builder.values.clone();
        this.reducer = builder.reducer;
        this.identity = builder.identity;
    }

    @Override
    public long estimatedVramBytes() {
        // Estimate: input array + output + some overhead
        return (values.length * 8L) + 16L + 1024L; // 8 bytes per double + overhead
    }

    @Override
    public int estimatedComputeUnits() {
        return Math.max(1, Math.min(16, values.length / 10000));
    }

    @Override
    public Double executeOnGPU(OpenCLContext context) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float[] floatValues = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                floatValues[i] = (float) values[i];
            }

            // Create buffers
            long inputBuffer = context.createBuffer(CL10.CL_MEM_READ_ONLY,
                floatValues.length * 4L);
            long outputBuffer = context.createBuffer(CL10.CL_MEM_WRITE_ONLY,
                floatValues.length * 4L);

            // Create kernel
            long kernel = context.createKernel("vector_sum");

            // Set kernel arguments
            context.setKernelArgBuffer(kernel, 0, inputBuffer);
            context.setKernelArgBuffer(kernel, 1, outputBuffer);
            context.setKernelArg(kernel, 2, floatValues.length);

            // Write input data
            ByteBuffer inputBuf = stack.malloc(floatValues.length * 4);
            for (float v : floatValues) {
                inputBuf.putFloat(v);
            }
            inputBuf.flip();
            context.enqueueWriteBuffer(inputBuffer, true, 0, floatValues.length * 4L, inputBuf);

            // Execute kernel
            PointerBuffer globalWorkSize = stack.pointers(floatValues.length);
            context.enqueueNDRangeKernel(kernel, 1, globalWorkSize);
            context.finish();

            // Read results
            ByteBuffer outputBuf = stack.malloc(floatValues.length * 4);
            context.enqueueReadBuffer(outputBuffer, true, 0, floatValues.length * 4L, outputBuf);
            outputBuf.flip();

            // CPU reduction of GPU results (simplified - real implementation would do parallel reduction on GPU)
            double result = identity;
            for (int i = 0; i < floatValues.length; i++) {
                result = reducer.applyAsDouble(result, outputBuf.getFloat());
            }

            // Cleanup
            context.releaseBuffer(inputBuffer);
            context.releaseBuffer(outputBuffer);
            context.releaseKernel(kernel);

            return result;

        } catch (Exception e) {
            // Fallback to CPU
            return cpuFallback().get();
        }
    }

    public static final class Builder extends OpenCLTask.Builder<Double> {

        private double[] values;
        private DoubleBinaryOperator reducer;
        private double identity;

        public Builder(String modId, String name, long taskKey, double[] values,
                      DoubleBinaryOperator reducer, double identity, Supplier<Double> cpuFallback) {
            super(modId, name, taskKey, cpuFallback);
            this.values = values;
            this.reducer = reducer;
            this.identity = identity;
        }

        public Builder values(double[] values) {
            this.values = values.clone();
            return this;
        }

        public Builder reducer(DoubleBinaryOperator reducer) {
            this.reducer = reducer;
            return this;
        }

        public Builder identity(double identity) {
            this.identity = identity;
            return this;
        }

        @Override
        public VectorReductionTask build() {
            if (values == null || values.length == 0) {
                throw new IllegalArgumentException("values cannot be null or empty");
            }
            if (reducer == null) {
                throw new IllegalArgumentException("reducer cannot be null");
            }
            return new VectorReductionTask(this);
        }
    }

    public static Builder sum(String modId, String name, long taskKey, double[] values) {
        Supplier<Double> cpuFallback = () -> Arrays.stream(values).sum();
        return new Builder(modId, name, taskKey, values, Double::sum, 0.0, cpuFallback);
    }

    public static Builder average(String modId, String name, long taskKey, double[] values) {
        Supplier<Double> cpuFallback = () -> Arrays.stream(values).average().orElse(0.0);
        return new Builder(modId, name, taskKey, values,
                          (a, b) -> a + b, 0.0, cpuFallback)
                .reducer((accumulator, value) -> accumulator + value / values.length);
    }

    public static Builder max(String modId, String name, long taskKey, double[] values) {
        Supplier<Double> cpuFallback = () -> Arrays.stream(values).max().orElse(Double.NEGATIVE_INFINITY);
        return new Builder(modId, name, taskKey, values, Double::max, Double.NEGATIVE_INFINITY, cpuFallback);
    }

    public static Builder min(String modId, String name, long taskKey, double[] values) {
        Supplier<Double> cpuFallback = () -> Arrays.stream(values).min().orElse(Double.POSITIVE_INFINITY);
        return new Builder(modId, name, taskKey, values, Double::min, Double.POSITIVE_INFINITY, cpuFallback);
    }
}