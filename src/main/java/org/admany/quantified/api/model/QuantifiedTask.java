package org.admany.quantified.api.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.admany.quantified.core.common.async.task.PriorityTaskType;

public final class QuantifiedTask<T> {

    private final String modId;
    private final String name;
    private final Supplier<T> work;
    private final PriorityTaskType priority;
    private final boolean autoPriority;
    private final boolean threadSafe;
    private final Optional<Duration> timeout;
    private final boolean gpuPreferred;
    private final boolean gpuRequired;

    private QuantifiedTask(Builder<T> builder) {
        this.modId = builder.modId;
        this.name = builder.name;
        this.work = builder.work;
        this.priority = builder.priority;
        this.autoPriority = builder.autoPriority;
        this.threadSafe = builder.threadSafe;
        this.timeout = Optional.ofNullable(builder.timeout);
        this.gpuPreferred = builder.gpuPreferred;
        this.gpuRequired = builder.gpuRequired;
    }

    public String modId() {
        return modId;
    }

    public String name() {
        return name;
    }

    public Supplier<T> work() {
        return work;
    }

    public PriorityTaskType priority() {
        return priority;
    }

    public boolean autoPriority() {
        return autoPriority;
    }

    public boolean threadSafe() {
        return threadSafe;
    }

    public Optional<Duration> timeout() {
        return timeout;
    }

    public boolean gpuPreferred() {
        return gpuPreferred;
    }

    public boolean gpuRequired() {
        return gpuRequired;
    }

    public static <T> Builder<T> builder(String modId, String name, Supplier<T> work) {
        return new Builder<>(modId, name, work);
    }

    public static final class Builder<T> {
        private final String modId;
        private final String name;
        private final Supplier<T> work;
        private PriorityTaskType priority = PriorityTaskType.OTHER;
        private boolean autoPriority = true;
        private boolean threadSafe = true;
        private Duration timeout;
        private boolean gpuPreferred = false;
        private boolean gpuRequired = false;

        private Builder(String modId, String name, Supplier<T> work) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.name = Objects.requireNonNull(name, "name");
            this.work = Objects.requireNonNull(work, "work");
        }

        public Builder<T> priority(PriorityTaskType priority) {
            this.priority = Objects.requireNonNull(priority, "priority");
            this.autoPriority = false;
            return this;
        }

        public Builder<T> priorityAuto() {
            this.autoPriority = true;
            this.priority = PriorityTaskType.OTHER;
            return this;
        }

        public Builder<T> priorityForeground() {
            return priority(PriorityTaskType.FOREGROUND);
        }

        public Builder<T> priorityBackground() {
            return priority(PriorityTaskType.BACKGROUND);
        }

        public Builder<T> threadSafe() {
            return threadSafe(true);
        }

        public Builder<T> notThreadSafe() {
            return threadSafe(false);
        }

        public Builder<T> threadSafe(boolean threadSafe) {
            this.threadSafe = threadSafe;
            return this;
        }

        public Builder<T> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder<T> gpuPreferred() {
            this.gpuPreferred = true;
            return this;
        }

        public Builder<T> gpuRequired() {
            this.gpuRequired = true;
            return this;
        }

        public QuantifiedTask<T> build() {
            return new QuantifiedTask<>(this);
        }

        public String modId() {
            return modId;
        }
    }
}