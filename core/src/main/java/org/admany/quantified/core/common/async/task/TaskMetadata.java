package org.admany.quantified.core.common.async.task;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


public final class TaskMetadata {

    public static final TaskMetadata DEFAULT = builder().build();
    public static final TaskMetadata NON_BATCHABLE = builder().batchable(false).build();

    private final double estimatedCost;
    private final boolean batchable;
    private final boolean gpuPreferred;
    private final boolean gpuRequired;
    private final int preferredBatchSize;
    private final int maximumBatchSize;
    private final String affinityKey;
    private final GpuBatchWorkload gpuWorkload;

    private TaskMetadata(Builder builder) {
        this.estimatedCost = Math.max(0.0, builder.estimatedCost);
        this.batchable = builder.batchable;
        this.gpuPreferred = builder.gpuPreferred || builder.gpuRequired;
        this.gpuRequired = builder.gpuRequired;
        if (builder.batchable) {
            int preferred = Math.max(1, builder.preferredBatchSize);
            int max = Math.max(preferred, builder.maximumBatchSize);
            this.preferredBatchSize = preferred;
            this.maximumBatchSize = max;
        } else {
            this.preferredBatchSize = 1;
            this.maximumBatchSize = 1;
        }
        this.affinityKey = Objects.requireNonNullElse(builder.affinityKey, "");
        this.gpuWorkload = builder.gpuWorkload;
    }

    public double estimatedCost() {
        return estimatedCost;
    }

    public boolean batchable() {
        return batchable;
    }

    public boolean gpuPreferred() {
        return gpuPreferred;
    }

    public boolean gpuRequired() {
        return gpuRequired;
    }

    public int preferredBatchSize() {
        return preferredBatchSize;
    }

    public int maximumBatchSize() {
        return maximumBatchSize;
    }

    public String affinityKey() {
        return affinityKey;
    }

    public Optional<GpuBatchWorkload> gpuWorkload() {
        return Optional.ofNullable(gpuWorkload);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TaskMetadata batchableAffinity(String affinityKey, int preferredBatchSize, int maximumBatchSize) {
        Builder builder = new Builder();
        builder.affinityKey(affinityKey);
        builder.batchable(true);
        builder.preferredBatchSize(preferredBatchSize);
        builder.maximumBatchSize(maximumBatchSize);
        return new TaskMetadata(builder);
    }

    public static TaskMetadata merge(TaskMetadata current, TaskMetadata incoming) {
        if (incoming == null) {
            return current;
        }
        if (current == null || current == DEFAULT) {
            return incoming;
        }
        if (incoming == DEFAULT) {
            return current;
        }
        Builder builder = current.toBuilder();
        builder.estimatedCost(Math.max(current.estimatedCost(), incoming.estimatedCost()));
        builder.batchable(current.batchable() && incoming.batchable());
        if (incoming.gpuRequired()) {
            builder.gpuRequired(true);
        } else if (incoming.gpuPreferred()) {
            builder.gpuPreferred(true);
        }
        builder.preferredBatchSize(Math.max(current.preferredBatchSize(), incoming.preferredBatchSize()));
        builder.maximumBatchSize(Math.max(current.maximumBatchSize(), incoming.maximumBatchSize()));
        if (!incoming.affinityKey().isEmpty()) {
            builder.affinityKey(incoming.affinityKey());
        }
        if (incoming.gpuWorkload != null) {
            builder.gpuWorkload(incoming.gpuWorkload);
        }
        return builder.build();
    }

    public static final class Builder {
        private double estimatedCost = 1.0;
        private boolean batchable = true;
        private boolean gpuPreferred;
        private boolean gpuRequired;
        private int preferredBatchSize = 32;
        private int maximumBatchSize = 128;
        private String affinityKey = "";
        private GpuBatchWorkload gpuWorkload;

        private Builder() {
        }

        private Builder(TaskMetadata metadata) {
            this.estimatedCost = metadata.estimatedCost;
            this.batchable = metadata.batchable;
            this.gpuPreferred = metadata.gpuPreferred;
            this.gpuRequired = metadata.gpuRequired;
            this.preferredBatchSize = metadata.preferredBatchSize;
            this.maximumBatchSize = metadata.maximumBatchSize;
            this.affinityKey = metadata.affinityKey;
            this.gpuWorkload = metadata.gpuWorkload;
        }

        public Builder estimatedCost(double estimatedCost) {
            this.estimatedCost = Math.max(0.0, estimatedCost);
            return this;
        }

        public Builder batchable(boolean batchable) {
            this.batchable = batchable;
            return this;
        }

        public Builder gpuPreferred(boolean gpuPreferred) {
            this.gpuPreferred = gpuPreferred;
            return this;
        }

        public Builder gpuRequired(boolean gpuRequired) {
            this.gpuRequired = gpuRequired;
            return this;
        }

        public Builder preferredBatchSize(int preferredBatchSize) {
            this.preferredBatchSize = preferredBatchSize;
            return this;
        }

        public Builder maximumBatchSize(int maximumBatchSize) {
            this.maximumBatchSize = maximumBatchSize;
            return this;
        }

        public Builder affinityKey(String affinityKey) {
            this.affinityKey = affinityKey;
            return this;
        }

        public Builder gpuWorkload(GpuBatchWorkload gpuWorkload) {
            this.gpuWorkload = gpuWorkload;
            return this;
        }

        public TaskMetadata build() {
            return new TaskMetadata(this);
        }
    }

    @FunctionalInterface
    public interface GpuBatchWorkload {
        CompletableFuture<Void> submit(String modId, List<PriorityTask> tasks, TaskMetadata metadata);
    }
}
