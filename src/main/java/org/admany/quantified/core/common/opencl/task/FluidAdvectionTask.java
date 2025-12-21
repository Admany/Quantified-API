package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class FluidAdvectionTask extends OpenCLTask<float[][]> {

    private final float[][] initialField;
    private final int iterations;
    private final float diffusion;
    private final float timeStep;

    private FluidAdvectionTask(Builder builder) {
        super(builder);
        this.initialField = deepCopy(builder.field);
        this.iterations = builder.iterations;
        this.diffusion = builder.diffusion;
        this.timeStep = builder.timeStep;
    }

    @Override
    public long estimatedVramBytes() {
        int height = initialField.length;
        int width = height == 0 ? 0 : initialField[0].length;
        return (long) height * width * Float.BYTES * 2; // input + output buffers
    }

    @Override
    public int estimatedComputeUnits() {
        int cells = initialField.length * (initialField.length == 0 ? 0 : initialField[0].length);
        return Math.max(1, cells / 512);
    }

    @Override
    public float[][] executeOnGPU(OpenCLContext context) {
        int height = initialField.length;
        if (height == 0) {
            return deepCopy(initialField);
        }
        int width = initialField[0].length;
        if (width == 0) {
            return deepCopy(initialField);
        }

        long totalCells = (long) height * width;
        long bufferSize = totalCells * Float.BYTES;
        if (bufferSize <= 0 || bufferSize > Integer.MAX_VALUE) {
            return cpuFallback().get();
        }

        long inputBuffer = 0L;
        long outputBuffer = 0L;
        long kernel = 0L;
        try {
            inputBuffer = context.createBuffer(CL10.CL_MEM_READ_WRITE, bufferSize);
            outputBuffer = context.createBuffer(CL10.CL_MEM_READ_WRITE, bufferSize);
            kernel = context.createKernel("quant_fluid_diffuse");

            ByteBuffer initialData = ByteBuffer.allocateDirect((int) bufferSize).order(ByteOrder.nativeOrder());
            for (float[] row : initialField) {
                if (row.length != width) {
                    throw new IllegalArgumentException("All rows must have the same width for GPU execution");
                }
                for (float value : row) {
                    initialData.putFloat(value);
                }
            }
            initialData.flip();
            context.enqueueWriteBuffer(inputBuffer, true, 0, bufferSize, initialData);

            ByteBuffer zero = ByteBuffer.allocateDirect((int) bufferSize).order(ByteOrder.nativeOrder());
            zero.limit((int) bufferSize);
            context.enqueueWriteBuffer(outputBuffer, true, 0, bufferSize, zero);

            int steps = Math.max(1, iterations);
            float alpha = Math.max(0.0f, diffusion * timeStep);

            long currentBuffer = inputBuffer;
            long nextBuffer = outputBuffer;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer globalWorkSize = stack.pointers(width, height);
                for (int step = 0; step < steps; step++) {
                    context.setKernelArgBuffer(kernel, 0, currentBuffer);
                    context.setKernelArgBuffer(kernel, 1, nextBuffer);
                    context.setKernelArgInt(kernel, 2, width);
                    context.setKernelArgInt(kernel, 3, height);
                    context.setKernelArgFloat(kernel, 4, alpha);
                    context.enqueueNDRangeKernel(kernel, 2, globalWorkSize);
                    context.finish();
                    long swap = currentBuffer;
                    currentBuffer = nextBuffer;
                    nextBuffer = swap;
                }

                ByteBuffer resultBuffer = ByteBuffer.allocateDirect((int) bufferSize).order(ByteOrder.nativeOrder());
                context.enqueueReadBuffer(currentBuffer, true, 0, bufferSize, resultBuffer);
                resultBuffer.rewind();

                float[][] result = new float[height][width];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        result[y][x] = resultBuffer.getFloat();
                    }
                }
                return result;
            }
        } catch (Exception ex) {
            return cpuFallback().get();
        } finally {
            if (kernel != 0L) {
                context.releaseKernel(kernel);
            }
            if (inputBuffer != 0L) {
                context.releaseBuffer(inputBuffer);
            }
            if (outputBuffer != 0L) {
                context.releaseBuffer(outputBuffer);
            }
        }
    }

    private static float[][] simulate(float[][] field, int iterations, float diffusion, float timeStep) {
        if (field.length == 0 || field[0].length == 0) {
            return field;
        }
        int height = field.length;
        int width = field[0].length;
        float[][] current = deepCopy(field);
        float[][] buffer = new float[height][width];
        float alpha = diffusion * timeStep;
        for (int step = 0; step < Math.max(1, iterations); step++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float center = current[y][x];
                    float north = current[Math.max(0, y - 1)][x];
                    float south = current[Math.min(height - 1, y + 1)][x];
                    float west = current[y][Math.max(0, x - 1)];
                    float east = current[y][Math.min(width - 1, x + 1)];
                    float laplacian = (north + south + west + east) - (4.0f * center);
                    buffer[y][x] = center + alpha * laplacian;
                }
            }
            float[][] tmp = current;
            current = buffer;
            buffer = tmp;
        }
        return current;
    }

    private static float[][] deepCopy(float[][] source) {
        float[][] copy = new float[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
        return copy;
    }

    public static final class Builder extends OpenCLTask.Builder<float[][]> {
        private final float[][] field;
        private int iterations = 4;
        private float diffusion = 0.16f;
        private float timeStep = 0.1f;

        public Builder(String modId, String name, long taskKey, float[][] field) {
            super(modId, name, taskKey, () -> simulate(deepCopy(field), 4, 0.16f, 0.1f));
            this.field = deepCopy(field);
        }

        public Builder iterations(int iterations) {
            if (iterations > 0) {
                this.iterations = iterations;
            }
            return this;
        }

        public Builder diffusion(float diffusion) {
            if (diffusion >= 0.0f) {
                this.diffusion = diffusion;
            }
            return this;
        }

        public Builder timeStep(float timeStep) {
            if (timeStep > 0.0f) {
                this.timeStep = timeStep;
            }
            return this;
        }

        @Override
        public FluidAdvectionTask build() {
            this.cpuFallback = () -> simulate(field, iterations, diffusion, timeStep);
            return new FluidAdvectionTask(this);
        }
    }
}
