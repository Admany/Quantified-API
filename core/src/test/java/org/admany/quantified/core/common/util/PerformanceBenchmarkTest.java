
package org.admany.quantified.core.common.util;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Performance benchmarks for TaskScheduler and AsyncManager.
 * Tests throughput, latency, and resource utilization under load.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceBenchmarkTest {

    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    void setUpAll() {
        // Initialize AsyncManager for benchmarks
        testExecutor = Executors.newScheduledThreadPool(4);
        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors());
        AsyncManager.initialise(bootstrap, testExecutor);
    }

    @AfterAll
    void tearDownAll() {
        // Clean up
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void testTaskSchedulerThroughput() throws ExecutionException, InterruptedException {
        // Given - create multiple tasks
        int numTasks = 100;
        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] futures = new CompletableFuture[numTasks];

        long startTime = System.nanoTime();

        // When - submit tasks concurrently
        for (int i = 0; i < numTasks; i++) {
            final int taskId = i;
            futures[i] = TaskScheduler.submitCpuTask(
                "benchmark_mod",
                "throughput_test_" + taskId,
                2000L + taskId,
                () -> "Task " + taskId + " completed",
                null
            );
        }

        // Wait for all tasks to complete
        CompletableFuture.allOf(futures).get();

        long endTime = System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

        // Then - verify performance
        if (!(durationMs < 5000)) throw new AssertionError("Expected durationMs < 5000, was " + durationMs);

        // Verify all tasks completed successfully
        for (CompletableFuture<String> future : futures) {
            if (!future.isDone()) throw new AssertionError("Expected future to be done");
            try {
                String res = future.get();
                if (!res.startsWith("Task ")) throw new AssertionError("Unexpected task result: " + res);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
        if (stats.totalTasks() != numTasks) throw new AssertionError("Expected totalTasks == " + numTasks + ", was " + stats.totalTasks());
        if (stats.cpuTasks() != numTasks) throw new AssertionError("Expected cpuTasks == " + numTasks + ", was " + stats.cpuTasks());
    }

    @Test
    void testAsyncManagerThroughput() throws ExecutionException, InterruptedException {
        // Given
        int numTasks = 50;
                @SuppressWarnings("unchecked")
        CompletableFuture<String>[] futures = new CompletableFuture[numTasks];

        long startTime = System.nanoTime();

        // When - submit async tasks
        for (int i = 0; i < numTasks; i++) {
            final int taskId = i;
            futures[i] = AsyncManager.submitSync(
                3000L + taskId,
                org.admany.quantified.core.common.async.task.PriorityTaskType.BUILDING,
                1.0,
                () -> "Async Task " + taskId + " result",
                "benchmark_mod"
            );
        }

        // Wait for completion
        CompletableFuture.allOf(futures).get();

        long endTime = System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

        // Then - verify performance
        if (!(durationMs < 3000)) throw new AssertionError("Expected durationMs < 3000, was " + durationMs);

        for (CompletableFuture<String> future : futures) {
            try {
                String res = future.get();
                if (!res.startsWith("Async Task ")) throw new AssertionError("Unexpected async result: " + res);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void testTaskSchedulerLatency() throws ExecutionException, InterruptedException {
        // Test individual task latency
        long startTime = System.nanoTime();

        CompletableFuture<String> future = TaskScheduler.submitCpuTask(
            "latency_test_mod",
            "latency_test",
            4000L,
            () -> {
                // Simulate some work
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "latency result";
            },
            null
        );

        String result = future.get();
        long endTime = System.nanoTime();
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Then - verify reasonable latency (should be > 10ms due to sleep, but not excessive)
    if (!"latency result".equals(result)) throw new AssertionError("Unexpected latency result: " + result);
    if (latencyMs < 10) throw new AssertionError("Latency too small: " + latencyMs);
    if (latencyMs >= 500) throw new AssertionError("Latency too large: " + latencyMs);
    }

    @Test
    void testConcurrentTaskScheduling() throws ExecutionException, InterruptedException {
        // Test concurrent scheduling from multiple threads
        int numThreads = 5;
        int tasksPerThread = 20;
                @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] threadFutures = new CompletableFuture[numThreads];

        long startTime = System.nanoTime();

        // When - launch multiple threads submitting tasks
        for (int threadId = 0; threadId < numThreads; threadId++) {
            final int tId = threadId;
            threadFutures[threadId] = CompletableFuture.runAsync(() -> {
                for (int taskId = 0; taskId < tasksPerThread; taskId++) {
                    try {
                        final int localTaskId = taskId;
                        TaskScheduler.submitCpuTask(
                            "concurrent_mod",
                            "concurrent_task_" + tId + "_" + localTaskId,
                            5000L + tId * tasksPerThread + localTaskId,
                            () -> "Thread " + tId + " Task " + localTaskId,
                            null
                        ).get(); // Wait for each task
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        // Wait for all threads to complete
        CompletableFuture.allOf(threadFutures).get();

        long endTime = System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Then - verify concurrent execution worked
    if (!(durationMs < 10000)) throw new AssertionError("Expected concurrent duration < 10000, was " + durationMs);

    TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
    if (stats.totalTasks() != numThreads * tasksPerThread) throw new AssertionError("Expected totalTasks == " + (numThreads * tasksPerThread) + ", was " + stats.totalTasks());
    }

    @Test
    void testMemoryEfficiency() {
        // Test that the system doesn't leak memory under repeated task submission
        Runtime runtime = Runtime.getRuntime();

        // Force garbage collection to get baseline
        runtime.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // Submit many tasks
        for (int i = 0; i < 1000; i++) {
            final int localIndex = i;
            TaskScheduler.submitCpuTask(
                "memory_test_mod",
                "memory_task_" + localIndex,
                6000L + localIndex,
                () -> "memory test " + localIndex,
                null
            );
        }

        // Force cleanup
        TaskScheduler.resetStats();
        runtime.gc();

        long finalMemory = runtime.totalMemory() - runtime.freeMemory();

    // Memory usage should not have grown excessively
    // Allow some tolerance for JVM overhead
    long memoryGrowth = finalMemory - initialMemory;
    if (memoryGrowth >= 50L * 1024L * 1024L) throw new AssertionError("Memory growth too large: " + memoryGrowth);
    }

    @Test
    void testSystemStabilityUnderLoad() throws ExecutionException, InterruptedException {
        // Test system stability with mixed task types and priorities
        int numHighPriority = 20;
        int numLowPriority = 30;

        // Submit high priority tasks
        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] highPriorityFutures = new CompletableFuture[numHighPriority];
        for (int i = 0; i < numHighPriority; i++) {
            final int hi = i;
            highPriorityFutures[i] = TaskScheduler.submitCpuTask(
                "stability_mod",
                "high_priority_" + hi,
                7000L + hi,
                () -> "High priority task " + hi,
                null
            );
        }

        // Submit low priority tasks
                @SuppressWarnings("unchecked")
        CompletableFuture<String>[] lowPriorityFutures = new CompletableFuture[numLowPriority];
        for (int i = 0; i < numLowPriority; i++) {
            final int lo = i;
            lowPriorityFutures[i] = AsyncManager.submitSync(
                8000L + lo,
                org.admany.quantified.core.common.async.task.PriorityTaskType.CACHE,
                0.1, // Low priority
                () -> "Low priority task " + lo,
                "stability_mod"
            );
        }

        // Wait for all tasks to complete
        CompletableFuture<Void> allHigh = CompletableFuture.allOf(highPriorityFutures);
        CompletableFuture<Void> allLow = CompletableFuture.allOf(lowPriorityFutures);
        CompletableFuture.allOf(allHigh, allLow).get();

        // Verify all tasks completed
        for (CompletableFuture<String> future : highPriorityFutures) {
            try {
                String res = future.get();
                if (!res.startsWith("High priority task")) throw new AssertionError("Unexpected high priority result: " + res);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        for (CompletableFuture<String> future : lowPriorityFutures) {
            try {
                String res = future.get();
                if (!res.startsWith("Low priority task")) throw new AssertionError("Unexpected low priority result: " + res);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // System should still be stable
        if (!AsyncManager.isInitialised()) throw new AssertionError("AsyncManager not initialised");
        if (TaskScheduler.getStats().totalTasks() != numHighPriority) throw new AssertionError("Expected totalTasks == " + numHighPriority + ", was " + TaskScheduler.getStats().totalTasks());
    }

    @BeforeEach
    void beforeEach() {
        // Reset TaskScheduler stats so tests have deterministic expectations
        try {
            TaskScheduler.resetStats();
        } catch (Throwable ignored) {}
    }
}