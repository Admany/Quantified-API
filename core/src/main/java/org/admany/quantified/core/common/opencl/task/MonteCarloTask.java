package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * An example OpenCL task that estimates π using Monte Carlo methods.
 * Shows how to do parallel random sampling for math calculations.
 */
public final class MonteCarloTask extends OpenCLTask<Double> {

    private final int samples;

    public MonteCarloTask(String modId, String name, long taskKey, int samples) {
        super(new Builder(modId, name, taskKey, samples));
        this.samples = samples;
    }

    @Override
    public long estimatedVramBytes() {
        return samples * 8L * 2; // Input random pairs + output
    }

    @Override
    public int estimatedComputeUnits() {
        return Math.max(1, samples / 100000); // Scale with sample count
    }

    @Override
    public Double executeOnGPU(OpenCLContext context) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create output buffer for results
            long resultsBuffer = context.createBuffer(CL10.CL_MEM_WRITE_ONLY, samples * 4L);

            // Create kernel
            long kernel = context.createKernel("monte_carlo_pi");

            // Set kernel arguments
            context.setKernelArgBuffer(kernel, 0, resultsBuffer);
            context.setKernelArg(kernel, 1, samples);

            // Execute kernel
            PointerBuffer globalWorkSize = stack.pointers(samples);
            context.enqueueNDRangeKernel(kernel, 1, globalWorkSize);
            context.finish();

            // Read results
            ByteBuffer resultsBuf = stack.malloc(samples * 4);
            context.enqueueReadBuffer(resultsBuffer, true, 0, samples * 4L, resultsBuf);
            resultsBuf.flip();

            // Count hits
            int hits = 0;
            for (int i = 0; i < samples; i++) {
                if (resultsBuf.getFloat() > 0.5f) { // Results are 1.0f for hits, 0.0f for misses
                    hits++;
                }
            }

            // Cleanup
            context.releaseBuffer(resultsBuffer);
            context.releaseKernel(kernel);

            return 4.0 * hits / samples;

        } catch (Exception e) {
            // Fallback to CPU
            return cpuFallback().get();
        }
    }

    private static final class Builder extends OpenCLTask.Builder<Double> {
        private final int samples;

        public Builder(String modId, String name, long taskKey, int samples) {
            super(modId, name, taskKey, () -> runMonteCarloCPU(samples));
            this.samples = samples;
        }

        @Override
        public MonteCarloTask build() {
            return new MonteCarloTask(modId, name, taskKey, samples);
        }
    }

    private static double runMonteCarloCPU(int samples) {
        int insideCircle = 0;
        for (int i = 0; i < samples; i++) {
            double x = Math.random();
            double y = Math.random();
            if (x * x + y * y <= 1.0) {
                insideCircle++;
            }
        }
        return 4.0 * insideCircle / samples;
    }
}