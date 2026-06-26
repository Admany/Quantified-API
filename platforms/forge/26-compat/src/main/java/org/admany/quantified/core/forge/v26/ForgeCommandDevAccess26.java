package org.admany.quantified.core.forge.v26;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public final class ForgeCommandDevAccess26 {

    private ForgeCommandDevAccess26() {
    }

    public static boolean hasDevAccess(CommandSourceStack source) {
        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
            || source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            return true;
        }
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        return server != null && player != null && !server.isDedicatedServer();
    }
}