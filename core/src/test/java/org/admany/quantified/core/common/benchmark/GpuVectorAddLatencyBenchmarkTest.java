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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GpuVectorAddLatencyBenchmarkTest {

    private static final String MOD_ID = "benchmark_mod";
    private static final int WARMUP_RUNS = Math.max(1,
        Integer.getInteger("quantified.benchmarks.gpu.vector.warmups", 2));
    private static final int MEASURED_RUNS = Math.max(3,
        Integer.getInteger("quantified.benchmarks.gpu.vector.iterations", 5));
    private static final int VECTOR_LENGTH = Math.max(32_768,
        Integer.getInteger("quantified.benchmarks.gpu.vector.length", 1_000_000));

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
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void benchmarkOpenClVectorAdd() throws Exception {
        assumeTrue(benchmarksEnabled(), "Set QUANTIFIED_BENCHMARKS=true or quantified.benchmarks=true");
        GPUDetector.OpenCLDeviceInfo device = GPUDetector.listDevices().stream()
            .filter(candidate -> candidate.type() != GPUDetector.DeviceType.CPU)
            .min(Comparator.comparing(GPUDetector.OpenCLDeviceInfo::name))
            .orElse(null);
        assertNotNull(device, "No OpenCL GPU device available");

        float[] a = createVector(VECTOR_LENGTH, 0.25f, 0.003f);
        float[] b = createVector(VECTOR_LENGTH, -0.15f, 0.005f);

        OpenCLManager.shutdown();
        MultithreadingConfig.CONFIG.openclDeviceId = device.id();
        OpenCLManager.initialize();
        assumeTrue(OpenCLManager.forceProbeSynchronous(), "OpenCL unavailable");

        LatencyReport report = benchmark(() -> new OpenClVectorAddTask(a, b).run());
        System.out.printf(Locale.ROOT,
            "OPENCL vector_add device=%s avg=%.3f ms median=%.3f ms%n",
            device.name(),
            report.averageMs(),
            report.medianMs());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void benchmarkVulkanVectorAdd() throws Exception {
        assumeTrue(benchmarksEnabled(), "Set QUANTIFIED_BENCHMARKS=true or quantified.benchmarks=true");
        VulkanManager.forceProbeSynchronous();
        VulkanManager.VulkanDeviceInfo device = VulkanManager.listDevices().stream()
            .filter(candidate -> !candidate.softwareAdapter())
            .filter(candidate -> candidate.name() == null || !candidate.name().startsWith("Microsoft ("))
            .min(Comparator.comparing(VulkanManager.VulkanDeviceInfo::name))
            .orElse(null);
        assertNotNull(device, "No Vulkan GPU device available");

        float[] a = createVector(VECTOR_LENGTH, 0.25f, 0.003f);
        float[] b = createVector(VECTOR_LENGTH, -0.15f, 0.005f);

        VulkanManager.shutdown();
        VulkanManager.setPreferredDevice(device.name());
        VulkanManager.forceProbeSynchronous();
        assumeTrue(VulkanManager.ensureInitialised(), "Vulkan unavailable");

        LatencyReport report = benchmark(() -> new VulkanVectorAddTask(a, b).run());
        System.out.printf(Locale.ROOT,
            "VULKAN vector_add device=%s avg=%.3f ms median=%.3f ms%n",
            VulkanManager.deviceName(),
            report.averageMs(),
            report.medianMs());
    }

    private LatencyReport benchmark(ThrowingCall call) throws Exception {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            call.run();
        }
        List<Long> timings = new ArrayList<>(MEASURED_RUNS);
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long start = System.nanoTime();
            call.run();
            timings.add(System.nanoTime() - start);
        }
        return new LatencyReport(averageMs(timings), medianMs(timings));
    }

    private static boolean benchmarksEnabled() {
        return Boolean.getBoolean("quantified.benchmarks")
            || "true".equalsIgnoreCase(System.getenv("QUANTIFIED_BENCHMARKS"));
    }

    private static float[] createVector(int length, float base, float delta) {
        float[] vector = new float[length];
        for (int i = 0; i < length; i++) {
            vector[i] = base + (i % 2048) * delta;
        }
        return vector;
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

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private record LatencyReport(double averageMs, double medianMs) {
    }

    @SuppressWarnings("unchecked")
    private static <T> Supplier<T> forbiddenCpuFallback(String name) {
        return (Supplier<T>) (() -> {
            throw new IllegalStateException("CPU fallback was used during GPU benchmark: " + name);
        });
    }

    private static final class OpenClVectorAddTask extends OpenCLTask<float[]> {
        private final float[] a;
        private final float[] b;

        private OpenClVectorAddTask(float[] a, float[] b) {
            super(MOD_ID, "vector_opencl", 911L, forbiddenCpuFallback("vector_opencl"), null);
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

    private static final class VulkanVectorAddTask extends VulkanTask<float[]> {
        private final float[] a;
        private final float[] b;

        private VulkanVectorAddTask(float[] a, float[] b) {
            super(MOD_ID, "vector_vulkan", 912L, forbiddenCpuFallback("vector_vulkan"), null);
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

        private float[] run() throws Exception {
            return VulkanManager.executeOnGpu(this).get(2, TimeUnit.MINUTES);
        }
    }
}
