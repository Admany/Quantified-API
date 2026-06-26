package org.admany.quantified.api;

import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.interfaces.ConnectedMod;
import org.admany.quantified.api.interfaces.ModCacheManager;
import org.admany.quantified.api.interfaces.ModConnectionListener;
import org.admany.quantified.api.interfaces.ModStatistics;
import org.admany.quantified.api.graph.QuantifiedTaskGraph;
import org.admany.quantified.api.model.QuantifiedPacket;
import org.admany.quantified.api.model.QuantifiedTask;
import org.admany.quantified.api.util.ForgeMetadataUtil;
import org.admany.quantified.api.util.ModMetadataResolver;
import org.admany.quantified.api.parallel.ParallelCompute;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.common.util.ConnectedModImpl;
import org.admany.quantified.core.compat.LegacyApiRegistry;

import java.time.Duration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public final class QuantifiedAPI {

    private static final long DEFAULT_CACHE_MAX_SIZE = 0L;

    private static final ThreadLocal<QuantifiedHandle> currentHandle = new ThreadLocal<>();
    private static final ConcurrentMap<String, QuantifiedHandle> handlesByMod = new ConcurrentHashMap<>();
    private static final Map<String, ConnectedMod> connectedMods = new ConcurrentHashMap<>();
    private static final List<ModConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    public static boolean register(String modId) {
        return register(modId, true);
    }

    /**
     * Registers a mod against Quantified API v2 and opts it out of the legacy fallback notice.
     */
    public static boolean registerV2(String modId) {
        if (modId != null && !modId.isBlank()) {
            LegacyApiRegistry.markV2(modId, modId);
        }
        return register(modId, false);
    }

    public static boolean registerV2(String modId, String displayName, String version) {
        if (modId != null && !modId.isBlank()) {
            LegacyApiRegistry.markV2(modId, displayName);
        }
        return register(modId, displayName, version, false);
    }

    private static boolean register(String modId, boolean legacySurface) {
        ModMetadataResolver.ResolvedMod resolved = ModMetadataResolver.resolveByModId(modId);
        if (resolved != null) {
            return register(resolved.modId(), resolved.displayName(), resolved.version(), legacySurface);
        }
        String displayName = ForgeMetadataUtil.getModDisplayNameFromForge(modId);
        String version = ForgeMetadataUtil.getModVersionFromForge(modId);
        return register(modId, displayName, version, legacySurface);
    }

    public static boolean register(String modId, String displayName, String version) {
        return register(modId, displayName, version, true);
    }

    private static boolean register(String modId, String displayName, String version, boolean legacySurface) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        String resolvedDisplayName = displayName;
        String resolvedVersion = version;
        ModMetadataResolver.ResolvedMod metadata = ModMetadataResolver.resolveByModId(modId);
        if ((resolvedDisplayName == null || resolvedDisplayName.trim().isEmpty()) && metadata != null) {
            resolvedDisplayName = metadata.displayName();
        }
        if (resolvedVersion == null || resolvedVersion.trim().isEmpty()) {
            resolvedVersion = metadata != null ? metadata.version() : ForgeMetadataUtil.getModVersionFromForge(modId);
        }
        if (resolvedDisplayName == null || resolvedDisplayName.trim().isEmpty()) {
            resolvedDisplayName = ForgeMetadataUtil.getModDisplayNameFromForge(modId);
        }
        if (resolvedVersion == null || resolvedVersion.trim().isEmpty()) {
            resolvedVersion = "unknown";
        }

        final String versionForHandle = resolvedVersion;
        QuantifiedHandle handle = handlesByMod.compute(modId, (id, existing) -> {
            if (existing != null) {
                existing.updateVersion(versionForHandle);
                return existing;
            }
            return new QuantifiedHandle(id, versionForHandle);
        });
        currentHandle.set(handle);
        
        QuantifiedCoreRuntime.registerMod(modId, versionForHandle);

        ConnectedMod existingMod = connectedMods.get(modId);
        if (existingMod != null) {
            if (existingMod instanceof ConnectedModImpl impl) {
                impl.updateDisplayName(resolvedDisplayName);
                impl.updateVersion(versionForHandle);
            }
            return true;
        }

        ConnectedMod newMod = null;
        for (ModConnectionListener listener : connectionListeners) {
            try {
                newMod = listener.onModConnecting(modId, versionForHandle, resolvedDisplayName);
                if (newMod != null) {
                    break;
                }
            } catch (RuntimeException e) {
                System.err.println("Error in connection listener: " + e.getMessage());
            }
        }

        if (newMod == null) {
            newMod = new ConnectedModImpl(modId, versionForHandle, resolvedDisplayName);
        }

        connectedMods.put(modId, newMod);

        for (ModConnectionListener listener : connectionListeners) {
            try {
                listener.onModConnected(newMod);
            } catch (Exception e) {
                System.err.println("Error notifying connection listener: " + e.getMessage());
            }
        }

        if (legacySurface && shouldTrackLegacyBinding(modId)) {
            LegacyApiRegistry.markLegacyRegistration(modId, resolvedDisplayName, "register()");
        }

        return true;
    }

    @Deprecated
    public static void init(String modId, String version) {
        if (shouldTrackLegacyBinding(modId)) {
            LegacyApiRegistry.markLegacyRegistration(modId, modId, "init()");
        }
        register(modId, modId, version, true);
    }

    private static boolean shouldTrackLegacyBinding(String modId) {
        return modId != null
            && !modId.isBlank()
            && !QuantifiedCoreRuntime.MODID.equals(modId);
    }

    public static CompletableFuture<Void> sendPacket(String channelName, QuantifiedPacket packet) {
        QuantifiedHandle handle = getHandle();
        return handle.sendPacket(channelName, packet);
    }

    public static <T> CompletableFuture<T> submitGraph(QuantifiedTaskGraph.Builder builder,
                                                       QuantifiedTaskGraph.NodeHandle<T> terminal) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(terminal, "terminal");
        QuantifiedHandle handle = resolveHandle(builder.modId());
        return handle.submitGraph(builder, terminal);
    }

    public static CompletableFuture<Map<String, Object>> submitGraph(QuantifiedTaskGraph.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        QuantifiedHandle handle = resolveHandle(builder.modId());
        return handle.submitGraphAll(builder);
    }

    public static <T> ComputeRequest<T> compute(String taskName) {
        QuantifiedHandle handle = getHandle();
        return new ComputeRequest<>(handle.modId(), taskName);
    }

    public static <T> ComputeRequest<T> compute(String modId, String taskName) {
        QuantifiedHandle handle = resolveHandle(modId);
        return new ComputeRequest<>(handle.modId(), taskName);
    }

    public static ParallelRequest parallel(String taskName) {
        QuantifiedHandle handle = getHandle();
        return new ParallelRequest(handle.modId(), taskName);
    }

    public static ParallelRequest parallel(String modId, String taskName) {
        QuantifiedHandle handle = resolveHandle(modId);
        return new ParallelRequest(handle.modId(), taskName);
    }

    public static QuantifiedTaskGraph.Builder graph(String graphName) {
        QuantifiedHandle handle = getHandle();
        return QuantifiedTaskGraph.builder(handle.modId(), graphName);
    }

    public static QuantifiedTaskGraph.Builder graph(String modId, String graphName) {
        QuantifiedHandle handle = resolveHandle(modId);
        return QuantifiedTaskGraph.builder(handle.modId(), graphName);
    }

    public static CacheRequest cache(String cacheName) {
        QuantifiedHandle handle = getHandle();
        return new CacheRequest(handle.modId(), cacheName);
    }

    public static CacheRequest cache(String modId, String cacheName) {
        QuantifiedHandle handle = resolveHandle(modId);
        return new CacheRequest(handle.modId(), cacheName);
    }

    public static CompletableFuture<Void> runAsync(String taskName, Runnable work) {
        Objects.requireNonNull(work, "work");
        return QuantifiedAPI.<Void>compute(taskName)
            .submit(() -> {
                work.run();
                return null;
            });
    }

    /**
     * Legacy v1.x task submit. Prefer {@link #compute(String)} or {@link ComputeRequest#submit(Supplier)}.
     */
    public static <T> CompletableFuture<T> submit(String taskName, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        trackLegacyApiCall("submit(String)");
        QuantifiedHandle handle = getHandle();
        return submitTaskInternal(QuantifiedTask.builder(handle.modId(), taskName, supplier).build());
    }

    /**
     * Legacy v1.x task submit. Prefer {@link ComputeRequest#submit(Supplier)}.
     */
    public static <T> CompletableFuture<T> submit(QuantifiedTask.Builder<T> builder) {
        Objects.requireNonNull(builder, "builder");
        trackLegacyApiCall(builder.modId(), "submit(Builder)");
        return submitTaskInternal(builder.build());
    }

    /**
     * Legacy v1.x parallel submit. Prefer {@link ParallelRequest}.
     */
    public static <S, R, O> CompletableFuture<O> submitParallel(ParallelCompute.Builder<S, R, O> builder) {
        Objects.requireNonNull(builder, "builder");
        trackLegacyApiCall("submitParallel");
        return builder.submit();
    }

    /**
     * Legacy v1.x cache read. Prefer {@link CacheRequest#get(String, Supplier)}.
     */
    public static <T> T getCached(String cacheName, String key, Supplier<T> loader) {
        trackLegacyApiCall("getCached");
        return cache(cacheName).get(key, loader);
    }

    /**
     * Legacy v1.x cache read. Prefer {@link CacheRequest#get(String, Supplier)}.
     */
    public static <T> T getCached(
        String cacheName,
        String key,
        Supplier<T> loader,
        Duration ttl,
        long maximumSize,
        boolean persistence
    ) {
        trackLegacyApiCall("getCached");
        return configureLegacyCache(cacheName, ttl, maximumSize, persistence).get(key, loader);
    }

    /**
     * Legacy v1.x async cache read. Prefer {@link CacheRequest#getAsync(String, Supplier)}.
     */
    public static <T> CompletableFuture<T> getCachedAsync(String cacheName, String key, Supplier<T> loader) {
        trackLegacyApiCall("getCachedAsync");
        return cache(cacheName).getAsync(key, loader);
    }

    /**
     * Legacy v1.x async cache read. Prefer {@link CacheRequest#getAsync(String, Supplier)}.
     */
    public static <T> CompletableFuture<T> getCachedAsync(
        String cacheName,
        String key,
        Supplier<T> loader,
        Duration ttl,
        long maximumSize,
        boolean persistence
    ) {
        trackLegacyApiCall("getCachedAsync");
        return configureLegacyCache(cacheName, ttl, maximumSize, persistence).getAsync(key, loader);
    }

    /**
     * Legacy v1.x cache write. Prefer {@link CacheRequest#put(String, Object)}.
     */
    public static <T> void putCached(String cacheName, String key, T value) {
        trackLegacyApiCall("putCached");
        cache(cacheName).put(key, value);
    }

    /**
     * Legacy v1.x cache write. Prefer {@link CacheRequest#put(String, Object)}.
     */
    public static <T> void putCached(
        String cacheName,
        String key,
        T value,
        Duration ttl,
        long maximumSize,
        boolean persistence
    ) {
        trackLegacyApiCall("putCached");
        configureLegacyCache(cacheName, ttl, maximumSize, persistence).put(key, value);
    }

    private static CacheRequest configureLegacyCache(
        String cacheName,
        Duration ttl,
        long maximumSize,
        boolean persistence
    ) {
        CacheRequest request = cache(cacheName);
        if (ttl != null) {
            request.ttl(ttl);
        }
        if (maximumSize > 0) {
            request.maxEntries(maximumSize);
        }
        if (persistence) {
            request.persistent();
        } else {
            request.memoryOnly();
        }
        return request;
    }

    private static void trackLegacyApiCall(String reason) {
        try {
            trackLegacyApiCall(getHandle().modId(), reason);
        } catch (RuntimeException ignored) {
        }
    }

    private static void trackLegacyApiCall(String modId, String reason) {
        if (shouldTrackLegacyBinding(modId)) {
            LegacyApiRegistry.markLegacyRegistration(modId, modId, reason);
        }
    }

    public static <T> void forEach(String taskName, java.util.Collection<T> values, java.util.function.Consumer<T> consumer) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(consumer, "consumer");
        parallel(taskName).items(values).forEach(consumer).join();
    }

    public static <T, R> List<R> mapOrdered(String taskName, java.util.Collection<T> values, Function<T, R> mapper) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(mapper, "mapper");
        return parallel(taskName).items(values).map(mapper).submit().join();
    }

    public static <R> List<R> mapRange(String taskName, int startInclusive, int endExclusive, IntFunction<R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return parallel(taskName).range(startInclusive, endExclusive).map(mapper).submit().join();
    }

    public static boolean isPrintDebugLogs() {
        return false;
    }

    public static ModCacheManager getCacheManager() {
        QuantifiedHandle handle = getHandle();
        return handle.getCacheManager();
    }

    public static ModCacheManager getCacheManager(String modId) {
        QuantifiedHandle handle = resolveHandle(modId);
        return handle.getCacheManager();
    }

    public static ModStatistics getModStatistics(String modId) {
        ConnectedMod mod = connectedMods.get(modId);
        return mod != null ? mod.getStatistics() : null;
    }

    public static void reportCacheUsage(String modId, long entryCount, long bytes) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        ConnectedModImpl mod = lookupConnectedMod(modId);
        if (mod != null) {
            mod.updateCacheStats(entryCount, bytes);
        }
    }

    public static java.util.Map<String, ModStatistics> getAllModStatistics() {
        java.util.Map<String, ModStatistics> stats = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, ConnectedMod> entry : connectedMods.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().getStatistics());
        }
        return stats;
    }

    public static void addConnectionListener(ModConnectionListener listener) {
        connectionListeners.add(listener);
        handlesByMod.forEach((modId, handle) -> {
            if (connectedMods.containsKey(modId)) {
                return;
            }
            try {
                ConnectedMod mod = listener.onModConnecting(modId, handle.version(), ForgeMetadataUtil.getModDisplayNameFromForge(modId));
                if (mod != null) {
                    connectedMods.put(modId, mod);
                    for (ModConnectionListener l : connectionListeners) {
                        try {
                            l.onModConnected(mod);
                        } catch (Exception e) {
                            System.err.println("Error notifying connection listener: " + e.getMessage());
                        }
                    }
                }
            } catch (RuntimeException ex) {
                System.err.println("Error in connection listener during initial replay: " + ex.getMessage());
            }
        });
    }

    public static void disconnect(String modId) {
        ConnectedMod mod = connectedMods.remove(modId);
        if (mod != null) {
            mod.disconnect();
            for (ModConnectionListener listener : connectionListeners) {
                try {
                    listener.onModDisconnected(mod);
                } catch (Exception e) {
                    System.err.println("Error notifying disconnection listener: " + e.getMessage());
                }
            }
        }
        QuantifiedHandle removed = handlesByMod.remove(modId);
        QuantifiedHandle current = currentHandle.get();
        if (removed != null && removed == current) {
            currentHandle.remove();
        }
    }

    public static boolean isConnected(String modId) {
        return connectedMods.containsKey(modId);
    }

    public static java.util.Collection<ConnectedMod> getConnectedMods() {
        return new java.util.ArrayList<>(connectedMods.values());
    }

    public static void setGpuBackendPreference(String modId, GpuBackendPreference preference) {
        QuantifiedHandle handle = resolveHandle(modId);
        GpuBackendRouter.setModPreference(handle.modId(), preference);
    }

    public static GpuBackendPreference getGpuBackendPreference(String modId) {
        if (modId == null || modId.isBlank()) {
            QuantifiedHandle handle = getHandle();
            return GpuBackendRouter.getModPreference(handle.modId());
        }
        return GpuBackendRouter.getModPreference(modId);
    }

    static ConnectedModImpl lookupConnectedMod(String modId) {
        ConnectedMod connected = connectedMods.get(modId);
        if (connected instanceof ConnectedModImpl impl) {
            return impl;
        }
        return null;
    }

    static QuantifiedHandle resolveHandleForApi(String modId) {
        return resolveHandle(modId);
    }

    static <T> CompletableFuture<T> submitTaskInternal(QuantifiedTask<T> task) {
        Objects.requireNonNull(task, "task");
        return resolveHandle(task.modId()).submitTask(task);
    }

    private static QuantifiedHandle resolveHandle(String modId) {
        QuantifiedHandle current = currentHandle.get();
        if (current != null && (modId == null || modId.isBlank() || current.modId().equals(modId))) {
            return current;
        }
        if (modId != null && !modId.isBlank()) {
            QuantifiedHandle mapped = handlesByMod.get(modId);
            if (mapped != null) {
                currentHandle.set(mapped);
                return mapped;
            }

            try {
                register(modId);
            } catch (Throwable ignored) {
            }

            QuantifiedHandle created = handlesByMod.computeIfAbsent(modId, id -> {
                String version = null;
                try {
                    version = ForgeMetadataUtil.getModVersionFromForge(id);
                } catch (Throwable ignored) {
                }
                if (version == null || version.isBlank()) {
                    version = "unknown";
                }
                try {
                    QuantifiedCoreRuntime.registerMod(id, version);
                } catch (Throwable ignored) {
                }
                return new QuantifiedHandle(id, version);
            });
            currentHandle.set(created);
            return created;
        }
        return getHandle();
    }

    private static QuantifiedHandle getHandle() {
        QuantifiedHandle handle = currentHandle.get();
        if (handle == null) {
            QuantifiedHandle autoDetected = autoRegisterCallerHandle();
            if (autoDetected != null) {
                return autoDetected;
            }
            if (handlesByMod.size() == 1) {
                QuantifiedHandle single = handlesByMod.values().iterator().next();
                currentHandle.set(single);
                return single;
            }
            throw new IllegalStateException("QuantifiedAPI could not auto-detect the active mod. Use the explicit modId overloads like compute(modId, taskName) or call QuantifiedAPI.register(modId) as an override.");
        }

        if (!handlesByMod.containsKey(handle.modId())) {
            // Be resilient to lifecycle ordering: lazily re-bind the handle instead of failing.
            try {
                register(handle.modId());
            } catch (Throwable ignored) {
            }
            QuantifiedHandle mapped = handlesByMod.computeIfAbsent(handle.modId(), id -> {
                String version = handle.version();
                if (version == null || version.isBlank()) {
                    version = "unknown";
                }
                try {
                    QuantifiedCoreRuntime.registerMod(id, version);
                } catch (Throwable ignored) {
                }
                return new QuantifiedHandle(id, version);
            });
            currentHandle.set(mapped);
            return mapped;
        }

        return handle;
    }

    private static QuantifiedHandle autoRegisterCallerHandle() {
        ModMetadataResolver.ResolvedMod resolved = null;
        try {
            resolved = ModMetadataResolver.resolveCallerMod();
        } catch (Throwable ignored) {
        }
        if (resolved == null || resolved.modId() == null || resolved.modId().isBlank()) {
            return null;
        }
        register(resolved.modId(), resolved.displayName(), resolved.version());
        QuantifiedHandle handle = handlesByMod.get(resolved.modId());
        if (handle != null) {
            currentHandle.set(handle);
        }
        return handle;
    }
}
