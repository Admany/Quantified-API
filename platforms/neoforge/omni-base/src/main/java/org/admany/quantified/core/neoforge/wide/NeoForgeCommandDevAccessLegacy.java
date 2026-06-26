package org.admany.quantified.core.neoforge.wide;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class NeoForgeCommandDevAccessLegacy {

    private NeoForgeCommandDevAccessLegacy() {
    }

    public static boolean hasDevAccess(CommandSourceStack source) {
        if (hasPermissionLevel(source, 2)) {
            return true;
        }
        MinecraftServer server = source.getServer();
        if (server == null || server.isDedicatedServer()) {
            return false;
        }
        return source.getPlayer() != null;
    }

    private static boolean hasPermissionLevel(CommandSourceStack source, int level) {
        if (invokeBooleanMethod(source, "hasPermission", level)) {
            return true;
        }
        return invokeBooleanMethod(source, "method_9259", level);
    }

    private static boolean invokeBooleanMethod(Object target, String name, int level) {
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(name, int.class);
            Object result = method.invoke(target, level);
            return result instanceof Boolean allowed && allowed;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
