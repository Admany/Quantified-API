package org.admany.quantified.core.common.benchmark;

import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.admany.quantified.core.common.opencl.gpu.GPUDetector;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.vulkan.core.VulkanContext;
import org.admany.quantified.core.common.vulkan.core.VulkanManager;
import org.admany.quantified.core.common.vulkan.core.VulkanTask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "quantified.benchmarks", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GpuBackendComparisonBenchmarkTest {

    private static final String MOD_ID = "benchmark_mod";
    private static final int WARMUP_RUNS = Math.max(1,
        Integer.getInteger("quantified.benchmarks.gpu.compare.warmups", 2));
    private static final int MEASURED_RUNS = Math.max(2,
        Integer.getInteger("quantified.benchmarks.gpu.compare.iterations", 5));
    private static final int VECTOR_LENGTH = Math.max(32_768,
        Integer.getInteger("quantified.benchmarks.gpu.compare.vectorLength", 1_000_000));
    private static final int MATRIX_SIZE = Math.max(64,
        Integer.getInteger("quantified.benchmarks.gpu.compare.matrixSize", 384));
    private static final int MONTE_CARLO_SAMPLES = Math.max(100_000,
        Integer.getInteger("quantified.benchmarks.gpu.compare.samples", 1_000_000));

    private static final String WORKLOAD_VECTOR_ADD = "vector_add";
    private static final String WORKLOAD_MATRIX_MULTIPLY = "matrix_multiply";
    private static final String WORKLOAD_MONTE_CARLO = "monte_carlo_pi";

    private String originalOpenClDeviceId;

    @BeforeAll
    void setUp() {
        LwjglRuntimeTuning.ensureConfigured();
        OpenCLManager.shutdown();
        VulkanManager.shutdown();
        VulkanManager.clearPreferredDevice();

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
            VulkanManager.shutdown();
            VulkanManager.clearPreferredDevice();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void compareOpenClAndVulkanBackends() {
        BenchmarkInputs inputs = new BenchmarkInputs(
            createVector(VECTOR_LENGTH, 0.25f, 0.003f),
            createVector(VECTOR_LENGTH, -0.15f, 0.005f),
            createMatrix(MATRIX_SIZE, MATRIX_SIZE, 0.021f),
            createMatrix(MATRIX_SIZE, MATRIX_SIZE, 0.017f)
        );

        List<BenchmarkTarget> targets = discoverTargets();
        assertFalse(targets.isEmpty(), "No GPU devices discovered for OpenCL/Vulkan benchmark");

        List<DeviceComparisonReport> reports = new ArrayList<>(targets.size());
        int openClSuccesses = 0;
        int vulkanSuccesses = 0;
        int pairedComparisons = 0;

        for (BenchmarkTarget target : targets) {
            BackendSuiteResult openCl = benchmarkOpenClSuite(target.openCl(), inputs);
            BackendSuiteResult vulkan = benchmarkVulkanSuite(target.vulkan(), inputs);

            if (openCl.hasAnySuccessfulWorkload()) {
                openClSuccesses++;
            }
            if (vulkan.hasAnySuccessfulWorkload()) {
                vulkanSuccesses++;
            }

            pairedComparisons += compareSuccessfulResults(openCl, vulkan);
            reports.add(new DeviceComparisonReport(target.displayName(), openCl, vulkan));
        }

        printReport(reports);

        assertTrue(openClSuccesses > 0, "No OpenCL workloads completed successfully on any GPU");
        assertTrue(vulkanSuccesses > 0, "No Vulkan workloads completed successfully on any GPU");
        assertTrue(pairedComparisons > 0, "No GPU completed both OpenCL and Vulkan workloads for comparison");
    }

    private List<BenchmarkTarget> discoverTargets() {
        LinkedHashMap<String, BenchmarkTargetBuilder> targets = new LinkedHashMap<>();

        for (GPUDetector.OpenCLDeviceInfo device : GPUDetector.listDevices()) {
            if (device.type() == GPUDetector.DeviceType.CPU) {
                continue;
            }
            String key = normalizeDeviceKey(device.name());
            targets.computeIfAbsent(key, ignored -> new BenchmarkTargetBuilder(displayName(device.vendor(), device.name())))
                .openCl(device);
        }

        for (VulkanManager.VulkanDeviceInfo device : VulkanManager.listDevices()) {
            if (device.softwareAdapter()) {
                continue;
            }
            String key = normalizeDeviceKey(device.name());
            targets.computeIfAbsent(key, ignored -> new BenchmarkTargetBuilder(displayName(device.vendor(), device.name())))
                .vulkan(device);
        }

        return targets.values().stream()
            .map(BenchmarkTargetBuilder::build)
            .sorted(Comparator.comparing(BenchmarkTarget::displayName))
            .toList();
    }

    private BackendSuiteResult benchmarkOpenClSuite(GPUDetector.OpenCLDeviceInfo device, BenchmarkInputs inputs) {
        if (device == null) {
            return BackendSuiteResult.unavailable("OpenCL", "Unavailable", "No OpenCL device matched this GPU");
        }

        try {
            OpenCLManager.shutdown();
            MultithreadingConfig.CONFIG.openclDeviceId = device.id();
            OpenCLManager.initialize();
            boolean ready = OpenCLManager.forceProbeSynchronous();
            if (!ready) {
                return BackendSuiteResult.failed("OpenCL", device.name(), OpenCLManager.runtimeStatus().failureReason());
            }

            LinkedHashMap<String, BackendWorkloadResult> workloads = new LinkedHashMap<>();
            workloads.put(WORKLOAD_VECTOR_ADD, benchmarkWorkload(WORKLOAD_VECTOR_ADD,
                () -> new OpenClVectorAddTask(inputs.vectorA(), inputs.vectorB()).run()));
            workloads.put(WORKLOAD_MATRIX_MULTIPLY, benchmarkWorkload(WORKLOAD_MATRIX_MULTIPLY,
                () -> new OpenClMatrixMultiplyTask(inputs.matrixA(), inputs.matrixB()).run()));
            workloads.put(WORKLOAD_MONTE_CARLO, benchmarkWorkload(WORKLOAD_MONTE_CARLO,
                () -> new OpenClMonteCarloTask(MONTE_CARLO_SAMPLES).run()));
            return BackendSuiteResult.success("OpenCL", device.name(), workloads);
        } catch (Throwable throwable) {
            return BackendSuiteResult.failed("OpenCL", device.name(), describeThrowable(throwable));
        } finally {
            OpenCLManager.shutdown();
        }
    }

    private BackendSuiteResult benchmarkVulkanSuite(VulkanManager.VulkanDeviceInfo device, BenchmarkInputs inputs) {
        if (device == null) {
            return BackendSuiteResult.unavailable("Vulkan", "Unavailable", "No Vulkan device matched this GPU");
        }

        try {
            VulkanManager.setPreferredDevice(device.name());
            boolean ready = VulkanManager.ensureInitialised();
            if (!ready) {
                return BackendSuiteResult.failed("Vulkan", device.name(), VulkanManager.runtimeStatus().failureReason());
            }

            LinkedHashMap<String, BackendWorkloadResult> workloads = new LinkedHashMap<>();
            workloads.put(WORKLOAD_VECTOR_ADD, benchmarkWorkload(WORKLOAD_VECTOR_ADD,
                () -> new VulkanVectorAddTask(inputs.vectorA(), inputs.vectorB()).run()));
            workloads.put(WORKLOAD_MATRIX_MULTIPLY, benchmarkWorkload(WORKLOAD_MATRIX_MULTIPLY,
                () -> new VulkanMatrixMultiplyTask(inputs.matrixA(), inputs.matrixB()).run()));
            workloads.put(WORKLOAD_MONTE_CARLO, benchmarkWorkload(WORKLOAD_MONTE_CARLO,
                () -> new VulkanMonteCarloTask(MONTE_CARLO_SAMPLES).run()));
            return BackendSuiteResult.success("Vulkan", VulkanManager.deviceName(), workloads);
        } catch (Throwable throwable) {
            return BackendSuiteResult.failed("Vulkan", device.name(), describeThrowable(throwable));
        } finally {
            VulkanManager.shutdown();
            VulkanManager.clearPreferredDevice();
        }
    }

    private int compareSuccessfulResults(BackendSuiteResult openCl, BackendSuiteResult vulkan) {
        int successfulComparisons = 0;
        successfulComparisons += compareWorkload(openCl, vulkan, WORKLOAD_VECTOR_ADD, this::assertVectorsEquivalent);
        successfulComparisons += compareWorkload(openCl, vulkan, WORKLOAD_MATRIX_MULTIPLY, this::assertMatricesEquivalent);
        successfulComparisons += compareWorkload(openCl, vulkan, WORKLOAD_MONTE_CARLO, this::assertMonteCarloEquivalent);
        return successfulComparisons;
    }

    @SuppressWarnings("unchecked")
    private <T> int compareWorkload(BackendSuiteResult openCl,
                                    BackendSuiteResult vulkan,
                                    String workload,
                                    ResultComparator<T> comparator) {
        BackendWorkloadResult openClResult = openCl.workloads().get(workload);
        BackendWorkloadResult vulkanResult = vulkan.workloads().get(workload);
        if (openClResult == null || vulkanResult == null || !openClResult.success() || !vulkanResult.success()) {
            return 0;
        }
        comparator.assertEquivalent((T) openClResult.sampleValue(), (T) vulkanResult.sampleValue());
        return 1;
    }

    private BackendWorkloadResult benchmarkWorkload(String workload, BackendCall<?> call) {
        try {
            Object sample = null;
            for (int i = 0; i < WARMUP_RUNS; i++) {
                sample = measure(call).value();
            }

            List<Long> timings = new ArrayList<>(MEASURED_RUNS);
            for (int i = 0; i < MEASURED_RUNS; i++) {
                TimedResult<?> result = measure(call);
                sample = result.value();
                timings.add(result.nanos());
            }

            return BackendWorkloadResult.success(workload, averageMs(timings), medianMs(timings), sample);
        } catch (Throwable throwable) {
            return BackendWorkloadResult.failed(workload, describeThrowable(throwable));
        }
    }

    private TimedResult<?> measure(BackendCall<?> call) throws Exception {
        long start = System.nanoTime();
        Object value = call.run();
        return new TimedResult<>(value, System.nanoTime() - start);
    }

    private void assertVectorsEquivalent(float[] openCl, float[] vulkan) {
        assertArrayEquals(openCl, vulkan, 0.0001f, "Vector add results diverged");
    }

    private void assertMatricesEquivalent(float[][] openCl, float[][] vulkan) {
        assertEquals(openCl.length, vulkan.length, "Matrix row counts differ");
        for (int row = 0; row < openCl.length; row++) {
            assertArrayEquals(openCl[row], vulkan[row], 0.0015f,
                "Matrix multiply row " + row + " diverged");
        }
    }

    private void assertMonteCarloEquivalent(Double openCl, Double vulkan) {
        assertTrue(openCl > 2.5d && openCl < 4.5d, "OpenCL Pi estimate out of range: " + openCl);
        assertTrue(vulkan > 2.5d && vulkan < 4.5d, "Vulkan Pi estimate out of range: " + vulkan);
        assertEquals(openCl, vulkan, 0.0001d, "Monte Carlo Pi estimates diverged");
    }

    private void printReport(List<DeviceComparisonReport> reports) {
        System.out.println();
        System.out.println("=== Quantified GPU Backend Comparison ===");
        System.out.printf(Locale.ROOT,
            "Config : warmups=%d, iterations=%d, vectorLength=%d, matrixSize=%d, samples=%d%n",
            WARMUP_RUNS, MEASURED_RUNS, VECTOR_LENGTH, MATRIX_SIZE, MONTE_CARLO_SAMPLES);
        System.out.println();

        for (DeviceComparisonReport report : reports) {
            System.out.printf(Locale.ROOT, "GPU: %s%n", report.displayName());
            System.out.printf(Locale.ROOT, "  OpenCL : %s%n", report.openCl().summaryLine());
            System.out.printf(Locale.ROOT, "  Vulkan : %s%n", report.vulkan().summaryLine());
            System.out.printf(Locale.ROOT, "  %-18s %18s %18s %10s%n",
                "Workload", "OpenCL", "Vulkan", "Speedup");

            for (String workload : List.of(WORKLOAD_VECTOR_ADD, WORKLOAD_MATRIX_MULTIPLY, WORKLOAD_MONTE_CARLO)) {
                BackendWorkloadResult openClResult = report.openCl().workloads().getOrDefault(workload,
                    BackendWorkloadResult.failed(workload, report.openCl().failureReason()));
                BackendWorkloadResult vulkanResult = report.vulkan().workloads().getOrDefault(workload,
                    BackendWorkloadResult.failed(workload, report.vulkan().failureReason()));
                String speedup = formatSpeedup(openClResult, vulkanResult);
                System.out.printf(Locale.ROOT, "  %-18s %18s %18s %10s%n",
                    workload,
                    openClResult.display(),
                    vulkanResult.display(),
                    speedup);
            }
            System.out.println();
        }
    }

    private String formatSpeedup(BackendWorkloadResult openCl, BackendWorkloadResult vulkan) {
        if (!openCl.success() || !vulkan.success() || vulkan.averageMs() <= 0.0d) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2fx", openCl.averageMs() / vulkan.averageMs());
    }

    private static String displayName(String vendor, String name) {
        String left = vendor != null ? vendor.trim() : "";
        String right = name != null ? name.trim() : "";
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        if (left.equalsIgnoreCase(right)) {
            return right;
        }
        String lowerLeft = left.toLowerCase(Locale.ROOT);
        String lowerRight = right.toLowerCase(Locale.ROOT);
        if (lowerRight.startsWith(lowerLeft)) {
            return right;
        }
        if (lowerLeft.endsWith(" corporation")) {
            String compact = left.substring(0, left.length() - " corporation".length()).trim();
            if (!compact.isEmpty() && lowerRight.startsWith(compact.toLowerCase(Locale.ROOT))) {
                return right;
            }
        }
        return left + " " + right;
    }

    private static String normalizeDeviceKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "Unknown failure";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message != null && !message.isBlank()) {
            return root.getClass().getSimpleName() + ": " + message;
        }
        return root.getClass().getSimpleName();
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

    private static Supplier<Object> forbiddenCpuFallback(String name) {
        return () -> {
            throw new IllegalStateException("CPU fallback was used during GPU backend benchmark: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> Supplier<T> castFallback(String name) {
        return (Supplier<T>) forbiddenCpuFallback(name);
    }

    private static double averageMs(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0d) / 1_000_000.0d;
    }

    private static double medianMs(List<Long> values) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 0) {
            return ((sorted.get(middle - 1) + sorted.get(middle)) / 2.0d) / 1_000_000.0d;
        }
        return sorted.get(middle) / 1_000_000.0d;
    }

    @FunctionalInterface
    private interface BackendCall<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    private interface ResultComparator<T> {
        void assertEquivalent(T openCl, T vulkan);
    }

    private record BenchmarkInputs(float[] vectorA, float[] vectorB, float[][] matrixA, float[][] matrixB) {
    }

    private record TimedResult<T>(T value, long nanos) {
    }

    private record BenchmarkTarget(String displayName,
                                   GPUDetector.OpenCLDeviceInfo openCl,
                                   VulkanManager.VulkanDeviceInfo vulkan) {
    }

    private static final class BenchmarkTargetBuilder {
        private final String displayName;
        private GPUDetector.OpenCLDeviceInfo openCl;
        private VulkanManager.VulkanDeviceInfo vulkan;

        private BenchmarkTargetBuilder(String displayName) {
            this.displayName = displayName;
        }

        private BenchmarkTargetBuilder openCl(GPUDetector.OpenCLDeviceInfo device) {
            this.openCl = device;
            return this;
        }

        private BenchmarkTargetBuilder vulkan(VulkanManager.VulkanDeviceInfo device) {
            this.vulkan = device;
            return this;
        }

        private BenchmarkTarget build() {
            return new BenchmarkTarget(displayName, openCl, vulkan);
        }
    }

    private record DeviceComparisonReport(String displayName,
                                          BackendSuiteResult openCl,
                                          BackendSuiteResult vulkan) {
    }

    private record BackendSuiteResult(String backend,
                                      String deviceName,
                                      Map<String, BackendWorkloadResult> workloads,
                                      String failureReason) {
        static BackendSuiteResult success(String backend,
                                          String deviceName,
                                          Map<String, BackendWorkloadResult> workloads) {
            return new BackendSuiteResult(backend, deviceName, Map.copyOf(workloads), null);
        }

        static BackendSuiteResult failed(String backend, String deviceName, String failureReason) {
            return new BackendSuiteResult(backend, deviceName, Map.of(), failureReason);
        }

        static BackendSuiteResult unavailable(String backend, String deviceName, String failureReason) {
            return failed(backend, deviceName, failureReason);
        }

        boolean hasAnySuccessfulWorkload() {
            return workloads.values().stream().anyMatch(BackendWorkloadResult::success);
        }

        String summaryLine() {
            if (failureReason != null && workloads.isEmpty()) {
                return "FAILED - " + failureReason;
            }
            long successful = workloads.values().stream().filter(BackendWorkloadResult::success).count();
            long failed = workloads.size() - successful;
            return String.format(Locale.ROOT, "OK (%s, %d passed, %d failed)", deviceName, successful, failed);
        }
    }

    private record BackendWorkloadResult(String workload,
                                         boolean success,
                                         double averageMs,
                                         double medianMs,
                                         Object sampleValue,
                                         String failureReason) {
        static BackendWorkloadResult success(String workload, double averageMs, double medianMs, Object sampleValue) {
            return new BackendWorkloadResult(workload, true, averageMs, medianMs, sampleValue, null);
        }

        static BackendWorkloadResult failed(String workload, String failureReason) {
            return new BackendWorkloadResult(workload, false, 0.0d, 0.0d, null, failureReason);
        }

        String display() {
            if (!success) {
                return "FAILED";
            }
            return String.format(Locale.ROOT, "%.3f ms", averageMs);
        }
    }

    private static final class OpenClVectorAddTask extends OpenCLTask<float[]> implements BackendCall<float[]> {
        private final float[] a;
        private final float[] b;

        private OpenClVectorAddTask(float[] a, float[] b) {
            super(MOD_ID, "benchmark_opencl_vector_add", 11L,
                castFallback("benchmark_opencl_vector_add"), null);
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

                ByteBuffer resultBuffer = ByteBuffer.allocateDirect(a.length * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
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

        @Override
        public float[] run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }

    private static final class VulkanVectorAddTask extends VulkanTask<float[]> implements BackendCall<float[]> {
        private final float[] a;
        private final float[] b;

        private VulkanVectorAddTask(float[] a, float[] b) {
            super(MOD_ID, "benchmark_vulkan_vector_add", 12L,
                castFallback("benchmark_vulkan_vector_add"), null);
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
        public float[] executeOnGPU(VulkanContext context) {
            return context.vectorAdd(a, b);
        }

        @Override
        public float[] run() throws Exception {
            return VulkanManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }

    private static final class OpenClMatrixMultiplyTask extends OpenCLTask<float[][]> implements BackendCall<float[][]> {
        private final float[][] a;
        private final float[][] b;
        private final int rows;
        private final int inner;
        private final int cols;

        private OpenClMatrixMultiplyTask(float[][] a, float[][] b) {
            super(MOD_ID, "benchmark_opencl_matrix_multiply", 21L,
                castFallback("benchmark_opencl_matrix_multiply"), null);
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

                ByteBuffer resultBuffer = ByteBuffer.allocateDirect(outputSize * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
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

        @Override
        public float[][] run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(3, TimeUnit.MINUTES);
        }
    }

    private static final class VulkanMatrixMultiplyTask extends VulkanTask<float[][]> implements BackendCall<float[][]> {
        private final float[][] a;
        private final float[][] b;

        private VulkanMatrixMultiplyTask(float[][] a, float[][] b) {
            super(MOD_ID, "benchmark_vulkan_matrix_multiply", 22L,
                castFallback("benchmark_vulkan_matrix_multiply"), null);
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
        public float[][] executeOnGPU(VulkanContext context) {
            return context.matrixMultiply(a, b);
        }

        @Override
        public float[][] run() throws Exception {
            return VulkanManager.executeOnGpu(this).get(3, TimeUnit.MINUTES);
        }
    }

    private static final class OpenClMonteCarloTask extends OpenCLTask<Double> implements BackendCall<Double> {
        private final int samples;

        private OpenClMonteCarloTask(int samples) {
            super(MOD_ID, "benchmark_opencl_monte_carlo", 31L,
                castFallback("benchmark_opencl_monte_carlo"), null);
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

                ByteBuffer resultBuffer = ByteBuffer.allocateDirect(samples * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
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

        @Override
        public Double run() throws Exception {
            return OpenCLManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }

    private static final class VulkanMonteCarloTask extends VulkanTask<Double> implements BackendCall<Double> {
        private final int samples;

        private VulkanMonteCarloTask(int samples) {
            super(MOD_ID, "benchmark_vulkan_monte_carlo", 32L,
                castFallback("benchmark_vulkan_monte_carlo"), null);
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
        public Double executeOnGPU(VulkanContext context) {
            return context.monteCarloPi(samples);
        }

        @Override
        public Double run() throws Exception {
            return VulkanManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }
}
