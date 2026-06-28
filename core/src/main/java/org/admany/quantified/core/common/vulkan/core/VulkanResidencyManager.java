package org.admany.quantified.core.common.vulkan.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class VulkanResidencyManager {

    private static final long VRAM_PRESSURE_COOLDOWN_MS = 2500L;
    private static final long PRESSURE_LOG_INTERVAL_MS = 5000L;
    private static final double VRAM_SOFT_PRESSURE_FRACTION = 0.72d;
    private static final double VRAM_HARD_PRESSURE_FRACTION = 0.82d;
    private static final long WORKSPACE_IDLE_SOFT_NANOS = 20000000000L;
    private static final long WORKSPACE_IDLE_MEDIUM_NANOS = 8000000000L;
    private static final long WORKSPACE_IDLE_HARD_NANOS = 2000000000L;
    private static final long SLAB_IDLE_SOFT_NANOS = 30000000000L;
    private static final long SLAB_IDLE_MEDIUM_NANOS = 10000000000L;
    private static final long SLAB_IDLE_HARD_NANOS = 2000000000L;
    private static final AtomicLong TRIM_EVENTS = new AtomicLong();
    private static final AtomicLong TRIMMED_BYTES = new AtomicLong();
    private static final AtomicLong TRIMMED_WORKSPACE_POOLS = new AtomicLong();
    private static final AtomicLong TRIMMED_WORKSPACE_BYTES = new AtomicLong();
    private static final AtomicLong TRIMMED_SLABS = new AtomicLong();
    private static final AtomicLong TRIMMED_SLAB_BYTES = new AtomicLong();
    private static final AtomicLong PRESSURE_COOLDOWNS = new AtomicLong();
    private static final AtomicLong PRESSURE_COOLDOWN_HITS = new AtomicLong();
    private static final AtomicLong PRESSURE_REJECTS = new AtomicLong();

    private VulkanResidencyManager() {
    }

    static long pressureCooldownMs() {
        return VRAM_PRESSURE_COOLDOWN_MS;
    }

    static long pressureLogIntervalMs() {
        return PRESSURE_LOG_INTERVAL_MS;
    }

    static long softLimitBytes(long localMemoryBytes, long minimumBytes) {
        return Math.max(minimumBytes, (long) (localMemoryBytes * VRAM_SOFT_PRESSURE_FRACTION));
    }

    static long hardLimitBytes(long localMemoryBytes, long minimumBytes) {
        return Math.max(minimumBytes, (long) (localMemoryBytes * VRAM_HARD_PRESSURE_FRACTION));
    }

    static boolean isFrameSensitiveThread(Thread thread) {
        String name = thread.getName().toLowerCase(Locale.ROOT);
        return name.contains("render")
            || name.contains("server")
            || name.contains("client")
            || name.contains("game")
            || name.contains("main")
            || name.contains("worker-main")
            || name.contains("modloading");
    }

    static TrimLevel trimLevel(boolean aggressive) {
        return aggressive ? TrimLevel.HARD : TrimLevel.SOFT;
    }

    static void noteTrim(TrimResult result) {
        if (result == null || result.totalFreedBytes() <= 0L) {
            return;
        }
        TRIM_EVENTS.incrementAndGet();
        TRIMMED_BYTES.addAndGet(result.totalFreedBytes());
        TRIMMED_WORKSPACE_POOLS.addAndGet(result.workspacePoolsFreed());
        TRIMMED_WORKSPACE_BYTES.addAndGet(result.workspaceBytesFreed());
        TRIMMED_SLABS.addAndGet(result.slabsFreed());
        TRIMMED_SLAB_BYTES.addAndGet(result.slabBytesFreed());
    }

    static void notePressureCooldown() {
        PRESSURE_COOLDOWNS.incrementAndGet();
    }

    static void notePressureCooldownHit() {
        PRESSURE_COOLDOWN_HITS.incrementAndGet();
    }

    static void notePressureReject() {
        PRESSURE_REJECTS.incrementAndGet();
    }

    static Snapshot snapshot(long reservedBytes,
                             long localMemoryBytes,
                             int slabCount,
                             int workspacePoolCount,
                             long workspacePoolBytes,
                             boolean cooldownActive,
                             long cooldownRemainingMs) {
        long softLimitBytes = localMemoryBytes > 0L ? softLimitBytes(localMemoryBytes, 0L) : 0L;
        long hardLimitBytes = localMemoryBytes > 0L ? hardLimitBytes(localMemoryBytes, 0L) : 0L;
        return new Snapshot(
            reservedBytes,
            localMemoryBytes,
            softLimitBytes,
            hardLimitBytes,
            slabCount,
            workspacePoolCount,
            workspacePoolBytes,
            cooldownActive,
            cooldownRemainingMs,
            TRIM_EVENTS.get(),
            TRIMMED_BYTES.get(),
            TRIMMED_WORKSPACE_POOLS.get(),
            TRIMMED_WORKSPACE_BYTES.get(),
            TRIMMED_SLABS.get(),
            TRIMMED_SLAB_BYTES.get(),
            PRESSURE_COOLDOWNS.get(),
            PRESSURE_COOLDOWN_HITS.get(),
            PRESSURE_REJECTS.get()
        );
    }

    enum TrimLevel {
        SOFT(1, WORKSPACE_IDLE_SOFT_NANOS, SLAB_IDLE_SOFT_NANOS),
        MEDIUM(1, WORKSPACE_IDLE_MEDIUM_NANOS, SLAB_IDLE_MEDIUM_NANOS),
        HARD(0, WORKSPACE_IDLE_HARD_NANOS, SLAB_IDLE_HARD_NANOS);

        private final int keepFreeSlabsPerClass;
        private final long workspaceIdleNanos;
        private final long slabIdleNanos;

        TrimLevel(int keepFreeSlabsPerClass, long workspaceIdleNanos, long slabIdleNanos) {
            this.keepFreeSlabsPerClass = keepFreeSlabsPerClass;
            this.workspaceIdleNanos = workspaceIdleNanos;
            this.slabIdleNanos = slabIdleNanos;
        }

        int keepFreeSlabsPerClass() {
            return this.keepFreeSlabsPerClass;
        }

        long workspaceIdleNanos() {
            return this.workspaceIdleNanos;
        }

        long slabIdleNanos() {
            return this.slabIdleNanos;
        }
    }

    static final class TrimResult {
        private static final TrimResult NONE = new TrimResult(0L, 0L, 0L, 0L);

        private final long workspacePoolsFreed;
        private final long workspaceBytesFreed;
        private final long slabsFreed;
        private final long slabBytesFreed;

        private TrimResult(long workspacePoolsFreed, long workspaceBytesFreed, long slabsFreed, long slabBytesFreed) {
            this.workspacePoolsFreed = workspacePoolsFreed;
            this.workspaceBytesFreed = workspaceBytesFreed;
            this.slabsFreed = slabsFreed;
            this.slabBytesFreed = slabBytesFreed;
        }

        static TrimResult none() {
            return NONE;
        }

        static TrimResult of(long workspacePoolsFreed, long workspaceBytesFreed, long slabsFreed, long slabBytesFreed) {
            if (workspacePoolsFreed == 0L && workspaceBytesFreed == 0L && slabsFreed == 0L && slabBytesFreed == 0L) {
                return NONE;
            }
            return new TrimResult(workspacePoolsFreed, workspaceBytesFreed, slabsFreed, slabBytesFreed);
        }

        TrimResult merge(TrimResult other) {
            if (other == null || other == NONE) {
                return this;
            }
            if (this == NONE) {
                return other;
            }
            return new TrimResult(
                this.workspacePoolsFreed + other.workspacePoolsFreed,
                this.workspaceBytesFreed + other.workspaceBytesFreed,
                this.slabsFreed + other.slabsFreed,
                this.slabBytesFreed + other.slabBytesFreed
            );
        }

        long workspacePoolsFreed() {
            return this.workspacePoolsFreed;
        }

        long workspaceBytesFreed() {
            return this.workspaceBytesFreed;
        }

        long slabsFreed() {
            return this.slabsFreed;
        }

        long slabBytesFreed() {
            return this.slabBytesFreed;
        }

        long totalFreedBytes() {
            return this.workspaceBytesFreed + this.slabBytesFreed;
        }
    }

    static final class Snapshot {
        private final long reservedBytes;
        private final long localMemoryBytes;
        private final long softLimitBytes;
        private final long hardLimitBytes;
        private final int slabCount;
        private final int workspacePoolCount;
        private final long workspacePoolBytes;
        private final boolean cooldownActive;
        private final long cooldownRemainingMs;
        private final long trimEvents;
        private final long trimmedBytes;
        private final long trimmedWorkspacePools;
        private final long trimmedWorkspaceBytes;
        private final long trimmedSlabs;
        private final long trimmedSlabBytes;
        private final long pressureCooldowns;
        private final long pressureCooldownHits;
        private final long pressureRejects;

        private Snapshot(long reservedBytes,
                         long localMemoryBytes,
                         long softLimitBytes,
                         long hardLimitBytes,
                         int slabCount,
                         int workspacePoolCount,
                         long workspacePoolBytes,
                         boolean cooldownActive,
                         long cooldownRemainingMs,
                         long trimEvents,
                         long trimmedBytes,
                         long trimmedWorkspacePools,
                         long trimmedWorkspaceBytes,
                         long trimmedSlabs,
                         long trimmedSlabBytes,
                         long pressureCooldowns,
                         long pressureCooldownHits,
                         long pressureRejects) {
            this.reservedBytes = reservedBytes;
            this.localMemoryBytes = localMemoryBytes;
            this.softLimitBytes = softLimitBytes;
            this.hardLimitBytes = hardLimitBytes;
            this.slabCount = slabCount;
            this.workspacePoolCount = workspacePoolCount;
            this.workspacePoolBytes = workspacePoolBytes;
            this.cooldownActive = cooldownActive;
            this.cooldownRemainingMs = cooldownRemainingMs;
            this.trimEvents = trimEvents;
            this.trimmedBytes = trimmedBytes;
            this.trimmedWorkspacePools = trimmedWorkspacePools;
            this.trimmedWorkspaceBytes = trimmedWorkspaceBytes;
            this.trimmedSlabs = trimmedSlabs;
            this.trimmedSlabBytes = trimmedSlabBytes;
            this.pressureCooldowns = pressureCooldowns;
            this.pressureCooldownHits = pressureCooldownHits;
            this.pressureRejects = pressureRejects;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("reservedBytes", this.reservedBytes);
            values.put("localMemoryBytes", this.localMemoryBytes);
            values.put("softLimitBytes", this.softLimitBytes);
            values.put("hardLimitBytes", this.hardLimitBytes);
            values.put("slabCount", this.slabCount);
            values.put("workspacePoolCount", this.workspacePoolCount);
            values.put("workspacePoolBytes", this.workspacePoolBytes);
            values.put("cooldownActive", this.cooldownActive);
            values.put("cooldownRemainingMs", this.cooldownRemainingMs);
            values.put("trimEvents", this.trimEvents);
            values.put("trimmedBytes", this.trimmedBytes);
            values.put("trimmedWorkspacePools", this.trimmedWorkspacePools);
            values.put("trimmedWorkspaceBytes", this.trimmedWorkspaceBytes);
            values.put("trimmedSlabs", this.trimmedSlabs);
            values.put("trimmedSlabBytes", this.trimmedSlabBytes);
            values.put("pressureCooldowns", this.pressureCooldowns);
            values.put("pressureCooldownHits", this.pressureCooldownHits);
            values.put("pressureRejects", this.pressureRejects);
            return values;
        }
    }
}
