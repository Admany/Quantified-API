package org.admany.quantified.core.common.util;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.async.gpu.GpuWorkloadRegistry;
import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
class TaskSchedulerTest {

    private static final String TEST_MOD_ID = "test_mod";
    private static final String TEST_TASK_NAME = "test_task";
    private static final long TEST_TASK_KEY = 12345L;

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

    @BeforeEach
    void setUp() {
        if (MultithreadingConfig.CONFIG == null) {
            MultithreadingConfig.CONFIG = new MultithreadingConfig.Config();
        }
        MultithreadingConfig.CONFIG.enableGpuAcceleration = true;
        MultithreadingConfig.CONFIG.openclForced = false;
        MultithreadingConfig.CONFIG.preferredGpuBackend = "VULKAN_PREFERRED";
        GpuBackendRouter.resetForTesting();
        TaskScheduler.resetStats();
        TaskScheduler.setGpuWorkloadForTesting(new TestGpuBatchWorkload());
    }

    @AfterEach
    void tearDown() {
        if (MultithreadingConfig.CONFIG != null) {
            MultithreadingConfig.CONFIG.openclForced = false;
            MultithreadingConfig.CONFIG.preferredGpuBackend = "VULKAN_PREFERRED";
        }
        GpuBackendRouter.resetForTesting();
        TaskScheduler.resetStats();
        TaskScheduler.setGpuWorkloadForTesting(null);
    }

    @Test
    void testSubmitCpuOnlyTask() throws ExecutionException, InterruptedException {
        // Given
        Supplier<String> cpuTask = () -> "CPU result";

        // When
        CompletableFuture<String> future = TaskScheduler.submitCpuTask(
            TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY, cpuTask, Duration.ofSeconds(30));

        // Then
        assertThat(future).isNotNull();
        assertThat(future.get()).isEqualTo("CPU result");

        TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
        assertThat(stats.totalTasks()).isEqualTo(1);
        assertThat(stats.cpuTasks()).isEqualTo(1);
        assertThat(stats.gpuTasks()).isEqualTo(0);
    }

    @Test
    void testSubmitComputeTaskCpuOnly() throws ExecutionException, InterruptedException {
        // Given - no GPU implementation
        Supplier<String> cpuTask = () -> "CPU result";

        // When
        CompletableFuture<String> future = TaskScheduler.submitComputeTask(
            TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
            cpuTask, null, // No GPU task
            1024L, 100, TaskScheduler.TaskComplexity.MODERATE, TaskScheduler.TaskType.GENERAL, null, true);

        // Then
        assertThat(future).isNotNull();
        assertThat(future.get()).isEqualTo("CPU result");

        TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
        assertThat(stats.totalTasks()).isEqualTo(1);
        assertThat(stats.cpuTasks()).isEqualTo(1);
        assertThat(stats.gpuTasks()).isEqualTo(0);
    }

