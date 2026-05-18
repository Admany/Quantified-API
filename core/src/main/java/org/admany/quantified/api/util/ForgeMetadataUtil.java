package org.admany.quantified.api.util;

public final class ForgeMetadataUtil {

    private ForgeMetadataUtil() {}

    public static String getModDisplayNameFromForge(String modId) {
        ModMetadataResolver.ResolvedMod resolved = ModMetadataResolver.resolveByModId(modId);
        return resolved != null ? resolved.displayName() : "unknown";
    }

    public static String getModVersionFromForge(String modId) {
        ModMetadataResolver.ResolvedMod resolved = ModMetadataResolver.resolveByModId(modId);
        return resolved != null ? resolved.version() : "unknown";
    }
}
