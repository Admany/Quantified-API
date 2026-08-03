package org.admany.quantified.api.vulkan;

import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.admany.quantified.core.common.gpu.backend.VulkanExecutionSupport;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.admany.quantified.core.common.vulkan.core.VulkanPreparedDispatcher;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class QuantifiedVulkan {

    private QuantifiedVulkan() {
    }

    public enum Complexity {
        SIMPLE,
        MODERATE,
        COMPLEX,
        MASSIVE
    }

    public enum WorkloadKind {
        GENERAL,
        VECTOR_MATH,
        MATRIX_MATH,
        STATISTICAL,
        SIMULATION,
        SIGNAL_PROCESSING,
        SPATIAL_ANALYSIS
    }

    public interface Context {
        float[] vectorAdd(float[] a, float[] b);
        float[][] matrixMultiply(float[][] a, float[][] b);
        double monteCarloPi(int samples);
        float[] terrainGeneration(float[] inputCoords);
        float[] dispatchSpirv(String programKey,
                              byte[] spirv,
                              int storageBufferCount,
                              int pushConstantBytes,
                              float[][] inputBuffers,
                              int outputFloatCount,
                              int[] pushConstants,
                              int groupCountX,
                              int groupCountY,
                              int groupCountZ);

        default float[] dispatch(SpirvComputeProgram program,
                                 float[][] inputBuffers,
                                 int outputFloatCount,
                                 int[] pushConstants,
                                 int groupCountX) {
            Objects.requireNonNull(program, "program");
            return dispatchSpirv(program.key(), program.spirvUnsafe(), program.storageBufferCount(),
                program.pushConstantBytes(), inputBuffers, outputFloatCount, pushConstants,
                groupCountX, 1, 1);
        }

        default float[] dispatch(SpirvComputeProgram program,
                                 float[][] inputBuffers,
                                 int outputFloatCount,
                                 int[] pushConstants,
                                 int groupCountX,
                                 int groupCountY,
                                 int groupCountZ) {
            Objects.requireNonNull(program, "program");
            return dispatchSpirv(program.key(), program.spirvUnsafe(), program.storageBufferCount(),
                program.pushConstantBytes(), inputBuffers, outputFloatCount, pushConstants,
                groupCountX, groupCountY, groupCountZ);
        }
        String deviceName();
    }

    public interface Workload<T> {
        long estimatedVramBytes();
        int estimatedComputeUnits();
        T execute(Context context) throws Exception;
    }

    public static <T> Builder<T> builder(String modId, String taskName, long taskKey) {
        return new Builder<>(modId, taskName, taskKey);
    }

    /**
     * Prepares a stable SPIR-V program for explicitly coalesced, latency-sensitive
     * batches. This lane avoids the generic scheduler's collection window and
     * registry bridge; it is intended for region-sized work, never tiny per-cell
     * dispatches.
     */
    public static PreparedProgram prepare(String modId, String taskName, SpirvComputeProgram program) {
        return new PreparedProgram(modId, taskName, program);
    }

    public static final class PreparedProgram implements AutoCloseable {
        private final String modId;
        private final String taskName;
        private final SpirvComputeProgram program;
        private final LongAdder submitted = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder fallbacks = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder uploadedBytes = new LongAdder();
        private final LongAdder readbackBytes = new LongAdder();
        private volatile boolean closed;

        private PreparedProgram(String modId, String taskName, SpirvComputeProgram program) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.taskName = Objects.requireNonNull(taskName, "taskName");
            this.program = Objects.requireNonNull(program, "program");
        }

        public CompletableFuture<float[]> submit(long taskKey,
                                                  Dispatch dispatch,
                                                  Duration timeout,
                                                  Supplier<float[]> cpuFallback) {
            return submitInternal(taskKey, dispatch, timeout).handle((result, failure) -> {
                if (failure == null) {
                    return result;
                }
                fallbacks.increment();
                return cpuFallback.get();
            });
        }

        /**
         * Submits through the prepared Vulkan lane without disguising a failed
         * GPU dispatch as a successful CPU result. The caller remains responsible
         * for its exact fallback and can therefore report truthful GPU counters.
         */
        public CompletableFuture<float[]> submitRequired(long taskKey,
                                                          Dispatch dispatch,
                                                          Duration timeout) {
            return submitInternal(taskKey, dispatch, timeout);
        }

        private CompletableFuture<float[]> submitInternal(long taskKey,
                                                           Dispatch dispatch,
                                                           Duration timeout) {
            Objects.requireNonNull(dispatch, "dispatch");
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Prepared Vulkan program is closed"));
            }
            long inputBytes = dispatch.inputBytes();
            long outputBytes = (long) dispatch.outputFloatCount() * Float.BYTES;
            Workload<float[]> workload = new PreparedDispatchWorkload(program, dispatch, inputBytes + outputBytes);
            Builder<float[]> builder = QuantifiedVulkan.<float[]>builder(modId, taskName, taskKey)
                .cpuFallback(() -> {
                    throw new IllegalStateException("Prepared Vulkan required dispatch has no implicit CPU fallback");
                })
                .workload(workload)
                .dataSizeBytes(inputBytes + outputBytes)
                .parallelUnits(dispatch.computeUnits(program))
                .timeout(timeout)
                .allowMainThreadRerouting(false);
            ApiVulkanTask<float[]> task = new ApiVulkanTask<>(builder);
            submitted.increment();
            uploadedBytes.add(inputBytes);
            readbackBytes.add(outputBytes);
            return VulkanPreparedDispatcher.submit(task, timeout)
                .handle((result, failure) -> {
                    if (failure == null) {
                        completed.increment();
                        return result;
                    }
                    failures.increment();
                    if (failure instanceof CompletionException completionException) {
                        throw completionException;
                    }
                    throw new CompletionException(failure);
                });
        }

        public Snapshot snapshot() {
            return new Snapshot(submitted.sum(), completed.sum(), fallbacks.sum(), failures.sum(),
                uploadedBytes.sum(), readbackBytes.sum());
        }

        @Override
        public void close() {
            closed = true;
        }

        public record Snapshot(long submitted, long completed, long fallbacks, long failures,
                               long uploadedBytes, long readbackBytes) {
        }
    }

    /** Public so the isolated legacy-Minecraft Vulkan bridge can invoke it without illegal access. */
    public static final class PreparedDispatchWorkload implements Workload<float[]> {
        private final SpirvComputeProgram program;
        private final Dispatch dispatch;
        private final long vramBytes;

        public PreparedDispatchWorkload(SpirvComputeProgram program, Dispatch dispatch, long vramBytes) {
            this.program = Objects.requireNonNull(program, "program");
            this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
            this.vramBytes = vramBytes;
        }

        @Override
        public long estimatedVramBytes() {
            return vramBytes;
        }

        @Override
        public int estimatedComputeUnits() {
            return dispatch.computeUnits(program);
        }

        @Override
        public float[] execute(Context context) {
            return context.dispatch(program, dispatch.inputBuffers(), dispatch.outputFloatCount(),
                dispatch.pushConstants(), dispatch.groupCountX(), dispatch.groupCountY(), dispatch.groupCountZ());
        }
    }

    public record Dispatch(float[][] inputBuffers,
                           int outputFloatCount,
                           int[] pushConstants,
                           int groupCountX,
                           int groupCountY,
                           int groupCountZ) {
        public Dispatch {
            Objects.requireNonNull(inputBuffers, "inputBuffers");
            Objects.requireNonNull(pushConstants, "pushConstants");
            if (inputBuffers.length == 0 || outputFloatCount < 0
                || groupCountX < 1 || groupCountY < 1 || groupCountZ < 1) {
                throw new IllegalArgumentException("Invalid prepared Vulkan dispatch dimensions");
            }
            for (float[] input : inputBuffers) {
                Objects.requireNonNull(input, "input buffer");
            }
        }

        long inputBytes() {
            long floats = 0L;
            for (float[] input : inputBuffers) {
                floats += input.length;
            }
            return floats * Float.BYTES;
        }

        int computeUnits(SpirvComputeProgram program) {
            long groups = (long) groupCountX * groupCountY * groupCountZ;
            long localSize = (long) program.localSizeX() * program.localSizeY() * program.localSizeZ();
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, groups * localSize));
        }
    }

    public static boolean isGpuReady() {
        return VulkanExecutionSupport.hasExecutableRuntime();
    }

    public static String deviceName() {
        return VulkanExecutionSupport.deviceName();
    }

    public static final class Builder<T> {
        private final String modId;
        private final String taskName;
        private final long taskKey;
        private Supplier<T> cpuFallback;
        private Workload<T> workload;
        private long dataSizeBytes = 4 * 1024L;
        private int parallelUnits = 256;
        private Complexity complexity = Complexity.MODERATE;
        private WorkloadKind kind = WorkloadKind.GENERAL;
        private Duration timeout;
        private boolean allowMainThreadRerouting = true;

        private Builder(String modId, String taskName, long taskKey) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.taskName = Objects.requireNonNull(taskName, "taskName");
            this.taskKey = taskKey;
        }

        public Builder<T> cpuFallback(Supplier<T> cpuFallback) {
            this.cpuFallback = Objects.requireNonNull(cpuFallback, "cpuFallback");
            return this;
        }

        public Builder<T> workload(Workload<T> workload) {
            this.workload = Objects.requireNonNull(workload, "workload");
            return this;
        }

        public Builder<T> dataSizeBytes(long dataSizeBytes) {
            if (dataSizeBytes > 0) {
                this.dataSizeBytes = dataSizeBytes;
            }
            return this;
        }

        public Builder<T> parallelUnits(int parallelUnits) {
            if (parallelUnits > 0) {
                this.parallelUnits = parallelUnits;
            }
            return this;
        }

        public Builder<T> complexity(Complexity complexity) {
            this.complexity = Objects.requireNonNull(complexity, "complexity");
            return this;
        }

        public Builder<T> kind(WorkloadKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
            return this;
        }

        public Builder<T> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder<T> allowMainThreadRerouting(boolean allowMainThreadRerouting) {
            this.allowMainThreadRerouting = allowMainThreadRerouting;
            return this;
        }

        public CompletableFuture<T> submit() {
            return QuantifiedVulkan.submit(this);
        }

        public Object buildTask() {
            validate();
            return new ApiVulkanTask<>(this);
        }

        public String modId() {
            return modId;
        }

        public String taskName() {
            return taskName;
        }

        public long taskKey() {
            return taskKey;
        }

        public Supplier<T> cpuFallback() {
            return cpuFallback;
        }

        public Workload<T> workload() {
            return workload;
        }

        public long dataSizeBytes() {
            return dataSizeBytes;
        }

        public int parallelUnits() {
            return parallelUnits;
        }

        public Complexity complexity() {
            return complexity;
        }

        public WorkloadKind kind() {
            return kind;
        }

        public Duration timeout() {
            return timeout;
        }

        public boolean allowMainThreadRerouting() {
            return allowMainThreadRerouting;
        }

        private void validate() {
            Objects.requireNonNull(cpuFallback, "cpuFallback");
            Objects.requireNonNull(workload, "workload");
        }
    }

    public static <T> CompletableFuture<T> submit(Builder<T> builder) {
        builder.validate();
        Object task = new ApiVulkanTask<>(builder);
        long dataSize = Math.max(builder.dataSizeBytes(), builder.workload().estimatedVramBytes());
        return TaskScheduler.submitComputeTask(
            builder.modId(),
            builder.taskName(),
            builder.taskKey(),
            builder.cpuFallback(),
            task,
            dataSize,
            builder.parallelUnits(),
            mapComplexity(builder.complexity()),
            mapKind(builder.kind()),
            builder.timeout(),
            builder.allowMainThreadRerouting(),
            GpuBackendPreference.VULKAN_REQUIRED
        );
    }

    public static final class ApiVulkanTask<T> {
        private final Builder<T> spec;

        public ApiVulkanTask(Builder<T> spec) {
            this.spec = spec;
        }

        public long estimatedVramBytes() {
            return spec.workload().estimatedVramBytes();
        }

        public int estimatedComputeUnits() {
            return spec.workload().estimatedComputeUnits();
        }

        public T executeOnGPU(Object context) throws Exception {
            try {
                return spec.workload().execute(new ApiContext(context));
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Exception exception) {
                throw new RuntimeException("Vulkan workload failed", exception);
            }
        }

        public String modId() {
            return spec.modId();
        }

        public String name() {
            return spec.taskName();
        }

        public long taskKey() {
            return spec.taskKey();
        }

        public Supplier<T> cpuFallback() {
            return spec.cpuFallback();
        }

        public Optional<Duration> timeout() {
            return Optional.ofNullable(spec.timeout());
        }
    }

    private static final class ApiContext implements Context {
        private final Object delegate;
        private final Method vectorAdd;
        private final Method matrixMultiply;
        private final Method monteCarloPi;
        private final Method terrainGeneration;
        private final Method dispatchSpirv;
        private final Method deviceName;

        private ApiContext(Object delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            Class<?> type = delegate.getClass();
            this.vectorAdd = method(type, "vectorAdd", float[].class, float[].class);
            this.matrixMultiply = method(type, "matrixMultiply", float[][].class, float[][].class);
            this.monteCarloPi = method(type, "monteCarloPi", int.class);
            this.terrainGeneration = method(type, "terrainGeneration", float[].class);
            this.dispatchSpirv = method(type, "dispatchSpirv", String.class, byte[].class, int.class, int.class,
                float[][].class, int.class, int[].class, int.class, int.class, int.class);
            this.deviceName = method(type, "deviceName");
        }

        @Override
        public float[] vectorAdd(float[] a, float[] b) {
            return invoke(vectorAdd, float[].class, a, b);
        }

        @Override
        public float[][] matrixMultiply(float[][] a, float[][] b) {
            return invoke(matrixMultiply, float[][].class, a, b);
        }

        @Override
        public double monteCarloPi(int samples) {
            return invoke(monteCarloPi, Double.class, samples);
        }

        @Override
        public float[] terrainGeneration(float[] inputCoords) {
            return invoke(terrainGeneration, float[].class, inputCoords);
        }

        @Override
        public float[] dispatchSpirv(String programKey,
                                     byte[] spirv,
                                     int storageBufferCount,
                                     int pushConstantBytes,
                                     float[][] inputBuffers,
                                     int outputFloatCount,
                                     int[] pushConstants,
                                     int groupCountX,
                                     int groupCountY,
                                     int groupCountZ) {
            return invoke(dispatchSpirv, float[].class, programKey, spirv, storageBufferCount, pushConstantBytes,
                inputBuffers, outputFloatCount, pushConstants, groupCountX, groupCountY, groupCountZ);
        }

        @Override
        public String deviceName() {
            return invoke(deviceName, String.class);
        }

        private static Method method(Class<?> type, String name, Class<?>... parameterTypes) {
            try {
                return type.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException("Vulkan context missing method " + name, exception);
            }
        }

        private <R> R invoke(Method method, Class<R> resultType, Object... args) {
            try {
                Object result = method.invoke(delegate, args);
                return resultType.cast(result);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Vulkan context call failed: " + method.getName(), exception);
            }
        }
    }

    public static CompletableFuture<float[]> parallelVectorAdd(String modId, String taskName, long taskKey, float[] a, float[] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths must match: " + a.length + " vs " + b.length);
        }
        if (!VulkanExecutionSupport.hasExecutableRuntime()) {
            return QuantifiedOpenCL.parallelVectorAdd(modId, taskName, taskKey, a, b);
        }
        return QuantifiedVulkan.<float[]>builder(modId, taskName, taskKey)
            .cpuFallback(() -> {
                float[] result = new float[a.length];
                for (int i = 0; i < a.length; i++) {
                    result[i] = a[i] + b[i];
                }
                return result;
            })
            .workload(new Workload<>() {
                @Override
                public long estimatedVramBytes() {
                    return (long) a.length * Float.BYTES * 3L;
                }

                @Override
                public int estimatedComputeUnits() {
                    return a.length;
                }

                @Override
                public float[] execute(Context context) {
                    return context.vectorAdd(a, b);
                }
            })
            .dataSizeBytes((long) a.length * Float.BYTES * 3L)
            .parallelUnits(a.length)
            .complexity(Complexity.SIMPLE)
            .kind(WorkloadKind.VECTOR_MATH)
            .submit();
    }

    public static CompletableFuture<float[][]> parallelMatrixMultiply(String modId, String taskName, long taskKey, float[][] a, float[][] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.length == 0 || b.length == 0 || a[0].length != b.length) {
            throw new IllegalArgumentException("Invalid matrix dimensions for multiplication");
        }
        if (!VulkanExecutionSupport.hasExecutableRuntime()) {
            return QuantifiedOpenCL.parallelMatrixMultiply(modId, taskName, taskKey, a, b);
        }
        int m = a.length;
        int p = a[0].length;
        int n = b[0].length;
        return QuantifiedVulkan.<float[][]>builder(modId, taskName, taskKey)
            .cpuFallback(() -> {
                float[][] result = new float[m][n];
                for (int row = 0; row < m; row++) {
                    for (int col = 0; col < n; col++) {
                        float sum = 0.0f;
                        for (int k = 0; k < p; k++) {
                            sum += a[row][k] * b[k][col];
                        }
                        result[row][col] = sum;
                    }
                }
                return result;
            })
            .workload(new Workload<>() {
                @Override
                public long estimatedVramBytes() {
                    return (long) (m * p + p * n + m * n) * Float.BYTES;
                }

                @Override
                public int estimatedComputeUnits() {
                    return m * n;
                }

                @Override
                public float[][] execute(Context context) {
                    return context.matrixMultiply(a, b);
                }
            })
            .dataSizeBytes((long) (m * p + p * n + m * n) * Float.BYTES)
            .parallelUnits(m * n)
            .complexity(Complexity.COMPLEX)
            .kind(WorkloadKind.MATRIX_MATH)
            .submit();
    }

    public static CompletableFuture<Double> parallelMonteCarloPi(String modId, String taskName, long taskKey, int samples) {
        if (samples <= 0) {
            throw new IllegalArgumentException("Samples must be positive: " + samples);
        }
        if (!VulkanExecutionSupport.hasExecutableRuntime()) {
            return QuantifiedOpenCL.parallelMonteCarloPi(modId, taskName, taskKey, samples);
        }
        return QuantifiedVulkan.<Double>builder(modId, taskName, taskKey)
            .cpuFallback(() -> {
                int hits = 0;
                for (int i = 0; i < samples; i++) {
                    double x = Math.random() * 2.0d - 1.0d;
                    double y = Math.random() * 2.0d - 1.0d;
                    if ((x * x + y * y) <= 1.0d) {
                        hits++;
                    }
                }
                return 4.0d * hits / samples;
            })
            .workload(new Workload<>() {
                @Override
                public long estimatedVramBytes() {
                    return (long) samples * Float.BYTES;
                }

                @Override
                public int estimatedComputeUnits() {
                    return samples;
                }

                @Override
                public Double execute(Context context) {
                    return context.monteCarloPi(samples);
                }
            })
            .dataSizeBytes((long) samples * Float.BYTES)
            .parallelUnits(samples)
            .complexity(Complexity.MODERATE)
            .kind(WorkloadKind.STATISTICAL)
            .submit();
    }

    private static TaskScheduler.TaskComplexity mapComplexity(Complexity complexity) {
        return switch (complexity) {
            case SIMPLE -> TaskScheduler.TaskComplexity.SIMPLE;
            case MODERATE -> TaskScheduler.TaskComplexity.MODERATE;
            case COMPLEX -> TaskScheduler.TaskComplexity.COMPLEX;
            case MASSIVE -> TaskScheduler.TaskComplexity.MASSIVE;
        };
    }

    private static TaskScheduler.TaskType mapKind(WorkloadKind kind) {
        return switch (kind) {
            case GENERAL -> TaskScheduler.TaskType.GENERAL;
            case VECTOR_MATH -> TaskScheduler.TaskType.VECTOR_MATH;
            case MATRIX_MATH -> TaskScheduler.TaskType.MATRIX_MATH;
            case STATISTICAL -> TaskScheduler.TaskType.STATISTICAL;
            case SIMULATION -> TaskScheduler.TaskType.SIMULATION;
            case SIGNAL_PROCESSING -> TaskScheduler.TaskType.SIGNAL_PROCESSING;
            case SPATIAL_ANALYSIS -> TaskScheduler.TaskType.SPATIAL_ANALYSIS;
        };
    }
}
