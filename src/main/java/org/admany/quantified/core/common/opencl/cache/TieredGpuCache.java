package org.admany.quantified.core.common.opencl.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.opencl.core.OpenCLContext;
import org.admany.quantified.core.common.opencl.core.OpenCLManager.CLBuffer;
import org.admany.quantified.core.common.opencl.gpu.GPUDetector;
import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;
import org.admany.quantified.core.common.util.QuantifiedPaths;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TieredGpuCache {
    private static final Logger LOGGER = Logger.getLogger(TieredGpuCache.class.getName());
    private static final Gson DISK_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MANIFEST_TYPE = new TypeToken<List<ManifestRecord>>() { }.getType();

    private final GPUMonitor monitor;
    private final OpenCLContext context;
    private final Supplier<GPUDetector.GPUCapabilities> capabilitiesSupplier;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, CacheEntry> entries = new HashMap<>();
    private final LinkedHashMap<String, CacheEntry> accessOrder = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<String, DiskManifest> manifests = new HashMap<>();

    private static final double VRAM_TRIM_TRIGGER_FRACTION = 0.70d;
    private static final double VRAM_TRIM_TARGET_FRACTION = 0.60d;
    private static final double VRAM_RESUME_THRESHOLD_FRACTION = 0.55d;
    private static final long LOG_DEBOUNCE_MS = 4_000L;

    private long vramBudgetBytes;
    private long vramUsageBytes;
    private long ramUsageBytes;
    private boolean ramPromotionPaused;
    private boolean vramPromotionPaused;
    private long lastVramTrimLogMs;
    private long lastVramPauseLogMs;
    private final boolean keepVramResident = Boolean.parseBoolean(
        System.getProperty("quantified.gpu.cache.keep_vram", "true"));

    public TieredGpuCache(GPUMonitor monitor, OpenCLContext context, Supplier<GPUDetector.GPUCapabilities> capabilitiesSupplier) {
        this.monitor = monitor;
        this.context = context;
        this.capabilitiesSupplier = capabilitiesSupplier != null ? capabilitiesSupplier : () -> null;
        refreshVramBudget();
    }

    public void put(String modId, String key, ByteBuffer data) {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");

        ByteBuffer copy = data.duplicate();
        byte[] payload = new byte[copy.remaining()];
        copy.get(payload);
        long now = System.currentTimeMillis();

        lock.lock();
        try {
            MemoryPressure pressure = evaluateMemoryPressure();
            if (pressure.stage == Stage.STAGE3) {
                handleStage3Locked(pressure);
                return;
            }
            ensureResume(pressure);

            String composite = compositeKey(modId, key);
            CacheEntry existing = entries.remove(composite);
            if (existing != null) {
                accessOrder.remove(composite);
                removeEntry(existing, true);
            } else {
                manifestFor(modId).remove(key);
            }

            CacheEntry entry = new CacheEntry(modId, key, payload.length);
            setPayload(entry, payload);
            entry.lastAccessMs = now;
            entry.hitCount = 0;
            entry.tier = CacheTier.RAM;

            entries.put(composite, entry);
            accessOrder.put(composite, entry);

            refreshVramBudget();
            if (!isPromotionPaused() && tryPromoteToVram(entry)) {
                entry.tier = CacheTier.VRAM;
                if (keepVramResident) {
                    setPayload(entry, null);
                }
            }

            rebalance(pressure);
        } finally {
            lock.unlock();
        }
    }

    public CacheHit get(String modId, String key) {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(key, "key");

        lock.lock();
        try {
            String composite = compositeKey(modId, key);
            CacheEntry entry = entries.get(composite);
            if (entry == null) {
                DiskManifest manifest = manifestFor(modId);
                ManifestRecord record = manifest.recordFor(key);
                if (record != null) {
                    entry = registerDiskEntry(modId, key, manifest, record);
                }
            }
            if (entry == null) {
                return CacheHit.miss();
            }

            entry.lastAccessMs = System.currentTimeMillis();
            entry.hitCount++;
            touch(composite, entry);

            MemoryPressure pressure = evaluateMemoryPressure();
            if (pressure.stage == Stage.STAGE3) {
                handleStage3Locked(pressure);
            }

            if (entry.tier == CacheTier.DISK && loadFromDisk(entry)) {
                entry.tier = CacheTier.RAM;
            }

            if (entry.tier == CacheTier.RAM && !isPromotionPaused()) {
                tryPromoteToVram(entry);
            }

            ByteBuffer dataBuffer = null;
            if (entry.payload != null) {
                dataBuffer = ByteBuffer.wrap(Arrays.copyOf(entry.payload, entry.payload.length)).asReadOnlyBuffer();
            } else if (entry.buffer != null) {
                ByteBuffer tmp = ByteBuffer.allocateDirect((int) entry.sizeBytes);
                tmp.clear();
                entry.buffer.read(tmp, true);
                tmp.flip();
                dataBuffer = tmp.asReadOnlyBuffer();
            }
            CacheHit hit = new CacheHit(true, entry.tier, entry.buffer, dataBuffer, entry.diskPath);

            if (entry.diskPath != null) {
                manifestFor(entry.modId).record(entry);
            }

            rebalance(pressure);
            return hit;
        } finally {
            lock.unlock();
        }
    }

    public boolean has(String modId, String key) {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            String composite = compositeKey(modId, key);
            if (entries.containsKey(composite)) {
                return true;
            }
            return manifestFor(modId).has(key);
        } finally {
            lock.unlock();
        }
    }

    public void remove(String modId, String key) {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            String composite = compositeKey(modId, key);
            CacheEntry entry = entries.remove(composite);
            if (entry != null) {
                accessOrder.remove(composite);
                removeEntry(entry, true);
            } else {
                manifestFor(modId).remove(key);
            }
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            flushAllToDisk();
            entries.clear();
            accessOrder.clear();
            vramUsageBytes = 0L;
            ramUsageBytes = 0L;
            ramPromotionPaused = false;
            vramPromotionPaused = false;
            lastVramTrimLogMs = 0L;
            lastVramPauseLogMs = 0L;
            manifests.values().forEach(DiskManifest::saveIfDirty);
        } finally {
            lock.unlock();
        }
    }

    public long getVramUsageBytes() {
        lock.lock();
        try {
            return vramUsageBytes;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            flushAllToDisk();
            entries.values().forEach(entry -> removeEntry(entry, false));
            entries.clear();
            accessOrder.clear();
            ramPromotionPaused = false;
            vramPromotionPaused = false;
            lastVramTrimLogMs = 0L;
            lastVramPauseLogMs = 0L;
            manifests.values().forEach(DiskManifest::saveIfDirty);
        } finally {
            lock.unlock();
        }
    }

    private void flushAllToDisk() {
        for (CacheEntry entry : entries.values()) {
            if (entry.tier == CacheTier.VRAM) {
                demoteToRam(entry);
            }
            persistToDisk(entry, true);
        }
    }

    private DiskManifest manifestFor(String modId) {
        DiskManifest manifest = manifests.get(modId);
        if (manifest != null) {
            return manifest;
        }
        DiskManifest created = new DiskManifest(modId);
        manifests.put(modId, created);
        for (ManifestRecord record : created.records()) {
            registerDiskEntry(modId, record.key(), created, record);
        }
        return created;
    }

    private CacheEntry registerDiskEntry(String modId, String key, DiskManifest manifest, ManifestRecord record) {
        String composite = compositeKey(modId, key);
        CacheEntry entry = entries.get(composite);
        if (entry == null) {
            entry = new CacheEntry(modId, key, record.sizeBytes());
            entry.tier = CacheTier.DISK;
            entry.diskPath = manifest.resolve(record.file());
            entry.lastAccessMs = record.lastAccessMs();
            entry.hitCount = record.hitCount();
            entries.put(composite, entry);
            accessOrder.put(composite, entry);
        } else {
            entry.diskPath = manifest.resolve(record.file());
        }
        return entry;
    }

    private void rebalance(MemoryPressure pressure) {
        refreshVramBudget();
        if (vramUsageBytes > vramBudgetBytes) {
            demoteVramEntriesToRam(vramUsageBytes - vramBudgetBytes);
        }

        switch (pressure.stage) {
            case STAGE1 -> demoteVramEntriesForStage1();
            case STAGE2 -> {
                long target = pressure.maxMemory > 0 ? (long) (pressure.maxMemory * 0.80d) : 0L;
                long excess = target > 0 ? Math.max(0L, pressure.usedMemory - target) : 0L;
                demoteRamEntriesToDisk(excess);
            }
            case STAGE3 -> handleStage3Locked(pressure);
            default -> ensureResume(pressure);
        }

        manifests.values().forEach(DiskManifest::saveIfDirty);

        enforceVramPolicies();

            if (LOGGER.isLoggable(Level.FINER)) {
                double ramMb = ramUsageBytes / (1024.0d * 1024.0d);
                double vramMb = vramUsageBytes / (1024.0d * 1024.0d);
                LOGGER.finer(String.format(Locale.ROOT,
                    "Cache rebalance stage=%s ram=%.1f MB vram=%.1f MB entries=%d",
                    pressure.stage, ramMb, vramMb, entries.size()));
            }
    }

    private void ensureResume(MemoryPressure pressure) {
        if (ramPromotionPaused && pressure.ratio < 0.80d) {
            ramPromotionPaused = false;
            DeveloperOverlayManager.recordApiLog("[OpenCL] System memory stabilized; resuming GPU cache promotions");
        }
    }

    private void handleStage3Locked(MemoryPressure pressure) {
        if (!ramPromotionPaused) {
            ramPromotionPaused = true;
            DeveloperOverlayManager.recordApiLog("[OpenCL] System memory critical - flushing GPU cache to disk and pausing promotion");
        }
        flushAllToDisk();
        entries.values().forEach(entry -> {
            if (entry.tier != CacheTier.DISK) {
                entry.tier = CacheTier.DISK;
            }
            if (entry.payload != null) {
                setPayload(entry, null);
            }
            if (entry.buffer != null) {
                setBuffer(entry, null);
            }
        });
    }

    private void demoteVramEntriesForStage1() {
        int demoted = 0;
        Iterator<Map.Entry<String, CacheEntry>> iterator = accessOrder.entrySet().iterator();
        while (iterator.hasNext() && demoted < 2) {
            CacheEntry entry = iterator.next().getValue();
            if (entry.tier == CacheTier.VRAM) {
                demoteToRam(entry);
                entry.tier = CacheTier.RAM;
                demoted++;
            }
        }
    }

    private void demoteVramEntriesToRam(long bytesToFree) {
        if (bytesToFree <= 0) {
            return;
        }
        long freed = 0L;
        Iterator<Map.Entry<String, CacheEntry>> iterator = accessOrder.entrySet().iterator();
        while (iterator.hasNext() && freed < bytesToFree) {
            CacheEntry entry = iterator.next().getValue();
            if (entry.tier != CacheTier.VRAM) {
                continue;
            }
            long size = entry.sizeBytes;
            demoteToRam(entry);
            entry.tier = CacheTier.RAM;
            freed += size;
        }
    }

    private void demoteRamEntriesToDisk(long bytesToFree) {
        if (bytesToFree < 0) {
            bytesToFree = 0;
        }
        long freed = 0L;
        Iterator<Map.Entry<String, CacheEntry>> iterator = accessOrder.entrySet().iterator();
        while (iterator.hasNext() && (bytesToFree == 0 || freed < bytesToFree)) {
            CacheEntry entry = iterator.next().getValue();
            if (entry.tier == CacheTier.RAM || entry.tier == CacheTier.VRAM) {
                if (entry.tier == CacheTier.VRAM) {
                    demoteToRam(entry);
                }
                if (persistToDisk(entry, true)) {
                    entry.tier = CacheTier.DISK;
                    freed += entry.sizeBytes;
                }
            }
            if (bytesToFree == 0 && freed > 0L) {
                break;
            }
        }
    }

    private boolean tryPromoteToVram(CacheEntry entry) {
        if (isPromotionPaused()) {
            return false;
        }
        if (context == null || !context.isHealthy()) {
            return false;
        }
        if (entry.sizeBytes <= 0) {
            return false;
        }
        if (entry.buffer != null) {
            return true;
        }
        if (entry.sizeBytes > vramBudgetBytes) {
            return false;
        }
        if (vramUsageBytes + entry.sizeBytes > vramBudgetBytes) {
            demoteVramEntriesToRam(vramUsageBytes + entry.sizeBytes - vramBudgetBytes);
            if (vramUsageBytes + entry.sizeBytes > vramBudgetBytes) {
                return false;
            }
        }
        CLBuffer buffer = CLBuffer.createReadWrite(context, entry.sizeBytes);
        byte[] source = ensurePayloadBytes(entry);
        buffer.write(ByteBuffer.wrap(source), true);
        setBuffer(entry, buffer);
        entry.tier = CacheTier.VRAM;
        if (keepVramResident) {
            setPayload(entry, null);
        }
        return true;
    }

    private void demoteToRam(CacheEntry entry) {
        if (entry.buffer == null) {
            entry.tier = CacheTier.RAM;
            return;
        }
        byte[] payload = ensurePayloadBytes(entry);
        if (entry.payload == null) {
            setPayload(entry, payload);
        }
        setBuffer(entry, null);
        entry.tier = CacheTier.RAM;
    }

    private boolean persistToDisk(CacheEntry entry, boolean dropPayload) {
        try {
            DiskManifest manifest = manifestFor(entry.modId);
            Path target = entry.diskPath;
            if (target == null) {
                target = manifest.ensurePath(entry.key);
                entry.diskPath = target;
            }
            Files.createDirectories(target.getParent());
            byte[] data = ensurePayloadBytes(entry);
            try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))) {
                out.write(data);
            }
            if (dropPayload) {
                setPayload(entry, null);
            }
            entry.lastAccessMs = System.currentTimeMillis();
            manifest.record(entry);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to persist GPU cache entry " + entry.key + " for mod " + entry.modId, e);
            return false;
        }
    }

    private boolean loadFromDisk(CacheEntry entry) {
        Path path = entry.diskPath;
        if (path == null || !Files.exists(path)) {
            return false;
        }
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] data = in.readAllBytes();
            setPayload(entry, data);
            entry.tier = CacheTier.RAM;
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to read GPU cache entry " + entry.key + " for mod " + entry.modId, e);
            return false;
        }
    }

    private byte[] ensurePayloadBytes(CacheEntry entry) {
        if (entry.payload != null) {
            return Arrays.copyOf(entry.payload, entry.payload.length);
        }
        if (entry.buffer != null) {
            ByteBuffer tmp = ByteBuffer.allocateDirect((int) entry.sizeBytes);
            tmp.clear();
            entry.buffer.read(tmp, true);
            tmp.flip();
            byte[] data = new byte[tmp.remaining()];
            tmp.get(data);
            return data;
        }
        if (entry.diskPath != null && Files.exists(entry.diskPath)) {
            try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(entry.diskPath))) {
                return in.readAllBytes();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Failed to hydrate GPU cache entry " + entry.key + " for mod " + entry.modId, e);
            }
        }
        return new byte[0];
    }

    private void removeEntry(CacheEntry entry, boolean deleteDisk) {
        setBuffer(entry, null);
        if (deleteDisk && entry.diskPath != null) {
            try {
                Files.deleteIfExists(entry.diskPath);
            } catch (IOException ignored) {
            }
            DiskManifest manifest = manifests.get(entry.modId);
            if (manifest != null) {
                manifest.remove(entry.key);
            }
            entry.diskPath = null;
        }
        setPayload(entry, null);
    }

    private void setPayload(CacheEntry entry, byte[] payload) {
        if (entry.payload != null) {
            ramUsageBytes -= entry.payload.length;
        }
        entry.payload = payload;
        if (payload != null) {
            ramUsageBytes += payload.length;
            entry.sizeBytes = payload.length;
        }
    }

    private void setBuffer(CacheEntry entry, CLBuffer buffer) {
        if (entry.buffer != null) {
            vramUsageBytes -= entry.buffer.getSize();
            try {
                entry.buffer.close();
            } catch (Exception ignored) {
            }
        }
        entry.buffer = buffer;
        if (buffer != null) {
            vramUsageBytes += buffer.getSize();
        }
    }

    private void touch(String composite, CacheEntry entry) {
        accessOrder.remove(composite);
        accessOrder.put(composite, entry);
    }

    private void refreshVramBudget() {
        long budget = 0L;
        if (monitor != null) {
            GPUMonitor.GPUStatus status = monitor.getStatus();
            if (status != null) {
                budget = status.totalVramBytes();
            }
        }
        if (budget <= 0L) {
            GPUDetector.GPUCapabilities capabilities = capabilitiesSupplier.get();
            if (capabilities != null && capabilities.device() != null) {
                long deviceTotal = capabilities.device().vramBytes();
                budget = (long) Math.max(128L * 1024L * 1024L, deviceTotal * 0.25d);
            }
        }
        if (budget <= 0L) {
            budget = (long) Math.max(128L * 1024L * 1024L, (2L * 1024L * 1024L * 1024L) * 0.25d);
        }
        vramBudgetBytes = budget;
    }

    private MemoryPressure evaluateMemoryPressure() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long used = total - free;
        double ratio;
        if (max > 0 && max != Long.MAX_VALUE) {
            ratio = (double) used / max;
        } else if (total > 0) {
            ratio = (double) used / total;
        } else {
            ratio = 0.0d;
        }
        Stage stage;
        if (ratio >= 0.95d) {
            stage = Stage.STAGE3;
        } else if (ratio >= 0.85d) {
            stage = Stage.STAGE2;
        } else if (ratio >= 0.70d) {
            stage = Stage.STAGE1;
        } else {
            stage = Stage.STAGE0;
        }
        return new MemoryPressure(stage, ratio, max, used);
    }

    private static String compositeKey(String modId, String key) {
        return modId + '|' + key;
    }

    private record MemoryPressure(Stage stage, double ratio, long maxMemory, long usedMemory) {
    }

    private boolean isPromotionPaused() {
        return ramPromotionPaused || vramPromotionPaused;
    }

    private void enforceVramPolicies() {
        if (vramBudgetBytes <= 0L) {
            return;
        }

        double usageRatio = vramUsageBytes <= 0L ? 0.0d : (double) vramUsageBytes / vramBudgetBytes;

        if (usageRatio >= 1.0d) {
            if (!vramPromotionPaused) {
                vramPromotionPaused = true;
                logVramPause("VRAM cache paused - dedicated budget exhausted; routing new entries to RAM/disk");
            }
        } else if (vramPromotionPaused && usageRatio <= VRAM_RESUME_THRESHOLD_FRACTION) {
            vramPromotionPaused = false;
            lastVramPauseLogMs = 0L;
            logVramPause("VRAM cache resumed - usage back under 55% of budget");
        }

        if (usageRatio >= VRAM_TRIM_TRIGGER_FRACTION) {
            long targetUsage = Math.max(0L, (long) (vramBudgetBytes * VRAM_TRIM_TARGET_FRACTION));
            long excess = Math.max(0L, vramUsageBytes - targetUsage);
            if (excess > 0L) {
                demoteVramEntriesToRam(excess);
                if (shouldLogVramTrim()) {
                    DeveloperOverlayManager.recordApiLog("[OpenCL] VRAM cache trimmed to maintain headroom (70% budget trigger)");
                }
            }
        }
    }

    private boolean shouldLogVramTrim() {
        long now = System.currentTimeMillis();
        if (now - lastVramTrimLogMs >= LOG_DEBOUNCE_MS) {
            lastVramTrimLogMs = now;
            return true;
        }
        return false;
    }

    private void logVramPause(String message) {
        long now = System.currentTimeMillis();
        if (now - lastVramPauseLogMs >= LOG_DEBOUNCE_MS) {
            lastVramPauseLogMs = now;
            DeveloperOverlayManager.recordApiLog("[OpenCL] " + message);
        }
    }

    private enum Stage {
        STAGE0,
        STAGE1,
        STAGE2,
        STAGE3
    }

    private static final class CacheEntry {
        final String modId;
        final String key;
        long sizeBytes;
        long lastAccessMs;
        long hitCount;
        CacheTier tier;
        CLBuffer buffer;
        byte[] payload;
        Path diskPath;

        CacheEntry(String modId, String key, long sizeBytes) {
            this.modId = modId;
            this.key = key;
            this.sizeBytes = sizeBytes;
            this.lastAccessMs = System.currentTimeMillis();
            this.hitCount = 0L;
            this.tier = CacheTier.RAM;
        }
    }

    private static final class DiskManifest {
        private final String modId;
        private final Path cacheDir;
        private final Path manifestPath;
        private final Map<String, ManifestRecord> records = new HashMap<>();
        private boolean dirty;

        DiskManifest(String modId) {
            this.modId = modId;
            QuantifiedPaths.ensureCacheLayout();
            Path base = QuantifiedPaths.getCacheDir().resolve(modId);
            this.cacheDir = base.resolve("gpuCache");
            this.manifestPath = base.resolve("gpu-manifest.json");
            try {
                Files.createDirectories(cacheDir);
            } catch (IOException ignored) {
            }
            load();
        }

        void load() {
            if (!Files.exists(manifestPath)) {
                return;
            }
            try (Reader reader = new InputStreamReader(new BufferedInputStream(Files.newInputStream(manifestPath)), StandardCharsets.UTF_8)) {
                List<ManifestRecord> stored = DISK_GSON.fromJson(reader, MANIFEST_TYPE);
                if (stored != null) {
                    for (ManifestRecord record : stored) {
                        records.put(record.key(), record);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Failed to read GPU cache manifest for mod " + modId, e);
            }
        }

        List<ManifestRecord> records() {
            return new ArrayList<>(records.values());
        }

        ManifestRecord recordFor(String key) {
            return records.get(key);
        }

        boolean has(String key) {
            return records.containsKey(key);
        }

        void record(CacheEntry entry) {
            if (entry.diskPath == null) {
                return;
            }
            Path relative = cacheDir.relativize(entry.diskPath);
            ManifestRecord record = new ManifestRecord(
                entry.key,
                relative.toString(),
                entry.sizeBytes,
                entry.lastAccessMs,
                entry.hitCount);
            records.put(entry.key, record);
            dirty = true;
        }

        void remove(String key) {
            if (records.remove(key) != null) {
                dirty = true;
            }
        }

        Path ensurePath(String key) throws IOException {
            ManifestRecord existing = records.get(key);
            if (existing != null) {
                return cacheDir.resolve(existing.file());
            }
            Files.createDirectories(cacheDir);
            String filename = sanitize(key) + '-' + Integer.toHexString(key.hashCode()) + ".bin";
            Path path = cacheDir.resolve(filename);
            records.put(key, new ManifestRecord(key, filename, 0L, System.currentTimeMillis(), 0L));
            dirty = true;
            return path;
        }

        Path resolve(String relative) {
            return cacheDir.resolve(relative);
        }

        void saveIfDirty() {
            if (!dirty) {
                return;
            }
            try {
                Files.createDirectories(manifestPath.getParent());
                try (Writer writer = new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(
                    manifestPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)), StandardCharsets.UTF_8)) {
                    DISK_GSON.toJson(records.values(), MANIFEST_TYPE, writer);
                }
                dirty = false;
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Failed to write GPU cache manifest for mod " + modId, e);
            }
        }

        private static String sanitize(String key) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if ((c >= 'a' && c <= 'z') ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') ||
                    c == '-' || c == '_' || c == '.') {
                    builder.append(c);
                } else {
                    builder.append('_');
                }
            }
            return builder.toString();
        }
    }

    private record ManifestRecord(String key, String file, long sizeBytes, long lastAccessMs, long hitCount) {
    }

    public enum CacheTier {
        VRAM,
        RAM,
        DISK,
        MISS
    }

    public record CacheHit(boolean present,
                           CacheTier tier,
                           CLBuffer buffer,
                           ByteBuffer data,
                           Path diskPath) {
        public static CacheHit miss() {
            return new CacheHit(false, CacheTier.MISS, null, null, null);
        }
    }
}


