package org.admany.quantified.core.common.async.gpu;

import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.vulkan.core.ApiVulkanTaskWrapper;
import org.admany.quantified.core.common.vulkan.core.VulkanIsolatedExecutor;
import org.admany.quantified.core.common.vulkan.core.VulkanTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class VulkanIsolatedBatchWorkload implements TaskMetadata.GpuBatchWorkload {

    private static final Logger LOGGER = Logger.getLogger(VulkanIsolatedBatchWorkload.class.getName());
    private static final AtomicLong NEXT_TASK_FAILURE_WARNING_MS = new AtomicLong();

    public static final VulkanIsolatedBatchWorkload INSTANCE = new VulkanIsolatedBatchWorkload();

    private VulkanIsolatedBatchWorkload() {
    }

    @Override
    public CompletableFuture<Void> submit(String modId, List<PriorityTask> tasks, TaskMetadata metadata) {
        Objects.requireNonNull(tasks, "tasks");
        if (!VulkanIsolatedExecutor.canExecute()) {
            return null;
        }
        List<VulkanTask<?>> peekedTasks = GpuWorkloadRegistry.peekVulkan(tasks);
        if (peekedTasks.isEmpty() || !allApiTasks(peekedTasks)) {
            return null;
        }
        List<VulkanTask<?>> claimedTasks = GpuWorkloadRegistry.claimVulkan(tasks);
        if (claimedTasks.isEmpty()) {
            return null;
        }
        List<QuantifiedVulkan.ApiVulkanTask<?>> apiTasks = new ArrayList<>(claimedTasks.size());
        List<Long> taskKeys = new ArrayList<>(claimedTasks.size());
        for (VulkanTask<?> task : claimedTasks) {
            if (!(task instanceof ApiVulkanTaskWrapper<?> wrapper)) {
                GpuWorkloadRegistry.completeExceptionally(
                    task.taskKey(),
                    new IllegalStateException("Isolated Vulkan batching only supports QuantifiedVulkan API tasks")
                );
                continue;
            }
            apiTasks.add(wrapper.apiTask());
            taskKeys.add(task.taskKey());
        }
        if (apiTasks.isEmpty()) {
            return null;
        }
        executeBatch(modId, apiTasks, taskKeys);
        return CompletableFuture.completedFuture(null);
    }

    private static boolean allApiTasks(List<VulkanTask<?>> tasks) {
        for (VulkanTask<?> task : tasks) {
            if (!(task instanceof ApiVulkanTaskWrapper<?>)) {
                return false;
            }
        }
        return true;
    }

    private static void executeBatch(String modId,
                                     List<QuantifiedVulkan.ApiVulkanTask<?>> apiTasks,
                                     List<Long> taskKeys) {
        try {
            Object[] results = VulkanIsolatedExecutor.executeApiTasks(apiTasks);
            int limit = Math.min(results.length, taskKeys.size());
            for (int i = 0; i < limit; i++) {
                Object result = results[i];
                if (result instanceof Throwable throwable) {
                    warnTaskFailure(modId, throwable);
                    GpuWorkloadRegistry.completeExceptionally(taskKeys.get(i), throwable);
                } else {
                    GpuWorkloadRegistry.complete(taskKeys.get(i), result);
                }
            }
            for (int i = limit; i < taskKeys.size(); i++) {
                GpuWorkloadRegistry.completeExceptionally(
                    taskKeys.get(i),
                    new IllegalStateException("Isolated Vulkan batch returned fewer results than submitted tasks")
                );
            }
        } catch (Throwable throwable) {
            LOGGER.warning(() -> "Isolated Vulkan batch failed for mod " + modId + ": " + throwable.getMessage());
            for (Long taskKey : taskKeys) {
                GpuWorkloadRegistry.completeExceptionally(taskKey, throwable);
            }
        }
    }

    private static void warnTaskFailure(String modId, Throwable throwable) {
        long now = System.currentTimeMillis();
        long next = NEXT_TASK_FAILURE_WARNING_MS.get();
        if (now < next || !NEXT_TASK_FAILURE_WARNING_MS.compareAndSet(next, now + 60_000L)) {
            return;
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        LOGGER.warning("Isolated Vulkan task failed for mod " + modId + ": "
            + root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message));
    }
}
