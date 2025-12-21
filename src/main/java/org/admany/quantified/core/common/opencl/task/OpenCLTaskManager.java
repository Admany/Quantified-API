package org.admany.quantified.core.common.opencl.task;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.opencl.cache.TieredGpuCache;
import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLRuntime;
import org.admany.quantified.core.common.opencl.core.OpenCLTask;
import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OpenCLTaskManager {

    private static final Logger LOGGER = Logger.getLogger(OpenCLTaskManager.class.getName());

    private static volatile AdaptiveThrottle taskThrottle;
    private static final long VRAM_PRESSURE_COOLDOWN_MS = 2_000L;
    private static final AtomicLong vramPressureCooldownUntilMs = new AtomicLong(0);
    private static final AtomicLong lastPressureLogMs = new AtomicLong(0);
    private static final int MAX_TASK_HISTORY = 20;
    private static final Object TASK_HISTORY_LOCK = new Object();
    private static final Deque<TaskEvent> TASK_HISTORY = new ArrayDeque<>();
    private static final long TASK_HISTORY_WINDOW_MS = 5_000L;
    private static final long EXTERNAL_PRESSURE_LOG_INTERVAL_MS = 5_000L;

    private static final AtomicLong lastExternalPressureLogMs = new AtomicLong(0);

    private static GPUMonitor monitor;
    private static OpenCLContext context;
    private static TieredGpuCache tieredCache;

    private OpenCLTaskManager() {}

    public static void setDependencies(GPUMonitor monitor, OpenCLContext context, TieredGpuCache tieredCache) {
        OpenCLTaskManager.monitor = monitor;
        OpenCLTaskManager.context = context;
        OpenCLTaskManager.tieredCache = tieredCache;
    }

    public static void initializeThrottle(GPUMonitor monitor) {
        taskThrottle = new AdaptiveThrottle(128, monitor);
    }

    public static <T> CompletableFuture<T> submitTask(OpenCLTask<T> task) {
        if (taskThrottle != null && !taskThrottle.tryAcquire()) {
            LOGGER.warning("Task rejected due to high GPU load: " + task.name());
            recordTaskEvent(task, TaskEventType.GPU_THROTTLED, "semaphore limit reached");
            return CompletableFuture.completedFuture(null);
        }

        try {
            if (!canAcceptTask(task)) {
                LOGGER.fine("GPU capacity check failed, routing to async pool: " + task.name());
                DeveloperOverlayManager.recordFallbackEvent("GPU capacity limit reached", task.modId());
                return submitToAsync(task, "GPU capacity limit reached");
            }
            return org.admany.quantified.core.common.util.TaskScheduler.submitComputeTask(
                task.modId(),
                task.name(),
                task.taskKey(),
                task.cpuFallback(),
                task,
                task.estimatedVramBytes(),
                task.estimatedComputeUnits(),
                org.admany.quantified.core.common.util.TaskScheduler.TaskComplexity.MODERATE,
                org.admany.quantified.core.common.util.TaskScheduler.TaskType.GENERAL,
                task.timeout().orElse(null),
                true // Allow main thread rerouting for OpenCL tasks
            );
        } finally {
            if (taskThrottle != null) {
                taskThrottle.release();
            }
        }
    }

    public static boolean canAcceptTask(OpenCLTask<?> task) {
        if (monitor == null) {
            return true;
        }
        return monitor.canAcceptTask(
            task.estimatedVramBytes(),
            task.estimatedComputeUnits()
        );
    }

    public static <T> CompletableFuture<T> executeOnGpu(OpenCLTask<T> task) {
        Objects.requireNonNull(task, "task");
        if (taskThrottle != null && !taskThrottle.tryAcquire()) {
            LOGGER.warning("GPU task rejected due to throttle: " + task.name());
            recordTaskEvent(task, TaskEventType.GPU_THROTTLED, "semaphore limit reached");
            return CompletableFuture.failedFuture(new IllegalStateException("GPU busy"));
        }
        try {
            if (!canAcceptTask(task)) {
                LOGGER.fine("GPU capacity check failed, executing on async pool: " + task.name());
                DeveloperOverlayManager.recordFallbackEvent("GPU capacity limit reached", task.modId());
                return submitToAsync(task, "GPU capacity limit reached");
            }
            return executeOnGpuInternal(task);
        } finally {
            if (taskThrottle != null) {
                taskThrottle.release();
            }
        }
    }

    private static <T> CompletableFuture<T> executeOnGpuInternal(OpenCLTask<T> task) {
        recordTaskEvent(task, TaskEventType.GPU_EXECUTE, null);
        GPUMonitor.TaskSample sample = monitor != null
            ? monitor.beginTask(task.estimatedVramBytes(), task.estimatedComputeUnits())
            : null;
        try {
            LOGGER.fine("Executing task on GPU: " + task.name());
            T result = task.executeOnGPU(context);
            return CompletableFuture.completedFuture(result);
        } catch (Throwable throwable) {
            recordTaskEvent(task, TaskEventType.GPU_ERROR, throwable.getClass().getSimpleName());
            LOGGER.log(Level.WARNING, "GPU execution failed, falling back to CPU", throwable);
            DeveloperOverlayManager.recordFallbackEvent("GPU execution error – " + throwable.getClass().getSimpleName(), task.modId());
            if (monitor != null) {
                monitor.recordFallback();
            }
            return submitToAsync(task, "GPU execution error: " + throwable.getMessage());
        } finally {
            if (monitor != null) {
                monitor.endTask(sample);
            }
        }
    }

    public static void handleVramSaturation() {
        handleVramSaturation(null);
    }

    public static void handleVramSaturation(String cause) {
        GPUMonitor.GPUStatus status = monitor != null ? monitor.getStatus() : null;

        if (isLikelyExternalPressure(status, cause)) {
            offloadCachesForExternalPressure();
            logExternalPressure(status, cause);
            return;
        }

        try {
            clearGPUCaches();
            // Destroy OpenCL context to free VRAM
            if (context != null) {
                try {
                    context.close();
                } catch (Throwable t) {
                    LOGGER.fine("Failed to close OpenCL context: " + t.getMessage());
                }
                context = null;
            }
            OpenCLRuntime.destroy();
            if (monitor != null) {
                try {
                    monitor.refreshNow();
                } catch (Throwable t) {
                    LOGGER.fine("Failed to refresh GPU monitor: " + t.getMessage());
                }
            }
            enterVramPressureCooldown();
            logSaturationContext(status, cause);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to handle VRAM saturation", t);
        }
    }

    private static void clearGPUCaches() {
        TieredGpuCache cache = tieredCache;
        if (cache != null) {
            cache.clear();
        }
        if (monitor != null) {
            monitor.clearMemoryTracking();
            LOGGER.info("GPU memory tracking cleared");
            DeveloperOverlayManager.recordApiLog("[OpenCL] GPU memory tracking cleared");
        }
    }

    public static boolean isInVramPressureCooldown() {
        long now = System.currentTimeMillis();
        long until = vramPressureCooldownUntilMs.get();
        if (until <= now) {
            return false;
        }
        long lastLog = lastPressureLogMs.get();
        if (now - lastLog > 5_000L && lastPressureLogMs.compareAndSet(lastLog, now)) {
            long seconds = Math.max(1L, (until - now) / 1000L);
            DeveloperOverlayManager.recordApiLog("[OpenCL] VRAM spike - routing GPU work to CPU for ~" + seconds + "s");
        }
        return true;
    }

    private static void enterVramPressureCooldown() {
        long until = System.currentTimeMillis() + VRAM_PRESSURE_COOLDOWN_MS;
        vramPressureCooldownUntilMs.set(until);
    }

    private static void logSaturationContext(GPUMonitor.GPUStatus status, String cause) {
        DeveloperOverlayManager.recordApiLog("[OpenCL] VRAM saturation detected - destroyed context to free VRAM, pausing GPU work temporarily");
        LOGGER.warning("VRAM saturation detected - destroyed context to free VRAM, pausing GPU work temporarily");

        String detail = buildPressureDetail("VRAM saturation context", status, cause, true);
        LOGGER.warning(detail);
        DeveloperOverlayManager.recordApiLog("[OpenCL] " + detail);
    }

    private static void logExternalPressure(GPUMonitor.GPUStatus status, String cause) {
        long now = System.currentTimeMillis();
        long lastLog = lastExternalPressureLogMs.get();
        if (now - lastLog < EXTERNAL_PRESSURE_LOG_INTERVAL_MS) {
            return;
        }
        if (!lastExternalPressureLogMs.compareAndSet(lastLog, now)) {
            return;
        }

        String headline = "[OpenCL] VRAM pressure from external usage – deferring cache growth";
        DeveloperOverlayManager.recordApiLog(headline);
        LOGGER.info(headline.substring("[OpenCL] ".length()));

        String detail = buildPressureDetail("External VRAM pressure context", status, cause, true);
        LOGGER.info(detail);
        DeveloperOverlayManager.recordApiLog("[OpenCL] " + detail);
    }

    private static void offloadCachesForExternalPressure() {
        try {
            clearGPUCaches();
        } catch (Throwable ignored) {
        }

        try {
            Class<?> gpuMemoryManager = Class.forName("org.admany.lc2h.worldgen.gpu.GPUMemoryManager");
            gpuMemoryManager.getMethod("clearQuantifiedAPICaches").invoke(null);
            DeveloperOverlayManager.recordApiLog("[OpenCL] Offloaded Quantified caches to RAM/disk due to external VRAM pressure");
        } catch (Throwable ignored) {
            // LC2H not present or method unavailable; best effort
        }
    }

    private static String buildPressureDetail(String heading, GPUMonitor.GPUStatus status, String cause, boolean includeRecentTasks) {
        StringBuilder builder = new StringBuilder(heading);
        builder.append(": ");
        if (cause != null && !cause.isBlank()) {
            builder.append(cause);
        } else {
            builder.append("no trigger supplied");
        }

        if (status != null) {
            long totalMb = Math.max(1L, status.totalVramBytes() / (1024 * 1024));
            long usedMb = Math.max(0L, status.usedVramBytes() / (1024 * 1024));
            double memoryPercent = Math.max(0.0d, Math.min(100.0d, status.memoryUtilization() * 100.0d));
            double systemPercent = Math.max(0.0d, Math.min(100.0d, status.systemUsageRatio() * 100.0d));
            double computePercent = Math.max(0.0d, Math.min(100.0d, status.computeUtilization() * 100.0d));
            double temperature = status.temperatureC();
            String device = status.deviceName() != null ? status.deviceName() : "Unknown GPU";

            builder.append(String.format(Locale.ROOT,
                " | GPU %s VRAM %d/%d MB (API %.0f%% · system %.0f%%) · Compute %.0f%% · Temp %.1f°C",
                device,
                usedMb,
                totalMb,
                memoryPercent,
                systemPercent,
                computePercent,
                temperature));
        } else {
            builder.append(" | GPU status unavailable");
        }

        if (includeRecentTasks) {
            List<TaskEvent> recent = snapshotRecentTaskEvents(TASK_HISTORY_WINDOW_MS);
            if (!recent.isEmpty()) {
                builder.append(" | recent GPU work: ");
                int limit = Math.min(3, recent.size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) {
                        builder.append("; ");
                    }
                    builder.append(formatTaskEvent(recent.get(i)));
                }
            }
        }

        return builder.toString();
    }

    private static List<TaskEvent> snapshotRecentTaskEvents(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        List<TaskEvent> events = new ArrayList<>();
        synchronized (TASK_HISTORY_LOCK) {
            for (TaskEvent event : TASK_HISTORY) {
                if (event.timestampMs() >= cutoff) {
                    events.add(event);
                } else {
                    break;
                }
            }
        }
        return events;
    }

    private static boolean hasRecentGpuExecution(long windowMs) {
        List<TaskEvent> events = snapshotRecentTaskEvents(windowMs);
        for (TaskEvent event : events) {
            if (event.type() == TaskEventType.GPU_EXECUTE) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelyExternalPressure(GPUMonitor.GPUStatus status, String cause) {
        if (cause != null) {
            String lower = cause.toLowerCase(Locale.ROOT);
            if (lower.contains("lc2h") || lower.contains("quantified gpu cache")) {
                return false;
            }
        }

        if (hasRecentGpuExecution(3_000L)) {
            return false;
        }

        long activeEstimate = monitor != null ? monitor.estimatedActiveVramBytes() : 0L;
        if (status != null && status.systemUsageRatio() < 0.75d) {
            return false;
        }
        long thresholdBytes = 16L * 1024L * 1024L; // 16 MB default threshold
        if (status != null && status.totalVramBytes() > 0) {
            thresholdBytes = Math.max(thresholdBytes, (long) (status.totalVramBytes() * 0.05));
        }

        return activeEstimate < thresholdBytes;
    }

    private static void recordTaskEvent(OpenCLTask<?> task, TaskEventType type, String detail) {
        if (task == null) {
            recordTaskEvent("unknown", "unknown", -1L, type, detail);
            return;
        }
        recordTaskEvent(task.modId(), task.name(), task.estimatedVramBytes(), type, detail);
    }

    private static void recordTaskEvent(String modId, String taskName, long estimatedVramBytes, TaskEventType type, String detail) {
        String safeMod = (modId != null && !modId.isBlank()) ? modId : "unknown";
        String safeTask = (taskName != null && !taskName.isBlank()) ? taskName : "unknown";
        TaskEvent event = new TaskEvent(System.currentTimeMillis(), safeMod, safeTask, Math.max(-1L, estimatedVramBytes), type, detail);
        synchronized (TASK_HISTORY_LOCK) {
            TASK_HISTORY.addFirst(event);
            while (TASK_HISTORY.size() > MAX_TASK_HISTORY) {
                TASK_HISTORY.removeLast();
            }
        }
    }

    private static String formatTaskEvent(TaskEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append(event.modId());
        sb.append('/');
        sb.append(event.taskName());
        sb.append(' ');
        sb.append(event.type().name());
        if (event.estimatedVramBytes() >= 0) {
            sb.append('(');
            long bytes = event.estimatedVramBytes();
            if (bytes >= 1024 * 1024) {
                sb.append(bytes / (1024 * 1024)).append("MB");
            } else if (bytes >= 1024) {
                sb.append(bytes / 1024).append("KB");
            } else {
                sb.append(bytes).append('B');
            }
            sb.append(')');
        }
        if (event.detail() != null && !event.detail().isBlank()) {
            sb.append(" - ");
            sb.append(event.detail());
        }
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - event.timestampMs()) / 1000L);
        sb.append(" @");
        sb.append(ageSeconds);
        sb.append('s');
        sb.append(" ago");
        return sb.toString();
    }

    private static <T> CompletableFuture<T> submitToAsync(OpenCLTask<T> task, String reason) {
        LOGGER.fine("Routing OpenCL task to async pool: " + reason + " - " + task.name());
        recordTaskEvent(task, TaskEventType.ROUTED_CPU, reason);

        return AsyncManager.submitSync(
            task.taskKey(),
            PriorityTaskType.BACKGROUND,
            PriorityTaskType.BACKGROUND.defaultScore(),
            task.cpuFallback(),
            task.timeout().orElse(null),
            true,
            task.modId()
        );
    }

    private enum TaskEventType {
        GPU_EXECUTE,
        ROUTED_CPU,
        GPU_ERROR,
        GPU_THROTTLED
    }

    private record TaskEvent(long timestampMs,
                             String modId,
                             String taskName,
                             long estimatedVramBytes,
                             TaskEventType type,
                             String detail) {
    }

    public static class AdaptiveThrottle {
        private final Semaphore baseSemaphore;
        private final GPUMonitor monitor;
        private volatile int dynamicLimit;
        private final Object configMutex = new Object();

        public AdaptiveThrottle(int baseLimit, GPUMonitor monitor) {
            this.baseSemaphore = new Semaphore(baseLimit);
            this.monitor = monitor;
            this.dynamicLimit = baseLimit;
        }

        public boolean tryAcquire() {
            int limitSnapshot;
            synchronized (configMutex) {
                limitSnapshot = dynamicLimit;
            }

            if (monitor == null) {
                return baseSemaphore.tryAcquire();
            }

            int available = baseSemaphore.availablePermits();
            int used = Math.max(0, limitSnapshot - available);
            int thermalCap = monitor.isThermallyLimited() ? Math.max(1, limitSnapshot / 2) : limitSnapshot;
            if (used >= thermalCap) {
                return false;
            }

            GPUMonitor.GPUStatus status = monitor.getStatus();
            double utilization = status != null ? status.computeUtilization() : 0.0;
            int utilCap = Math.max(1, (int) Math.round(limitSnapshot * (1.0 - utilization * 0.5)));
            int effectiveLimit = Math.min(thermalCap, utilCap);
            if (used >= effectiveLimit) {
                return false;
            }

            return baseSemaphore.tryAcquire();
        }

        public void release() {
            baseSemaphore.release();
        }

        public void adjustLimit(int newLimit) {
            synchronized (configMutex) {
                this.dynamicLimit = Math.max(1, newLimit);
                LOGGER.fine("Adaptive throttle limit adjusted to: " + dynamicLimit);
            }
        }

        public int getCurrentLimit() {
            synchronized (configMutex) {
                return dynamicLimit;
            }
        }
    }

    public static class QueuePool {
        private final List<ManagedQueue> pool = new ArrayList<>();
        private final Object poolMutex = new Object();
        private final int maxPoolSize;

        public QueuePool(Object ignored, int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public ManagedQueue borrow() {
            synchronized (poolMutex) {
                for (ManagedQueue mq : pool) {
                    if (mq.isAvailable()) {
                        mq.borrow();
                        return mq;
                    }
                }

                if (pool.size() < maxPoolSize) {
                    ManagedQueue newQueue = new ManagedQueue(createNewQueue(), this);
                    pool.add(newQueue);
                    newQueue.borrow();
                    LOGGER.fine("Created new managed queue, pool size: " + pool.size());
                    return newQueue;
                }

                return null;
            }
        }

        public void returnQueue(ManagedQueue queue) {
            synchronized (poolMutex) {
                queue.returnToPool();
            }
        }

        public void cleanup() {
            synchronized (poolMutex) {
                for (ManagedQueue mq : pool) {
                    try {
                        mq.forceClose();
                    } catch (Exception e) {
                        LOGGER.warning("Failed to cleanup managed queue: " + e.getMessage());
                    }
                }
                pool.clear();
            }
        }

        private long createNewQueue() {
            throw new UnsupportedOperationException("Queue creation not implemented");
        }
    }

    public static class ManagedQueue implements AutoCloseable {
        private final long queuePtr;
        private final QueuePool pool;
        private volatile boolean borrowed = false;
        private volatile long lastUsed = System.nanoTime();
        private volatile boolean healthy = true;

        ManagedQueue(long ptr, QueuePool pool) {
            this.queuePtr = ptr;
            this.pool = pool;
        }

        void borrow() {
            borrowed = true;
            lastUsed = System.nanoTime();
        }

        void returnToPool() {
            borrowed = false;
            lastUsed = System.nanoTime();
        }

        public boolean isAvailable() {
            return !borrowed && healthy;
        }

        public long getQueuePtr() {
            return queuePtr;
        }

        public boolean isHealthy() {
            return healthy && (System.nanoTime() - lastUsed) < 300_000_000_000L;
        }

        void markUnhealthy() {
            healthy = false;
        }

        void forceClose() {
            healthy = false;
            borrowed = false;
        }

        @Override
        public void close() {
            pool.returnQueue(this);
        }
    }
}