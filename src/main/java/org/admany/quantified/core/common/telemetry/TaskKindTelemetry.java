package org.admany.quantified.core.common.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TaskKindTelemetry {
    public static final String INTERNAL_CPU_BATCH_PREFIX = "__cpu_batch__:";
    private static final long DEFAULT_WINDOW_MS = 5 * 60 * 1000L;
    private static final int MAX_EVENTS = 5000;
    private static final int MAX_BATCH_EVENTS = 2000;
    private static final int MAX_ENTRIES = 16;
    private static final Object LOCK = new Object();
    private static final Deque<Event> EVENTS = new ArrayDeque<>();
    private static final Deque<BatchEvent> BATCH_EVENTS = new ArrayDeque<>();

    private TaskKindTelemetry() {
    }

    public static void recordMultithreading(String modId, String taskName) {
        record(modId, taskName, Route.MULTITHREADING);
    }

    public static void recordParallel(String modId, String taskName) {
        record(modId, taskName, Route.PARALLEL);
    }

    public static void recordGpu(String modId, String taskName) {
        record(modId, taskName, Route.GPU_ACCEL);
    }

    public static void recordBatch(String modId, String taskName, int batchSize) {
        if (batchSize <= 1) {
            return;
        }
        String safeMod = normalize(modId, "unknown-mod");
        String safeTask = normalize(taskName, "unknown-task");
        long now = System.currentTimeMillis();
        int safeSize = Math.max(1, batchSize);
        synchronized (LOCK) {
            BATCH_EVENTS.addLast(new BatchEvent(now, safeMod, safeTask, Route.PARALLEL, safeSize));
            while (BATCH_EVENTS.size() > MAX_BATCH_EVENTS) {
                BATCH_EVENTS.removeFirst();
            }
        }
    }

    public static Snapshot snapshot() {
        return snapshot(DEFAULT_WINDOW_MS, MAX_ENTRIES);
    }

    public static Snapshot snapshot(long windowMs, int maxEntries) {
        long now = System.currentTimeMillis();
        long cutoff = now - Math.max(1L, windowMs);
        Map<String, KindStats> aggregate = new HashMap<>();
        synchronized (LOCK) {
            while (!EVENTS.isEmpty() && EVENTS.peekFirst().timestampMs < cutoff) {
                EVENTS.removeFirst();
            }
            while (!BATCH_EVENTS.isEmpty() && BATCH_EVENTS.peekFirst().timestampMs < cutoff) {
                BATCH_EVENTS.removeFirst();
            }
            for (Event event : EVENTS) {
                String key = event.modId + "::" + event.taskName + "::" + event.route.name();
                KindStats stats = aggregate.computeIfAbsent(
                    key,
                    ignored -> new KindStats(event.modId, event.taskName, event.route.label, 0L, 0L, 0L, 0L, 0)
                );
                stats.count++;
                stats.lastSeenMs = Math.max(stats.lastSeenMs, event.timestampMs);
            }
            for (BatchEvent event : BATCH_EVENTS) {
                String key = event.modId + "::" + event.taskName + "::" + event.route.name();
                KindStats stats = aggregate.computeIfAbsent(
                    key,
                    ignored -> new KindStats(event.modId, event.taskName, event.route.label, 0L, 0L, 0L, 0L, 0)
                );
                stats.batchCount++;
                stats.batchTotal += event.batchSize;
                stats.batchMax = Math.max(stats.batchMax, event.batchSize);
                stats.lastSeenMs = Math.max(stats.lastSeenMs, event.timestampMs);
            }
        }
        List<KindStats> stats = new ArrayList<>(aggregate.values());
        stats.sort(Comparator
            .comparingLong((KindStats entry) -> entry.count)
            .reversed()
            .thenComparing(entry -> entry.taskName, String.CASE_INSENSITIVE_ORDER));
        if (stats.size() > maxEntries) {
            stats = stats.subList(0, maxEntries);
        }
        return new Snapshot(windowMs, stats);
    }

    public static boolean isInternalBatchName(String taskName) {
        if (taskName == null) {
            return false;
        }
        return taskName.startsWith(INTERNAL_CPU_BATCH_PREFIX);
    }

    private static void record(String modId, String taskName, Route route) {
        String safeMod = normalize(modId, "unknown-mod");
        String safeTask = normalize(taskName, "unknown-task");
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            EVENTS.addLast(new Event(now, safeMod, safeTask, route));
            while (EVENTS.size() > MAX_EVENTS) {
                EVENTS.removeFirst();
            }
        }
    }

    private static String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public enum Route {
        MULTITHREADING("Multithreading"),
        PARALLEL("Parallel"),
        GPU_ACCEL("GPU Accel");

        public final String label;

        Route(String label) {
            this.label = label;
        }
    }

    private record Event(long timestampMs, String modId, String taskName, Route route) {
    }

    private record BatchEvent(long timestampMs, String modId, String taskName, Route route, int batchSize) {
    }

    public static final class KindStats {
        public final String modId;
        public final String taskName;
        public final String route;
        public long count;
        public long lastSeenMs;
        public long batchCount;
        public long batchTotal;
        public int batchMax;

        private KindStats(String modId, String taskName, String route, long count, long lastSeenMs, long batchCount, long batchTotal, int batchMax) {
            this.modId = modId;
            this.taskName = taskName;
            this.route = route;
            this.count = count;
            this.lastSeenMs = lastSeenMs;
            this.batchCount = batchCount;
            this.batchTotal = batchTotal;
            this.batchMax = batchMax;
        }
    }

    public record Snapshot(long windowMs, List<KindStats> entries) {
    }
}
