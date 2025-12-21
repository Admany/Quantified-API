package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class HistogramTask extends OpenCLTask<int[]> {

    private static final int MAX_GPU_WORKERS = 2048;

    private final int[] samples;
    private final int bucketCount;
    private final int minValue;
    private final int maxValue;

    private HistogramTask(Builder builder) {
        super(builder);
        this.samples = Arrays.copyOf(builder.samples, builder.samples.length);
        this.bucketCount = builder.bucketCount;
        this.minValue = builder.minValue;
        this.maxValue = builder.maxValue;
    }

    @Override
    public long estimatedVramBytes() {
        long sampleBytes = (long) samples.length * Integer.BYTES;
        int workerEstimate = Math.max(1, Math.min(MAX_GPU_WORKERS, computeGlobalSize(samples.length)));
        long histogramBytes = (long) bucketCount * workerEstimate * Integer.BYTES;
        return sampleBytes + histogramBytes;
    }

    @Override
    public int estimatedComputeUnits() {
        return Math.max(1, samples.length / 1024);
    }

    @Override
    public int[] executeOnGPU(OpenCLContext context) {
        if (samples.length == 0 || bucketCount <= 0) {
            return new int[Math.max(bucketCount, 0)];
        }

        int globalSize = Math.min(MAX_GPU_WORKERS, computeGlobalSize(samples.length));
        long histogramBytes = (long) bucketCount * globalSize * Integer.BYTES;
        if (histogramBytes <= 0 || histogramBytes > Integer.MAX_VALUE) {
            return cpuFallback().get();
        }
        long sampleBytes = (long) samples.length * Integer.BYTES;
        if (sampleBytes <= 0 || sampleBytes > Integer.MAX_VALUE) {
            return cpuFallback().get();
        }

        long sampleBuffer = 0L;
        long histogramBuffer = 0L;
        long kernel = 0L;
        try {
            sampleBuffer = context.createBuffer(CL10.CL_MEM_READ_ONLY, sampleBytes);
            histogramBuffer = context.createBuffer(CL10.CL_MEM_READ_WRITE, histogramBytes);
            kernel = context.createKernel("quant_histogram_int");

            ByteBuffer sampleData = ByteBuffer.allocateDirect((int) sampleBytes).order(ByteOrder.nativeOrder());
            for (int value : samples) {
                sampleData.putInt(value);
            }
            sampleData.flip();
            context.enqueueWriteBuffer(sampleBuffer, true, 0, sampleBytes, sampleData);

            ByteBuffer zeroInit = ByteBuffer.allocateDirect((int) histogramBytes).order(ByteOrder.nativeOrder());
            zeroInit.limit((int) histogramBytes);
            context.enqueueWriteBuffer(histogramBuffer, true, 0, histogramBytes, zeroInit);

            context.setKernelArgBuffer(kernel, 0, sampleBuffer);
            context.setKernelArgBuffer(kernel, 1, histogramBuffer);
            context.setKernelArgInt(kernel, 2, samples.length);
            context.setKernelArgInt(kernel, 3, bucketCount);
            context.setKernelArgInt(kernel, 4, minValue);
            context.setKernelArgInt(kernel, 5, safeSpan());

            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer globalWork = stack.pointers(globalSize);
                context.enqueueNDRangeKernel(kernel, 1, globalWork);
                context.finish();
            }

            ByteBuffer resultBytes = ByteBuffer.allocateDirect((int) histogramBytes).order(ByteOrder.nativeOrder());
            context.enqueueReadBuffer(histogramBuffer, true, 0, histogramBytes, resultBytes);
            resultBytes.rewind();

            int[] histogram = new int[bucketCount];
            for (int worker = 0; worker < globalSize; worker++) {
                for (int bucket = 0; bucket < bucketCount; bucket++) {
                    histogram[bucket] += resultBytes.getInt();
                }
            }
            return histogram;
        } catch (Exception ex) {
            return cpuFallback().get();
        } finally {
            if (kernel != 0L) {
                context.releaseKernel(kernel);
            }
            if (sampleBuffer != 0L) {
                context.releaseBuffer(sampleBuffer);
            }
            if (histogramBuffer != 0L) {
                context.releaseBuffer(histogramBuffer);
            }
        }
    }

    private int safeSpan() {
        long span = (long) maxValue - (long) minValue + 1L;
        if (span < 1L) {
            span = 1L;
        }
        if (span > Integer.MAX_VALUE) {
            span = Integer.MAX_VALUE;
        }
        return (int) span;
    }

    private static int computeGlobalSize(int sampleCount) {
        if (sampleCount <= 0) {
            return 64;
        }
        int rounded = ((sampleCount + 63) / 64) * 64;
        return Math.min(Math.max(64, rounded), 65536);
    }

    private static int[] computeHistogram(int[] source, int bucketCount, int minValue, int maxValue) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        int[] histogram = new int[bucketCount];
        if (source.length == 0) {
            return histogram;
        }
        int span = Math.max(1, maxValue - minValue + 1);
        double bucketWidth = span / (double) bucketCount;
        for (int value : source) {
            int clamped = Math.min(Math.max(value, minValue), maxValue);
            int bucket = (int) ((clamped - minValue) / bucketWidth);
            if (bucket >= bucketCount) {
                bucket = bucketCount - 1;
            }
            histogram[bucket]++;
        }
        return histogram;
    }

    public static final class Builder extends OpenCLTask.Builder<int[]> {
        private final int[] samples;
        private int bucketCount = 32;
        private int minValue = 0;
        private int maxValue = Integer.MAX_VALUE;

        public Builder(String modId, String name, long taskKey, int[] samples) {
            super(modId, name, taskKey, () -> computeHistogram(samples.clone(), 32, 0, Integer.MAX_VALUE));
            this.samples = samples.clone();
        }

        public Builder bucketCount(int bucketCount) {
            if (bucketCount > 0) {
                this.bucketCount = bucketCount;
            }
            return this;
        }

        public Builder range(int minValue, int maxValue) {
            if (maxValue < minValue) {
                maxValue = minValue;
            }
            this.minValue = minValue;
            this.maxValue = maxValue;
            return this;
        }

        @Override
        public HistogramTask build() {
            this.cpuFallback = () -> computeHistogram(samples, bucketCount, minValue, maxValue);
            return new HistogramTask(this);
        }
    }
}
