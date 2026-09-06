package org.admany.quantified.core.common.opencl.core;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.admany.quantified.core.common.gpu.backend.GpuStartupDiagnostics;

public final class OpenCLRuntime {

    private static final Logger LOGGER = Logger.getLogger(OpenCLRuntime.class.getName());
    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);
    private static final Object INITIALISE_LOCK = new Object();
    private static final AtomicReference<String> IN_PROCESS_FAILURE = new AtomicReference<>(null);
    private static final AtomicReference<String> LAST_ERROR = new AtomicReference<>(null);
    private static final AtomicReference<Binding> BINDING = new AtomicReference<>(Binding.UNKNOWN);
    private static final AtomicReference<Boolean> PROBE_RUNTIME_PRESENT = new AtomicReference<>(null);
    private static final AtomicReference<ProbeSnapshot> LAST_PROBE_SNAPSHOT = new AtomicReference<>(null);
    private static final AtomicBoolean OWNS_LWJGL = new AtomicBoolean(false);

    private enum Binding {
        UNKNOWN,
        LWJGL,
        FOREIGN,
        NONE
    }

    private OpenCLRuntime() {}

    public static boolean ensureInitialised() {
        if (INITIALISED.get()) {
            return true;
        }
        if (IN_PROCESS_FAILURE.get() != null) {
            return false;
        }
        synchronized (INITIALISE_LOCK) {
            if (INITIALISED.get()) {
                return true;
            }
            if (IN_PROCESS_FAILURE.get() != null) {
                return false;
            }
            try {
                OpenCLLinuxLoaderCompatibility.configureBeforeLwjglOpenCl();
                Binding b = BINDING.get();
                if (b == Binding.UNKNOWN) {
                    b = probeBinding();
                    BINDING.set(b);
                }

                if (b == Binding.LWJGL) {
                    if (hasForeignOwner()) {
                        BINDING.set(Binding.FOREIGN);
                        LAST_ERROR.set("OpenCL binding is owned by another runtime");
                        return false;
                    }
                    LOGGER.fine("Attempting to initialize LWJGL OpenCL runtime");
                    try {
                        if (!invokeLWJGLCreate()) {
                            BINDING.set(Binding.FOREIGN);
                            LAST_ERROR.set("OpenCL binding is owned by another runtime");
                            return false;
                        }
                        OWNS_LWJGL.set(true);
                    } catch (Throwable t) {
                        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                        LAST_ERROR.set(message);
                        if (isTerminalNativeFailure(t)) {
                            IN_PROCESS_FAILURE.compareAndSet(null, failureDetail(t));
                        }
                        LOGGER.warning("OpenCL runtime unavailable (LWJGL): " + message);
                        GpuStartupDiagnostics.reportOpenCl(LOGGER, "in-process-init", true, embeddedProbePresent(), false,
                            failureDetail(t), List.of());
                        if (!isMissingLwjgl(t)) {
                            LOGGER.log(Level.INFO, "Full OpenCL init failure", t);
                        }
                        return false;
                    }
                    INITIALISED.set(true);
                    LAST_ERROR.set(null);
                    LOGGER.info("OpenCL runtime initialised via LWJGL");
                    GpuStartupDiagnostics.reportOpenCl(LOGGER, "in-process-init", true, embeddedProbePresent(), true,
                        null, List.of());
                    return true;
                } else if (b == Binding.FOREIGN) {
                    LAST_ERROR.set("OpenCL binding is owned by another runtime");
                    return false;
                } else {
                    LAST_ERROR.set("No Java OpenCL binding found (org.lwjgl.opencl)");
                    LOGGER.warning("OpenCL runtime unavailable: No Java binding found");
                    GpuStartupDiagnostics.reportOpenCl(LOGGER, "in-process-init", false, embeddedProbePresent(), false,
                        LAST_ERROR.get(), List.of());
                    return false;
                }
            } catch (Throwable throwable) {
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getName();
                LAST_ERROR.set(message);
                if (isTerminalNativeFailure(throwable)) {
                    IN_PROCESS_FAILURE.compareAndSet(null, failureDetail(throwable));
                }
                LOGGER.warning("OpenCL runtime unavailable: " + message);
                GpuStartupDiagnostics.reportOpenCl(LOGGER, "in-process-init", BINDING.get() == Binding.LWJGL,
                    embeddedProbePresent(), false, failureDetail(throwable), List.of());
                if (!isMissingLwjgl(throwable)) {
                    LOGGER.log(Level.INFO, "Full OpenCL init failure", throwable);
                }
                return false;
            }
        }
    }

    public static void destroy() {
        if (INITIALISED.compareAndSet(true, false)) {
            try {
                if (OWNS_LWJGL.compareAndSet(true, false) && !hasForeignOwner()) {
                    invokeLWJGLDestroy();
                }
                LOGGER.fine("OpenCL runtime destroyed");
            } catch (Throwable throwable) {
                LOGGER.log(Level.FINE, "Error shutting down OpenCL runtime", throwable);
            }
        }
    }

    public static AvailabilitySnapshot snapshot() {
        boolean available = ensureInitialised();
        return new AvailabilitySnapshot(available, available ? null : lastError());
    }

    public static boolean hasBindings() {
        Binding binding = BINDING.get();
        if (binding == Binding.UNKNOWN) {
            binding = probeBinding();
            BINDING.set(binding);
        }
        return binding == Binding.LWJGL;
    }

    public static boolean hasProbeRuntime() {
        Boolean cached = PROBE_RUNTIME_PRESENT.get();
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean present = hasBindings() || embeddedProbePresent();
        PROBE_RUNTIME_PRESENT.compareAndSet(null, present);
        return present;
    }

    public static ProbeSnapshot probeSnapshot() {
        ProbeSnapshot cached = LAST_PROBE_SNAPSHOT.get();
        if (cached != null) {
            return cached;
        }
        ProbeSnapshot snapshot;
        boolean bindingsPresent = hasBindings();
        boolean embeddedProbePresent = embeddedProbePresent();
        if (!bindingsPresent && !embeddedProbePresent) {
            snapshot = ProbeSnapshot.failed("No OpenCL probe runtime is bundled with this build", List.of());
        } else if (bindingsPresent && snapshot().available()) {
            snapshot = ProbeSnapshot.failed("In-process OpenCL runtime is available; isolated probe not required", List.of());
        } else if (!embeddedProbePresent) {
            snapshot = ProbeSnapshot.failed("Isolated OpenCL probe bundle is not present in this build", List.of());
        } else {
            OpenCLSubprocessProbe.Result result = OpenCLSubprocessProbe.run(org.slf4j.LoggerFactory.getLogger(OpenCLRuntime.class), 1);
            List<ProbeDeviceInfo> devices = result.devices().stream()
                .map(device -> new ProbeDeviceInfo(
                    device.id(),
                    device.name(),
                    device.vendor(),
                    device.type(),
                    device.vramBytes(),
                    device.computeUnits(),
                    device.supportsOpenCL32(),
                    device.supportsOpenCL12()))
                .toList();
            snapshot = result.ok()
                ? ProbeSnapshot.success(devices)
                : ProbeSnapshot.failed(result.failureReason(), devices);
            GpuStartupDiagnostics.reportOpenCl(LOGGER, "isolated-probe", bindingsPresent, embeddedProbePresent,
                snapshot.success() && !devices.isEmpty(), snapshot.success() && devices.isEmpty()
                    ? "Probe completed but the Linux/host OpenCL ICD reported zero devices"
                    : snapshot.failureReason(), devices);
        }
        LAST_PROBE_SNAPSHOT.compareAndSet(null, snapshot);
        return LAST_PROBE_SNAPSHOT.get();
    }

    public static ProbeSnapshot cachedProbeSnapshot() {
        return LAST_PROBE_SNAPSHOT.get();
    }

    public static String lastError() {
        String terminal = IN_PROCESS_FAILURE.get();
        return terminal != null ? terminal : LAST_ERROR.get();
    }

    public static boolean isInitialised() {
        return INITIALISED.get();
    }

    public static String getBindingName() {
        Binding b = BINDING.get();
        return b == null ? "UNKNOWN" : b.name();
    }

    private static Binding probeBinding() {
        if (hasForeignOwner()) {
            return Binding.FOREIGN;
        }
        if (isClassPresent("org.lwjgl.opencl.CL")) {
            return Binding.LWJGL;
        }

        return Binding.NONE;
    }

    private static boolean embeddedProbePresent() {
        ClassLoader loader = OpenCLRuntime.class.getClassLoader();
        return loader.getResource("quantified/embedded/openclProbe/classpath.index") != null
            && loader.getResource("quantified/embedded/openclProbe/quantified-opencl-probe.jar.bin") != null;
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, OpenCLRuntime.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isMissingLwjgl(Throwable t) {
        // Covers: ClassNotFoundException from Class.forName, NoClassDefFoundError during linking,
        // and other linkage issues when LWJGL isn't on the classpath (common on dedicated servers).
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof ClassNotFoundException || cur instanceof NoClassDefFoundError || cur instanceof LinkageError) {
                String msg = cur.getMessage();
                if (msg != null && msg.contains("org/lwjgl")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean hasForeignOwner() {
        if (Boolean.getBoolean("quantified.opencl.foreignOwner")) {
            return true;
        }
        String[] markers = {
            "com.ishland.c2me.opts.accel.opencl.ModuleEntryPoint",
            "com.ishland.c2me.opts.accel.opencl.common.gen.CLServerGlobalContext",
            "com.ishland.c2me.opts.accel.opencl.common.gen.CLServerWorldContext",
            "com.ishland.c2me.opts.accel.opencl.common.gen.OpenCLDevice"
        };
        ClassLoader[] loaders = {
            OpenCLRuntime.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            for (String marker : markers) {
                try {
                    Class.forName(marker, false, loader);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static boolean isTerminalNativeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof UnsatisfiedLinkError
                || current instanceof ExceptionInInitializerError
                || current instanceof NoClassDefFoundError) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("already loaded in another classloader")
                || message.contains("Could not initialize class org.lwjgl.opencl.CL"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String failureDetail(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        StringBuilder detail = new StringBuilder(throwable.getClass().getName());
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            detail.append(" caused-by ").append(cause.getClass().getName());
        }
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            detail.append(": ").append(message);
        }
        return detail.toString();
    }

    private static boolean invokeLWJGLCreate() throws Exception {
        Class<?> cl = Class.forName("org.lwjgl.opencl.CL");
        Method create = cl.getMethod("create");
        try {
            create.invoke(null);
            return true;
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException invocation
                ? invocation.getCause() : e.getCause();
            if (cause instanceof IllegalStateException
                && cause.getMessage() != null
                && cause.getMessage().contains("already been created")) {
                return false;
            }
            throw e;
        }
    }

    private static void invokeLWJGLDestroy() throws Exception {
        Class<?> cl = Class.forName("org.lwjgl.opencl.CL");
        Method destroy = cl.getMethod("destroy");
        destroy.invoke(null);
    }

    public static final class AvailabilitySnapshot {
        private final boolean available;
        private final String failureReason;

        private AvailabilitySnapshot(boolean available, String failureReason) {
            this.available = available;
            this.failureReason = failureReason;
        }

        public boolean available() {
            return available;
        }

        public String failureReason() {
            return failureReason;
        }
    }

    public static final class ProbeSnapshot {
        private final boolean success;
        private final String failureReason;
        private final List<ProbeDeviceInfo> devices;

        private ProbeSnapshot(boolean success, String failureReason, List<ProbeDeviceInfo> devices) {
            this.success = success;
            this.failureReason = failureReason;
            this.devices = devices != null ? List.copyOf(devices) : List.of();
        }

        public static ProbeSnapshot success(List<ProbeDeviceInfo> devices) {
            return new ProbeSnapshot(true, null, devices);
        }

        public static ProbeSnapshot failed(String failureReason, List<ProbeDeviceInfo> devices) {
            return new ProbeSnapshot(false, failureReason, devices);
        }

        public boolean success() {
            return success;
        }

        public String failureReason() {
            return failureReason;
        }

        public List<ProbeDeviceInfo> devices() {
            return devices;
        }
    }

    public static final class ProbeDeviceInfo {
        private final String id;
        private final String name;
        private final String vendor;
        private final String type;
        private final long vramBytes;
        private final int computeUnits;
        private final boolean supportsOpenCL32;
        private final boolean supportsOpenCL12;

        private ProbeDeviceInfo(String id,
                                String name,
                                String vendor,
                                String type,
                                long vramBytes,
                                int computeUnits,
                                boolean supportsOpenCL32,
                                boolean supportsOpenCL12) {
            this.id = id;
            this.name = name;
            this.vendor = vendor;
            this.type = type;
            this.vramBytes = vramBytes;
            this.computeUnits = computeUnits;
            this.supportsOpenCL32 = supportsOpenCL32;
            this.supportsOpenCL12 = supportsOpenCL12;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String vendor() {
            return vendor;
        }

        public String type() {
            return type;
        }

        public long vramBytes() {
            return vramBytes;
        }

        public int computeUnits() {
            return computeUnits;
        }

        public boolean supportsOpenCL32() {
            return supportsOpenCL32;
        }

        public boolean supportsOpenCL12() {
            return supportsOpenCL12;
        }
    }
}
