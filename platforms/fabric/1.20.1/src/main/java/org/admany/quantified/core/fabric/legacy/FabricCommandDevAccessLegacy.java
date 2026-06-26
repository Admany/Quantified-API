package org.admany.quantified.core.fabric.legacy;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;

public final class FabricCommandDevAccessLegacy {

    private FabricCommandDevAccessLegacy() {
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

    public static boolean hasPermissionLevel(CommandSourceStack source, int level) {
        if (invokeBooleanMethod(source, "hasPermission", level)) {
            return true;
        }
        return invokeBooleanMethod(source, "method_9259", level);
    }

    private static boolean invokeBooleanMethod(Object target, String name, int level) {
        try {
            Method method = target.getClass().getMethod(name, int.class);
            Object result = method.invoke(target, level);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
