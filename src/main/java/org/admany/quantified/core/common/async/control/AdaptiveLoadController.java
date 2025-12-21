package org.admany.quantified.core.common.async.control;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class AdaptiveLoadController {

    private static final long MIN_DELAY_MILLIS = 5;
    private static final long MAX_DELAY_MILLIS = 150;

    private final LongAdder samples = new LongAdder();
    private final AtomicLong lastDecisionNanos = new AtomicLong();
    private volatile double throttleLevel;
    private volatile long lastDelayMillis;

    public AdaptiveLoadController() {
        this.throttleLevel = 0.0;
        this.lastDelayMillis = 0L;
    }

    public Decision evaluate(LoadSnapshot snapshot) {
        samples.increment();
        double load = clamp01(snapshot.cpuLoad());
        double pendingScore = clamp01(snapshot.pendingTasks() / (double) snapshot.capacityHint());
        double queueScore = clamp01(snapshot.uniqueQueue() / (double) Math.max(1, snapshot.capacityHint()));

        double desired = Math.max(load, Math.max(pendingScore, queueScore));
        throttleLevel = smooth(throttleLevel, desired);

        long delay = computeDelay(throttleLevel);
        lastDelayMillis = delay;
        lastDecisionNanos.set(System.nanoTime());
        return new Decision(delay, throttleLevel);
    }

    private static double smooth(double previous, double next) {
        final double alpha = 0.35;
        return (alpha * next) + ((1 - alpha) * previous);
    }

    private static long computeDelay(double throttle) {
        if (throttle <= 0.05) {
            return 0L;
        }
        double scaled = MIN_DELAY_MILLIS + ( (MAX_DELAY_MILLIS - MIN_DELAY_MILLIS) * clamp01(throttle) );
        return Math.round(scaled);
    }

    private static double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    public double throttleLevel() {
        return throttleLevel;
    }

    public long lastDelayMillis() {
        return lastDelayMillis;
    }

    public long nanosSinceLastDecision() {
        long last = lastDecisionNanos.get();
        return last == 0L ? Long.MAX_VALUE : System.nanoTime() - last;
    }

    public long sampleCount() {
        return samples.sum();
    }

    public record LoadSnapshot(int pendingTasks,
                               int uniqueQueue,
                               int capacityHint,
                               double cpuLoad) {
    }

    public record Decision(long delayMillis, double throttleLevel) {
        public Duration delay() {
            return Duration.ofMillis(delayMillis);
        }
    }
}