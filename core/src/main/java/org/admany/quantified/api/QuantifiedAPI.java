package org.admany.quantified.api;

import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.QuantifiedCompute;
import org.admany.quantified.api.interfaces.ConnectedMod;
import org.admany.quantified.api.interfaces.ModCacheManager;
import org.admany.quantified.api.interfaces.ModConnectionListener;
import org.admany.quantified.api.interfaces.ModStatistics;
import org.admany.quantified.api.graph.QuantifiedTaskGraph;
import org.admany.quantified.api.model.QuantifiedHybrid;
import org.admany.quantified.api.model.QuantifiedPacket;
import org.admany.quantified.api.model.QuantifiedTask;
import org.admany.quantified.api.parallel.ParallelCompute;
import org.admany.quantified.api.util.ForgeMetadataUtil;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.util.ConnectedModImpl;
import org.admany.quantified.core.forge.QuantifiedCoreForge;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;

public final class QuantifiedAPI {

    private static final long DEFAULT_CACHE_MAX_SIZE = 0L;

    private static final ThreadLocal<QuantifiedHandle> currentHandle = new ThreadLocal<>();
    private static final ConcurrentMap<String, QuantifiedHandle> handlesByMod = new ConcurrentHashMap<>();
    private static final Map<String, ConnectedMod> connectedMods = new ConcurrentHashMap<>();
    private static final List<ModConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    public static boolean register(String modId) {
        String displayName = ForgeMetadataUtil.getModDisplayNameFromForge(modId);
        String version = ForgeMetadataUtil.getModVersionFromForge(modId);
        return register(modId, displayName, version);
    }

