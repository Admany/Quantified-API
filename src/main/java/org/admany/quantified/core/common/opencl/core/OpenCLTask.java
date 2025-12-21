package org.admany.quantified.core.common.opencl.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public abstract class OpenCLTask<T> {

    private final String modId;
    private final String name;
    private final long taskKey;
    private final Supplier<T> cpuFallback;
    private final Optional<Duration> timeout;

    protected OpenCLTask(Builder<T> builder) {
        this(builder.modId, builder.name, builder.taskKey, builder.cpuFallback, builder.timeout);
    }

    protected OpenCLTask(String modId, String name, long taskKey, Supplier<T> cpuFallback, Duration timeout) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.name = Objects.requireNonNull(name, "name");
        this.taskKey = taskKey;
        this.cpuFallback = Objects.requireNonNull(cpuFallback, "cpuFallback");
        this.timeout = Optional.ofNullable(timeout);
    }

    public String modId() {
        return modId;
    }

    public String name() {
        return name;
    }

    public long taskKey() {
        return taskKey;
    }

    public Supplier<T> cpuFallback() {
        return cpuFallback;
    }

    public Optional<Duration> timeout() {
        return timeout;
    }

    public abstract long estimatedVramBytes();

    public abstract int estimatedComputeUnits();

    public abstract T executeOnGPU(OpenCLContext context);

    public static abstract class Builder<T> {
        protected String modId;
        protected String name;
        protected long taskKey;
        protected Supplier<T> cpuFallback;
        protected Duration timeout;

        protected Builder(String modId, String name, long taskKey, Supplier<T> cpuFallback) {
            this.modId = modId;
            this.name = name;
            this.taskKey = taskKey;
            this.cpuFallback = cpuFallback;
        }

        public Builder<T> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public abstract OpenCLTask<T> build();
    }
}