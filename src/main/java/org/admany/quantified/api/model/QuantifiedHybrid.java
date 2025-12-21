package org.admany.quantified.api.model;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

import org.admany.quantified.core.common.async.task.PriorityTaskType;

public final class QuantifiedHybrid<T> {

    private final QuantifiedTask<T> delegate;
    private final String cacheName;
    private final String cacheKey;
    private final Duration ttl;
    private final long maximumSize;

    private QuantifiedHybrid(Builder<T> builder) {
        this.delegate = builder.taskBuilder.build();
        this.cacheName = builder.cacheName;
        this.cacheKey = builder.cacheKey;
        this.ttl = builder.ttl;
        this.maximumSize = builder.maximumSize;
    }

    public QuantifiedTask<T> toTask() {
        return delegate;
    }

    public String cacheName() {
        return cacheName;
    }

    public String cacheKey() {
        return cacheKey;
    }

    public Duration ttl() {
        return ttl;
    }

    public long maximumSize() {
        return maximumSize;
    }

    public PriorityTaskType priority() {
        return delegate.priority();
    }

    public boolean autoPriority() {
        return delegate.autoPriority();
    }

    public boolean threadSafe() {
        return delegate.threadSafe();
    }

    public static <T> Builder<T> builder(String modId, String name, Supplier<T> work) {
        return new Builder<>(modId, name, work);
    }

    public static final class Builder<T> {
        private final QuantifiedTask.Builder<T> taskBuilder;
        private String cacheName = "default";
        private String cacheKey;
        private Duration ttl = Duration.ofMinutes(5);
        private long maximumSize = 2048;

        private Builder(String modId, String name, Supplier<T> work) {
            this.taskBuilder = QuantifiedTask.builder(modId, name, work);
        }

        public Builder<T> priority(PriorityTaskType priority) {
            taskBuilder.priority(priority);
            return this;
        }

        public Builder<T> priorityAuto() {
            taskBuilder.priorityAuto();
            return this;
        }

        public Builder<T> priorityForeground() {
            taskBuilder.priorityForeground();
            return this;
        }

        public Builder<T> priorityBackground() {
            taskBuilder.priorityBackground();
            return this;
        }

        public Builder<T> threadSafe() {
            taskBuilder.threadSafe();
            return this;
        }

        public Builder<T> notThreadSafe() {
            taskBuilder.notThreadSafe();
            return this;
        }

        public Builder<T> threadSafe(boolean threadSafe) {
            taskBuilder.threadSafe(threadSafe);
            return this;
        }

        public Builder<T> timeout(Duration timeout) {
            taskBuilder.timeout(timeout);
            return this;
        }

        public Builder<T> cache(String cacheName) {
            this.cacheName = Objects.requireNonNull(cacheName, "cacheName");
            return this;
        }

        public Builder<T> cacheKey(String cacheKey) {
            this.cacheKey = cacheKey;
            return this;
        }

        public Builder<T> ttl(Duration ttl) {
            this.ttl = Objects.requireNonNull(ttl, "ttl");
            return this;
        }

        public Builder<T> maximumSize(long maximumSize) {
            this.maximumSize = maximumSize;
            return this;
        }

        public QuantifiedHybrid<T> build() {
            if (cacheKey == null || cacheKey.isBlank()) {
                throw new IllegalStateException("Hybrid tasks require a cache key");
            }
            return new QuantifiedHybrid<>(this);
        }
    }
}