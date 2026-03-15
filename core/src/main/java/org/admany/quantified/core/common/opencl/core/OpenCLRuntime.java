package org.admany.quantified.core.common.opencl.core;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OpenCLRuntime {

    private static final Logger LOGGER = Logger.getLogger(OpenCLRuntime.class.getName());
    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);
    private static final AtomicReference<String> LAST_ERROR = new AtomicReference<>(null);
    private static final AtomicReference<Binding> BINDING = new AtomicReference<>(Binding.UNKNOWN);

    private enum Binding {
        UNKNOWN,
        LWJGL,
        NONE
    }

    private OpenCLRuntime() {}

    public static boolean ensureInitialised() {
        if (INITIALISED.get()) {
            return true;
        }
        try {
            Binding b = BINDING.get();
            if (b == Binding.UNKNOWN) {
                b = probeBinding();
                BINDING.set(b);
            }

            if (b == Binding.LWJGL) {
                LOGGER.fine("Attempting to initialize LWJGL OpenCL runtime");
                try {
                    invokeLWJGLCreate();
                } catch (Throwable t) {
                    String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                    LAST_ERROR.set(message);
                    LOGGER.warning("OpenCL runtime unavailable (LWJGL): " + message);
                    // Dedicated servers (or mismatched LWJGL runtimes) may not ship the required org.lwjgl classes.
                    // Don't spam full stack traces for a known "binding missing" condition.
                    if (!isMissingLwjgl(t)) {
                        LOGGER.log(Level.INFO, "Full OpenCL init failure", t);
                    }
                    return false;
                }
                INITIALISED.set(true);
                LAST_ERROR.set(null);
                LOGGER.info("OpenCL runtime initialised via LWJGL");
                return true;
            } else {
                LAST_ERROR.set("No Java OpenCL binding found (org.lwjgl.opencl)");
                LOGGER.warning("OpenCL runtime unavailable: No Java binding found");
                return false;
            }
        } catch (Throwable throwable) {
            String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getName();
            LAST_ERROR.set(message);
            LOGGER.warning("OpenCL runtime unavailable: " + message);
            if (!isMissingLwjgl(throwable)) {
                LOGGER.log(Level.INFO, "Full OpenCL init failure", throwable);
            }
            return false;
        }
    }

    public static void destroy() {
        if (INITIALISED.compareAndSet(true, false)) {
            try {
                if (BINDING.get() == Binding.LWJGL) {
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
        return new AvailabilitySnapshot(available, available ? null : LAST_ERROR.get());
    }

    public static String lastError() {
        return LAST_ERROR.get();
    }

    public static boolean isInitialised() {
        return INITIALISED.get();
    }

    public static String getBindingName() {
        Binding b = BINDING.get();
        return b == null ? "UNKNOWN" : b.name();
    }

    private static Binding probeBinding() {
        // LWJGL check: use reflection and treat LinkageError/CNFE as "not available".
        if (isClassPresent("org.lwjgl.opencl.CL")) {
            return Binding.LWJGL;
        }

        return Binding.NONE;
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

    private static void invokeLWJGLCreate() throws Exception {
        Class<?> cl = Class.forName("org.lwjgl.opencl.CL");
        Method create = cl.getMethod("create");
        try {
            create.invoke(null);
        } catch (Exception e) {
            if (e.getCause() instanceof IllegalStateException && e.getCause().getMessage().contains("already been created")) {
                LOGGER.fine("OpenCL already initialized");
            } else {
                throw e;
            }
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
}
