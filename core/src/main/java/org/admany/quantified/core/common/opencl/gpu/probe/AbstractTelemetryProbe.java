package org.admany.quantified.core.common.opencl.gpu.probe;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

abstract class AbstractTelemetryProbe implements GpuTelemetryService.VendorProbe {
    private final GpuTelemetryService.Vendor vendor;
    private final String name;
    private final AtomicBoolean initAttempted = new AtomicBoolean(false);
    private volatile boolean initialized;
    private final Supplier<String> preferredNameSupplier;

    protected AbstractTelemetryProbe(GpuTelemetryService.Vendor vendor,
                                     String name,
                                     Supplier<String> preferredNameSupplier) {
        this.vendor = vendor;
        this.name = name;
        this.preferredNameSupplier = preferredNameSupplier;
    }

    protected abstract boolean initializeNative();

    protected abstract GpuTelemetryService.TelemetrySample readSampleInternal() throws Exception;

    @Override
    public final GpuTelemetryService.Vendor vendor() {
        return vendor;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final boolean isSupported() {
        if (!initAttempted.get()) {
            initialized = initializeNative();
            initAttempted.set(true);
        }
        return initialized;
    }

    @Override
    public final GpuTelemetryService.TelemetrySample poll() throws Exception {
        if (!isSupported()) {
            return null;
        }
        return readSampleInternal();
    }

    protected String preferredDeviceName() {
        return preferredNameSupplier != null ? preferredNameSupplier.get() : null;
    }
}