    public static boolean register(String modId, String displayName, String version) {
        String resolvedVersion = version;
        if (resolvedVersion == null || resolvedVersion.trim().isEmpty()) {
            resolvedVersion = ForgeMetadataUtil.getModVersionFromForge(modId);
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
        
        QuantifiedCoreForge.registerMod(modId, versionForHandle);

        ConnectedMod existingMod = connectedMods.get(modId);
        if (existingMod != null) {
            return true;
        }

        ConnectedMod newMod = null;
        for (ModConnectionListener listener : connectionListeners) {
            try {
                newMod = listener.onModConnecting(modId, versionForHandle, displayName);
                if (newMod != null) {
                    break;
                }
            } catch (RuntimeException e) {
                System.err.println("Error in connection listener: " + e.getMessage());
            }
        }

        if (newMod == null) {
            return true;
        }

        connectedMods.put(modId, newMod);

        for (ModConnectionListener listener : connectionListeners) {
            try {
                listener.onModConnected(newMod);
            } catch (Exception e) {
                System.err.println("Error notifying connection listener: " + e.getMessage());
            }
        }

        return true;
    }

    @Deprecated
    public static void init(String modId, String version) {
        register(modId, modId, version);
    }

    public static <T> CompletableFuture<T> submit(String taskName, Supplier<T> work) {
        QuantifiedHandle handle = getHandle();
        return handle.submitTask(QuantifiedTask.builder(handle.modId(), taskName, work).build());
    }

    public static <T> CompletableFuture<T> submit(QuantifiedTask.Builder<T> builder) {
        QuantifiedHandle handle = resolveHandle(builder.modId());
        return handle.submitTask(builder.build());
    }

    public static <S, R, O> CompletableFuture<O> submitParallel(ParallelCompute.Builder<S, R, O> builder) {
        return Objects.requireNonNull(builder, "builder").submit();
    }

    public static <T> T getCached(String cacheName, String key, Supplier<T> loader) {
        QuantifiedHandle handle = getHandle();
        return handle.cacheGet(cacheName, key, loader, null, DEFAULT_CACHE_MAX_SIZE, false);
    }

    public static <T> T getCached(String cacheName, String key, Supplier<T> loader, Duration ttl, long maxSize, boolean persistence) {
        QuantifiedHandle handle = getHandle();
        return handle.cacheGet(cacheName, key, loader, ttl, maxSize, persistence);
    }

    public static <T> CompletableFuture<T> getCachedAsync(String cacheName, String key, Supplier<T> loader) {
        QuantifiedHandle handle = getHandle();
        return handle.cacheGetAsync(cacheName, key, loader, null, DEFAULT_CACHE_MAX_SIZE, false);
    }

    public static <T> CompletableFuture<T> getCachedAsync(String cacheName, String key, Supplier<T> loader, Duration ttl, long maxSize, boolean persistence) {
        QuantifiedHandle handle = getHandle();
        return handle.cacheGetAsync(cacheName, key, loader, ttl, maxSize, persistence);
    }

    public static <T> CompletableFuture<T> hybrid(String operationName, Supplier<T> work) {
        QuantifiedHandle handle = getHandle();
        QuantifiedHybrid<T> hybrid = QuantifiedHybrid.builder(handle.modId(), operationName, work)
            .cacheKey(operationName)
            .build();
        return handle.submitHybrid(hybrid);
    }

    public static <T> CompletableFuture<T> hybrid(QuantifiedHybrid.Builder<T> builder) {
        QuantifiedHandle handle = getHandle();
        return handle.submitHybrid(builder.build());
    }

    public static CompletableFuture<Void> sendPacket(String channelName, QuantifiedPacket packet) {
        QuantifiedHandle handle = getHandle();
        return handle.sendPacket(channelName, packet);
    }

    public static <T> void putCached(String cacheName, String key, T value) {
        if (value == null) {
            return;
        }
        QuantifiedHandle handle = getHandle();
        handle.cacheGet(cacheName, key, () -> value, null, DEFAULT_CACHE_MAX_SIZE, false);
    }

    public static <T> void putCached(String cacheName, String key, T value, Duration ttl, long maxSize, boolean persistence) {
        if (value == null) {
            return;
        }
        QuantifiedHandle handle = getHandle();
        handle.cacheGet(cacheName, key, () -> value, ttl, maxSize, persistence);
    }

    public static <T> QuantifiedTask.Builder<T> task(String taskName, Supplier<T> work) {
        QuantifiedHandle handle = getHandle();
        return QuantifiedTask.builder(handle.modId(), taskName, work);
    }

    public static QuantifiedTaskGraph.Builder graph(String graphName) {
        QuantifiedHandle handle = getHandle();
        return QuantifiedTaskGraph.builder(handle.modId(), graphName, ThreadLocalRandom.current().nextLong());
    }

    public static QuantifiedTaskGraph.Builder graph(String modId, String graphName) {
        QuantifiedHandle handle = resolveHandle(modId);
        return QuantifiedTaskGraph.builder(handle.modId(), graphName, ThreadLocalRandom.current().nextLong());
    }

    public static <T> QuantifiedCompute.Builder<T> compute(String taskName) {
        QuantifiedHandle handle = getHandle();
        return QuantifiedCompute.builder(handle.modId(), taskName, ThreadLocalRandom.current().nextLong());
    }

    public static <T> QuantifiedCompute.Builder<T> compute(String modId, String taskName) {
        QuantifiedHandle handle = resolveHandle(modId);
        return QuantifiedCompute.builder(handle.modId(), taskName, ThreadLocalRandom.current().nextLong());
    }

    public static <T> QuantifiedVulkan.Builder<T> vulkan(String taskName) {
        QuantifiedHandle handle = getHandle();
        return QuantifiedVulkan.builder(handle.modId(), taskName, ThreadLocalRandom.current().nextLong());
    }

    public static <T> QuantifiedVulkan.Builder<T> vulkan(String modId, String taskName) {
        QuantifiedHandle handle = resolveHandle(modId);
        return QuantifiedVulkan.builder(handle.modId(), taskName, ThreadLocalRandom.current().nextLong());
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

    public static <T> QuantifiedHybrid.Builder<T> hybridBuilder(String operationName, Supplier<T> work) {
        QuantifiedHandle handle = getHandle();
        return QuantifiedHybrid.builder(handle.modId(), operationName, work);
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
                    QuantifiedCoreForge.registerMod(id, version);
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
            if (handlesByMod.size() == 1) {
                QuantifiedHandle single = handlesByMod.values().iterator().next();
                currentHandle.set(single);
                return single;
            }
            throw new IllegalStateException("QuantifiedAPI not initialized. Call QuantifiedAPI.register(modId, displayName, version) first.");
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
                    QuantifiedCoreForge.registerMod(id, version);
                } catch (Throwable ignored) {
                }
                return new QuantifiedHandle(id, version);
            });
            currentHandle.set(mapped);
            return mapped;
        }

        return handle;
    }
}
