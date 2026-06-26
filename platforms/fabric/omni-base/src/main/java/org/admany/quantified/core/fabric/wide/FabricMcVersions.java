package org.admany.quantified.core.fabric.wide;

final class FabricMcVersions {
    private FabricMcVersions() {
    }

    static boolean isLegacy() {
        return FabricMcEra.current() != FabricMcEra.MODERN;
    }

    static boolean isModern() {
        return FabricMcEra.current() == FabricMcEra.MODERN;
    }
}