package org.admany.quantified.core.common.benchmark;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.admany.quantified.core.common.opencl.gpu.GPUDetector;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenClKernelRuntimeComparisonBenchmarkTest {

    private static final String MOD_ID = "benchmark_mod";
    private static final int WARMUP_RUNS = Math.max(1,
        Integer.getInteger("quantified.benchmarks.gpu.runtime.warmups", 2));
    private static final int MEASURED_RUNS = Math.max(3,
        Integer.getInteger("quantified.benchmarks.gpu.runtime.iterations", 5));
    private static final int VECTOR_LENGTH = Math.max(32_768,
        Integer.getInteger("quantified.benchmarks.gpu.runtime.vectorLength", 1_000_000));
    private static final int MATRIX_SIZE = Math.max(64,
        Integer.getInteger("quantified.benchmarks.gpu.runtime.matrixSize", 384));
    private static final int MONTE_CARLO_SAMPLES = Math.max(100_000,
        Integer.getInteger("quantified.benchmarks.gpu.runtime.samples", 1_000_000));

    private String originalOpenClDeviceId;

    @BeforeAll
    void setUp() {
        LwjglRuntimeTuning.ensureConfigured();
        OpenCLManager.shutdown();
        if (MultithreadingConfig.CONFIG == null) {
            MultithreadingConfig.CONFIG = new MultithreadingConfig.Config();
        }
        originalOpenClDeviceId = MultithreadingConfig.CONFIG.openclDeviceId;
    }

    @AfterAll
    void tearDown() {
        try {
            if (MultithreadingConfig.CONFIG != null) {
                MultithreadingConfig.CONFIG.openclDeviceId = originalOpenClDeviceId;
            }
        } finally {
            OpenCLManager.shutdown();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void compareLegacyAndModernOpenClKernelRuntime() {
        assumeTrue(benchmarksEnabled(), "Set quantified.benchmarks=true or QUANTIFIED_BENCHMARKS=true to run GPU benchmarks");
        GPUDetector.OpenCLDeviceInfo device = GPUDetector.listDevices().stream()
            .filter(candidate -> candidate.type() != GPUDetector.DeviceType.CPU)
            .min(Comparator.comparing(GPUDetector.OpenCLDeviceInfo::name))
            .orElse(null);
        assertNotNull(device, "No OpenCL GPU device available for runtime benchmark");

        MultithreadingConfig.CONFIG.openclDeviceId = device.id();
        OpenCLManager.initialize();
        assertTrue(OpenCLManager.forceProbeSynchronous(), "OpenCL runtime failed to initialize");

        float[] vectorA = createVector(VECTOR_LENGTH, 0.25f, 0.003f);
        float[] vectorB = createVector(VECTOR_LENGTH, -0.15f, 0.005f);
        float[][] matrixA = createMatrix(MATRIX_SIZE, MATRIX_SIZE, 0.021f);
        float[][] matrixB = createMatrix(MATRIX_SIZE, MATRIX_SIZE, 0.017f);

        WorkloadReport legacyVector = benchmark("legacy.vector_add",
            () -> new LegacyVectorAddTask(vectorA, vectorB).run());
        WorkloadReport modernVector = benchmark("modern.vector_add",
            () -> new ModernVectorAddTask(vectorA, vectorB).run());
        assertArrayEquals((float[]) legacyVector.sample(), (float[]) modernVector.sample(), 0.0001f);

        WorkloadReport legacyMatrix = benchmark("legacy.matrix_multiply",
            () -> new LegacyMatrixMultiplyTask(matrixA, matrixB).run());
        WorkloadReport modernMatrix = benchmark("modern.matrix_multiply",
            () -> new ModernMatrixMultiplyTask(matrixA, matrixB).run());
        assertMatricesEquivalent((float[][]) legacyMatrix.sample(), (float[][]) modernMatrix.sample());

        WorkloadReport legacyMonte = benchmark("legacy.monte_carlo_pi",
            () -> new LegacyMonteCarloTask(MONTE_CARLO_SAMPLES).run());
        WorkloadReport modernMonte = benchmark("modern.monte_carlo_pi",
            () -> new ModernMonteCarloTask(MONTE_CARLO_SAMPLES).run());
        assertEquals((Double) legacyMonte.sample(), (Double) modernMonte.sample(), 0.0001d);

        System.out.println();
        System.out.println("=== OpenCL Kernel Runtime Comparison ===");
        System.out.printf(Locale.ROOT, "Device: %s%n", device.name());
        printComparison("vector_add", legacyVector, modernVector);
        printComparison("matrix_multiply", legacyMatrix, modernMatrix);
        printComparison("monte_carlo_pi", legacyMonte, modernMonte);
        System.out.println();
    }

    private WorkloadReport benchmark(String name, BenchmarkCall call) {
        try {
            Object sample = null;
            for (int i = 0; i < WARMUP_RUNS; i++) {
                sample = call.run();
            }
            List<Long> timings = new ArrayList<>(MEASURED_RUNS);
            for (int i = 0; i < MEASURED_RUNS; i++) {
                long start = System.nanoTime();
                sample = call.run();
                timings.add(System.nanoTime() - start);
            }
            return new WorkloadReport(name, averageMs(timings), medianMs(timings), sample);
        } catch (Exception exception) {
            throw new RuntimeException("OpenCL runtime benchmark failed for " + name, exception);
        }
    }

    private void printComparison(String workload, WorkloadReport legacy, WorkloadReport modern) {
        double speedup = modern.averageMs() > 0.0d ? legacy.averageMs() / modern.averageMs() : 0.0d;
        System.out.printf(Locale.ROOT,
            "%s legacy=%.3f ms modern=%.3f ms speedup=%.2fx%n",
            workload,
            legacy.averageMs(),
            modern.averageMs(),
            speedup);
    }

    private void assertMatricesEquivalent(float[][] left, float[][] right) {
        assertEquals(left.length, right.length);
        for (int row = 0; row < left.length; row++) {
            assertArrayEquals(left[row], right[row], 0.0015f);
        }
    }

    private static float[] createVector(int length, float base, float delta) {
        float[] vector = new float[length];
        for (int i = 0; i < length; i++) {
            vector[i] = base + (i % 2048) * delta;
        }
        return vector;
    }

    private static float[][] createMatrix(int rows, int cols, float scale) {
        float[][] matrix = new float[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                matrix[row][col] = ((row * 31 + col * 17) % 257) * scale;
            }
        }
        return matrix;
    }

    private static float[] flatten(float[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        float[] flat = new float[rows * cols];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(matrix[row], 0, flat, row * cols, cols);
        }
        return flat;
    }

    private static float[][] expand(float[] flat, int rows, int cols) {
        float[][] matrix = new float[rows][cols];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(flat, row * cols, matrix[row], 0, cols);
        }
        return matrix;
    }

    private static ByteBuffer directFloatBuffer(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * Float.BYTES)
            .order(ByteOrder.nativeOrder());
        buffer.asFloatBuffer().put(values);
        buffer.position(0);
        return buffer;
    }

    @SuppressWarnings("unchecked")
    private static <T> Supplier<T> forbiddenCpuFallback(String name) {
        return (Supplier<T>) (() -> {
            throw new IllegalStateException("CPU fallback was used during GPU benchmark: " + name);
        });
    }

    private static double averageMs(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0d) / 1_000_000.0d;
    }

    private static double medianMs(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 0) {
            return ((sorted.get(middle - 1) + sorted.get(middle)) / 2.0d) / 1_000_000.0d;
        }
        return sorted.get(middle) / 1_000_000.0d;
    }

    private static boolean benchmarksEnabled() {
        return Boolean.getBoolean("quantified.benchmarks")
            || "true".equalsIgnoreCase(System.getenv("QUANTIFIED_BENCHMARKS"));
    }

    @FunctionalInterface
    private interface BenchmarkCall {
        Object run() throws Exception;
    }

    private record WorkloadReport(String name, double averageMs, double medianMs, Object sample) {
    }

    private static final class LegacyVectorAddTask extends OpenCLTask<float[]> {
        private final float[] a;
        private final float[] b;

        private LegacyVectorAddTask(float[] a, float[] b) {
            super(MOD_ID, "legacy_opencl_vector_add", 101L, forbiddenCpuFallback("legacy_opencl_vector_add"), null);
            this.a = a;
            this.b = b;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) a.length * Float.BYTES * 3L;
        }

        @Override
        public int estimatedComputeUnits() {
            return a.length;
        }

        @Override
        public float[] executeOnGPU(OpenCLContext context) {
            long bufferA = 0L;
            long bufferB = 0L;
            long bufferC = 0L;
            long kernel = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                bufferA = context.createBuffer(CL10.CL_MEM_READ_ONLY, (long) a.length * Float.BYTES);
                bufferB = context.createBuffer(CL10.CL_MEM_READ_ONLY, (long) b.length * Float.BYTES);
                bufferC = context.createBuffer(CL10.CL_MEM_WRITE_ONLY, (long) a.length * Float.BYTES);
                context.enqueueWriteBuffer(bufferA, true, 0, (long) a.length * Float.BYTES, directFloatBuffer(a));
                context.enqueueWriteBuffer(bufferB, true, 0, (long) b.length * Float.BYTES, directFloatBuffer(b));
                kernel = context.createKernel("vector_add");
                context.setKernelArgBuffer(kernel, 0, bufferA);
                context.setKernelArgBuffer(kernel, 1, bufferB);
                context.setKernelArgBuffer(kernel, 2, bufferC);
                PointerBuffer workSize = stack.pointers(a.length);
                context.enqueueNDRangeKernel(kernel, 1, workSize);
                context.finish();
                ByteBuffer resultBuffer = ByteBuffer.allocateDirect(a.length * Float.BYTES).order(ByteOrder.nativeOrder());
                context.enqueueReadBuffer(bufferC, true, 0, (long) a.length * Float.BYTES, resultBuffer);
                resultBuffer.position(0);
                float[] result = new float[a.length];
                resultBuffer.asFloatBuffer().get(result);
                return result;
            } finally {
                if (kernel != 0L) {
                    context.releaseKernel(kernel);
                }
                if (bufferA != 0L) {
                    context.releaseBuffer(bufferA);
                }
                if (bufferB != 0L) {
                    context.releaseBuffer(bufferB);
                }
                if (bufferC != 0L) {
                    context.releaseBuffer(bufferC);
                }
            }
        }

        private float[] run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }

    private static final class ModernVectorAddTask extends OpenCLTask<float[]> {
        private final float[] a;
        private final float[] b;

        private ModernVectorAddTask(float[] a, float[] b) {
            super(MOD_ID, "modern_opencl_vector_add", 102L, forbiddenCpuFallback("modern_opencl_vector_add"), null);
            this.a = a;
            this.b = b;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) a.length * Float.BYTES * 3L;
        }

        @Override
        public int estimatedComputeUnits() {
            return a.length;
        }

        @Override
        public float[] executeOnGPU(OpenCLContext context) {
            return context.vectorAdd(a, b);
        }

        private float[] run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }

    private static final class LegacyMatrixMultiplyTask extends OpenCLTask<float[][]> {
        private final float[][] a;
        private final float[][] b;
        private final int rows;
        private final int inner;
        private final int cols;

        private LegacyMatrixMultiplyTask(float[][] a, float[][] b) {
            super(MOD_ID, "legacy_opencl_matrix_multiply", 201L, forbiddenCpuFallback("legacy_opencl_matrix_multiply"), null);
            this.a = a;
            this.b = b;
            this.rows = a.length;
            this.inner = a[0].length;
            this.cols = b[0].length;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) (rows * inner + inner * cols + rows * cols) * Float.BYTES;
        }

        @Override
        public int estimatedComputeUnits() {
            return rows * cols;
        }

        @Override
        public float[][] executeOnGPU(OpenCLContext context) {
            float[] flatA = flatten(a);
            float[] flatB = flatten(b);
            int outputSize = rows * cols;
            long bufferA = 0L;
            long bufferB = 0L;
            long bufferC = 0L;
            long kernel = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                bufferA = context.createBuffer(CL10.CL_MEM_READ_ONLY, (long) flatA.length * Float.BYTES);
                bufferB = context.createBuffer(CL10.CL_MEM_READ_ONLY, (long) flatB.length * Float.BYTES);
                bufferC = context.createBuffer(CL10.CL_MEM_WRITE_ONLY, (long) outputSize * Float.BYTES);
                context.enqueueWriteBuffer(bufferA, true, 0, (long) flatA.length * Float.BYTES, directFloatBuffer(flatA));
                context.enqueueWriteBuffer(bufferB, true, 0, (long) flatB.length * Float.BYTES, directFloatBuffer(flatB));
                kernel = context.createKernel("matrix_multiply");
                context.setKernelArgBuffer(kernel, 0, bufferA);
                context.setKernelArgBuffer(kernel, 1, bufferB);
                context.setKernelArgBuffer(kernel, 2, bufferC);
                context.setKernelArgInt(kernel, 3, rows);
                context.setKernelArgInt(kernel, 4, cols);
                context.setKernelArgInt(kernel, 5, inner);
                PointerBuffer workSize = stack.pointers(rows, cols);
                context.enqueueNDRangeKernel(kernel, 2, workSize);
                context.finish();
                ByteBuffer resultBuffer = ByteBuffer.allocateDirect(outputSize * Float.BYTES).order(ByteOrder.nativeOrder());
                context.enqueueReadBuffer(bufferC, true, 0, (long) outputSize * Float.BYTES, resultBuffer);
                resultBuffer.position(0);
                float[] flatResult = new float[outputSize];
                resultBuffer.asFloatBuffer().get(flatResult);
                return expand(flatResult, rows, cols);
            } finally {
                if (kernel != 0L) {
                    context.releaseKernel(kernel);
                }
                if (bufferA != 0L) {
                    context.releaseBuffer(bufferA);
                }
                if (bufferB != 0L) {
                    context.releaseBuffer(bufferB);
                }
                if (bufferC != 0L) {
                    context.releaseBuffer(bufferC);
                }
            }
        }

        private float[][] run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(3, TimeUnit.MINUTES);
        }
    }

    private static final class ModernMatrixMultiplyTask extends OpenCLTask<float[][]> {
        private final float[][] a;
        private final float[][] b;

        private ModernMatrixMultiplyTask(float[][] a, float[][] b) {
            super(MOD_ID, "modern_opencl_matrix_multiply", 202L, forbiddenCpuFallback("modern_opencl_matrix_multiply"), null);
            this.a = a;
            this.b = b;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) (a.length * a[0].length + b.length * b[0].length + a.length * b[0].length) * Float.BYTES;
        }

        @Override
        public int estimatedComputeUnits() {
            return a.length * b[0].length;
        }

        @Override
        public float[][] executeOnGPU(OpenCLContext context) {
            return context.matrixMultiply(a, b);
        }

        private float[][] run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(3, TimeUnit.MINUTES);
        }
    }

    private static final class LegacyMonteCarloTask extends OpenCLTask<Double> {
        private final int samples;

        private LegacyMonteCarloTask(int samples) {
            super(MOD_ID, "legacy_opencl_monte_carlo", 301L, forbiddenCpuFallback("legacy_opencl_monte_carlo"), null);
            this.samples = samples;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) samples * Float.BYTES;
        }

        @Override
        public int estimatedComputeUnits() {
            return samples;
        }

        @Override
        public Double executeOnGPU(OpenCLContext context) {
            long resultsBuffer = 0L;
            long kernel = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                resultsBuffer = context.createBuffer(CL10.CL_MEM_WRITE_ONLY, (long) samples * Float.BYTES);
                kernel = context.createKernel("monte_carlo_pi");
                context.setKernelArgBuffer(kernel, 0, resultsBuffer);
                context.setKernelArgInt(kernel, 1, samples);
                PointerBuffer workSize = stack.pointers(samples);
                context.enqueueNDRangeKernel(kernel, 1, workSize);
                context.finish();
                ByteBuffer resultBuffer = ByteBuffer.allocateDirect(samples * Float.BYTES).order(ByteOrder.nativeOrder());
                context.enqueueReadBuffer(resultsBuffer, true, 0, (long) samples * Float.BYTES, resultBuffer);
                resultBuffer.position(0);
                int hits = 0;
                for (int i = 0; i < samples; i++) {
                    if (resultBuffer.getFloat() > 0.5f) {
                        hits++;
                    }
                }
                return 4.0d * hits / samples;
            } finally {
                if (kernel != 0L) {
                    context.releaseKernel(kernel);
                }
                if (resultsBuffer != 0L) {
                    context.releaseBuffer(resultsBuffer);
                }
            }
        }

        private Double run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }

    private static final class ModernMonteCarloTask extends OpenCLTask<Double> {
        private final int samples;

        private ModernMonteCarloTask(int samples) {
            super(MOD_ID, "modern_opencl_monte_carlo", 302L, forbiddenCpuFallback("modern_opencl_monte_carlo"), null);
            this.samples = samples;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) samples * Float.BYTES;
        }

        @Override
        public int estimatedComputeUnits() {
            return samples;
        }

        @Override
        public Double executeOnGPU(OpenCLContext context) {
            return context.monteCarloPi(samples);
        }

        private Double run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }
}
