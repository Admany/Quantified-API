package org.admany.quantified.api.opencl;

import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.admany.quantified.core.common.util.TaskScheduler.TaskComplexity;
import org.admany.quantified.core.common.util.TaskScheduler.TaskType;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class QuantifiedOpenCL {

    private QuantifiedOpenCL() {
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
        long createKernel(String kernelName);
        void releaseKernel(long kernel);
        long createBuffer(long flags, long size);
        void releaseBuffer(long buffer);
        void enqueueWriteBuffer(long buffer, boolean blocking, long offset, long size, ByteBuffer data);
        void enqueueReadBuffer(long buffer, boolean blocking, long offset, long size, ByteBuffer into);
        void setKernelArgBuffer(long kernel, int argIndex, long buffer);
        void setKernelArg(long kernel, int argIndex, long argValue);
        void enqueueNDRangeKernel(long kernel, int workDim, PointerBuffer globalWorkSize);
        void finish();
        default float[] vectorAdd(float[] a, float[] b) {
            throw new UnsupportedOperationException("vectorAdd");
        }
        default float[][] matrixMultiply(float[][] a, float[][] b) {
            throw new UnsupportedOperationException("matrixMultiply");
        }
        default double monteCarloPi(int samples) {
            throw new UnsupportedOperationException("monteCarloPi");
        }
    }

    public interface Workload<T> {
        long estimatedVramBytes();
        int estimatedComputeUnits();
        T execute(Context context) throws Exception;
    }

    public interface CacheCodec<T> {
        ByteBuffer encode(T value);
        T decode(ByteBuffer data);
    }

    public static <T> Builder<T> builder(String modId, String taskName, long taskKey) {
        return new Builder<>(modId, taskName, taskKey);
    }

    public static void registerGpuAvailabilityListener(GpuAvailabilityListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!isCoreAvailable()) {
            return;
        }
        OpenCLManager.registerAvailabilityListener(listener::onGpuReady);
    }

    public static boolean isGpuReady() {
        return isCoreAvailable() && OpenCLManager.isAvailable();
    }

    private static boolean isCoreAvailable() {
        try {
            Class.forName("org.admany.quantified.core.common.opencl.core.OpenCLManager");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
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
        private String cacheKey;
        private CacheCodec<T> cacheCodec;

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

        public Builder<T> cacheKey(String cacheKey) {
            this.cacheKey = cacheKey;
            return this;
        }

        public Builder<T> cacheCodec(CacheCodec<T> cacheCodec) {
            this.cacheCodec = cacheCodec;
            return this;
        }

        public CompletableFuture<T> submit() {
            return QuantifiedOpenCL.submit(this);
        }

        public Object buildTask() {
            validate();
            // Check if core is available
            try {
                Class.forName("org.admany.quantified.core.common.opencl.core.OpenCLTask");
            } catch (ClassNotFoundException e) {
                // Core not available, cannot build task
                return null;
            }
            return new ApiOpenClTask<T>(this);
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

        public String cacheKey() {
            return cacheKey;
        }

        public CacheCodec<T> cacheCodec() {
            return cacheCodec;
        }

        private void validate() {
            Objects.requireNonNull(cpuFallback, "cpuFallback");
            Objects.requireNonNull(workload, "workload");
        }
    }

    public static <T> CompletableFuture<T> submit(Builder<T> builder) {
        builder.validate();

        // Check if core is available
        try {
            Class.forName("org.admany.quantified.core.common.util.TaskScheduler");
            Class.forName("org.admany.quantified.core.common.opencl.core.OpenCLTask");
        } catch (ClassNotFoundException e) {
            // Core not available, fallback to CPU
            return CompletableFuture.completedFuture(builder.cpuFallback().get());
        }

        Object task = new ApiOpenClTask<T>(builder);
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
            GpuBackendPreference.OPENCL_REQUIRED
        );
    }

    private static TaskComplexity mapComplexity(Complexity complexity) {
        return switch (complexity) {
            case SIMPLE -> TaskComplexity.SIMPLE;
            case MODERATE -> TaskComplexity.MODERATE;
            case COMPLEX -> TaskComplexity.COMPLEX;
            case MASSIVE -> TaskComplexity.MASSIVE;
        };
    }

    private static TaskType mapKind(WorkloadKind kind) {
        return switch (kind) {
            case GENERAL -> TaskType.GENERAL;
            case VECTOR_MATH -> TaskType.VECTOR_MATH;
            case MATRIX_MATH -> TaskType.MATRIX_MATH;
            case STATISTICAL -> TaskType.STATISTICAL;
            case SIMULATION -> TaskType.SIMULATION;
            case SIGNAL_PROCESSING -> TaskType.SIGNAL_PROCESSING;
            case SPATIAL_ANALYSIS -> TaskType.SPATIAL_ANALYSIS;
        };
    }

    public static final class ApiOpenClTask<T> {
        private final Builder<T> spec;

        public ApiOpenClTask(Builder<T> spec) {
            this.spec = spec;
        }

        public long estimatedVramBytes() {
            return spec.workload().estimatedVramBytes();
        }

        public int estimatedComputeUnits() {
            return spec.workload().estimatedComputeUnits();
        }

        public T executeOnGPU(Object context) throws Exception {
            OpenCLContext ctx = (OpenCLContext) context;
            try {
                return spec.workload().execute(new ApiContext(ctx));
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Exception exception) {
                throw new RuntimeException("OpenCL workload failed", exception);
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

        public String cacheKey() {
            return spec.cacheKey();
        }

        public CacheCodec<T> cacheCodec() {
            return spec.cacheCodec();
        }
    }

    private static final class ApiContext implements Context {
        private final OpenCLContext delegate;

        private ApiContext(OpenCLContext delegate) {
            this.delegate = delegate;
        }

        @Override
        public long createKernel(String kernelName) {
            return delegate.createKernel(kernelName);
        }

        @Override
        public void releaseKernel(long kernel) {
            delegate.releaseKernel(kernel);
        }

        @Override
        public long createBuffer(long flags, long size) {
            return delegate.createBuffer(flags, size);
        }

        @Override
        public void releaseBuffer(long buffer) {
            delegate.releaseBuffer(buffer);
        }

        @Override
        public void enqueueWriteBuffer(long buffer, boolean blocking, long offset, long size, ByteBuffer data) {
            delegate.enqueueWriteBuffer(buffer, blocking, offset, size, data);
        }

        @Override
        public void enqueueReadBuffer(long buffer, boolean blocking, long offset, long size, ByteBuffer into) {
            delegate.enqueueReadBuffer(buffer, blocking, offset, size, into);
        }

        @Override
        public void setKernelArgBuffer(long kernel, int argIndex, long buffer) {
            delegate.setKernelArgBuffer(kernel, argIndex, buffer);
        }

        @Override
        public void setKernelArg(long kernel, int argIndex, long argValue) {
            delegate.setKernelArg(kernel, argIndex, argValue);
        }

        @Override
        public void enqueueNDRangeKernel(long kernel, int workDim, PointerBuffer globalWorkSize) {
            delegate.enqueueNDRangeKernel(kernel, workDim, globalWorkSize);
        }

        @Override
        public void finish() {
            delegate.finish();
        }

        @Override
        public float[] vectorAdd(float[] a, float[] b) {
            return delegate.vectorAdd(a, b);
        }

        @Override
        public float[][] matrixMultiply(float[][] a, float[][] b) {
            return delegate.matrixMultiply(a, b);
        }

        @Override
        public double monteCarloPi(int samples) {
            return delegate.monteCarloPi(samples);
        }
    }

    // ===== HIGH-LEVEL GPU UTILITY METHODS =====

    /**
     * Executes parallel vector addition on the GPU, computing c[i] = a[i] + b[i] for each element.
     * This leverages GPU parallelism for efficient computation on large vectors.
     * @param modId Unique identifier for the mod submitting the task
     * @param taskName Descriptive name for the task, useful for logging and debugging
     * @param taskKey A unique key to prevent duplicate task submissions and enable caching
     * @param a The first input vector array of floats
     * @param b The second input vector array of floats
     * @return A CompletableFuture that will contain the resulting vector array upon completion
     */
    public static CompletableFuture<float[]> parallelVectorAdd(String modId, String taskName, long taskKey, float[] a, float[] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths must match: " + a.length + " vs " + b.length);
        }

        return QuantifiedOpenCL.<float[]>builder(modId, taskName, taskKey)
            .cpuFallback(() -> {
                float[] result = new float[a.length];
                for (int i = 0; i < a.length; i++) {
                    result[i] = a[i] + b[i];
                }
                return result;
            })
            .workload(new VectorAddWorkload(a, b))
            .dataSizeBytes((long) a.length * 4L * 3L) // 3 vectors: a, b, result
            .parallelUnits(a.length)
            .complexity(Complexity.SIMPLE)
            .kind(WorkloadKind.VECTOR_MATH)
            .submit();
    }

    /**
     * Performs parallel matrix multiplication on the GPU, computing c = a * b where a is m x p and b is p x n.
     * This operation benefits greatly from GPU acceleration, especially for large matrices.
     * @param modId Unique identifier for the mod submitting the task
     * @param taskName Descriptive name for the task, useful for logging and debugging
     * @param taskKey A unique key to prevent duplicate task submissions and enable caching
     * @param a The first input matrix as a 2D float array (m rows x p columns)
     * @param b The second input matrix as a 2D float array (p rows x n columns)
     * @return A CompletableFuture that will contain the resulting matrix (m x n) upon completion
     */
    public static CompletableFuture<float[][]> parallelMatrixMultiply(String modId, String taskName, long taskKey, float[][] a, float[][] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.length == 0 || b.length == 0 || a[0].length != b.length) {
            throw new IllegalArgumentException("Invalid matrix dimensions for multiplication");
        }

        int m = a.length;
        int p = a[0].length;
        int n = b[0].length;

        return QuantifiedOpenCL.<float[][]>builder(modId, taskName, taskKey)
            .cpuFallback(() -> {
                float[][] result = new float[m][n];
                for (int i = 0; i < m; i++) {
                    for (int j = 0; j < n; j++) {
                        float sum = 0.0f;
                        for (int k = 0; k < p; k++) {
                            sum += a[i][k] * b[k][j];
                        }
                        result[i][j] = sum;
                    }
                }
                return result;
            })
            .workload(new MatrixMultiplyWorkload(a, b, m, p, n))
            .dataSizeBytes((long) (m * p + p * n + m * n) * 4L)
            .parallelUnits(m * n)
            .complexity(Complexity.COMPLEX)
            .kind(WorkloadKind.MATRIX_MATH)
            .submit();
    }

    /**
     * Runs a Monte Carlo simulation on the GPU to estimate the value of π using random sampling.
     * This method generates random points and checks if they fall within a unit circle to approximate π.
     * @param modId Unique identifier for the mod submitting the task
     * @param taskName Descriptive name for the task, useful for logging and debugging
     * @param taskKey A unique key to prevent duplicate task submissions and enable caching
     * @param samples The number of random samples to generate for the estimation (higher = more accurate)
     * @return A CompletableFuture that will contain the estimated value of π as a Double upon completion
     */
    public static CompletableFuture<Double> parallelMonteCarloPi(String modId, String taskName, long taskKey, int samples) {
        if (samples <= 0) {
            throw new IllegalArgumentException("Samples must be positive: " + samples);
        }

        return QuantifiedOpenCL.<Double>builder(modId, taskName, taskKey)
            .cpuFallback(() -> {
                int hits = 0;
                for (int i = 0; i < samples; i++) {
                    double x = Math.random() * 2 - 1;
                    double y = Math.random() * 2 - 1;
                    if (x*x + y*y <= 1.0) {
                        hits++;
                    }
                }
                return 4.0 * hits / samples;
            })
            .workload(new MonteCarloPiWorkload(samples))
            .dataSizeBytes((long) samples * 4L)
            .parallelUnits(samples)
            .complexity(Complexity.MODERATE)
            .kind(WorkloadKind.STATISTICAL)
            .submit();
    }

    // ===== WORKLOAD IMPLEMENTATIONS =====

    private static final class VectorAddWorkload implements Workload<float[]> {
        private final float[] a;
        private final float[] b;

        VectorAddWorkload(float[] a, float[] b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) a.length * 4L * 3L; 
        }

        @Override
        public int estimatedComputeUnits() {
            return a.length;
        }

        @Override
        public float[] execute(Context context) throws Exception {
            return context.vectorAdd(a, b);
        }
    }

    private static final class MatrixMultiplyWorkload implements Workload<float[][]> {
        private final float[][] a;
        private final float[][] b;
        private final int m, p, n;

        MatrixMultiplyWorkload(float[][] a, float[][] b, int m, int p, int n) {
            this.a = a;
            this.b = b;
            this.m = m;
            this.p = p;
            this.n = n;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) (m * p + p * n + m * n) * 4L;
        }

        @Override
        public int estimatedComputeUnits() {
            return m * n;
        }

        @Override
        public float[][] execute(Context context) throws Exception {
            return context.matrixMultiply(a, b);
        }
    }

    private static final class MonteCarloPiWorkload implements Workload<Double> {
        private final int samples;

        MonteCarloPiWorkload(int samples) {
            this.samples = samples;
        }

        @Override
        public long estimatedVramBytes() {
            return (long) samples * 4L;
        }

        @Override
        public int estimatedComputeUnits() {
            return samples;
        }

        @Override
        public Double execute(Context context) throws Exception {
            return context.monteCarloPi(samples);
        }
    }
}
