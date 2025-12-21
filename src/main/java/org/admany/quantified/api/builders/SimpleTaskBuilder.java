package org.admany.quantified.api.builders;

import org.admany.quantified.api.model.QuantifiedTask;

public class SimpleTaskBuilder<T> implements QuantifiedTaskBuilder {
    private final String modId;
    private final String name;
    private final java.util.function.Supplier<T> work;
    private Priority priority = Priority.AUTO;
    private java.time.Duration timeout;
    @SuppressWarnings("unused")
    private int maxRetries = 0;
    @SuppressWarnings("unused")
    private long vramBytes = 0;
    @SuppressWarnings("unused")
    private int computeUnits = 1;
    @SuppressWarnings("unused")
    private java.util.function.Consumer<Double> progressCallback;
    @SuppressWarnings("unused")
    private java.util.function.Consumer<Throwable> failureCallback;
    @SuppressWarnings("unused")
    private boolean allowMainThreadRerouting = false;

    public SimpleTaskBuilder(String modId, String name, java.util.function.Supplier<T> work) {
        this.modId = modId;
        this.name = name;
        this.work = work;
    }

    @Override
    public QuantifiedTaskBuilder priority(Priority priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public QuantifiedTaskBuilder timeout(java.time.Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    @Override
    public QuantifiedTaskBuilder retry(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    @Override
    public QuantifiedTaskBuilder resourceEstimate(long vramBytes, int computeUnits) {
        this.vramBytes = vramBytes;
        this.computeUnits = computeUnits;
        return this;
    }

    @Override
    public QuantifiedTaskBuilder onProgress(java.util.function.Consumer<Double> progressCallback) {
        this.progressCallback = progressCallback;
        return this;
    }

    @Override
    public QuantifiedTaskBuilder onFailure(java.util.function.Consumer<Throwable> failureCallback) {
        this.failureCallback = failureCallback;
        return this;
    }

    @Override
    public QuantifiedTaskBuilder allowMainThreadRerouting(boolean allow) {
        this.allowMainThreadRerouting = allow;
        return this;
    }

    @Override
    public <R> java.util.concurrent.CompletableFuture<R> submit(java.util.function.Supplier<R> work) {
        try {
            return java.util.concurrent.CompletableFuture.completedFuture(work.get());
        } catch (Throwable t) {
            java.util.concurrent.CompletableFuture<R> cf = new java.util.concurrent.CompletableFuture<>();
            cf.completeExceptionally(t);
            return cf;
        }
    }

    public QuantifiedTask<T> build() {
        return QuantifiedTask.builder(modId, name, work)
            .priority(mapPriority(priority))
            .timeout(timeout)
            .build();
    }

    private org.admany.quantified.core.common.async.task.PriorityTaskType mapPriority(Priority p) {
        switch (p) {
            case FOREGROUND: return org.admany.quantified.core.common.async.task.PriorityTaskType.FOREGROUND;
            case BACKGROUND: return org.admany.quantified.core.common.async.task.PriorityTaskType.BACKGROUND;
            case CRITICAL: return org.admany.quantified.core.common.async.task.PriorityTaskType.OTHER;
            case AUTO: return org.admany.quantified.core.common.async.task.PriorityTaskType.OTHER;
            default: return org.admany.quantified.core.common.async.task.PriorityTaskType.OTHER;
        }
    }
}