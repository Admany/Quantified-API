package org.admany.quantified.api.opencl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.async.gpu.GpuWorkloadRegistry;

import static org.junit.jupiter.api.Assertions.*;

public class QuantifiedOpenCLUtilitiesTest {

    private static final String TEST_MOD_ID = "test-mod";
    private static final long TEST_TASK_KEY = 0x2_0000L;

    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    static void setUpAll() {
        GpuWorkloadRegistry.clear();
        testExecutor = Executors.newScheduledThreadPool(2);
        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors());
        AsyncManager.initialise(bootstrap, testExecutor);
    }

    @AfterAll
    static void tearDownAll() {
        GpuWorkloadRegistry.clear();
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testParallelVectorAdd() throws Exception {
        float[] a = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
        float[] b = {0.5f, 1.5f, 2.5f, 3.5f, 4.5f};

        CompletableFuture<float[]> future = QuantifiedOpenCL.parallelVectorAdd(
            TEST_MOD_ID, "vector-add-test", TEST_TASK_KEY + 1L, a, b);

        float[] result = future.get();

        assertNotNull(result);
        assertEquals(a.length, result.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i] + b[i], result[i], 0.001f,
                "Result[" + i + "] should be " + (a[i] + b[i]));
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testParallelMatrixMultiply() throws Exception {
        // 2x3 matrix
        float[][] a = {
            {1.0f, 2.0f, 3.0f},
            {4.0f, 5.0f, 6.0f}
        };
        // 3x2 matrix
        float[][] b = {
            {7.0f, 8.0f},
            {9.0f, 10.0f},
            {11.0f, 12.0f}
        };

        CompletableFuture<float[][]> future = QuantifiedOpenCL.parallelMatrixMultiply(
            TEST_MOD_ID, "matrix-multiply-test", TEST_TASK_KEY, a, b);

        float[][] result = future.get();

        assertNotNull(result);
        assertEquals(2, result.length); // 2 rows
        assertEquals(2, result[0].length); // 2 columns

        // Expected result: 2x2 matrix
        // [1,2,3]   [7,8]   [1*7+2*9+3*11, 1*8+2*10+3*12] = [58, 64]
        // [4,5,6] * [9,10] = [4*7+5*9+6*11, 4*8+5*10+6*12] = [139, 154]
        //         [11,12]

        assertEquals(58.0f, result[0][0], 0.001f);
        assertEquals(64.0f, result[0][1], 0.001f);
        assertEquals(139.0f, result[1][0], 0.001f);
        assertEquals(154.0f, result[1][1], 0.001f);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testParallelMonteCarloPi() throws Exception {
        int samples = 10000;

        CompletableFuture<Double> future = QuantifiedOpenCL.parallelMonteCarloPi(
            TEST_MOD_ID, "monte-carlo-pi-test", TEST_TASK_KEY + 2L, samples);

        Double piEstimate = future.get();

        assertNotNull(piEstimate);
        assertTrue(piEstimate > 2.5 && piEstimate < 4.5,
            "π estimate should be roughly between 2.5 and 4.5, got: " + piEstimate);

        // With enough samples, should be close to actual π
        if (samples >= 10000) {
            assertTrue(Math.abs(piEstimate - Math.PI) < 0.5,
                "With " + samples + " samples, π estimate should be within 0.5 of actual π. Got: " + piEstimate);
        }
    }

    @Test
    public void testVectorAddValidation() {
        float[] a = {1.0f, 2.0f};
        float[] b = {1.0f}; // Different length

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            QuantifiedOpenCL.parallelVectorAdd(TEST_MOD_ID, "test", TEST_TASK_KEY, a, b));

        assertTrue(exception.getMessage().contains("Vector lengths must match"));
    }

    @Test
    public void testMatrixMultiplyValidation() {
        float[][] a = {{1.0f, 2.0f}}; // 1x2
        float[][] b = {{1.0f}}; // 1x1 - incompatible dimensions

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            QuantifiedOpenCL.parallelMatrixMultiply(TEST_MOD_ID, "test", TEST_TASK_KEY, a, b));

        assertTrue(exception.getMessage().contains("Invalid matrix dimensions"));
    }

    @Test
    public void testMonteCarloPiValidation() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            QuantifiedOpenCL.parallelMonteCarloPi(TEST_MOD_ID, "test", TEST_TASK_KEY, 0));

        assertTrue(exception.getMessage().contains("Samples must be positive"));
    }

    @Test
    public void testNullInputs() {
        assertThrows(NullPointerException.class, () ->
            QuantifiedOpenCL.parallelVectorAdd(TEST_MOD_ID, "test", TEST_TASK_KEY, null, new float[1]));

        assertThrows(NullPointerException.class, () ->
            QuantifiedOpenCL.parallelVectorAdd(TEST_MOD_ID, "test", TEST_TASK_KEY, new float[1], null));

        assertThrows(NullPointerException.class, () ->
            QuantifiedOpenCL.parallelMatrixMultiply(TEST_MOD_ID, "test", TEST_TASK_KEY, null, new float[1][1]));

        assertThrows(NullPointerException.class, () ->
            QuantifiedOpenCL.parallelMatrixMultiply(TEST_MOD_ID, "test", TEST_TASK_KEY, new float[1][1], null));
    }
}
