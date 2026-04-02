package org.admany.quantified.api.interfaces;

import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.builders.QuantifiedCacheBuilder;
import org.admany.quantified.api.builders.QuantifiedHybridBuilder;
import org.admany.quantified.api.builders.QuantifiedNetworkBuilder;
import org.admany.quantified.api.builders.QuantifiedTaskBuilder;
import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.QuantifiedCompute;
import org.admany.quantified.api.graph.QuantifiedTaskGraph;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;

import java.util.concurrent.ThreadLocalRandom;

public interface ConnectedMod {
    String getModId();
    String getVersion();
    String getDisplayName();
    ModStatistics getStatistics();
    QuantifiedTaskBuilder task(String name);
    QuantifiedCacheBuilder cache(Enum<?> cacheType);
    QuantifiedHybridBuilder hybrid(String name);
    QuantifiedNetworkBuilder network(String channel);
    default QuantifiedTaskGraph.Builder graph(String name) {
        return QuantifiedTaskGraph.builder(getModId(), name, ThreadLocalRandom.current().nextLong());
    }
    default <T> QuantifiedCompute.Builder<T> compute(String name) {
        return QuantifiedCompute.builder(getModId(), name, ThreadLocalRandom.current().nextLong());
    }
    default <T> QuantifiedVulkan.Builder<T> vulkan(String name) {
        return QuantifiedVulkan.builder(getModId(), name, ThreadLocalRandom.current().nextLong());
    }
    default void setGpuBackendPreference(GpuBackendPreference preference) {
        QuantifiedAPI.setGpuBackendPreference(getModId(), preference);
    }
    default GpuBackendPreference getGpuBackendPreference() {
        return QuantifiedAPI.getGpuBackendPreference(getModId());
    }
    void disconnect();
}
