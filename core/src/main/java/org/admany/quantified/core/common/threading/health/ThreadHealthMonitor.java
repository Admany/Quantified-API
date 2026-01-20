package org.admany.quantified.core.common.threading.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.admany.quantified.core.common.threading.core.ThreadRole;

public final class ThreadHealthMonitor {

    private static final Logger LOGGER = Logger.getLogger(ThreadHealthMonitor.class.getName());

    private static final ConcurrentMap<Long, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<ThreadRole, AtomicInteger> ROLE_COUNTS = new EnumMap<>(ThreadRole.class);

    private static volatile Duration heartbeatTolerance = Duration.ofSeconds(5);

    static {
        for (ThreadRole role : ThreadRole.values()) {
            ROLE_COUNTS.put(role, new AtomicInteger());
        }
    }

    private ThreadHealthMonitor() {
    }

    public static void setHeartbeatTolerance(Duration tolerance) {
        if (tolerance == null || tolerance.isZero() || tolerance.isNegative()) {
            throw new IllegalArgumentException("Heartbeat tolerance must be positive");
        }
        heartbeatTolerance = tolerance;
    }

    public static void register(Thread thread, ThreadRole role) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(role, "role");
        long id = safeThreadId(thread);
        Entry entry = new Entry(id, thread.getName(), role);
        ENTRIES.put(id, entry);
        ROLE_COUNTS.getOrDefault(role, ROLE_COUNTS.get(ThreadRole.UNKNOWN)).incrementAndGet();
        LOGGER.log(Level.FINEST, "Registered thread {0} as {1}", new Object[]{thread.getName(), role});
    }

    public static void unregister(Thread thread) {
        if (thread == null) {
            return;
        }
        Entry removed = ENTRIES.remove(safeThreadId(thread));
        if (removed != null) {
            ROLE_COUNTS.get(removed.role).decrementAndGet();
            removed.terminated = true;
            removed.lastHeartbeat = Instant.now();
        }
    }

    public static void heartbeat(ThreadRole role) {
        Thread current = Thread.currentThread();
        long id = safeThreadId(current);
        Entry entry = ENTRIES.computeIfAbsent(id, key -> new Entry(key, current.getName(), role == null ? ThreadRole.UNKNOWN : role));
        entry.role = role == null ? ThreadRole.UNKNOWN : role;
        entry.lastHeartbeat = Instant.now();
        entry.lastState = current.getState();
    }

    public static void recordCrash(ThreadRole role, Throwable throwable) {
        Thread thread = Thread.currentThread();
        long id = safeThreadId(thread);
        Entry entry = ENTRIES.computeIfAbsent(id, key -> new Entry(key, thread.getName(), role == null ? ThreadRole.UNKNOWN : role));
        entry.crashCount.incrementAndGet();
        entry.lastHeartbeat = Instant.now();
    }

    private static long safeThreadId(Thread thread) {
        if (thread == null) {
            return -1L;
        }
        try {
            java.lang.reflect.Method threadId = Thread.class.getMethod("threadId");
            Object value = threadId.invoke(thread);
            if (value instanceof Long) {
                return (Long) value;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method getId = Thread.class.getMethod("getId");
            Object value = getId.invoke(thread);
            if (value instanceof Long) {
                return (Long) value;
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    public static Optional<ThreadHealthStatus> status(long threadId) {
        Entry entry = ENTRIES.get(threadId);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(entry.toStatus());
    }

    public static ThreadHealthSnapshot snapshot() {
        List<ThreadHealthStatus> statuses = new ArrayList<>(ENTRIES.size());
        int stalled = 0;
        int terminated = 0;
        Instant now = Instant.now();
        for (Entry entry : ENTRIES.values()) {
            ThreadHealthStatus status = entry.toStatus(now);
            statuses.add(status);
            if (status.state() == ThreadHealthState.STALLED) {
                stalled++;
            } else if (status.state() == ThreadHealthState.TERMINATED) {
                terminated++;
            }
        }
        statuses.sort((a, b) -> a.threadName().compareToIgnoreCase(b.threadName()));
        return new ThreadHealthSnapshot(now, statuses, stalled, terminated);
    }

    public static Map<ThreadRole, Integer> roleCounts() {
        Map<ThreadRole, Integer> counts = new EnumMap<>(ThreadRole.class);
        for (Map.Entry<ThreadRole, AtomicInteger> entry : ROLE_COUNTS.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().get());
        }
        return counts;
    }

    private static ThreadHealthState deriveState(Entry entry, Instant now) {
        if (entry.terminated) {
            return ThreadHealthState.TERMINATED;
        }
        Duration sinceHeartbeat = Duration.between(entry.lastHeartbeat, now);
        if (sinceHeartbeat.compareTo(heartbeatTolerance) > 0) {
            return ThreadHealthState.STALLED;
        }
        if (entry.lastState == Thread.State.WAITING || entry.lastState == Thread.State.TIMED_WAITING) {
            return ThreadHealthState.IDLE;
        }
        return ThreadHealthState.HEALTHY;
    }

    private static final class Entry {
        final long threadId;
        final String threadName;
        final AtomicInteger crashCount = new AtomicInteger();
        volatile ThreadRole role;
        volatile Instant lastHeartbeat;
        volatile Thread.State lastState;
        volatile boolean terminated;

        Entry(long threadId, String threadName, ThreadRole role) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.role = role == null ? ThreadRole.UNKNOWN : role;
            this.lastHeartbeat = Instant.now();
            this.lastState = Thread.State.NEW;
        }

        ThreadHealthStatus toStatus() {
            return toStatus(Instant.now());
        }

        ThreadHealthStatus toStatus(Instant now) {
            ThreadHealthState state = deriveState(this, now);
            Duration idleDuration = Duration.between(lastHeartbeat, now);
            if (idleDuration.isNegative()) {
                idleDuration = Duration.ZERO;
            }
            return new ThreadHealthStatus(
                threadId,
                threadName,
                role,
                lastHeartbeat,
                idleDuration,
                crashCount.get(),
                state);
        }
    }
}