    @Test
    void testSubmitComputeTaskSmallDataSize() throws ExecutionException, InterruptedException {
        // Given - small data size, should prefer CPU
        Supplier<String> cpuTask = () -> "CPU result";
        OpenCLTask<String> gpuTask = createGpuTask(TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
            cpuTask, () -> "GPU result");

        try (MockedStatic<OpenCLManager> openCLMock = Mockito.mockStatic(OpenCLManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            stubGpuAcceptingState(openCLMock);

            // When
            CompletableFuture<String> future = TaskScheduler.submitComputeTask(
                TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
                cpuTask, gpuTask,
                100L, // Small data size
                10, TaskScheduler.TaskComplexity.MODERATE, TaskScheduler.TaskType.GENERAL, null, true);

            // Then
            assertThat(future).isNotNull();
            assertThat(future.get()).isEqualTo("CPU result");

            TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
            assertThat(stats.totalTasks()).isEqualTo(1);
            assertThat(stats.cpuTasks()).isEqualTo(1);
            assertThat(stats.gpuTasks()).isEqualTo(0);
        }
    }

    @Test
    void testSubmitComputeTaskGpuAvailable() throws ExecutionException, InterruptedException {
        // Given - large data size, should prefer GPU
        Supplier<String> cpuTask = () -> "CPU result";
        OpenCLTask<String> gpuTask = createGpuTask(TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
            cpuTask, () -> "GPU result");

        try (MockedStatic<OpenCLManager> openCLMock = Mockito.mockStatic(OpenCLManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            stubGpuAcceptingState(openCLMock);

            // When
            CompletableFuture<String> future = TaskScheduler.submitComputeTask(
                TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
                cpuTask, gpuTask,
                1024L * 1024L * 200, // Large data size (200MB)
                10000, TaskScheduler.TaskComplexity.COMPLEX, TaskScheduler.TaskType.VECTOR_MATH, null, true);

            // Then
            assertThat(future).isNotNull();
            assertThat(future.get()).isEqualTo("GPU result");

            TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
            assertThat(stats.totalTasks()).isEqualTo(1);
            assertThat(stats.cpuTasks()).isEqualTo(0);
            assertThat(stats.gpuTasks()).isEqualTo(1);
        }
    }

    @Test
    void testSubmitComputeTaskGpuNotAvailable() throws ExecutionException, InterruptedException {
        // Given - GPU not available, should fallback to CPU
        Supplier<String> cpuTask = () -> "CPU result";
        OpenCLTask<String> gpuTask = createGpuTask(TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
            cpuTask, () -> "GPU result");

        try (MockedStatic<OpenCLManager> openCLMock = Mockito.mockStatic(OpenCLManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            openCLMock.when(OpenCLManager::isAvailable).thenReturn(false);
            openCLMock.when(OpenCLManager::isInVramPressureCooldown).thenReturn(false);

            // When
            CompletableFuture<String> future = TaskScheduler.submitComputeTask(
                TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
                cpuTask, gpuTask,
                1024L * 1024L * 200, // Large data size
                10000, TaskScheduler.TaskComplexity.COMPLEX, TaskScheduler.TaskType.VECTOR_MATH, null, true);

            // Then
            assertThat(future).isNotNull();
            assertThat(future.get()).isEqualTo("CPU result");

            TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
            assertThat(stats.totalTasks()).isEqualTo(1);
            assertThat(stats.cpuTasks()).isEqualTo(1);
            assertThat(stats.gpuTasks()).isEqualTo(0);
        }
    }

    @Test
    void testSubmitSpatialAnalysisTask() throws ExecutionException, InterruptedException {
        // Given - spatial analysis task with GPU
        Supplier<String> cpuTask = () -> "CPU result";
        OpenCLTask<String> gpuTask = createGpuTask(TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
            cpuTask, () -> "GPU result");

        try (MockedStatic<OpenCLManager> openCLMock = Mockito.mockStatic(OpenCLManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            stubGpuAcceptingState(openCLMock);

            // When
            CompletableFuture<String> future = TaskScheduler.submitSpatialAnalysisTask(
                TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
                cpuTask, gpuTask,
                1024L * 1024L * 50, // 50MB
                5000);

            // Then
            assertThat(future).isNotNull();
            assertThat(future.get()).isEqualTo("GPU result");

            TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
            assertThat(stats.totalTasks()).isEqualTo(1);
            assertThat(stats.gpuTasks()).isEqualTo(1);
        }
    }

    @Test
    void testSubmitMassiveDataTask() throws ExecutionException, InterruptedException {
        // Given - massive data task
        Supplier<String> cpuTask = () -> "CPU result";
        OpenCLTask<String> gpuTask = createGpuTask(TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
            cpuTask, () -> "GPU result");

        try (MockedStatic<OpenCLManager> openCLMock = Mockito.mockStatic(OpenCLManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            stubGpuAcceptingState(openCLMock);

            // When
            CompletableFuture<String> future = TaskScheduler.submitMassiveDataTask(
                TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
                cpuTask, gpuTask,
                1024L * 1024L * 500, // 500MB
                50000);

            // Then
            assertThat(future).isNotNull();
            assertThat(future.get()).isEqualTo("GPU result");

            TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
            assertThat(stats.totalTasks()).isEqualTo(1);
            assertThat(stats.gpuTasks()).isEqualTo(1);
        }
    }

    @Test
    void testStatisticsTracking() {
        // Given
        Supplier<String> cpuTask = () -> "CPU result";

        try (MockedStatic<OpenCLManager> openCLMock = Mockito.mockStatic(OpenCLManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            stubGpuAcceptingState(openCLMock);

            // When - submit multiple tasks
            TaskScheduler.submitCpuTask(TEST_MOD_ID, "cpu1", 1L, cpuTask, null);
            TaskScheduler.submitCpuTask(TEST_MOD_ID, "cpu2", 2L, cpuTask, null);
            TaskScheduler.submitComputeTask(TEST_MOD_ID, "gpu1", 3L, cpuTask,
                createGpuTask(TEST_MOD_ID, "gpu1", 3L, cpuTask, () -> "GPU result"),
                1024L * 1024L * 200, 10000, TaskScheduler.TaskComplexity.COMPLEX,
                TaskScheduler.TaskType.VECTOR_MATH, null, true);
            TaskScheduler.submitComputeTask(TEST_MOD_ID, "gpu2", 4L, cpuTask,
                createGpuTask(TEST_MOD_ID, "gpu2", 4L, cpuTask, () -> "GPU result"),
                1024L * 1024L * 200, 10000, TaskScheduler.TaskComplexity.COMPLEX,
                TaskScheduler.TaskType.VECTOR_MATH, null, true);

            // Then
            TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
            assertThat(stats.totalTasks()).isEqualTo(4);
            assertThat(stats.cpuTasks()).isEqualTo(2);
            assertThat(stats.gpuTasks()).isEqualTo(2);
            assertThat(stats.gpuUtilizationRatio()).isEqualTo(0.5);
        }
    }

    @Test
    void testResetStats() {
        // Given - some tasks executed
        Supplier<String> cpuTask = () -> "CPU result";
        TaskScheduler.submitCpuTask(TEST_MOD_ID, "cpu1", 1L, cpuTask, null);

        // When - reset stats
        TaskScheduler.resetStats();

        // Then - stats should be zero
        TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
        assertThat(stats.totalTasks()).isEqualTo(0);
        assertThat(stats.cpuTasks()).isEqualTo(0);
        assertThat(stats.gpuTasks()).isEqualTo(0);
        assertThat(stats.gpuUtilizationRatio()).isEqualTo(0.0);
    }

    @Test
    void testExpectedSpeedupCalculation() {
        // Test various task types and complexities
        // Note: This tests the private calculateExpectedSpeedup method indirectly through task submission

        Supplier<String> cpuTask = () -> "CPU result";

        try (MockedStatic<OpenCLManager> openCLMock = Mockito.mockStatic(OpenCLManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            stubGpuAcceptingState(openCLMock);

            // Vector math should have high speedup
            TaskScheduler.submitComputeTask(TEST_MOD_ID, "vector", 1L, cpuTask,
                createGpuTask(TEST_MOD_ID, "vector", 1L, cpuTask, () -> "GPU result"),
                1024L * 1024L * 10, 5000, TaskScheduler.TaskComplexity.COMPLEX,
                TaskScheduler.TaskType.VECTOR_MATH, null, true);

            // Simple task should prefer CPU
            TaskScheduler.submitComputeTask(TEST_MOD_ID, "simple", 2L, cpuTask,
                createGpuTask(TEST_MOD_ID, "simple", 2L, cpuTask, () -> "GPU result"),
                1024L, 10, TaskScheduler.TaskComplexity.SIMPLE,
                TaskScheduler.TaskType.GENERAL, null, true);

            TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
            assertThat(stats.totalTasks()).isEqualTo(2);
            assertThat(stats.gpuTasks()).isEqualTo(1); // Only vector math task went to GPU
            assertThat(stats.cpuTasks()).isEqualTo(1); // Simple task stayed on CPU
        }
    }

    @Test
    void testSimplifiedSubmitComputeTask() throws ExecutionException, InterruptedException {
        // Test the simplified version of submitComputeTask
        Supplier<String> cpuTask = () -> "CPU result";
        OpenCLTask<String> gpuTask = createGpuTask(TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
            cpuTask, () -> "GPU result");

        try (MockedStatic<OpenCLManager> openCLMock = Mockito.mockStatic(OpenCLManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            stubGpuAcceptingState(openCLMock);

            // When
            CompletableFuture<String> future = TaskScheduler.submitComputeTask(
                TEST_MOD_ID, TEST_TASK_NAME, TEST_TASK_KEY,
                cpuTask, gpuTask,
                1024L * 1024L * 200, // Large data
                10000); // High parallelism

            // Then
            assertThat(future.get()).isEqualTo("GPU result");
            TaskScheduler.SchedulingStats stats = TaskScheduler.getStats();
            assertThat(stats.gpuTasks()).isEqualTo(1);
        }
    }

    private static OpenCLTask<String> createGpuTask(String modId,
                                                    String taskName,
                                                    long taskKey,
                                                    Supplier<String> cpuFallback,
                                                    Supplier<String> gpuSupplier) {
        return new SimpleOpenClTask<>(modId, taskName, taskKey, cpuFallback, gpuSupplier);
    }

    private static void stubGpuAcceptingState(MockedStatic<OpenCLManager> openCLMock) {
        openCLMock.when(OpenCLManager::isAvailable).thenReturn(true);
        openCLMock.when(OpenCLManager::isInVramPressureCooldown).thenReturn(false);
        openCLMock.when(() -> OpenCLManager.canAcceptTask(Mockito.any(OpenCLTask.class))).thenReturn(true);
    }

    private static final class TestGpuBatchWorkload implements TaskMetadata.GpuBatchWorkload {
        @Override
        public CompletableFuture<Void> submit(String modId,
                                              List<PriorityTask> tasks,
                                              TaskMetadata metadata) {
            List<OpenCLTask<?>> gpuTasks = GpuWorkloadRegistry.collect(tasks);
            for (OpenCLTask<?> task : gpuTasks) {
                Object result;
                if (task instanceof SimpleOpenClTask<?> simpleTask) {
                    result = simpleTask.runGpu();
                } else {
                    result = task.cpuFallback().get();
                }
                GpuWorkloadRegistry.complete(task.taskKey(), result);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class SimpleOpenClTask<T> extends OpenCLTask<T> {
        private final Supplier<T> gpuSupplier;
        private final long vram;
        private final int computeUnits;

        private SimpleOpenClTask(String modId,
                                 String taskName,
                                 long taskKey,
                                 Supplier<T> cpuFallback,
                                 Supplier<T> gpuSupplier) {
            this(modId, taskName, taskKey, cpuFallback, gpuSupplier, 64L * 1024L * 1024L, 2048);
        }

        private SimpleOpenClTask(String modId,
                                 String taskName,
                                 long taskKey,
                                 Supplier<T> cpuFallback,
                                 Supplier<T> gpuSupplier,
                                 long vramBytes,
                                 int computeUnits) {
            super(new SimpleBuilder<>(modId, taskName, taskKey, cpuFallback));
            this.gpuSupplier = gpuSupplier;
            this.vram = vramBytes;
            this.computeUnits = computeUnits;
        }

        @Override
        public long estimatedVramBytes() {
            return vram;
        }

        @Override
        public int estimatedComputeUnits() {
            return computeUnits;
        }

        @Override
        public T executeOnGPU(OpenCLContext context) {
            return gpuSupplier.get();
        }

        T runGpu() {
            return gpuSupplier.get();
        }

        private static final class SimpleBuilder<T> extends OpenCLTask.Builder<T> {
            private SimpleBuilder(String modId, String name, long taskKey, Supplier<T> cpuFallback) {
                super(modId, name, taskKey, cpuFallback);
            }

            @Override
            public OpenCLTask<T> build() {
                throw new UnsupportedOperationException("Not used in tests");
            }
        }
    }
}
