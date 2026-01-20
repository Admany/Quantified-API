package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OpenCLTestTask extends OpenCLTask<String> {

    private static final Logger LOGGER = Logger.getLogger(OpenCLTestTask.class.getName());

    private static final float[] TEST_VECTOR_A = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    private static final float[] TEST_VECTOR_B = {5.0f, 4.0f, 3.0f, 2.0f, 1.0f};
    private static final float[] EXPECTED_RESULT = {6.0f, 6.0f, 6.0f, 6.0f, 6.0f};
    private final boolean quiet;

    private OpenCLTestTask(Builder builder) {
        super(builder);
        this.quiet = builder.quiet;
    }

    @Override
    public long estimatedVramBytes() {
        // Small test: 2 input arrays + 1 output array + overhead
        return (TEST_VECTOR_A.length * 4L * 3) + 1024L;
    }

    @Override
    public int estimatedComputeUnits() {
        return 1; // Very simple task
    }

    @Override
    public String executeOnGPU(OpenCLContext context) {
        if (!quiet) {
            LOGGER.info("Executing OpenCL vector addition test on GPU");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create buffers
            long bufferA = context.createBuffer(CL10.CL_MEM_READ_ONLY,
                TEST_VECTOR_A.length * 4L);
            long bufferB = context.createBuffer(CL10.CL_MEM_READ_ONLY,
                TEST_VECTOR_B.length * 4L);
            long bufferC = context.createBuffer(CL10.CL_MEM_WRITE_ONLY,
                TEST_VECTOR_A.length * 4L);

            // Create kernel (simple vector addition)
            long kernel = context.createKernel("vector_add");

            // Set kernel arguments
            context.setKernelArgBuffer(kernel, 0, bufferA);
            context.setKernelArgBuffer(kernel, 1, bufferB);
            context.setKernelArgBuffer(kernel, 2, bufferC);

            // Write input data
            ByteBuffer inputBufA = stack.malloc(TEST_VECTOR_A.length * 4);
            for (float v : TEST_VECTOR_A) {
                inputBufA.putFloat(v);
            }
            inputBufA.flip();
            context.enqueueWriteBuffer(bufferA, true, 0, TEST_VECTOR_A.length * 4L, inputBufA);

            ByteBuffer inputBufB = stack.malloc(TEST_VECTOR_B.length * 4);
            for (float v : TEST_VECTOR_B) {
                inputBufB.putFloat(v);
            }
            inputBufB.flip();
            context.enqueueWriteBuffer(bufferB, true, 0, TEST_VECTOR_B.length * 4L, inputBufB);

            // Execute kernel
            PointerBuffer globalWorkSize = stack.pointers(TEST_VECTOR_A.length);
            context.enqueueNDRangeKernel(kernel, 1, globalWorkSize);
            context.finish();

            // Read results
            ByteBuffer outputBuf = stack.malloc(TEST_VECTOR_A.length * 4);
            context.enqueueReadBuffer(bufferC, true, 0, TEST_VECTOR_A.length * 4L, outputBuf);
            outputBuf.rewind(); // ensure position at start without collapsing limit to zero

            // Verify results
            boolean success = true;
            for (int i = 0; i < TEST_VECTOR_A.length; i++) {
                float actual = outputBuf.getFloat();
                float expected = EXPECTED_RESULT[i];
                if (Math.abs(actual - expected) > 0.001f) {
                    success = false;
                    break;
                }
            }

            // Cleanup
            context.releaseBuffer(bufferA);
            context.releaseBuffer(bufferB);
            context.releaseBuffer(bufferC);
            context.releaseKernel(kernel);

            String result = success ? "PASSED" : "FAILED";
            if (!quiet) {
                LOGGER.info("OpenCL GPU test result: " + result);
            }
            return result;

        } catch (Exception e) {
            if (!quiet) {
                LOGGER.log(Level.WARNING, "OpenCL GPU test execution failed, falling back to CPU", e);
            }
            // Fallback to CPU verification
            return cpuFallback().get();
        }
    }

    public static final class Builder extends OpenCLTask.Builder<String> {
        private boolean quiet;

        public Builder(String modId, String name, long taskKey, Supplier<String> cpuFallback) {
            super(modId, name, taskKey, cpuFallback);
        }

        public Builder quiet(boolean quiet) {
            this.quiet = quiet;
            return this;
        }

        @Override
        public OpenCLTestTask build() {
            return new OpenCLTestTask(this);
        }
    }

    public static Builder create(String modId, String name, long taskKey) {
        return create(modId, name, taskKey, false);
    }

    public static Builder create(String modId, String name, long taskKey, boolean quiet) {
        Supplier<String> cpuFallback = () -> {
            if (!quiet) {
                LOGGER.info("Executing OpenCL test verification on CPU (fallback)");
            }
            boolean success = true;
            for (int i = 0; i < TEST_VECTOR_A.length; i++) {
                float actual = TEST_VECTOR_A[i] + TEST_VECTOR_B[i];
                float expected = EXPECTED_RESULT[i];
                if (Math.abs(actual - expected) > 0.001f) {
                    success = false;
                    break;
                }
            }
            String result = success ? "PASSED (CPU fallback)" : "FAILED (CPU fallback)";
            if (!quiet) {
                LOGGER.info("OpenCL CPU fallback test result: " + result);
            }
            return result;
        };
        return new Builder(modId, name, taskKey, cpuFallback).quiet(quiet);
    }
}
