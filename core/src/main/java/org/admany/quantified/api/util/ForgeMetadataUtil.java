package org.admany.quantified.api.util;

public final class ForgeMetadataUtil {

    private static final java.util.concurrent.ConcurrentHashMap<String, String> DISPLAY_NAME_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> VERSION_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    private ForgeMetadataUtil() {}

    public static String getModDisplayNameFromForge(String modId) {
        if (modId == null || modId.isBlank()) {
            return "unknown";
        }
        return DISPLAY_NAME_CACHE.computeIfAbsent(modId, ForgeMetadataUtil::resolveDisplayName);
    }

    public static String getModVersionFromForge(String modId) {
        if (modId == null || modId.isBlank()) {
            return "unknown";
        }
        return VERSION_CACHE.computeIfAbsent(modId, ForgeMetadataUtil::resolveVersion);
    }

    private static String resolveDisplayName(String modId) {
        try {
            Object modInfo = getModInfo(modId);
            if (modInfo != null) {
                Object displayName = modInfo.getClass().getMethod("getDisplayName").invoke(modInfo);
                return displayName.toString();
            }
        } catch (ReflectiveOperationException e) {
        }
        return modId.substring(0, 1).toUpperCase() + modId.substring(1).replace('_', ' ');
    }

    private static String resolveVersion(String modId) {
        try {
            Object modInfo = getModInfo(modId);
            if (modInfo != null) {
                Object version = modInfo.getClass().getMethod("getVersion").invoke(modInfo);
                return version.toString();
            }
        } catch (ReflectiveOperationException e) {
        }
        return "unknown";
    }

    private static Object getModInfo(String modId) throws ReflectiveOperationException {
        Class<?> modListClass = Class.forName("net.minecraftforge.fml.ModList");
        Object modList = modListClass.getMethod("get").invoke(null);
        if (modList != null) {
            Object containerOptional = modListClass.getMethod("getModContainerById", String.class).invoke(modList, modId);
            if (containerOptional != null) {
                boolean hasValue = (Boolean) containerOptional.getClass().getMethod("isPresent").invoke(containerOptional);
                if (hasValue) {
                    Object container = containerOptional.getClass().getMethod("get").invoke(containerOptional);
                    return container.getClass().getMethod("getModInfo").invoke(container);
                }
            }
        }
        return null;
    }
}
