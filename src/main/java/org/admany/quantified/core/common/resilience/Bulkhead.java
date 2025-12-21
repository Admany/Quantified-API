package org.admany.quantified.core.common.resilience;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class Bulkhead {
    private final String name;
    private final Semaphore semaphore;
    private final LinkedBlockingQueue<QueuedTask<?>> queue;
    private final int maxQueueSize;

    public Bulkhead(String name, int maxConcurrent, int maxQueueSize) {
        this.name = name;
        this.semaphore = new Semaphore(maxConcurrent);
        this.queue = new LinkedBlockingQueue<>(maxQueueSize);
        this.maxQueueSize = maxQueueSize;
    }

    public Bulkhead(String name, int maxConcurrent) {
        this(name, maxConcurrent, Integer.MAX_VALUE);
    }

    public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> taskSupplier) {
        QueuedTask<T> queuedTask = new QueuedTask<>(taskSupplier);

        if (!queue.offer(queuedTask)) {
            // Queue is full
            CompletableFuture<T> failed = new CompletableFuture<>();
            ErrorHandler.QuantifiedException exception = ErrorHandler.getInstance().createException(
                ErrorHandler.ErrorType.BULKHEAD_FULL,
                "Bulkhead execution",
                Map.of("bulkhead", name, "maxQueueSize", maxQueueSize)
            );
            failed.completeExceptionally(exception);
            return failed;
        }

        return processQueue();
    }

    private <T> CompletableFuture<T> processQueue() {
        try {
            if (!semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Failed to acquire semaphore permit");
            }

            QueuedTask<?> queuedTask = queue.poll();
            if (queuedTask == null) {
                semaphore.release();
                throw new IllegalStateException("No task in queue");
            }

            @SuppressWarnings("unchecked")
            QueuedTask<T> typedTask = (QueuedTask<T>) queuedTask;

            try {
                CompletableFuture<T> task = typedTask.taskSupplier.get();
                return task.whenComplete((result, error) -> {
                    semaphore.release();
                    if (!queue.isEmpty()) {
                        processQueue();
                    }
                });
            } catch (Exception e) {
                semaphore.release();
                if (!queue.isEmpty()) {
                    processQueue();
                }
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    public int getActiveCount() {
        return semaphore.availablePermits();
    }

    public int getQueueSize() {
        return queue.size();
    }

    public int getMaxConcurrent() {
        return semaphore.availablePermits() + (semaphore.drainPermits() - semaphore.availablePermits());
    }

    private static class QueuedTask<T> {
        final Supplier<CompletableFuture<T>> taskSupplier;

        QueuedTask(Supplier<CompletableFuture<T>> taskSupplier) {
            this.taskSupplier = taskSupplier;
        }
    }
}


