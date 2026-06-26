package org.admany.quantified.core.forge.commands;

import net.minecraftforge.fml.loading.FMLLoader;

public enum ForgeMcEra {
    LEGACY,
    MID,
    MODERN;

    public static ForgeMcEra current() {
        String mcVersion = FMLLoader.versionInfo().mcVersion();
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

    public static boolean usesLegacyCommandPermissions() {
        String mcVersion = normalisedMinecraftVersion();
        return mcVersion.equals("1.21.1");
    }

    private static String normalisedMinecraftVersion() {
        String mcVersion = FMLLoader.versionInfo().mcVersion();
        int dash = mcVersion.indexOf('-');
        if (dash >= 0) {
            mcVersion = mcVersion.substring(0, dash);
        }
        return mcVersion;
    }
}
