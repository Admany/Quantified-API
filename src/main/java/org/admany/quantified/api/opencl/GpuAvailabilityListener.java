package org.admany.quantified.api.opencl;

@FunctionalInterface
public interface GpuAvailabilityListener {
    void onGpuReady();
}
