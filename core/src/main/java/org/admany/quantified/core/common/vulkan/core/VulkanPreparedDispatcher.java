package org.admany.quantified.core.common.vulkan.core;

import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.admany.quantified.core.common.gpu.backend.VulkanExecutionSupport;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-latency execution lane for explicitly prepared, already-coalesced Vulkan work.
 *
 * <p>This deliberately bypasses the generic task scheduler. Callers must submit a
 * substantial, pure batch through {@link QuantifiedVulkan.PreparedProgram}; QAPI
 * still owns runtime selection, Vulkan thread ownership, residency checks,
 * timeout accounting and CPU fallback.</p>
 */
public final class VulkanPreparedDispatcher {
    private static final long MAX_REQUEST_BYTES = 512L * 1024L * 1024L;
    private static final LongAdder ACCEPTED = new LongAdder();
    private static final LongAdder COMPLETED = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder FAILED = new LongAdder();

    private VulkanPreparedDispatcher() {
    }

    public static <T> CompletableFuture<T> submit(QuantifiedVulkan.ApiVulkanTask<T> task,
                                                   Duration timeout) {
        Objects.requireNonNull(task, "task");
        long bytes = Math.max(0L, task.estimatedVramBytes());
        if (bytes > MAX_REQUEST_BYTES || !VulkanExecutionSupport.hasExecutableRuntime()) {
            REJECTED.increment();
            return CompletableFuture.failedFuture(new IllegalStateException(
                bytes > MAX_REQUEST_BYTES
                    ? "Prepared Vulkan request exceeds the 512 MiB safety limit"
                    : "Vulkan runtime is unavailable"));
        }

        CompletableFuture<T> future;
        if (VulkanExecutionSupport.canUseInProcessManager()) {
            ApiVulkanTaskWrapper<T> wrapped = new ApiVulkanTaskWrapper<>(task);
            if (!VulkanManager.canAcceptTask(wrapped)) {
                REJECTED.increment();
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Vulkan runtime rejected the prepared request due to pressure or availability"));
            }
            future = VulkanManager.executeOnGpu(wrapped);
        } else if (VulkanIsolatedExecutor.canExecute()) {
            future = VulkanIsolatedExecutor.executeApiTaskAsync(task);
        } else {
            REJECTED.increment();
            return CompletableFuture.failedFuture(new IllegalStateException("Vulkan runtime is not executable"));
        }

        ACCEPTED.increment();
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            future = future.orTimeout(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        }
        return future.whenComplete((unused, failure) -> {
            if (failure == null) {
                COMPLETED.increment();
            } else {
                FAILED.increment();
            }
        });
    }

    public static Snapshot snapshot() {
        return new Snapshot(ACCEPTED.sum(), COMPLETED.sum(), REJECTED.sum(), FAILED.sum());
    }

    public record Snapshot(long accepted, long completed, long rejected, long failed) {
    }
}
