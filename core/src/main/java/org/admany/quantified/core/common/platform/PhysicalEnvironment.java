package org.admany.quantified.core.common.platform;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Loader-neutral physical-side detection which never probes Minecraft client classes. */
public final class PhysicalEnvironment {
    private static final AtomicReference<Boolean> CLIENT = new AtomicReference<>();

    private PhysicalEnvironment() {
    }

    public static boolean isClient() {
        Boolean cached = CLIENT.get();
        if (cached != null) {
            return cached;
        }
        boolean detected = detectForgeLikeClient("net.minecraftforge.fml.loading.FMLEnvironment")
            || detectForgeLikeClient("net.neoforged.fml.loading.FMLEnvironment")
            || detectFabricClient();
        CLIENT.compareAndSet(null, detected);
        return CLIENT.get();
    }

    public static boolean isDedicatedServer() {
        return !isClient();
    }

    private static boolean detectForgeLikeClient(String environmentClassName) {
        try {
            Class<?> environmentClass = Class.forName(environmentClassName, false, PhysicalEnvironment.class.getClassLoader());
            Object dist = environmentClass.getField("dist").get(null);
            return dist != null && "CLIENT".equals(String.valueOf(dist).toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean detectFabricClient() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader", false,
                PhysicalEnvironment.class.getClassLoader());
            Method getInstance = loaderClass.getMethod("getInstance");
            Object loader = getInstance.invoke(null);
            Object type = loaderClass.getMethod("getEnvironmentType").invoke(loader);
            return type != null && "CLIENT".equals(String.valueOf(type).toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
