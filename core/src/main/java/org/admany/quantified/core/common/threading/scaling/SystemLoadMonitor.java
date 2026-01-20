package org.admany.quantified.core.common.threading.scaling;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;

public final class SystemLoadMonitor {

    private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean();
    private static final Method SYSTEM_LOAD_METHOD = findMethod("getSystemCpuLoad");
    private static final Method PROCESS_LOAD_METHOD = findMethod("getProcessCpuLoad");
    private static final int LOGICAL_CORES = Runtime.getRuntime().availableProcessors();
    private static final double DEFAULT_CPU_CAP = 0.8;

    private SystemLoadMonitor() {
    }

    public static double currentSystemLoad() {
        Double value = invokeDouble(SYSTEM_LOAD_METHOD);
        if (value != null && value >= 0.0) {
            return clamp(value);
        }
        Double process = invokeDouble(PROCESS_LOAD_METHOD);
        if (process != null && process >= 0.0) {
            return clamp(process);
        }
        return 0.5;
    }

    public static double maxCpuLoad() {
        String raw = System.getProperty("quantified.maxCpuLoad");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CPU_CAP;
        }
        try {
            double parsed = Double.parseDouble(raw);
            if (parsed <= 0.0 || parsed > 1.0) {
                return DEFAULT_CPU_CAP;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return DEFAULT_CPU_CAP;
        }
    }

    public static double normalizedSystemLoad() {
        double cap = maxCpuLoad();
        if (cap <= 0.0) {
            cap = DEFAULT_CPU_CAP;
        }
        return clamp(currentSystemLoad() / cap);
    }

    public static boolean isSmtCapable() {
        return LOGICAL_CORES >= 4 && (LOGICAL_CORES % 2 == 0);
    }

    private static Double invokeDouble(Method method) {
        if (method == null) {
            return null;
        }
        try {
            Object result = method.invoke(OS);
            if (result instanceof Number number) {
                return number.doubleValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Method findMethod(String name) {
        try {
            Method method = OS.getClass().getMethod(name);
            return method.canAccess(OS) ? method : null;
        } catch (NoSuchMethodException | SecurityException ignored) {
            return null;
        }
    }

    private static double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
