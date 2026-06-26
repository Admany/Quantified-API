package org.admany.quantified.core.neoforge.wide;

import net.minecraft.commands.CommandSourceStack;

public final class NeoForgeCommandDevAccess {

    private NeoForgeCommandDevAccess() {
    }

    public static boolean hasDevAccess(CommandSourceStack source) {
        Boolean modern = tryEra("org.admany.quantified.core.neoforge.v26.NeoForgeCommandDevAccess26", source);
        if (modern != null) {
            return modern;
        }
        Boolean mid = tryEra("org.admany.quantified.core.neoforge.v21.NeoForgeCommandDevAccess21", source);
        if (mid != null) {
            return mid;
        }
        return NeoForgeCommandDevAccessLegacy.hasDevAccess(source);
    }

    private static Boolean tryEra(String className, CommandSourceStack source) {
        try {
            return (boolean) Class.forName(className)
                .getMethod("hasDevAccess", CommandSourceStack.class)
                .invoke(null, source);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
