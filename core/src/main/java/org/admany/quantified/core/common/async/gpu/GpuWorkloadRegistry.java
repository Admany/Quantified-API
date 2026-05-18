package org.admany.quantified.core.common.async.gpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.admany.quantified.api.compute.GpuBackendType;
import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.admany.quantified.core.common.vulkan.core.VulkanTask;

public final class GpuWorkloadRegistry {

    private static final ConcurrentHashMap<Long, RegisteredTask> TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, CompletableFuture<Object>> RESULTS = new ConcurrentHashMap<>();

    private GpuWorkloadRegistry() {
    }

    public static void register(long taskKey, OpenCLTask<?> task) {
        Objects.requireNonNull(task, "task");
        TASKS.put(taskKey, new RegisteredTask(GpuBackendType.OPENCL, task));
        RESULTS.computeIfAbsent(taskKey, ignored -> new CompletableFuture<>());
    }

    public static void register(long taskKey, VulkanTask<?> task) {
        Objects.requireNonNull(task, "task");
        TASKS.put(taskKey, new RegisteredTask(GpuBackendType.VULKAN, task));
        RESULTS.computeIfAbsent(taskKey, ignored -> new CompletableFuture<>());
    }

    public static OpenCLTask<?> take(long taskKey) {
        RegisteredTask task = TASKS.get(taskKey);
        if (task == null || task.backendType != GpuBackendType.OPENCL || !TASKS.remove(taskKey, task)) {
            return null;
        }
        return (OpenCLTask<?>) task.task;
    }

    public static VulkanTask<?> takeVulkan(long taskKey) {
        RegisteredTask task = TASKS.get(taskKey);
        if (task == null || task.backendType != GpuBackendType.VULKAN || !TASKS.remove(taskKey, task)) {
            return null;
        }
        return (VulkanTask<?>) task.task;
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
        return claimOpenCl(tasks);
    }

    public static List<OpenCLTask<?>> collectOpenCl(Collection<PriorityTask> tasks) {
        return claimOpenCl(tasks);
    }

    public static List<OpenCLTask<?>> peekOpenCl(Collection<PriorityTask> tasks) {
        List<OpenCLTask<?>> collected = new ArrayList<>(tasks.size());
        for (PriorityTask task : tasks) {
            RegisteredTask registeredTask = TASKS.get(task.taskKey());
            OpenCLTask<?> gpuTask = registeredTask != null && registeredTask.backendType == GpuBackendType.OPENCL
                ? (OpenCLTask<?>) registeredTask.task
                : null;
            if (gpuTask != null) {
                collected.add(gpuTask);
            }
        }
        return collected;
    }

    public static List<OpenCLTask<?>> claimOpenCl(Collection<PriorityTask> tasks) {
        List<OpenCLTask<?>> collected = new ArrayList<>(tasks.size());
        for (PriorityTask task : tasks) {
            OpenCLTask<?> gpuTask = take(task.taskKey());
            if (gpuTask != null) {
                collected.add(gpuTask);
            }
        }
        return collected;
    }

    public static List<VulkanTask<?>> collectVulkan(Collection<PriorityTask> tasks) {
        return claimVulkan(tasks);
    }

    public static List<VulkanTask<?>> peekVulkan(Collection<PriorityTask> tasks) {
        List<VulkanTask<?>> collected = new ArrayList<>(tasks.size());
        for (PriorityTask task : tasks) {
            RegisteredTask registeredTask = TASKS.get(task.taskKey());
            VulkanTask<?> gpuTask = registeredTask != null && registeredTask.backendType == GpuBackendType.VULKAN
                ? (VulkanTask<?>) registeredTask.task
                : null;
            if (gpuTask != null) {
                collected.add(gpuTask);
            }
        }
        return collected;
    }

    public static List<VulkanTask<?>> claimVulkan(Collection<PriorityTask> tasks) {
        List<VulkanTask<?>> collected = new ArrayList<>(tasks.size());
        for (PriorityTask task : tasks) {
            VulkanTask<?> gpuTask = takeVulkan(task.taskKey());
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

    private static final class RegisteredTask {
        private final GpuBackendType backendType;
        private final Object task;

        private RegisteredTask(GpuBackendType backendType, Object task) {
            this.backendType = backendType;
            this.task = task;
        }
    }
}
