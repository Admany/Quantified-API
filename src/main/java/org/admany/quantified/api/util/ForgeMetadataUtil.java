package org.admany.quantified.api.util;

public final class ForgeMetadataUtil {

    private ForgeMetadataUtil() {}

    public static String getModDisplayNameFromForge(String modId) {
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

    public static String getModVersionFromForge(String modId) {
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