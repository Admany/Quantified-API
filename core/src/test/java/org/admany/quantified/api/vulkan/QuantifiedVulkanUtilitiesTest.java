package org.admany.quantified.api.vulkan;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QuantifiedVulkanUtilitiesTest {

    private static final String TEST_MOD_ID = "test-mod";
    private static final long TEST_TASK_KEY = 54321L;

    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    static void setUpAll() {
        testExecutor = Executors.newScheduledThreadPool(2);
        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors());
        AsyncManager.initialise(bootstrap, testExecutor);
    }

    @AfterAll
    static void tearDownAll() {
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testParallelVectorAdd() throws Exception {
        float[] a = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
        float[] b = {0.5f, 1.5f, 2.5f, 3.5f, 4.5f};

        CompletableFuture<float[]> future = QuantifiedVulkan.parallelVectorAdd(
            TEST_MOD_ID, "vector-add-test", TEST_TASK_KEY, a, b);

        float[] result = future.get();
        assertNotNull(result);
        assertEquals(a.length, result.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i] + b[i], result[i], 0.001f);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testParallelMatrixMultiply() throws Exception {
        float[][] a = {
            {1.0f, 2.0f, 3.0f},
            {4.0f, 5.0f, 6.0f}
        };
        float[][] b = {
            {7.0f, 8.0f},
            {9.0f, 10.0f},
            {11.0f, 12.0f}
        };

        CompletableFuture<float[][]> future = QuantifiedVulkan.parallelMatrixMultiply(
            TEST_MOD_ID, "matrix-multiply-test", TEST_TASK_KEY, a, b);

        float[][] result = future.get();
        assertNotNull(result);
        assertEquals(2, result.length);
        assertEquals(2, result[0].length);
        assertEquals(58.0f, result[0][0], 0.001f);
        assertEquals(64.0f, result[0][1], 0.001f);
        assertEquals(139.0f, result[1][0], 0.001f);
        assertEquals(154.0f, result[1][1], 0.001f);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testParallelMonteCarloPi() throws Exception {
        int samples = 10_000;
        CompletableFuture<Double> future = QuantifiedVulkan.parallelMonteCarloPi(
            TEST_MOD_ID, "monte-carlo-pi-test", TEST_TASK_KEY, samples);

        Double estimate = future.get();
        assertNotNull(estimate);
        assertTrue(estimate > 2.5d && estimate < 4.5d);
        assertTrue(Math.abs(estimate - Math.PI) < 0.5d);
    }

    @Test
    void testVectorAddValidation() {
        float[] a = {1.0f, 2.0f};
        float[] b = {1.0f};
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            QuantifiedVulkan.parallelVectorAdd(TEST_MOD_ID, "test", TEST_TASK_KEY, a, b));
        assertTrue(exception.getMessage().contains("Vector lengths must match"));
    }

    @Test
    void testMatrixMultiplyValidation() {
        float[][] a = {{1.0f, 2.0f}};
        float[][] b = {{1.0f}};
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            QuantifiedVulkan.parallelMatrixMultiply(TEST_MOD_ID, "test", TEST_TASK_KEY, a, b));
        assertTrue(exception.getMessage().contains("Invalid matrix dimensions"));
    }

    @Test
    void testMonteCarloPiValidation() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            QuantifiedVulkan.parallelMonteCarloPi(TEST_MOD_ID, "test", TEST_TASK_KEY, 0));
        assertTrue(exception.getMessage().contains("Samples must be positive"));
    }
}
