package org.admany.quantified.core.fabric.v21;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public final class FabricCommandDevAccess21 {

    private FabricCommandDevAccess21() {
    }

    public static boolean hasDevAccess(CommandSourceStack source) {
        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
            || source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
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
        return server.isSingleplayerOwner(player.nameAndId());
    }
}