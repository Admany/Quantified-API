package org.admany.quantified.core.common.async.gpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;

public final class GpuWorkloadRegistry {

    private static final ConcurrentHashMap<Long, OpenCLTask<?>> TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, CompletableFuture<Object>> RESULTS = new ConcurrentHashMap<>();

    private GpuWorkloadRegistry() {
    }

    public static void register(long taskKey, OpenCLTask<?> task) {
        Objects.requireNonNull(task, "task");
        TASKS.put(taskKey, task);
        RESULTS.computeIfAbsent(taskKey, ignored -> new CompletableFuture<>());
    }

    public static OpenCLTask<?> take(long taskKey) {
        return TASKS.remove(taskKey);
    }

    public static CompletableFuture<Object> result(long taskKey) {
        return RESULTS.get(taskKey);
    }

    public static void complete(long taskKey, Object result) {
        CompletableFuture<Object> future = RESULTS.get(taskKey);
        if (future != null && !future.isDone()) {
            future.complete(result);
        }
    }

    public static void completeExceptionally(long taskKey, Throwable throwable) {
        CompletableFuture<Object> future = RESULTS.get(taskKey);
        if (future != null && !future.isDone()) {
            future.completeExceptionally(throwable);
        }
    }

    public static void cancel(long taskKey) {
        CompletableFuture<Object> future = RESULTS.remove(taskKey);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    public static List<OpenCLTask<?>> collect(Collection<PriorityTask> tasks) {
        List<OpenCLTask<?>> collected = new ArrayList<>(tasks.size());
        for (PriorityTask task : tasks) {
            OpenCLTask<?> gpuTask = take(task.taskKey());
            if (gpuTask != null) {
                collected.add(gpuTask);
            }
        }
        return collected;
    }

    public static void clear() {
        TASKS.clear();
        RESULTS.values().forEach(future -> future.cancel(true));
        RESULTS.clear();
    }
}
