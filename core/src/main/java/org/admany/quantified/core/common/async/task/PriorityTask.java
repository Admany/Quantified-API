package org.admany.quantified.core.common.async.task;

import java.util.Objects;

public final class PriorityTask implements Comparable<PriorityTask> {
    private final long taskKey;
    private final PriorityTaskType type;
    private final Runnable payload;
    private final long enqueuedAtNanos;
    private final TaskMetadata metadata;
    private final String modId;
    private volatile double score;
    private final java.util.concurrent.atomic.AtomicBoolean superseded = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable onDrop;

    public PriorityTask(long taskKey, PriorityTaskType type, double score, Runnable payload) {
        this(taskKey, type, score, payload, TaskMetadata.DEFAULT, "");
    }

    public PriorityTask(long taskKey,
                        PriorityTaskType type,
                        double score,
                        Runnable payload,
                        TaskMetadata metadata) {
        this(taskKey, type, score, payload, metadata, "");
    }

    public PriorityTask(long taskKey,
                        PriorityTaskType type,
                        double score,
                        Runnable payload,
                        TaskMetadata metadata,
                        String modId) {
        this(taskKey, type, score, payload, metadata, modId, null);
    }

    public PriorityTask(long taskKey,
                        PriorityTaskType type,
                        double score,
                        Runnable payload,
                        TaskMetadata metadata,
                        String modId,
                        Runnable onDrop) {
        this.taskKey = taskKey;
        this.type = Objects.requireNonNull(type, "type");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.modId = Objects.requireNonNullElse(modId, "");
        this.score = score;
        this.enqueuedAtNanos = System.nanoTime();
        this.onDrop = onDrop;
    }

   public void notifyDrop() {
        if (onDrop != null) {
            try {
                onDrop.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public long taskKey() {
        return taskKey;
    }

    public PriorityTaskType type() {
        return type;
    }

    public Runnable payload() {
        return payload;
    }

    public double score() {
        return score;
    }

    public TaskMetadata metadata() {
        return metadata;
    }

    public String modId() {
        return modId;
    }

    public void adjustScore(double delta) {
        this.score += delta;
    }

    public void markSuperseded() {
        superseded.set(true);
    }

    public boolean isSuperseded() {
        return superseded.get();
    }

    public long enqueuedAtNanos() {
        return enqueuedAtNanos;
    }

    @Override
    public int compareTo(PriorityTask other) {
        return -Double.compare(this.score, other.score);
    }
}
