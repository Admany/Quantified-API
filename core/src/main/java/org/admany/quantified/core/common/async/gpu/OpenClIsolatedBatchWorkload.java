package org.admany.quantified.core.common.async.gpu;

import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.admany.quantified.core.common.opencl.core.ApiOpenClTaskWrapper;
import org.admany.quantified.core.common.opencl.core.OpenCLIsolatedExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Runs API OpenCL workloads through the isolated, child class loader runtime. */
public final class OpenClIsolatedBatchWorkload implements TaskMetadata.GpuBatchWorkload {

    private static final Logger LOGGER = Logger.getLogger(OpenClIsolatedBatchWorkload.class.getName());
    private static final AtomicLong NEXT_WARNING_MS = new AtomicLong();

    public static final OpenClIsolatedBatchWorkload INSTANCE = new OpenClIsolatedBatchWorkload();

    private OpenClIsolatedBatchWorkload() {
    }

    @Override
    public CompletableFuture<Void> submit(String modId, List<PriorityTask> tasks, TaskMetadata metadata) {
        Objects.requireNonNull(tasks, "tasks");
        if (!OpenCLIsolatedExecutor.canExecute()) {
            return null;
        }
        List<org.admany.quantified.core.common.opencl.core.OpenCLTask<?>> peeked = GpuWorkloadRegistry.peekOpenCl(tasks);
        if (peeked.isEmpty() || !allApiTasks(peeked)) {
            return null;
        }
        List<org.admany.quantified.core.common.opencl.core.OpenCLTask<?>> claimed = GpuWorkloadRegistry.claimOpenCl(tasks);
        if (claimed.isEmpty()) {
            return null;
        }

        List<QuantifiedOpenCL.ApiOpenClTask<?>> apiTasks = new ArrayList<>(claimed.size());
        List<Long> taskKeys = new ArrayList<>(claimed.size());
        for (org.admany.quantified.core.common.opencl.core.OpenCLTask<?> task : claimed) {
            ApiOpenClTaskWrapper<?> wrapper = (ApiOpenClTaskWrapper<?>) task;
            apiTasks.add(wrapper.apiTask());
            taskKeys.add(task.taskKey());
        }

        // OpenCLIsolatedExecutor already serialises work on its dedicated
        // runtime thread.  Adding another common-pool hop here only delays
        // completion and makes the dispatcher wait on a second executor.
        executeBatch(modId, apiTasks, taskKeys);
        return CompletableFuture.completedFuture(null);
    }

    private static boolean allApiTasks(List<org.admany.quantified.core.common.opencl.core.OpenCLTask<?>> tasks) {
        for (org.admany.quantified.core.common.opencl.core.OpenCLTask<?> task : tasks) {
            if (!(task instanceof ApiOpenClTaskWrapper<?>)) {
                return false;
            }
        }
        return true;
    }

    private static void executeBatch(String modId,
                                     List<QuantifiedOpenCL.ApiOpenClTask<?>> apiTasks,
                                     List<Long> taskKeys) {
        try {
            Object[] results = OpenCLIsolatedExecutor.executeApiTasks(apiTasks);
            int limit = Math.min(results.length, taskKeys.size());
            for (int index = 0; index < limit; index++) {
                Object result = results[index];
                if (result instanceof Throwable throwable) {
                    GpuWorkloadRegistry.completeExceptionally(taskKeys.get(index), throwable);
                } else {
                    GpuWorkloadRegistry.complete(taskKeys.get(index), result);
                }
            }
            for (int index = limit; index < taskKeys.size(); index++) {
                GpuWorkloadRegistry.completeExceptionally(taskKeys.get(index),
                    new IllegalStateException("Isolated OpenCL batch returned fewer results than submitted tasks"));
            }
        } catch (Throwable throwable) {
            warnFailure(modId, throwable);
            for (Long taskKey : taskKeys) {
                GpuWorkloadRegistry.completeExceptionally(taskKey, throwable);
            }
            throw throwable instanceof RuntimeException runtimeException
                ? runtimeException
                : new RuntimeException(throwable);
        }
    }

    private static void warnFailure(String modId, Throwable throwable) {
        long now = System.currentTimeMillis();
        long next = NEXT_WARNING_MS.get();
        if (now < next || !NEXT_WARNING_MS.compareAndSet(next, now + 60_000L)) {
            return;
        }
        LOGGER.warning("Isolated OpenCL batch failed for mod " + modId + ": " + throwable.getMessage());
    }
}
