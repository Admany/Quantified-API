package org.admany.quantified.core.common.async.task;


public enum PriorityTaskType {
    /** Latency-sensitive tasks that should prefer the foreground pool. */
    FOREGROUND(true, 1.0),
    /** Bulk work that can tolerate longer delays. */
    BACKGROUND(true, 0.75),
    /** Noise/sample generation requests. */
    NOISE(false, 0.5),
    /** Building/layout computations. */
    BUILDING(true, 0.9),
    /** Cache refresh or eviction jobs. */
    CACHE(true, 0.8),
    /** Fallback bucket for callers that do not specify a type. */
    OTHER(false, 0.6);

    private final boolean requiresThreadSafe;
    private final double defaultScore;

    PriorityTaskType(boolean requiresThreadSafe, double defaultScore) {
        this.requiresThreadSafe = requiresThreadSafe;
        this.defaultScore = defaultScore;
    }

    public boolean requiresThreadSafe() {
        return requiresThreadSafe;
    }

    public double defaultScore() {
        return defaultScore;
    }

    public double sanitiseScore(double score) {
        if (!Double.isFinite(score)) {
            return defaultScore;
        }
        double clamped = Math.max(0.0d, Math.min(10.0d, score));
        return clamped == score ? score : clamped;
    }
}
