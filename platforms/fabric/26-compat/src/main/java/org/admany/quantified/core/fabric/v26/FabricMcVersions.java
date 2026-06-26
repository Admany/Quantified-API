package org.admany.quantified.core.fabric.v26;

import net.fabricmc.loader.api.FabricLoader;

final class FabricMcVersions {
    private FabricMcVersions() {
    }

    static boolean isModern() {
        return majorVersion() >= 26;
    }

    private static int majorVersion() {
        String version = FabricLoader.getInstance()
            .getModContainer("minecraft")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString();
        int dash = version.indexOf('-');
        if (dash >= 0) {
            version = version.substring(0, dash);
        }
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        return Integer.parseInt(major);
    }
}