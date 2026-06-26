package org.admany.quantified.core.forge.wide;

import net.minecraftforge.fml.loading.FMLLoader;

final class ForgeMcVersions {
    private ForgeMcVersions() {
    }

    static boolean isLegacy() {
        return majorVersion(FMLLoader.versionInfo().mcVersion()) < 26;
    }

    private static int majorVersion(String mcVersion) {
        int dot = mcVersion.indexOf('.');
        String major = dot < 0 ? mcVersion : mcVersion.substring(0, dot);
        return Integer.parseInt(major);
    }
}