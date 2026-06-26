package org.admany.quantified.core.fabric.wide;

import net.fabricmc.loader.api.FabricLoader;

public enum FabricMcEra {
    LEGACY,
    MID,
    MODERN;

    public static FabricMcEra current() {
        String mcVersion = FabricLoader.getInstance()
            .getModContainer("minecraft")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString();
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
}