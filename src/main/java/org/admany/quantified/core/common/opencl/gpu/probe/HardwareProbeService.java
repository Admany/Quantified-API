package org.admany.quantified.core.common.opencl.gpu.probe;

import oshi.SystemInfo;

public final class HardwareProbeService {

    private static final SystemInfo SENSOR_INFO = new SystemInfo();

    private HardwareProbeService() {}

    public static String getGPUNameFromSystem() {
        try {
            java.util.List<oshi.hardware.GraphicsCard> cards = SENSOR_INFO.getHardware().getGraphicsCards();
            if (cards != null) {
                for (oshi.hardware.GraphicsCard card : cards) {
                    if (card == null) continue;
                    String cleaned = cleanGPUName(card.getName());
                    if (!"Unknown GPU".equals(cleaned)) {
                        return cleaned;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "Unknown GPU";
    }

    private static String cleanGPUName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return "Unknown GPU";
        }

        String name = rawName.trim();

        if (name.toLowerCase().contains("basic") ||
            name.toLowerCase().contains("generic") ||
            name.toLowerCase().contains("standard") ||
            name.equalsIgnoreCase("amx86")) {
            return "Unknown GPU";
        }

        if (name.toLowerCase().contains("amd") || name.toLowerCase().contains("radeon")) {
            name = name.replaceAll("\\s+", " ");
            if (name.equalsIgnoreCase("AMD Radeon Graphics") ||
                name.equalsIgnoreCase("AMD Radeon(TM) Graphics")) {
                return "AMD Radeon Integrated Graphics";
            }
        }

        if (name.toLowerCase().contains("nvidia") || name.toLowerCase().contains("geforce")) {
            name = name.replaceAll("\\s+", " ");
        }

        if (name.toLowerCase().contains("intel")) {
            name = name.replaceAll("\\s+", " ");
        }

        return name;
    }

    public static String getCPUNameFromSystem() {
        if (overrideCpuModel != null && !overrideCpuModel.isBlank()) {
            return sanitizeCpuString(overrideCpuModel);
        }
        try {
            String cpuName = SENSOR_INFO.getHardware().getProcessor().getProcessorIdentifier().getName();
            if (cpuName != null && !cpuName.isBlank()) {
                return sanitizeCpuString(cpuName);
            }
        } catch (Throwable ignored) {
        }
        String envName = System.getenv("PROCESSOR_IDENTIFIER");
        if (envName != null && !envName.isBlank()) {
            return sanitizeCpuString(envName);
        }
        String cpuProp = System.getProperty("os.arch");
        if (cpuProp != null && !cpuProp.isBlank()) {
            int processors = Runtime.getRuntime().availableProcessors();
            return sanitizeCpuString(String.format("%s (%d cores)", cpuProp, processors));
        }
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            String name = osBean.getName() + " (" + System.getProperty("os.arch", "") + ")";
            return sanitizeCpuString(name);
        } catch (Throwable ignored) {
        }
        return "Unknown CPU";
    }

    public static void setCpuModelFromGame(String model) {
        if (model == null) return;
        String cleaned = sanitizeCpuString(model);
        if (!cleaned.equals("Unknown CPU")) {
            overrideCpuModel = cleaned;
        }
    }

    private static String sanitizeCpuString(String raw) {
        if (raw == null) return "Unknown CPU";
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 128) cleaned = cleaned.substring(0, 128).trim();
        return cleaned.isEmpty() ? "Unknown CPU" : cleaned;
    }

    private static volatile String overrideCpuModel = null;
}
