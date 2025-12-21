package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.admany.quantified.core.common.opencl.util.CLDataUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

public final class MatrixMultiplyTask extends OpenCLTask<double[][]> {

    private final double[][] a;
    private final double[][] b;

    public MatrixMultiplyTask(String modId, String name, long taskKey, double[][] a, double[][] b) {
        super(new Builder(modId, name, taskKey, a, b));
        this.a = deepCopy(a);
        this.b = deepCopy(b);
    }

    @Override
    public long estimatedVramBytes() {
        return (a.length * a[0].length + b.length * b[0].length + a.length * b[0].length) * 8L;
    }

    @Override
    public int estimatedComputeUnits() {
        return Math.max(4, (a.length * b[0].length) / 10000);
    }

    @Override
    public double[][] executeOnGPU(OpenCLContext context) {
        CLDataUtil.MemoryLayout layout = new CLDataUtil.MemoryLayout();

        int m = a.length;
        int n = b[0].length;
        int p = b.length;

        // Flatten matrices for GPU
        float[] flatA = new float[m * p];
        float[] flatB = new float[p * n];
        float[] flatC = new float[m * n];

        // Convert double to float for GPU efficiency
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < p; j++) {
                flatA[i * p + j] = (float) a[i][j];
            }
        }
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < n; j++) {
                flatB[i * n + j] = (float) b[i][j];
            }
        }

        // Allocate structured buffers using CLDataUtil
        layout.allocateInitialized("matrixA", CLDataUtil.DataType.KERNEL_PARAMS, floatArrayToBytes(flatA));
        layout.allocateInitialized("matrixB", CLDataUtil.DataType.KERNEL_PARAMS, floatArrayToBytes(flatB));
        layout.allocate("matrixC", CLDataUtil.DataType.RESULT_BUFFER, flatC.length * 4);

        // Create compute buffer for result
        ByteBuffer resultBuffer = CLDataUtil.createComputeBuffer(context, flatC.length * 4L, CL10.CL_MEM_WRITE_ONLY);

        // Create kernel
        long kernel = context.createKernel("matrix_multiply");

        // Set kernel arguments using structured buffer
        CLDataUtil.Allocation allocA = layout.getAllocation("matrixA");
        CLDataUtil.Allocation allocB = layout.getAllocation("matrixB");
        CLDataUtil.Allocation allocC = layout.getAllocation("matrixC");

        context.setKernelArgBuffer(kernel, 0, allocA.offset()); // bufferA handle
        context.setKernelArgBuffer(kernel, 1, allocB.offset()); // bufferB handle
        context.setKernelArgBuffer(kernel, 2, allocC.offset()); // bufferC handle
        context.setKernelArg(kernel, 3, m);
        context.setKernelArg(kernel, 4, n);
        context.setKernelArg(kernel, 5, p);

        // Execute kernel
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer globalWorkSize = stack.pointers(m, n);
            context.enqueueNDRangeKernel(kernel, 2, globalWorkSize);
            context.finish();

            // Read results using structured buffer
            byte[] resultBytes = new byte[flatC.length * 4];
            CLDataUtil.transferFromBuffer(resultBuffer, allocC.offset(), resultBytes);
            bytesToFloatArray(resultBytes, flatC);

            // Convert back to double result
            double[][] result = new double[m][n];
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) {
                    result[i][j] = flatC[i * n + j];
                }
            }

            context.releaseKernel(kernel);
            return result;
        }
    }

    private static final class Builder extends OpenCLTask.Builder<double[][]> {
        private final double[][] a;
        private final double[][] b;

        public Builder(String modId, String name, long taskKey, double[][] a, double[][] b) {
            super(modId, name, taskKey, () -> multiplyMatricesCPU(a, b));
            this.a = a;
            this.b = b;
        }

        @Override
        public MatrixMultiplyTask build() {
            return new MatrixMultiplyTask(modId, name, taskKey, a, b);
        }
    }

    private static double[][] multiplyMatricesCPU(double[][] a, double[][] b) {
        int m = a.length;
        int n = b[0].length;
        int p = b.length;
        double[][] result = new double[m][n];

        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < p; k++) {
                    result[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return result;
    }

    private static double[][] deepCopy(double[][] matrix) {
        double[][] copy = new double[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }

    private static byte[] floatArrayToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    private static void bytesToFloatArray(byte[] bytes, float[] floats) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
    }
}