package org.admany.quantified.core.neoforge.wide;

public enum NeoForgeMcEra {
    LEGACY,
    MID,
    MODERN;

    public static NeoForgeMcEra current() {
        String mcVersion = resolveMinecraftVersion();
        if (mcVersion == null || mcVersion.isBlank()) {
            return MODERN;
        }
        int dash = mcVersion.indexOf('-');
        if (dash >= 0) {
            mcVersion = mcVersion.substring(0, dash);
        }
        String[] parts = mcVersion.split("\\.", 3);
        int major = Integer.parseInt(parts[0]);
        if (major >= 26) {
            return MODERN;
        }
        if (major == 1 && parts.length > 1) {
            int minor = Integer.parseInt(parts[1]);
            if (minor >= 21) {
                return MID;
            }
        }
        return LEGACY;
    }

    private static String resolveMinecraftVersion() {
        String sharedConstantsVersion = resolveSharedConstantsVersion();
        if (sharedConstantsVersion != null && !sharedConstantsVersion.isBlank()) {
            return sharedConstantsVersion;
        }
        String loaderVersion = resolveLoaderVersionInfo();
        if (loaderVersion != null && !loaderVersion.isBlank()) {
            return loaderVersion;
        }
        String propertyVersion = System.getProperty("minecraft.version");
        if (propertyVersion == null || propertyVersion.isBlank()) {
            propertyVersion = System.getProperty("fml.mcVersion");
        }
        return propertyVersion;
    }

    private static String resolveSharedConstantsVersion() {
        try {
            Object version = Class.forName("net.minecraft.SharedConstants")
                .getMethod("getCurrentVersion")
                .invoke(null);
            for (String methodName : new String[] { "getName", "name", "id" }) {
                try {
                    Object value = version.getClass().getMethod(methodName).invoke(version);
                    if (value instanceof String text && !text.isBlank()) {
                        return text;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return null;
    }

    private static String resolveLoaderVersionInfo() {
        try {
            Object versionInfo = Class.forName("net.neoforged.fml.loading.FMLLoader")
                .getMethod("versionInfo")
                .invoke(null);
            Object value = versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
