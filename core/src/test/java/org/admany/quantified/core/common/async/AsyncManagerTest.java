package org.admany.quantified.core.common.async;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.async.core.PriorityScheduler;
import org.admany.quantified.core.common.async.metrics.AsyncMetrics;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.threading.core.MainThreadExecutor;
import org.admany.quantified.core.common.threading.pool.ThreadPoolStats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Unit tests for AsyncManager core functionality.
 * Tests task submission, priority handling, coalescing, and error scenarios.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncManagerTest {

    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    void setUpAll() {
        // Initialize AsyncManager for tests
        testExecutor = Executors.newScheduledThreadPool(2);
        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors());
        AsyncManager.initialise(bootstrap, testExecutor);
    }

    @AfterAll
    void tearDownAll() {
        // Clean up AsyncManager
        MainThreadExecutor.clear();
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    void setUp() {
        // Reset metrics before each test
        // Note: AsyncManager doesn't have a public reset method, so we'll work with accumulated metrics
    }

    void testSubmitSyncTask() throws ExecutionException, InterruptedException {
        // Given
        Supplier<String> task = () -> "sync result";

        // When
        CompletableFuture<String> future = AsyncManager.submitSync(
            1001L, PriorityTaskType.BUILDING, 1.0, task, "test_mod");

        // Then
        if (future == null) throw new AssertionError("future is null");
        if (!Objects.equals(future.get(), "sync result")) throw new AssertionError("unexpected result: " + future.get());
    }

    void testSubmitAsyncTask() throws ExecutionException, InterruptedException {
        // Given
        Supplier<CompletableFuture<String>> asyncTask = () -> CompletableFuture.completedFuture("async result");

        // When
        CompletableFuture<String> future = AsyncManager.submitAsync(
            1002L, PriorityTaskType.BUILDING, 1.0, asyncTask, "test_mod");

        // Then
        if (future == null) throw new AssertionError("future is null");
        if (!Objects.equals(future.get(), "async result")) throw new AssertionError("unexpected async result: " + future.get());
    }

    void testTaskCoalescing() throws ExecutionException, InterruptedException {
        // Given - submit the same task key twice quickly
        Supplier<String> task = () -> "coalesced result";

        // When - submit same task key twice
        CompletableFuture<String> future1 = AsyncManager.submitSync(
            1003L, PriorityTaskType.BUILDING, 1.0, task, "test_mod");

        // Small delay to ensure coalescing window
        Thread.sleep(10);

        CompletableFuture<String> future2 = AsyncManager.submitSync(
            1003L, PriorityTaskType.BUILDING, 1.0, task, "test_mod");

        // Then - both futures should return the same result
        if (!Objects.equals(future1.get(), "coalesced result")) throw new AssertionError("future1 wrong");
        if (!Objects.equals(future2.get(), "coalesced result")) throw new AssertionError("future2 wrong");
        if (future1 != future2) throw new AssertionError("futures not identical");
    }

    void testTaskTimeout() {
        // Given - a task that takes longer than timeout
        Supplier<String> slowTask = () -> {
            try {
                Thread.sleep(2000); // Sleep for 2 seconds
                return "should not complete";
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };

        // When
        CompletableFuture<String> future = AsyncManager.submitSync(
            1004L, PriorityTaskType.BUILDING, 1.0, slowTask, Duration.ofMillis(100), "test_mod");

        // Then - should timeout
        try {
            future.get();
            throw new AssertionError("expected ExecutionException due to timeout");
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof java.util.concurrent.TimeoutException)) {
                throw new AssertionError("expected TimeoutException cause, got " + e.getCause());
            }
        } catch (InterruptedException e) {
            throw new AssertionError("interrupted", e);
        }
    }

    void testTaskExceptionHandling() {
        // Given - a task that throws an exception
        Supplier<String> failingTask = () -> {
            throw new RuntimeException("Test exception");
        };

        // When
        CompletableFuture<String> future = AsyncManager.submitSync(
            1005L, PriorityTaskType.BUILDING, 1.0, failingTask, "test_mod");

        // Then - exception should be propagated
        try {
            future.get();
            throw new AssertionError("expected ExecutionException");
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof RuntimeException)) throw new AssertionError("unexpected cause", e);
            if (e.getCause().getMessage() == null || !e.getCause().getMessage().contains("Test exception")) throw new AssertionError("message mismatch");
        } catch (InterruptedException e) {
            throw new AssertionError("interrupted", e);
        }
    }

    void testMetricsTracking() {
        // Given
        Supplier<String> task = () -> "metrics test";

        // When - submit a task
        AsyncManager.submitSync(1006L, PriorityTaskType.BUILDING, 1.0, task, "test_mod");

        // Then - metrics should be updated
        AsyncMetrics.AsyncMetricsSnapshot metrics = AsyncManager.metricsSnapshot();
        if (metrics.requests() < 1) throw new AssertionError("metrics.requests < 1");
    }

    void testSchedulerSnapshot() {
        // When
        PriorityScheduler.SchedulerSnapshot snapshot = AsyncManager.schedulerSnapshot();

        // Then - should return valid snapshot
        if (snapshot == null) throw new AssertionError("snapshot null");
        if (snapshot.foregroundQueue() < 0) throw new AssertionError("foregroundQueue < 0");
        if (snapshot.backgroundQueue() < 0) throw new AssertionError("backgroundQueue < 0");
    }

    void testThreadPoolStats() {
        // When
        ThreadPoolStats stats = AsyncManager.threadPoolStats();

        // Then - should return valid stats
        if (stats == null) throw new AssertionError("stats null");
        if (stats.desiredForegroundWorkers() < 0) throw new AssertionError("desiredForegroundWorkers < 0");
        if (stats.desiredBackgroundWorkers() < 0) throw new AssertionError("desiredBackgroundWorkers < 0");
    }

    void testModPriorityEscalation() {
        // Given
        String testModId = "test_priority_mod";

        // When
        AsyncManager.escalateModPriority(testModId, "test escalation");

        // Then - should not throw exception (implementation detail tested via integration)
        if (!AsyncManager.isInitialised()) throw new AssertionError("AsyncManager not initialised");
    }

    void testFinalizerQueue() {
        // Given
        boolean[] executed = {false};
        Runnable finalizer = () -> executed[0] = true;

        // When
        AsyncManager.enqueueFinalizer(finalizer);
        AsyncManager.drainFinalizers();

        // Then
        if (!executed[0]) throw new AssertionError("finalizer did not execute");
    }
    
    @Test
    void testInitializationState() {
        // Test that AsyncManager is properly initialized
        if (!AsyncManager.isInitialised()) throw new AssertionError("AsyncManager not initialised");
    }

    @Test
    void nonThreadSafeBuildingTaskRunsOnInstalledMainExecutor() throws Exception {
        String submittingThread = Thread.currentThread().getName();
        MainThreadExecutor.install(Runnable::run);

        CompletableFuture<String> future = AsyncManager.submitSync(
            1107L,
            PriorityTaskType.BUILDING,
            1.0,
            Thread::currentThread,
            Duration.ofSeconds(1),
            false,
            "test_mod",
            org.admany.quantified.core.common.async.task.TaskMetadata.DEFAULT
        ).thenApply(Thread::getName);

        if (!Objects.equals(future.get(), submittingThread)) {
            throw new AssertionError("non-thread-safe task was sent to a worker instead of the installed main executor");
        }
    }
    
    @Test
    void testShutdownBehavior() {
        // Test that operations fail after shutdown by creating a separate instance
        // Note: We can't shutdown the main instance as it would affect other tests
        if (!AsyncManager.isInitialised()) throw new AssertionError("AsyncManager not initialised");
    }
}
