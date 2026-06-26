package org.admany.quantified.core.fabric.wide;

import net.minecraft.commands.CommandSourceStack;

public final class FabricCommandDevAccess {

    private FabricCommandDevAccess() {
    }

    public static boolean hasDevAccess(CommandSourceStack source) {
        return switch (FabricMcEra.current()) {
            case MODERN -> invokeEra("org.admany.quantified.core.fabric.v26.FabricCommandDevAccess26", source);
            case MID -> invokeEra("org.admany.quantified.core.fabric.v21.FabricCommandDevAccess21", source);
            case LEGACY -> invokeEra("org.admany.quantified.core.fabric.legacy.FabricCommandDevAccessLegacy", source);
        };
    }

    private static boolean invokeEra(String className, CommandSourceStack source) {
        try {
            return (boolean) Class.forName(className)
                .getMethod("hasDevAccess", CommandSourceStack.class)
                .invoke(null, source);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve Fabric dev-access gate: " + className, e);
        }
    }
}