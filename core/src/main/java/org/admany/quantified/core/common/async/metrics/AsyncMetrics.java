package org.admany.quantified.core.common.async.metrics;

import java.util.concurrent.atomic.LongAdder;

public final class AsyncMetrics {

    private final LongAdder requests = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder tasksCreated = new LongAdder();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder coalesced = new LongAdder();
    private final LongAdder totalComputeTimeNanos = new LongAdder();
    private final LongAdder rejectedSubmissions = new LongAdder();
    private final LongAdder threadSafetyViolations = new LongAdder();
    private final LongAdder typeDefaults = new LongAdder();
    private final LongAdder scoreSanitisations = new LongAdder();
    private final LongAdder safetyOverrides = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder cancellations = new LongAdder();

    public void incrementRequests() {
        requests.increment();
    }

    public void incrementCacheHits() {
        cacheHits.increment();
    }

    public void incrementTasksCreated() {
        tasksCreated.increment();
    }

    public void recordSuccess(long durationNanos) {
        successes.increment();
        if (durationNanos > 0) {
            totalComputeTimeNanos.add(durationNanos);
        }
    }

    public void recordFailure() {
        failures.increment();
    }

    public void incrementCoalesced() {
        coalesced.increment();
    }

    public void recordRejectedSubmission() {
        rejectedSubmissions.increment();
    }

    public void recordThreadSafetyViolation() {
        threadSafetyViolations.increment();
    }

    public void recordTypeDefaulted() {
        typeDefaults.increment();
    }

    public void recordScoreSanitised() {
        scoreSanitisations.increment();
    }

    public void recordSafetyOverride() {
        safetyOverrides.increment();
    }

    public void recordTimeout() {
        timeouts.increment();
    }

    public void recordCancellation() {
        cancellations.increment();
    }

    public long requests() {
        return requests.sum();
    }

    public long cacheHits() {
        return cacheHits.sum();
    }

    public long tasksCreated() {
        return tasksCreated.sum();
    }

    public long successes() {
        return successes.sum();
    }

    public long failures() {
        return failures.sum();
    }

    public long coalesced() {
        return coalesced.sum();
    }

    public long rejectedSubmissions() {
        return rejectedSubmissions.sum();
    }

    public long threadSafetyViolations() {
        return threadSafetyViolations.sum();
    }

    public long typeDefaults() {
        return typeDefaults.sum();
    }

    public long scoreSanitisations() {
        return scoreSanitisations.sum();
    }

    public long safetyOverrides() {
        return safetyOverrides.sum();
    }

    public long timeouts() {
        return timeouts.sum();
    }

    public long cancellations() {
        return cancellations.sum();
    }

    public double averageComputeMillis() {
        long successCount = successes.sum();
        if (successCount == 0) {
            return 0.0;
        }
        return (totalComputeTimeNanos.sum() / 1_000_000.0) / successCount;
    }

    public AsyncMetricsSnapshot snapshot(int cacheSize) {
        return new AsyncMetricsSnapshot(
            requests(),
            cacheHits(),
            tasksCreated(),
            successes(),
            failures(),
            coalesced(),
            averageComputeMillis(),
            cacheSize,
            threadSafetyViolations(),
            rejectedSubmissions(),
            typeDefaults(),
            scoreSanitisations(),
            safetyOverrides(),
            timeouts(),
            cancellations());
    }

    public record AsyncMetricsSnapshot(long requests,
                                       long cacheHits,
                                       long tasksCreated,
                                       long successes,
                                       long failures,
                                       long coalesced,
                                       double averageComputeMillis,
                                       int cacheSize,
                                       long threadSafetyViolations,
                                       long rejectedSubmissions,
                                       long typeDefaults,
                                       long scoreSanitisations,
                                       long safetyOverrides,
                                       long timeouts,
                                       long cancellations) {
    }
}
