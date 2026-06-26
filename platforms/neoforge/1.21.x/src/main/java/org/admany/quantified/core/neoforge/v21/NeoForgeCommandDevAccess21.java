package org.admany.quantified.core.neoforge.v21;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class NeoForgeCommandDevAccess21 {

    private NeoForgeCommandDevAccess21() {
    }

    public static boolean hasDevAccess(CommandSourceStack source) {
        if (hasPermissionLevel(source, 2)) {
            return true;
        }
        return hasIntegratedAccess(source);
    }

    private static boolean hasIntegratedAccess(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        if (server == null || player == null || server.isDedicatedServer()) {
            return false;
        }
        if (!server.isPublished()) {
            return true;
        }
        return server.isSingleplayerOwner(player.getGameProfile());
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
