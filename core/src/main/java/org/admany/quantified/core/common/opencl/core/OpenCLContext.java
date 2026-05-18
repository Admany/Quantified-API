package org.admany.quantified.core.common.opencl.core;

import org.admany.quantified.core.common.opencl.gpu.GPUDetector;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL20;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OpenCLContext implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(OpenCLContext.class.getName());

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile long contextHandle = 0;
    private volatile long commandQueueHandle = 0;
    private volatile long programHandle = 0;
    @SuppressWarnings("unused")
    private volatile long deviceId = 0;
    private final BufferPool bufferPool = new BufferPool();
    private final ThreadLocal<Map<String, Long>> kernelCache = ThreadLocal.withInitial(HashMap::new);
    private final ConcurrentHashMap<Long, Boolean> cachedKernelHandles = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<Integer, ByteBuffer>> hostBufferCache = ThreadLocal.withInitial(HashMap::new);

    private OpenCLContext() {}

    public static OpenCLContext create(GPUDetector.GPUCapabilities capabilities) {
        if (!capabilities.supported()) {
            throw new IllegalArgumentException("GPU not supported for OpenCL acceleration");
        }

        if (!OpenCLRuntime.ensureInitialised()) {
            throw new IllegalStateException("OpenCL runtime not initialised");
        }

        OpenCLContext context = new OpenCLContext();
        try {
            context.initialize(capabilities);
            LOGGER.info("OpenCL context initialized for device: " + capabilities.device().name());
            return context;
        } catch (Exception e) {
            context.close();
            throw new RuntimeException("Failed to initialize OpenCL context", e);
        }
    }

    private void initialize(GPUDetector.GPUCapabilities capabilities) {
        if (!initialized.compareAndSet(false, true)) {
            throw new IllegalStateException("Context already initialized");
        }

        try {
            contextHandle = createContext(capabilities.device());
            deviceId = capabilities.device().deviceId();

            commandQueueHandle = createCommandQueue(contextHandle, capabilities.device());

            programHandle = createProgram(contextHandle, capabilities.device());

            LOGGER.fine("OpenCL context initialized successfully");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize OpenCL context", e);
            throw e;
        }
    }

    public boolean isHealthy() {
     return initialized.get() &&
         contextHandle != 0 &&
         commandQueueHandle != 0;
    }

    public long getContextHandle() {
        return contextHandle;
    }

    public long getCommandQueueHandle() {
        return commandQueueHandle;
    }

    public long getProgramHandle() {
        return programHandle;
    }

    public long createBuffer(long flags, long size) {
        if (bufferPool.isEnabled() && bufferPool.isPoolable(flags)) {
            long pooled = bufferPool.acquire(flags, size);
            if (pooled != 0L) {
                return pooled;
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            ByteBuffer hostPtr = null;
            boolean requiresHostPtr = (flags & (CL10.CL_MEM_COPY_HOST_PTR | CL10.CL_MEM_USE_HOST_PTR)) != 0;
            if (requiresHostPtr) {
                if (size > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Buffer size exceeds maximum supported for stack allocation: " + size);
                }
                hostPtr = stack.malloc((int) size);
            }

            long buffer;
            if (hostPtr != null) {
                buffer = CL10.clCreateBuffer(contextHandle, flags, hostPtr, errcode);
            } else {
                buffer = CL10.clCreateBuffer(contextHandle, flags, size, errcode);
            }
            checkCleError(errcode.get(0), "clCreateBuffer");
            bufferPool.register(buffer, flags, size);
            return buffer;
        }
    }

    public long createKernel(String kernelName) {
        Map<String, Long> cache = kernelCache.get();
        Long cached = cache.get(kernelName);
        if (cached != null && cached.longValue() != 0L) {
            return cached.longValue();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            long kernel = CL10.clCreateKernel(programHandle, kernelName, errcode);
            checkCleError(errcode.get(0), "clCreateKernel(" + kernelName + ")");
            cache.put(kernelName, kernel);
            cachedKernelHandles.put(kernel, Boolean.TRUE);
            return kernel;
        }
    }

    public void enqueueWriteBuffer(long buffer, boolean blocking, long offset, long size, ByteBuffer ptr) {
        int err = CL10.clEnqueueWriteBuffer(commandQueueHandle, buffer, blocking, offset, ptr, null, null);
        checkCleError(err, "clEnqueueWriteBuffer");
    }

    public void enqueueReadBuffer(long buffer, boolean blocking, long offset, long size, ByteBuffer ptr) {
        int err = CL10.clEnqueueReadBuffer(commandQueueHandle, buffer, blocking, offset, ptr, null, null);
        checkCleError(err, "clEnqueueReadBuffer");
    }

    public void setKernelArg(long kernel, int argIndex, long argValue) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer argBuffer = stack.pointers(argValue);
            int err = CL10.clSetKernelArg(kernel, argIndex, argBuffer);
            checkCleError(err, "clSetKernelArg(" + argIndex + ")");
        }
    }

    public void setKernelArgInt(long kernel, int argIndex, int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(Integer.BYTES);
            buffer.putInt(value);
            buffer.flip();
            int err = CL10.clSetKernelArg(kernel, argIndex, buffer);
            checkCleError(err, "clSetKernelArg(" + argIndex + ", int)");
        }
    }

    public void setKernelArgFloat(long kernel, int argIndex, float value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(Float.BYTES);
            buffer.putFloat(value);
            buffer.flip();
            int err = CL10.clSetKernelArg(kernel, argIndex, buffer);
            checkCleError(err, "clSetKernelArg(" + argIndex + ", float)");
        }
    }

    public void setKernelArgBuffer(long kernel, int argIndex, long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer argBuffer = stack.pointers(buffer);
            int err = CL10.clSetKernelArg(kernel, argIndex, argBuffer);
            checkCleError(err, "clSetKernelArg(" + argIndex + ", buffer)");
        }
    }

    public void enqueueNDRangeKernel(long kernel, int workDim, PointerBuffer globalWorkSize) {
        int err = CL10.clEnqueueNDRangeKernel(commandQueueHandle, kernel, workDim, null, globalWorkSize, null, null, null);
        checkCleError(err, "clEnqueueNDRangeKernel");
    }

    public void finish() {
        int err = CL10.clFinish(commandQueueHandle);
        checkCleError(err, "clFinish");
    }

    public void releaseBuffer(long buffer) {
        if (bufferPool.release(buffer)) {
            return;
        }
        CL10.clReleaseMemObject(buffer);
    }

    public void releaseKernel(long kernel) {
        if (kernel == 0L || cachedKernelHandles.containsKey(kernel)) {
            return;
        }
        CL10.clReleaseKernel(kernel);
    }

    public float[] vectorAdd(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths must match");
        }
        long bufferA = 0L;
        long bufferB = 0L;
        long bufferC = 0L;
        long kernel = createKernel("vector_add");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int byteCount = a.length * Float.BYTES;
            bufferA = createBuffer(CL10.CL_MEM_READ_ONLY, byteCount);
            bufferB = createBuffer(CL10.CL_MEM_READ_ONLY, byteCount);
            bufferC = createBuffer(CL10.CL_MEM_WRITE_ONLY, byteCount);

            enqueueWriteBuffer(bufferA, true, 0, byteCount, asFloatBytes(a));
            enqueueWriteBuffer(bufferB, true, 0, byteCount, asFloatBytes(b));

            setKernelArgBuffer(kernel, 0, bufferA);
            setKernelArgBuffer(kernel, 1, bufferB);
            setKernelArgBuffer(kernel, 2, bufferC);

            PointerBuffer workSize = stack.mallocPointer(1);
            workSize.put(0, a.length);
            enqueueNDRangeKernel(kernel, 1, workSize);
            finish();

            ByteBuffer resultBuffer = hostBuffer(byteCount);
            enqueueReadBuffer(bufferC, true, 0, byteCount, resultBuffer);
            float[] result = new float[a.length];
            resultBuffer.asFloatBuffer().get(result);
            return result;
        } finally {
            if (bufferA != 0L) {
                releaseBuffer(bufferA);
            }
            if (bufferB != 0L) {
                releaseBuffer(bufferB);
            }
            if (bufferC != 0L) {
                releaseBuffer(bufferC);
            }
        }
    }

    public float[][] matrixMultiply(float[][] a, float[][] b) {
        if (a.length == 0 || b.length == 0 || a[0].length != b.length) {
            throw new IllegalArgumentException("Invalid matrix dimensions for multiplication");
        }
        int rows = a.length;
        int inner = a[0].length;
        int cols = b[0].length;
        float[] flatA = flattenMatrix(a);
        float[] flatB = flattenMatrix(b);
        int outputCount = rows * cols;
        long bufferA = 0L;
        long bufferB = 0L;
        long bufferC = 0L;
        long kernel = createKernel("matrix_multiply");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int bytesA = flatA.length * Float.BYTES;
            int bytesB = flatB.length * Float.BYTES;
            int bytesC = outputCount * Float.BYTES;
            bufferA = createBuffer(CL10.CL_MEM_READ_ONLY, bytesA);
            bufferB = createBuffer(CL10.CL_MEM_READ_ONLY, bytesB);
            bufferC = createBuffer(CL10.CL_MEM_WRITE_ONLY, bytesC);

            enqueueWriteBuffer(bufferA, true, 0, bytesA, asFloatBytes(flatA));
            enqueueWriteBuffer(bufferB, true, 0, bytesB, asFloatBytes(flatB));

            setKernelArgBuffer(kernel, 0, bufferA);
            setKernelArgBuffer(kernel, 1, bufferB);
            setKernelArgBuffer(kernel, 2, bufferC);
            setKernelArgInt(kernel, 3, rows);
            setKernelArgInt(kernel, 4, cols);
            setKernelArgInt(kernel, 5, inner);

            PointerBuffer workSize = stack.mallocPointer(2);
            workSize.put(0, rows);
            workSize.put(1, cols);
            enqueueNDRangeKernel(kernel, 2, workSize);
            finish();

            ByteBuffer resultBuffer = hostBuffer(bytesC);
            enqueueReadBuffer(bufferC, true, 0, bytesC, resultBuffer);
            float[] flatResult = new float[outputCount];
            resultBuffer.asFloatBuffer().get(flatResult);
            return expandMatrix(flatResult, rows, cols);
        } finally {
            if (bufferA != 0L) {
                releaseBuffer(bufferA);
            }
            if (bufferB != 0L) {
                releaseBuffer(bufferB);
            }
            if (bufferC != 0L) {
                releaseBuffer(bufferC);
            }
        }
    }

    public double monteCarloPi(int samples) {
        if (samples <= 0) {
            throw new IllegalArgumentException("Samples must be positive");
        }
        long resultsBuffer = 0L;
        long kernel = createKernel("monte_carlo_pi");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int bytes = samples * Float.BYTES;
            resultsBuffer = createBuffer(CL10.CL_MEM_WRITE_ONLY, bytes);
            setKernelArgBuffer(kernel, 0, resultsBuffer);
            setKernelArgInt(kernel, 1, samples);

            PointerBuffer workSize = stack.mallocPointer(1);
            workSize.put(0, samples);
            enqueueNDRangeKernel(kernel, 1, workSize);
            finish();

            ByteBuffer resultBuffer = hostBuffer(bytes);
            enqueueReadBuffer(resultsBuffer, true, 0, bytes, resultBuffer);
            FloatBuffer floatBuffer = resultBuffer.asFloatBuffer();
            int hits = 0;
            for (int i = 0; i < samples; i++) {
                if (floatBuffer.get(i) > 0.5f) {
                    hits++;
                }
            }
            return 4.0d * hits / samples;
        } finally {
            if (resultsBuffer != 0L) {
                releaseBuffer(resultsBuffer);
            }
        }
    }

    @Override
    public void close() {
        if (!initialized.get()) return;

        if (!OpenCLRuntime.isInitialised()) {
            resetHandles();
            LOGGER.fine("OpenCL context closed after runtime shutdown");
            return;
        }

        try {
            releaseCachedKernels();
            if (programHandle != 0) {
                releaseProgram(programHandle);
                programHandle = 0;
            }

            if (commandQueueHandle != 0) {
                releaseCommandQueue(commandQueueHandle);
                commandQueueHandle = 0;
            }

            if (contextHandle != 0) {
                releaseContext(contextHandle);
                contextHandle = 0;
            }

            bufferPool.clear();
            resetHandles();
            LOGGER.info("OpenCL context cleaned up");

        } catch (IllegalStateException e) {
            if (isLibraryNotLoaded(e)) {
                resetHandles();
                LOGGER.fine("OpenCL context cleanup skipped (library already unloaded)");
                return;
            }
            LOGGER.log(Level.WARNING, "Error during OpenCL context cleanup", e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during OpenCL context cleanup", e);
        }
    }

    private void resetHandles() {
        programHandle = 0;
        commandQueueHandle = 0;
        contextHandle = 0;
        deviceId = 0;
        initialized.set(false);
    }

    private boolean isLibraryNotLoaded(IllegalStateException e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("opencl library has not been loaded");
    }

    public void trimBufferPool(boolean aggressive) {
        bufferPool.trim(aggressive);
    }

    private void releaseCachedKernels() {
        for (Long kernel : cachedKernelHandles.keySet()) {
            try {
                CL10.clReleaseKernel(kernel.longValue());
            } catch (Throwable ignored) {
            }
        }
        cachedKernelHandles.clear();
    }

    private ByteBuffer asFloatBytes(float[] values) {
        ByteBuffer buffer = hostBuffer(values.length * Float.BYTES);
        buffer.asFloatBuffer().put(values);
        return buffer;
    }

    private ByteBuffer hostBuffer(int byteCount) {
        Map<Integer, ByteBuffer> cache = hostBufferCache.get();
        ByteBuffer buffer = cache.get(byteCount);
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
            cache.put(byteCount, buffer);
        }
        buffer.clear();
        buffer.limit(byteCount);
        return buffer;
    }

    private static float[] flattenMatrix(float[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        float[] flat = new float[rows * cols];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(matrix[row], 0, flat, row * cols, cols);
        }
        return flat;
    }

    private static float[][] expandMatrix(float[] flat, int rows, int cols) {
        float[][] matrix = new float[rows][cols];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(flat, row * cols, matrix[row], 0, cols);
        }
        return matrix;
    }

    private long createContext(GPUDetector.OpenCLDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer properties = stack.mallocPointer(3);
            properties.put(CL10.CL_CONTEXT_PLATFORM);
            properties.put(device.platformId());
            properties.put(0);
            properties.flip();

            PointerBuffer devices = stack.mallocPointer(1);
            devices.put(device.deviceId());
            devices.flip();

            IntBuffer errcode = stack.mallocInt(1);
            long context = CL10.clCreateContext(properties, devices, null, 0, errcode);
            checkCleError(errcode.get(0), "clCreateContext");
            if (context == 0) {
                throw new IllegalStateException("clCreateContext returned 0");
            }
            return context;
        }
    }

    private long createCommandQueue(long contextHandle, GPUDetector.OpenCLDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            long queue = CL10.clCreateCommandQueue(contextHandle, device.deviceId(), 0, errcode);
            int error = errcode.get(0);
            if (error != CL10.CL_SUCCESS || queue == 0) {
                errcode.put(0, 0);
                queue = CL20.clCreateCommandQueueWithProperties(contextHandle, device.deviceId(), (LongBuffer) null, errcode);
                error = errcode.get(0);
            }
            checkCleError(error, "clCreateCommandQueue");
            if (queue == 0) {
                throw new IllegalStateException("Failed to create OpenCL command queue");
            }
            return queue;
        }
    }

    private long createProgram(long contextHandle, GPUDetector.OpenCLDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            String source = "__kernel void quantified_warmup(__global const float* in, __global float* out) {\n" +
                "    size_t id = get_global_id(0);\n" +
                "    if (id == 0) { out[id] = in[id]; }\n" +
                "}\n" +
                "\n" +
                "__kernel void vector_add(__global const float* a, __global const float* b, __global float* c) {\n" +
                "    int id = get_global_id(0);\n" +
                "    c[id] = a[id] + b[id];\n" +
                "}\n" +
                "\n" +
                "__kernel void vector_sum(__global const float* input, __global float* output, const int n) {\n" +
                "    int id = get_global_id(0);\n" +
                "    if (id < n) {\n" +
                "        output[id] = input[id];\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "__kernel void monte_carlo_pi(__global float* results, const int samples) {\n" +
                "    int id = get_global_id(0);\n" +
                "    if (id < samples) {\n" +
                "        // Simple pseudo-random number generation\n" +
                "        uint seed = id * 1103515245u + 12345u;\n" +
                "        seed = (seed * 1103515245u + 12345u);\n" +
                "        float x = (float)(seed % 1000000u) / 500000.0f - 1.0f;\n" +
                "        \n" +
                "        seed = (seed * 1103515245u + 12345u);\n" +
                "        float y = (float)(seed % 1000000u) / 500000.0f - 1.0f;\n" +
                "        \n" +
                "        results[id] = (x*x + y*y <= 1.0f) ? 1.0f : 0.0f;\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "__kernel void matrix_multiply(__global const float* a, __global const float* b, __global float* c, const int m, const int n, const int p) {\n" +
                "    int row = get_global_id(0);\n" +
                "    int col = get_global_id(1);\n" +
                "    if (row < m && col < n) {\n" +
                "        float sum = 0.0f;\n" +
                "        for (int k = 0; k < p; k++) {\n" +
                "            sum += a[row * p + k] * b[k * n + col];\n" +
                "        }\n" +
                "        c[row * n + col] = sum;\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "__kernel void terrain_generation(__global const float* input_coords, __global float* output_features) {\n" +
                "    int id = get_global_id(0);\n" +
                "    uint seed = as_uint(input_coords[id]) ^ ((uint)id * 0x9e3779b9u);\n" +
                "    seed ^= seed >> 16;\n" +
                "    seed *= 0x7feb352du;\n" +
                "    seed ^= seed >> 15;\n" +
                "    seed *= 0x846ca68bu;\n" +
                "    seed ^= seed >> 16;\n" +
                "    int base = id * 4;\n" +
                "    output_features[base] = (float)(seed & 0x00ffffffu) / 16777215.0f;\n" +
                "    output_features[base + 1] = ((float)(((seed >> 24) & 0x0fu)) - 7.0f) * 0.5f;\n" +
                "    output_features[base + 2] = (float)((seed >> 20) & 0x07u);\n" +
                "    output_features[base + 3] = (float)((seed >> 16) & 0x0fu);\n" +
                "}\n" +
                "\n" +
                "__kernel void quant_histogram_int(__global const int* samples,\n" +
                "                                   __global int* partial_histograms,\n" +
                "                                   const int sampleCount,\n" +
                "                                   const int bucketCount,\n" +
                "                                   const int minValue,\n" +
                "                                   const int span) {\n" +
                "    int gid = get_global_id(0);\n" +
                "    size_t globalSize = get_global_size(0);\n" +
                "    int stride = (globalSize > 0) ? (int)globalSize : 1;\n" +
                "    if (gid >= stride) {\n" +
                "        return;\n" +
                "    }\n" +
                "    __global int* slice = partial_histograms + gid * bucketCount;\n" +
                "    for (int i = 0; i < bucketCount; i++) {\n" +
                "        slice[i] = 0;\n" +
                "    }\n" +
                "    float bucketWidth = fmax((float)span / (float)bucketCount, 1.0f);\n" +
                "    int maxValue = minValue + span - 1;\n" +
                "    if (maxValue < minValue) {\n" +
                "        maxValue = minValue;\n" +
                "    }\n" +
                "    for (int index = gid; index < sampleCount; index += stride) {\n" +
                "        int value = samples[index];\n" +
                "        if (value < minValue) value = minValue;\n" +
                "        if (value > maxValue) value = maxValue;\n" +
                "        int bucket = (int)((float)(value - minValue) / bucketWidth);\n" +
                "        if (bucket >= bucketCount) bucket = bucketCount - 1;\n" +
                "        if (bucket < 0) bucket = 0;\n" +
                "        slice[bucket] += 1;\n" +
                "    }\n" +
                "}\n" +
                "\n" +
                "__kernel void quant_fluid_diffuse(__global const float* input,\n" +
                "                                    __global float* output,\n" +
                "                                    const int width,\n" +
                "                                    const int height,\n" +
                "                                    const float alpha) {\n" +
                "    int x = get_global_id(0);\n" +
                "    int y = get_global_id(1);\n" +
                "    if (x >= width || y >= height) {\n" +
                "        return;\n" +
                "    }\n" +
                "    int rowStride = width;\n" +
                "    int idx = y * rowStride + x;\n" +
                "    int northY = y > 0 ? y - 1 : y;\n" +
                "    int southY = y < height - 1 ? y + 1 : y;\n" +
                "    int westX = x > 0 ? x - 1 : x;\n" +
                "    int eastX = x < width - 1 ? x + 1 : x;\n" +
                "    int northIdx = northY * rowStride + x;\n" +
                "    int southIdx = southY * rowStride + x;\n" +
                "    int westIdx = y * rowStride + westX;\n" +
                "    int eastIdx = y * rowStride + eastX;\n" +
                "    float center = input[idx];\n" +
                "    float north = input[northIdx];\n" +
                "    float south = input[southIdx];\n" +
                "    float west = input[westIdx];\n" +
                "    float east = input[eastIdx];\n" +
                "    float laplacian = (north + south + west + east) - (4.0f * center);\n" +
                "    output[idx] = center + alpha * laplacian;\n" +
                "}";

            IntBuffer errcode = stack.mallocInt(1);
            long program = CL10.clCreateProgramWithSource(contextHandle, source, errcode);
            checkCleError(errcode.get(0), "clCreateProgramWithSource");
            PointerBuffer deviceBuffer = stack.pointers(device.deviceId());
            int buildError = CL10.clBuildProgram(program, deviceBuffer, "", null, 0L);
            if (buildError != CL10.CL_SUCCESS) {
                LOGGER.log(Level.WARNING, () -> "OpenCL program failed to build: " + describeBuildFailure(program, device.deviceId(), buildError));
                CL10.clReleaseProgram(program);
                return 0;
            }
            return program;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Skipping OpenCL program creation", e);
            return 0;
        }
    }

    private void releaseProgram(long programHandle) {
        CL10.clReleaseProgram(programHandle);
    }

    private void releaseCommandQueue(long commandQueueHandle) {
        CL10.clReleaseCommandQueue(commandQueueHandle);
    }

    private void releaseContext(long contextHandle) {
        CL10.clReleaseContext(contextHandle);
    }

    private void checkCleError(int errcode, String operation) {
        if (errcode != CL10.CL_SUCCESS) {
            throw new IllegalStateException(operation + " failed with error code " + errcode);
        }
    }

    private final class BufferPool {
        private final java.util.Map<BufferKey, java.util.ArrayDeque<PooledBuffer>> buckets = new java.util.HashMap<>();
        private final java.util.Map<Long, PooledBuffer> byHandle = new java.util.HashMap<>();
        private long totalBytes = 0L;
        private final long maxBytes = Math.max(64L * 1024L * 1024L,
            Long.getLong("quantified.opencl.buffer_pool.max_bytes", 256L * 1024L * 1024L));
        private final long maxIdleNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(
            Math.max(5L, Long.getLong("quantified.opencl.buffer_pool.max_idle_seconds", 45L)));
        private final Object mutex = new Object();

        boolean isEnabled() {
            return maxBytes > 0L;
        }

        boolean isPoolable(long flags) {
            return (flags & (CL10.CL_MEM_COPY_HOST_PTR | CL10.CL_MEM_USE_HOST_PTR)) == 0;
        }

        long acquire(long flags, long size) {
            if (size <= 0L) {
                return 0L;
            }
            synchronized (mutex) {
                BufferKey key = new BufferKey(flags, size);
                java.util.ArrayDeque<PooledBuffer> queue = buckets.get(key);
                if (queue != null) {
                    while (!queue.isEmpty()) {
                        PooledBuffer buffer = queue.pollFirst();
                        if (buffer == null || buffer.released) {
                            continue;
                        }
                        if (buffer.inUse) {
                            continue;
                        }
                        buffer.inUse = true;
                        buffer.lastUsed = System.nanoTime();
                        return buffer.handle;
                    }
                }
            }
            return 0L;
        }

        void register(long handle, long flags, long size) {
            if (!isEnabled() || !isPoolable(flags) || handle == 0L || size <= 0L) {
                return;
            }
            synchronized (mutex) {
                if (byHandle.containsKey(handle)) {
                    return;
                }
                if (totalBytes + size > maxBytes) {
                    return;
                }
                PooledBuffer buffer = new PooledBuffer(handle, flags, size);
                buffer.inUse = true;
                byHandle.put(handle, buffer);
                totalBytes += size;
            }
        }

        boolean release(long handle) {
            if (!isEnabled() || handle == 0L) {
                return false;
            }
            synchronized (mutex) {
                PooledBuffer buffer = byHandle.get(handle);
                if (buffer == null || buffer.released) {
                    return false;
                }
                buffer.inUse = false;
                buffer.lastUsed = System.nanoTime();
                buckets.computeIfAbsent(buffer.key, k -> new java.util.ArrayDeque<>()).addLast(buffer);
                trim(false);
                return true;
            }
        }

        void trim(boolean aggressive) {
            synchronized (mutex) {
                long now = System.nanoTime();
                java.util.Iterator<java.util.Map.Entry<Long, PooledBuffer>> it = byHandle.entrySet().iterator();
                while (it.hasNext()) {
                    PooledBuffer buffer = it.next().getValue();
                    if (buffer == null || buffer.released) {
                        it.remove();
                        continue;
                    }
                    if (buffer.inUse) {
                        continue;
                    }
                    boolean idle = (now - buffer.lastUsed) >= maxIdleNanos;
                    boolean overBudget = totalBytes > maxBytes;
                    if (aggressive || idle || overBudget) {
                        releaseBufferHandle(buffer);
                        it.remove();
                    }
                }
            }
        }

        void clear() {
            synchronized (mutex) {
                for (PooledBuffer buffer : byHandle.values()) {
                    if (buffer != null && !buffer.released) {
                        releaseBufferHandle(buffer);
                    }
                }
                byHandle.clear();
                buckets.clear();
                totalBytes = 0L;
            }
        }

        private void releaseBufferHandle(PooledBuffer buffer) {
            try {
                CL10.clReleaseMemObject(buffer.handle);
            } catch (Throwable ignored) {
            }
            buffer.released = true;
            totalBytes = Math.max(0L, totalBytes - buffer.size);
            java.util.ArrayDeque<PooledBuffer> queue = buckets.get(buffer.key);
            if (queue != null) {
                queue.remove(buffer);
            }
        }

        private record BufferKey(long flags, long size) {
        }

        private final class PooledBuffer {
            final long handle;
            final BufferKey key;
            final long size;
            boolean inUse;
            boolean released;
            long lastUsed;

            PooledBuffer(long handle, long flags, long size) {
                this.handle = handle;
                this.key = new BufferKey(flags, size);
                this.size = size;
                this.lastUsed = System.nanoTime();
            }
        }
    }

    private String describeBuildFailure(long program, long deviceId, int buildError) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer lengthBuffer = stack.mallocPointer(1);
            CL10.clGetProgramBuildInfo(program, deviceId, CL10.CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, lengthBuffer);
            int length = (int) lengthBuffer.get(0);
            if (length <= 1) {
                return "error=" + buildError + " (no log)";
            }
            ByteBuffer logBuffer = stack.malloc(length);
            CL10.clGetProgramBuildInfo(program, deviceId, CL10.CL_PROGRAM_BUILD_LOG, logBuffer, null);
            byte[] bytes = new byte[length - 1];
            logBuffer.get(bytes);
            return "error=" + buildError + ", log=" + new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "error=" + buildError + " (log unavailable: " + e.getMessage() + ")";
        }
    }
}
