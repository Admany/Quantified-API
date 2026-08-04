import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class JarGpuPerfBench {

    private static final String MOD_ID = "benchie_gpu_mod";
    private static final String VERSION = "1.0.0";
    private static final int WARMUPS = Integer.getInteger("quantified.gpubench.warmups", 1);
    private static final int ITERS = Integer.getInteger("quantified.gpubench.iters", 3);
    private static final int VECTOR_LENGTH = Integer.getInteger("quantified.gpubench.vectorLength", 262_144);
    private static final int MATRIX_SIZE = Integer.getInteger("quantified.gpubench.matrixSize", 256);
    private static final int MONTE_SAMPLES = Integer.getInteger("quantified.gpubench.samples", 250_000);
    private static final boolean SKIP_OPENCL = Boolean.getBoolean("quantified.gpubench.skipOpencl");
    private static final boolean ALLOW_SOFTWARE_VULKAN = Boolean.getBoolean("quantified.gpubench.allowSoftwareVulkan");
    private static final boolean SKIP_RUNTIME_BOOTSTRAP = Boolean.getBoolean("quantified.gpubench.skipRuntimeBootstrap");

    private JarGpuPerfBench() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: JarGpuPerfBench <jar-path> [label]");
            System.exit(2);
        }

        String jarPath = new File(args[0]).getAbsolutePath();
        String label = args.length > 1 ? args[1] : new File(jarPath).getName();

        System.setProperty("quantified.lwjgl.stackSizeKb",
            System.getProperty("quantified.lwjgl.stackSizeKb", "65536"));
        System.setProperty("quantified.lwjgl.stackSizeBytes",
            System.getProperty("quantified.lwjgl.stackSizeBytes", String.valueOf(64 * 1024 * 1024)));

        try (URLClassLoader loader = new URLClassLoader(
            new URL[]{new File(jarPath).toURI().toURL()},
            JarGpuPerfBench.class.getClassLoader())) {
            GpuHarness harness = new GpuHarness(loader);
            try {
                harness.initialiseGlobals();

                LinkedHashMap<String, ScenarioStats> stats = new LinkedHashMap<>();
                if (!SKIP_OPENCL) {
                    stats.put("opencl_vector_add", measure(() -> harness.openClVectorAdd(), VECTOR_LENGTH));
                    stats.put("opencl_matrix_multiply", measure(() -> harness.openClMatrixMultiply(), MATRIX_SIZE * MATRIX_SIZE));
                    stats.put("opencl_monte_carlo_pi", measure(() -> harness.openClMonteCarlo(), MONTE_SAMPLES));
                }
                if (harness.vulkanAvailable()) {
                    stats.put("vulkan_vector_add", measure(() -> harness.vulkanVectorAdd(), VECTOR_LENGTH));
                    stats.put("vulkan_matrix_multiply", measure(() -> harness.vulkanMatrixMultiply(), MATRIX_SIZE * MATRIX_SIZE));
                    stats.put("vulkan_monte_carlo_pi", measure(() -> harness.vulkanMonteCarlo(), MONTE_SAMPLES));
                }

                printHuman(label, jarPath, harness, stats);
                System.out.println("RESULT_JSON=" + toJson(label, jarPath, harness, stats));
            } finally {
                harness.shutdown();
            }
        }
    }

    private static ScenarioStats measure(ThrowingSupplier<ScenarioRun> run, long unitCount) throws Exception {
        for (int i = 0; i < WARMUPS; i++) {
            run.get();
        }

        ArrayList<Double> timings = new ArrayList<>(ITERS);
        long extra = 0L;
        for (int i = 0; i < ITERS; i++) {
            ScenarioRun scenarioRun = run.get();
            timings.add(scenarioRun.nanos / 1_000_000.0d);
            extra = scenarioRun.extraMetric;
        }
        timings.sort(Double::compareTo);
        double sum = 0.0d;
        for (double timing : timings) {
            sum += timing;
        }
        double median = timings.get(timings.size() / 2);
        double mean = sum / timings.size();
        double min = timings.get(0);
        double max = timings.get(timings.size() - 1);
        double throughput = median <= 0.0d ? 0.0d : unitCount / (median / 1000.0d);
        return new ScenarioStats(median, mean, min, max, throughput, extra);
    }

    private static void printHuman(String label, String jarPath, GpuHarness harness, Map<String, ScenarioStats> stats) {
        System.out.println("QAPI GPU jar benchmark");
        System.out.println("label=" + label);
        System.out.println("jar=" + jarPath);
        System.out.println("openclDevice=" + harness.openClDeviceName);
        System.out.println("vulkanDevice=" + harness.vulkanDeviceName);
        if (!harness.vulkanAvailable() && !harness.vulkanUnavailableReason.isBlank()) {
            System.out.println("vulkanUnavailableReason=" + harness.vulkanUnavailableReason);
        }
        for (Map.Entry<String, ScenarioStats> entry : stats.entrySet()) {
            ScenarioStats stat = entry.getValue();
            System.out.printf(Locale.ROOT,
                "%s median=%.3fms mean=%.3fms min=%.3fms max=%.3fms throughput=%.1f/s extra=%d%n",
                entry.getKey(),
                stat.medianMs,
                stat.meanMs,
                stat.minMs,
                stat.maxMs,
                stat.throughputPerSecond,
                stat.extraMetric);
        }
    }

    private static String toJson(String label, String jarPath, GpuHarness harness, Map<String, ScenarioStats> stats) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        appendJsonField(json, "label", label).append(',');
        appendJsonField(json, "jar", jarPath).append(',');
        appendJsonField(json, "javaVersion", System.getProperty("java.version")).append(',');
        appendJsonField(json, "openclDevice", harness.openClDeviceName).append(',');
        appendJsonField(json, "vulkanDevice", harness.vulkanDeviceName).append(',');
        appendJsonField(json, "vulkanUnavailableReason", harness.vulkanUnavailableReason).append(',');
        json.append("\"scenarios\":{");
        boolean first = true;
        for (Map.Entry<String, ScenarioStats> entry : stats.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            ScenarioStats stat = entry.getValue();
            json.append('"').append(escape(entry.getKey())).append('"').append(':')
                .append('{')
                .append("\"medianMs\":").append(format(stat.medianMs)).append(',')
                .append("\"meanMs\":").append(format(stat.meanMs)).append(',')
                .append("\"minMs\":").append(format(stat.minMs)).append(',')
                .append("\"maxMs\":").append(format(stat.maxMs)).append(',')
                .append("\"throughputPerSec\":").append(format(stat.throughputPerSecond)).append(',')
                .append("\"extraMetric\":").append(stat.extraMetric)
                .append('}');
        }
        json.append("}}");
        return json.toString();
    }

    private static StringBuilder appendJsonField(StringBuilder json, String key, String value) {
        return json.append('"').append(escape(key)).append('"').append(':')
            .append('"').append(escape(value)).append('"');
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class ScenarioRun {
        private final long nanos;
        private final long extraMetric;

        private ScenarioRun(long nanos, long extraMetric) {
            this.nanos = nanos;
            this.extraMetric = extraMetric;
        }
    }

    private static final class ScenarioStats {
        private final double medianMs;
        private final double meanMs;
        private final double minMs;
        private final double maxMs;
        private final double throughputPerSecond;
        private final long extraMetric;

        private ScenarioStats(double medianMs, double meanMs, double minMs, double maxMs,
                              double throughputPerSecond, long extraMetric) {
            this.medianMs = medianMs;
            this.meanMs = meanMs;
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.throughputPerSecond = throughputPerSecond;
            this.extraMetric = extraMetric;
        }
    }

    private static final class GpuHarness {
        private final Method openClVectorAddMethod;
        private final Method openClMatrixMultiplyMethod;
        private final Method openClMonteCarloMethod;
        private final Method vulkanVectorAddMethod;
        private final Method vulkanMatrixMultiplyMethod;
        private final Method vulkanMonteCarloMethod;
        private final Method openClInitializeMethod;
        private final Method openClProbeMethod;
        private final Method openClListDevicesMethod;
        private final Method openClShutdownMethod;
        private final Method vulkanSetPreferredDeviceMethod;
        private final Method vulkanForceProbeMethod;
        private final Method vulkanWarmupAsyncMethod;
        private final Method vulkanEnsureInitialisedMethod;
        private final Method vulkanListDevicesMethod;
        private final Method vulkanShutdownMethod;
        private final Method vulkanRuntimeReprobeMethod;
        private final Method runtimeBootstrapMethod;
        private final Method runtimeShutdownMethod;
        private final Field configField;
        private final Object configObject;
        private final Field preferredGpuBackendField;
        private final Field enableGpuAccelerationField;
        private final Field openclDeviceIdField;
        private final Field vulkanDeviceIdField;
        private final Method lwjglEnsureConfiguredMethod;

        private final float[] vectorA;
        private final float[] vectorB;
        private final float[][] matrixA;
        private final float[][] matrixB;

        private String openClDeviceName = "Unavailable";
        private String vulkanDeviceName = "Unavailable";
        private String vulkanUnavailableReason = "";
        private final AtomicLong taskSequence = new AtomicLong(10_000L);

        private GpuHarness(ClassLoader loader) throws Exception {
            Class<?> quantifiedVulkanClass = Class.forName("org.admany.quantified.api.vulkan.QuantifiedVulkan", true, loader);
            Class<?> vulkanManagerClass = Class.forName("org.admany.quantified.core.common.vulkan.core.VulkanManager", true, loader);
            Class<?> vulkanRuntimeClass = Class.forName("org.admany.quantified.core.common.gpu.backend.VulkanRuntime", true, loader);
            Class<?> configClass = Class.forName("org.admany.quantified.core.common.config.MultithreadingConfig", true, loader);
            Class<?> configEntryClass = Class.forName("org.admany.quantified.core.common.config.MultithreadingConfig$Config", true, loader);
            Class<?> lwjglRuntimeTuningClass = Class.forName("org.admany.quantified.core.common.util.LwjglRuntimeTuning", true, loader);
            Class<?> runtimeClass = Class.forName("org.admany.quantified.core.common.platform.QuantifiedCoreRuntime", true, loader);
            Class<?> platformPathsClass = Class.forName("org.admany.quantified.core.common.platform.QuantifiedCoreRuntime$PlatformPaths", true, loader);
            Class<?> loggerClass = Class.forName("org.slf4j.Logger", true, loader);
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory", true, loader);

            if (SKIP_OPENCL) {
                openClVectorAddMethod = null;
                openClMatrixMultiplyMethod = null;
                openClMonteCarloMethod = null;
                openClInitializeMethod = null;
                openClProbeMethod = null;
                openClListDevicesMethod = null;
                openClShutdownMethod = null;
            } else {
                Class<?> quantifiedOpenClClass = Class.forName("org.admany.quantified.api.opencl.QuantifiedOpenCL", true, loader);
                Class<?> openClManagerClass = Class.forName("org.admany.quantified.core.common.opencl.core.OpenCLManager", true, loader);
                Class<?> gpuDetectorClass = Class.forName("org.admany.quantified.core.common.opencl.gpu.GPUDetector", true, loader);
                openClVectorAddMethod = quantifiedOpenClClass.getMethod("parallelVectorAdd",
                    String.class, String.class, long.class, float[].class, float[].class);
                openClMatrixMultiplyMethod = quantifiedOpenClClass.getMethod("parallelMatrixMultiply",
                    String.class, String.class, long.class, float[][].class, float[][].class);
                openClMonteCarloMethod = quantifiedOpenClClass.getMethod("parallelMonteCarloPi",
                    String.class, String.class, long.class, int.class);
                openClInitializeMethod = openClManagerClass.getMethod("initialize");
                openClProbeMethod = openClManagerClass.getMethod("forceProbeSynchronous");
                openClListDevicesMethod = gpuDetectorClass.getMethod("listDevices");
                openClShutdownMethod = openClManagerClass.getMethod("shutdown");
            }

            vulkanVectorAddMethod = quantifiedVulkanClass.getMethod("parallelVectorAdd",
                String.class, String.class, long.class, float[].class, float[].class);
            vulkanMatrixMultiplyMethod = quantifiedVulkanClass.getMethod("parallelMatrixMultiply",
                String.class, String.class, long.class, float[][].class, float[][].class);
            vulkanMonteCarloMethod = quantifiedVulkanClass.getMethod("parallelMonteCarloPi",
                String.class, String.class, long.class, int.class);

            vulkanSetPreferredDeviceMethod = vulkanManagerClass.getMethod("setPreferredDevice", String.class);
            vulkanForceProbeMethod = vulkanManagerClass.getMethod("forceProbeSynchronous");
            vulkanWarmupAsyncMethod = vulkanManagerClass.getMethod("warmupAsync", String.class);
            vulkanEnsureInitialisedMethod = vulkanManagerClass.getMethod("ensureInitialised");
            vulkanListDevicesMethod = vulkanManagerClass.getMethod("listDevices");
            vulkanShutdownMethod = vulkanManagerClass.getMethod("shutdown");
            vulkanRuntimeReprobeMethod = vulkanRuntimeClass.getMethod("reprobe");

            // A dedicated server can have no parent LWJGL at all: the isolated
            // runtime owns its bundled LWJGL classes and natives. Do not inspect
            // LwjglRuntimeTuning in that scenario; reflection resolves every
            // public signature on the class, including MemoryStack, which would
            // create a false parent-classpath dependency in this benchmark.
            Method ensureConfigured;
            try {
                ensureConfigured = lwjglRuntimeTuningClass.getMethod("ensureConfigured");
            } catch (LinkageError noParentLwjgl) {
                ensureConfigured = null;
            }
            lwjglEnsureConfiguredMethod = ensureConfigured;
            runtimeBootstrapMethod = runtimeClass.getMethod("bootstrap", loggerClass, platformPathsClass);
            Method shutdownMethod;
            try {
                shutdownMethod = runtimeClass.getMethod("shutdownRuntime");
            } catch (NoSuchMethodException ignored) {
                shutdownMethod = null;
            }
            runtimeShutdownMethod = shutdownMethod;

            configField = configClass.getField("CONFIG");
            configObject = configEntryClass.getConstructor().newInstance();
            preferredGpuBackendField = configEntryClass.getField("preferredGpuBackend");
            enableGpuAccelerationField = configEntryClass.getField("enableGpuAcceleration");
            openclDeviceIdField = configEntryClass.getField("openclDeviceId");
            vulkanDeviceIdField = configEntryClass.getField("vulkanDeviceId");

            Object logger = loggerFactoryClass.getMethod("getLogger", String.class)
                .invoke(null, "JarGpuPerfBench");
            Path runtimeRoot = Path.of(System.getProperty("java.io.tmpdir"), "quantified-jar-bench");
            runtimeRoot.toFile().mkdirs();
            Object platformPaths = platformPathsClass.getConstructor(Path.class, Path.class)
                .newInstance(runtimeRoot, runtimeRoot.resolve("config"));
            if (!SKIP_RUNTIME_BOOTSTRAP) {
                runtimeBootstrapMethod.invoke(null, logger, platformPaths);
            }

            vectorA = createVector(VECTOR_LENGTH, 0.25f, 0.003f);
            vectorB = createVector(VECTOR_LENGTH, -0.15f, 0.005f);
            matrixA = createMatrix(MATRIX_SIZE, MATRIX_SIZE, 0.021f);
            matrixB = createMatrix(MATRIX_SIZE, MATRIX_SIZE, 0.017f);
        }

        private void initialiseGlobals() throws Exception {
            if (lwjglEnsureConfiguredMethod != null) {
                lwjglEnsureConfiguredMethod.invoke(null);
            }
            enableGpuAccelerationField.setBoolean(configObject, true);
            // Keep Vulkan eligible while the harness explicitly exercises both APIs.
            // Selecting OPENCL here can suppress the Vulkan lifecycle before its
            // direct API scenarios have a chance to initialise.
            preferredGpuBackendField.set(configObject, "VULKAN_PREFERRED");
            configField.set(null, configObject);

            if (!SKIP_OPENCL) {
                selectOpenClDevice();
            }
            try {
                selectVulkanDevice();
            } catch (Exception exception) {
                vulkanDeviceName = "Unavailable";
                vulkanUnavailableReason = exception.getMessage();
            }
        }

        private void selectOpenClDevice() throws Exception {
            @SuppressWarnings("unchecked")
            List<Object> devices = (List<Object>) openClListDevicesMethod.invoke(null);
            Object selected = devices.stream()
                .filter(device -> !enumName(device, "type").equals("CPU"))
                .sorted(Comparator.comparing(device -> scoreOpenClDevice(device)).reversed())
                .findFirst()
                .orElse(null);
            if (selected == null) {
                throw new IllegalStateException("No OpenCL GPU device available");
            }
            openClDeviceName = Objects.toString(invoke(selected, "name"), "Unavailable");
            openclDeviceIdField.set(configObject, invoke(selected, "id"));
            openClInitializeMethod.invoke(null);
            boolean ready = (Boolean) openClProbeMethod.invoke(null);
            if (!ready) {
                throw new IllegalStateException("OpenCL probe failed for " + openClDeviceName);
            }
        }

        private void selectVulkanDevice() throws Exception {
            // listDevices reads the cached probe snapshot. Populate it first so a
            // freshly bootstrapped benchmark does not mistakenly report no GPU.
            vulkanRuntimeReprobeMethod.invoke(null);
            boolean probeReady = (Boolean) vulkanForceProbeMethod.invoke(null);
            if (!probeReady) {
                throw new IllegalStateException("Vulkan probe failed before device selection");
            }
            @SuppressWarnings("unchecked")
            List<Object> devices = (List<Object>) vulkanListDevicesMethod.invoke(null);
            Object selected = devices.stream()
                .filter(device -> ALLOW_SOFTWARE_VULKAN || !bool(device, "softwareAdapter"))
                .filter(device -> !Objects.toString(invoke(device, "name"), "").startsWith("Microsoft ("))
                .sorted(Comparator.comparing(device -> scoreVulkanDevice(device)).reversed())
                .findFirst()
                .orElse(null);
            if (selected == null) {
                throw new IllegalStateException("No Vulkan GPU device available");
            }
            vulkanDeviceName = Objects.toString(invoke(selected, "name"), "Unavailable");
            vulkanDeviceIdField.set(configObject, invoke(selected, "id"));
            vulkanSetPreferredDeviceMethod.invoke(null, vulkanDeviceName);
            // Changing the device intentionally shuts down the previous runtime. Requeue
            // the cached probe and await its large-stack warmup before executing kernels.
            if (!((Boolean) vulkanForceProbeMethod.invoke(null))) {
                throw new IllegalStateException("Vulkan probe failed after selecting " + vulkanDeviceName);
            }
            @SuppressWarnings("unchecked")
            CompletableFuture<Boolean> warmup = (CompletableFuture<Boolean>) vulkanWarmupAsyncMethod
                .invoke(null, "jar-gpu-benchmark-device-selection");
            boolean runtimeReady = warmup.get() && (Boolean) vulkanEnsureInitialisedMethod.invoke(null);
            if (!runtimeReady) {
                throw new IllegalStateException("Vulkan initialisation failed for " + vulkanDeviceName);
            }
        }

        private ScenarioRun openClVectorAdd() throws Exception {
            long start = System.nanoTime();
            float[] result = waitFuture(openClVectorAddMethod.invoke(null,
                MOD_ID, "bench_opencl_vector_" + taskSequence.incrementAndGet(), taskSequence.incrementAndGet(), vectorA, vectorB));
            verifyVector(result);
            return new ScenarioRun(System.nanoTime() - start, result.length);
        }

        private ScenarioRun openClMatrixMultiply() throws Exception {
            long start = System.nanoTime();
            float[][] result = waitFuture(openClMatrixMultiplyMethod.invoke(null,
                MOD_ID, "bench_opencl_matrix_" + taskSequence.incrementAndGet(), taskSequence.incrementAndGet(), matrixA, matrixB));
            verifyMatrix(result);
            return new ScenarioRun(System.nanoTime() - start, (long) result.length * result[0].length);
        }

        private ScenarioRun openClMonteCarlo() throws Exception {
            long start = System.nanoTime();
            Double result = waitFuture(openClMonteCarloMethod.invoke(null,
                MOD_ID, "bench_opencl_monte_" + taskSequence.incrementAndGet(), taskSequence.incrementAndGet(), MONTE_SAMPLES));
            verifyMonteCarlo(result);
            return new ScenarioRun(System.nanoTime() - start, MONTE_SAMPLES);
        }

        private ScenarioRun vulkanVectorAdd() throws Exception {
            long start = System.nanoTime();
            float[] result = waitFuture(vulkanVectorAddMethod.invoke(null,
                MOD_ID, "bench_vulkan_vector_" + taskSequence.incrementAndGet(), taskSequence.incrementAndGet(), vectorA, vectorB));
            verifyVector(result);
            return new ScenarioRun(System.nanoTime() - start, result.length);
        }

        private ScenarioRun vulkanMatrixMultiply() throws Exception {
            long start = System.nanoTime();
            float[][] result = waitFuture(vulkanMatrixMultiplyMethod.invoke(null,
                MOD_ID, "bench_vulkan_matrix_" + taskSequence.incrementAndGet(), taskSequence.incrementAndGet(), matrixA, matrixB));
            verifyMatrix(result);
            return new ScenarioRun(System.nanoTime() - start, (long) result.length * result[0].length);
        }

        private ScenarioRun vulkanMonteCarlo() throws Exception {
            long start = System.nanoTime();
            Double result = waitFuture(vulkanMonteCarloMethod.invoke(null,
                MOD_ID, "bench_vulkan_monte_" + taskSequence.incrementAndGet(), taskSequence.incrementAndGet(), MONTE_SAMPLES));
            verifyMonteCarlo(result);
            return new ScenarioRun(System.nanoTime() - start, MONTE_SAMPLES);
        }

        private boolean vulkanAvailable() {
            return !Objects.equals(vulkanDeviceName, "Unavailable");
        }

        private void shutdown() {
            if (runtimeShutdownMethod == null || SKIP_RUNTIME_BOOTSTRAP) {
                return;
            }
            try {
                runtimeShutdownMethod.invoke(null);
            } catch (Exception ignored) {
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T waitFuture(Object future) throws Exception {
            return ((CompletableFuture<T>) future).get();
        }

        private double scoreOpenClDevice(Object device) {
            String vendor = Objects.toString(invoke(device, "vendor"), "").toLowerCase(Locale.ROOT);
            String name = Objects.toString(invoke(device, "name"), "").toLowerCase(Locale.ROOT);
            long vram = ((Number) invoke(device, "vramBytes")).longValue();
            int units = ((Number) invoke(device, "computeUnits")).intValue();
            // A shared-memory iGPU can report a larger addressable memory figure than a
            // discrete adapter. Keep the benchmark representative by preferring the
            // discrete compute vendor before comparing its dedicated-memory capacity.
            double vendorScore = vendor.contains("nvidia") ? 10_000_000_000d
                : (vendor.contains("amd") ? 5_000_000_000d : 100_000d);
            double namePenalty = name.startsWith("microsoft (") ? -10_000_000d : 0d;
            return vendorScore + vram + units * 1000d + namePenalty;
        }

        private double scoreVulkanDevice(Object device) {
            String vendor = Objects.toString(invoke(device, "vendor"), "").toLowerCase(Locale.ROOT);
            long memory = ((Number) invoke(device, "localMemoryBytes")).longValue();
            int deviceType = ((Number) invoke(device, "deviceType")).intValue();
            // See scoreOpenClDevice: do not let an iGPU's shared-memory report outrank
            // a real discrete GPU during a hardware acceleration benchmark.
            double vendorScore = vendor.contains("nvidia") ? 10_000_000_000d
                : (vendor.contains("amd") ? 5_000_000_000d : 100_000d);
            return vendorScore + memory + deviceType * 1000d;
        }

        private static Object invoke(Object target, String methodName) {
            try {
                return target.getClass().getMethod(methodName).invoke(target);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }

        private static boolean bool(Object target, String methodName) {
            return (Boolean) invoke(target, methodName);
        }

        private static String enumName(Object target, String methodName) {
            Object value = invoke(target, methodName);
            return value instanceof Enum<?> enumeration ? enumeration.name() : Objects.toString(value, "");
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

        private void verifyVector(float[] result) {
            if (result.length != vectorA.length) {
                throw new IllegalStateException("Vector result length mismatch: " + result.length);
            }
        }

        private void verifyMatrix(float[][] result) {
            if (result.length != matrixA.length || result[0].length != matrixB[0].length) {
                throw new IllegalStateException("Matrix result shape mismatch");
            }
        }

        private void verifyMonteCarlo(Double result) {
            if (result == null || result < 2.5d || result > 4.5d) {
                throw new IllegalStateException("Monte Carlo result out of range: " + result);
            }
        }
    }
}
