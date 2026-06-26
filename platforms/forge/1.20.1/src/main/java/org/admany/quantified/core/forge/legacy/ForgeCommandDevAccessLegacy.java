package org.admany.quantified.core.forge.legacy;

import net.minecraft.commands.CommandSourceStack;

public final class ForgeCommandDevAccessLegacy {

    private ForgeCommandDevAccessLegacy() {
    }

    public static boolean hasDevAccess(CommandSourceStack source) {
        if (hasPermission(source, 2)) {
            return true;
        }
        Object server = invoke(source, NO_TYPES, NO_ARGS, "getServer", "m_81377_", "l");
        if (server == null || isDedicatedServer(server)) {
            return false;
        }
        return invoke(source, NO_TYPES, NO_ARGS, "getEntity", "m_81373_", "f") != null;
    }

    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Object[] NO_ARGS = new Object[0];

    private static boolean hasPermission(CommandSourceStack source, int level) {
        Object result = invoke(source, new Class<?>[]{int.class}, new Object[]{level}, "hasPermission", "m_6761_", "c");
        return Boolean.TRUE.equals(result);
    }

    private static boolean isDedicatedServer(Object server) {
        Object result = invoke(server, NO_TYPES, NO_ARGS, "isDedicatedServer", "m_6992_", "l");
        return Boolean.TRUE.equals(result);
    }

    private static Object invoke(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                return type.getMethod(name, parameterTypes).invoke(target, args);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Try the next mapping name. This class is merged into the omni jar unmapped on Forge 1.20.1.
            }
        }
        return null;
    }
}
