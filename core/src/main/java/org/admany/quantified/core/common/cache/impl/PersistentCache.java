package org.admany.quantified.core.common.cache.impl;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.AccessDeniedException;
import java.util.zip.ZipException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.admany.quantified.core.common.threading.core.WorkerClassLoaderContext;
import org.admany.quantified.core.common.util.QuantifiedPaths;

public class PersistentCache<K, V> implements ThreadSafeCache<K, V> {

    private static final Logger LOGGER = Logger.getLogger(PersistentCache.class.getName());
    public static Path cacheRootDirectory() {
        QuantifiedPaths.ensureCacheLayout();
        return QuantifiedPaths.getCacheDir();
    }

    private final ThreadSafeCache<K, V> delegate;
    private final String modId;
    private final String cacheName;
    private final Path cacheFile;
    private final boolean compression;
    private static final long SAVE_DEBOUNCE_MS = Math.max(250L, Long.getLong("quantified.cache.save_debounce_ms", 1500L));

    private static final ScheduledExecutorService IO_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
        WorkerClassLoaderContext.wrap(r -> {
            Thread t = new Thread(r, "quantified-cache-io");
            t.setDaemon(true);
            return t;
        }));

    private static final ConcurrentMap<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private Object fileLock() {
        Path normalized = cacheFile.toAbsolutePath().normalize();
        return FILE_LOCKS.computeIfAbsent(normalized, k -> new Object());
    }

    private final Object stateLock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean saveInFlight = new AtomicBoolean(false);
    private final AtomicBoolean loadScheduled = new AtomicBoolean(false);
    private final AtomicBoolean loadSuppressed = new AtomicBoolean(false);
    private final CountDownLatch initialLoadLatch = new CountDownLatch(1);
    private volatile ScheduledFuture<?> pendingSaveFuture;
    private volatile boolean closed = false;

    public PersistentCache(ThreadSafeCache<K, V> delegate, String modId, String cacheName, boolean compression) {
        this(delegate, modId, cacheName, compression, cacheRootDirectory());
    }

    public PersistentCache(ThreadSafeCache<K, V> delegate, String modId, String cacheName, boolean compression, Path baseDir) {
        this.delegate = delegate;
        this.modId = modId;
        this.cacheName = cacheName;
        this.compression = compression;

        Path modDir = baseDir.resolve(modId);
        this.cacheFile = modDir.resolve(cacheName + ".cache");

        try {
            Files.createDirectories(modDir);
            scheduleLoadFromDiskAsync();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create cache directory or load cache for " + modId + "/" + cacheName, e);
            initialLoadLatch.countDown();
        }
    }

    @Override
    public V getIfPresent(K key) {
        awaitInitialLoad();
        return delegate.getIfPresent(key);
    }

    @Override
    public V get(K key, java.util.function.Function<? super K, ? extends V> mappingFunction) {
        awaitInitialLoad();
        AtomicBoolean loaded = new AtomicBoolean(false);
        V value = delegate.get(key, candidate -> {
            V computed = mappingFunction.apply(candidate);
            if (computed != null) {
                loaded.set(true);
            }
            return computed;
        });
        if (loaded.get()) {
            saveToDiskAsync();
        }
        return value;
    }

    @Override
    public void put(K key, V value) {
        awaitInitialLoad();
        delegate.put(key, value);
        saveToDiskAsync();
    }

    @Override
    public void invalidate(K key) {
        awaitInitialLoad();
        delegate.invalidate(key);
        saveToDiskAsync();
    }

    @Override
    public void invalidateAll() {
        loadSuppressed.set(true);
        cancelPendingSave();
        delegate.invalidateAll();
        synchronized (fileLock()) {
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to delete cache file: " + cacheFile, e);
            }
        }
        releaseFileLockReference();
    }

    @Override
    public long size() {
        awaitInitialLoad();
        return delegate.size();
    }

    @Override
    public Map<K, V> snapshot() {
        awaitInitialLoad();
        return delegate.snapshot();
    }

    @Override
    public Optional<CacheStats> stats() {
        awaitInitialLoad();
        return delegate.stats();
    }

    @Override
    public void pruneIdleEntries(Duration idleThreshold) {
        awaitInitialLoad();
        delegate.pruneIdleEntries(idleThreshold);
    }

    @Override
    public void close() {
        awaitInitialLoad();
        closed = true;
        loadSuppressed.set(true);
        cancelPendingSave();

        long deadline = System.currentTimeMillis() + 5000L;
        synchronized (stateLock) {
            while (saveInFlight.get() && System.currentTimeMillis() < deadline) {
                try {
                    stateLock.wait(50L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        saveToDisk(); 
        delegate.close();
        releaseFileLockReference();
    }

    private void scheduleLoadFromDiskAsync() {
        if (!loadScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            IO_EXECUTOR.execute(() -> {
                try {
                    loadFromDisk();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to load cache from disk asynchronously: " + cacheFile, e);
                } finally {
                    initialLoadLatch.countDown();
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to schedule async cache load for " + cacheFile, e);
            initialLoadLatch.countDown();
        }
    }

    private void awaitInitialLoad() {
        if (!loadScheduled.get()) {
            return;
        }
        long waitMs = Long.getLong("quantified.cache.initial_load_wait_ms", 5000L);
        if (waitMs < 0L) {
            waitMs = 0L;
        }
        try {
            if (waitMs == 0L) {
                initialLoadLatch.await();
            } else {
                boolean completed = initialLoadLatch.await(waitMs, TimeUnit.MILLISECONDS);
                if (!completed) {
                    LOGGER.log(Level.WARNING,
                        "Persistent cache initial load timed out after {0}ms for {1}/{2} ({3}). Proceeding with in-memory state until hydrate completes.",
                        new Object[]{waitMs, modId, cacheName, cacheFile});
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (closed || loadSuppressed.get()) {
            return;
        }
        if (!Files.exists(cacheFile)) {
            return;
        }

        synchronized (fileLock()) {
            try (InputStream fis = Files.newInputStream(cacheFile);
                 InputStream is = compression ? new GZIPInputStream(fis) : fis;
                 ObjectInputStream ois = new ObjectInputStream(is)) {

                Map<K, V> data = (Map<K, V>) ois.readObject();
                for (Map.Entry<K, V> entry : data.entrySet()) {
                    if (closed || loadSuppressed.get()) {
                        break;
                    }
                    try {
                        // Runtime writes win over disk hydrate if they landed before initial load completed.
                        if (delegate.getIfPresent(entry.getKey()) != null) {
                            LOGGER.log(Level.FINEST,
                                "Skipping disk cache entry because in-memory value already exists for {0}/{1}: {2}",
                                new Object[]{modId, cacheName, entry.getKey()});
                            continue;
                        }
                        delegate.put(entry.getKey(), entry.getValue());
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to load cache entry: " + entry.getKey(), e);
                    }
                }

                LOGGER.log(Level.FINE, "Loaded {0} entries from disk cache for {1}/{2}",
                    new Object[]{data.size(), modId, cacheName});

            } catch (EOFException | StreamCorruptedException | ZipException e) {
                Path quarantined = quarantineCorruptCacheFile(e);
                LOGGER.log(Level.WARNING,
                    "Failed to load cache from disk (corrupt/truncated): {0}{1}. Starting with empty cache. Cause={2}: {3}",
                    new Object[]{
                        cacheFile,
                        quarantined != null ? " (moved to " + quarantined + ")" : "",
                        e.getClass().getSimpleName(),
                        e.getMessage()
                    });
            } catch (IOException e) {
                if (looksLikeTruncatedGzip(e)) {
                    Path quarantined = quarantineCorruptCacheFile(e);
                    LOGGER.log(Level.WARNING,
                        "Failed to load cache from disk (corrupt/truncated): {0}{1}. Starting with empty cache. Cause={2}: {3}",
                        new Object[]{
                            cacheFile,
                            quarantined != null ? " (moved to " + quarantined + ")" : "",
                            e.getClass().getSimpleName(),
                            e.getMessage()
                        });
                } else {
                    LOGGER.log(Level.WARNING,
                        String.format("Failed to load cache from disk: %s. This may be due to serialization incompatibility.", cacheFile), e);
                    quarantineCorruptCacheFile(e);
                }
            } catch (ClassNotFoundException e) {
                LOGGER.log(Level.WARNING,
                    String.format("Failed to load cache from disk: %s. Missing class during deserialization.", cacheFile), e);
                quarantineCorruptCacheFile(e);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                    String.format("Failed to load cache from disk: %s. This may be due to serialization incompatibility.", cacheFile), e);
                quarantineCorruptCacheFile(e);
            }
        }
    }

    private void saveToDiskAsync() {
        if (closed) {
            return;
        }
        dirty.set(true);
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            ScheduledFuture<?> pending = pendingSaveFuture;
            if (pending != null && !pending.isDone()) {
                pending.cancel(false);
            }
            pendingSaveFuture = IO_EXECUTOR.schedule(this::runSaveLoop, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void runSaveLoop() {
        if (!saveInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            while (!closed && dirty.getAndSet(false)) {
                saveToDisk();
            }
        } finally {
            saveInFlight.set(false);
            synchronized (stateLock) {
                pendingSaveFuture = null;
                stateLock.notifyAll();
            }

            if (!closed && dirty.get()) {
                saveToDiskAsync();
            }
        }
    }

    private void saveToDisk() {
        synchronized (fileLock()) {
            try {
                Map<K, V> snapshot = delegate.snapshot();
                if (snapshot.isEmpty()) {
                    Files.deleteIfExists(cacheFile);
                    return;
                }

                Map<K, V> serializableData = new HashMap<>();
                for (Map.Entry<K, V> entry : snapshot.entrySet()) {
                    if (entry.getKey() instanceof Serializable && entry.getValue() instanceof Serializable) {
                        serializableData.put(entry.getKey(), entry.getValue());
                    } else {
                        LOGGER.log(Level.FINEST, "Skipping non-serializable cache entry: {0}", entry.getKey());
                    }
                }

                if (serializableData.isEmpty()) {
                    LOGGER.log(Level.FINE, "No serializable data to save for cache {0}/{1}",
                        new Object[]{modId, cacheName});
                    return;
                }

                Path parent = cacheFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                // Write to a temp file first, then atomically replace.
                Path tmp = cacheFile.resolveSibling(cacheFile.getFileName().toString() + ".tmp");
                try (OutputStream fos = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                     OutputStream os = compression ? new GZIPOutputStream(fos) : fos;
                     ObjectOutputStream oos = new ObjectOutputStream(os)) {

                    oos.writeObject(serializableData);
                }

                moveTempIntoPlaceWithRetries(tmp, cacheFile);

            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, String.format("Failed to save cache to disk: %s", cacheFile), e);
            }
        }
    }

    private void cancelPendingSave() {
        synchronized (stateLock) {
            ScheduledFuture<?> pending = pendingSaveFuture;
            if (pending != null && !pending.isDone()) {
                pending.cancel(false);
            }
            pendingSaveFuture = null;
        }
    }

    private void releaseFileLockReference() {
        Path normalized = cacheFile.toAbsolutePath().normalize();
        FILE_LOCKS.remove(normalized);
    }

    private static void moveTempIntoPlaceWithRetries(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                last = e;
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                }
            } catch (IOException e) {
                last = e;
            }

            try {
                Thread.sleep(25L + (long) attempt * 50L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (last != null) {
            throw last;
        }
        throw new IOException("Failed to move temp cache file into place: " + tmp + " -> " + target);
    }

    private static boolean looksLikeTruncatedGzip(IOException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("Unexpected end of ZLIB input stream") || msg.contains("unexpected end of ZLIB input stream");
    }

    private Path quarantineCorruptCacheFile(Exception cause) {
        try {
            if (!Files.exists(cacheFile)) {
                return null;
            }
            String suffix = ".corrupt-" + System.currentTimeMillis();
            Path quarantined = cacheFile.resolveSibling(cacheFile.getFileName().toString() + suffix);
            Files.move(cacheFile, quarantined, StandardCopyOption.REPLACE_EXISTING);
            return quarantined;
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException deleteError) {
            }
            LOGGER.log(Level.FINE, String.format("Failed to quarantine corrupted cache file: %s", cacheFile), ex);
            return null;
        }
    }
}
