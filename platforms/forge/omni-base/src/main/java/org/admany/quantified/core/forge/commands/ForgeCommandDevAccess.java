package org.admany.quantified.core.forge.commands;

import net.minecraft.commands.CommandSourceStack;

public final class ForgeCommandDevAccess {

    private ForgeCommandDevAccess() {
    }

    public static boolean hasDevAccess(CommandSourceStack source) {
        if (ForgeMcEra.usesLegacyCommandPermissions()) {
            return invokeEra("org.admany.quantified.core.forge.legacy.ForgeCommandDevAccessLegacy", source);
        }
        return switch (ForgeMcEra.current()) {
            case MODERN -> invokeEra("org.admany.quantified.core.forge.v26.ForgeCommandDevAccess26", source);
            case MID -> invokeEra("org.admany.quantified.core.forge.v21.ForgeCommandDevAccess21", source);
            case LEGACY -> invokeEra("org.admany.quantified.core.forge.legacy.ForgeCommandDevAccessLegacy", source);
        };
    }

    private static boolean invokeEra(String className, CommandSourceStack source) {
        try {
            return (boolean) Class.forName(className)
                .getMethod("hasDevAccess", CommandSourceStack.class)
                .invoke(null, source);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve Forge dev-access gate: " + className, e);
        }
    }
}
