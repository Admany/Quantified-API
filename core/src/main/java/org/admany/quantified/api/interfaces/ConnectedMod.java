package org.admany.quantified.api.interfaces;

import org.admany.quantified.api.CacheRequest;
import org.admany.quantified.api.ComputeRequest;
import org.admany.quantified.api.ParallelRequest;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.graph.QuantifiedTaskGraph;

public interface ConnectedMod {
    String getModId();
    String getVersion();
    String getDisplayName();
    ModStatistics getStatistics();
    <T> ComputeRequest<T> compute(String name);
    ParallelRequest parallel(String name);
    CacheRequest cache(String cacheName);
    default QuantifiedTaskGraph.Builder graph(String name) {
        return QuantifiedTaskGraph.builder(getModId(), name);
    }
    default void setGpuBackendPreference(GpuBackendPreference preference) {
        QuantifiedAPI.setGpuBackendPreference(getModId(), preference);
    }
    default GpuBackendPreference getGpuBackendPreference() {
        return QuantifiedAPI.getGpuBackendPreference(getModId());
    }
    void disconnect();
}
