package org.admany.quantified.core.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.forge.commands.ForgeMcEra;
import org.admany.quantified.core.forge.wide.QuantifiedForgeWideBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

@Mod(QuantifiedCoreForge.MODID)
public final class QuantifiedCoreForge {

    public static final String MODID = QuantifiedCoreRuntime.MODID;
    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreForge.class);

    public QuantifiedCoreForge(FMLJavaModLoadingContext context) {
        org.admany.quantified.core.common.util.LwjglRuntimeTuning.ensureConfigured();
        installBootstrap(context);
    }

    @SuppressWarnings("removal")
    public QuantifiedCoreForge() {
        org.admany.quantified.core.common.util.LwjglRuntimeTuning.ensureConfigured();
        installBootstrap(FMLJavaModLoadingContext.get());
    }

    private static void installBootstrap(FMLJavaModLoadingContext context) {
        switch (ForgeMcEra.current()) {
            case MODERN -> installModernBootstrap(context);
            case MID -> installMidBootstrap(context);
            case LEGACY -> installLegacyBootstrap(context);
        }
    }

    private static void installModernBootstrap(FMLJavaModLoadingContext context) {
        invokeBootstrap("org.admany.quantified.core.forge.v26.QuantifiedForge26Bootstrap", context);
    }

    private static void installMidBootstrap(FMLJavaModLoadingContext context) {
        invokeBootstrap("org.admany.quantified.core.forge.v21.QuantifiedForge21Bootstrap", context);
    }

    private static void invokeBootstrap(String className, FMLJavaModLoadingContext context) {
        try {
            Class<?> bootstrap = Class.forName(className);
            Method install = bootstrap.getMethod("install", FMLJavaModLoadingContext.class);
            install.invoke(null, context);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to install Forge bootstrap: " + className, e);
        }
    }

    private static void installLegacyBootstrap(FMLJavaModLoadingContext context) {
        QuantifiedForgeWideBootstrap.install(context);
    }

    public static org.admany.quantified.core.common.network.NetworkManager getNetworkManager() {
        return QuantifiedCoreRuntime.getNetworkManager();
    }

    public static void registerMod(String modId, String version) {
        QuantifiedCoreRuntime.registerMod(modId, version, LOGGER);
    }

    public static void touchMod(String modId) {
        QuantifiedCoreRuntime.touchMod(modId);
    }

    public static QuantifiedCoreRuntime.ModInfo getModInfo(String modId) {
        return QuantifiedCoreRuntime.getModInfo(modId);
    }

    public static void logRegisteredMods() {
        QuantifiedCoreRuntime.logRegisteredMods(LOGGER);
    }

    public static ConcurrentHashMap<String, QuantifiedCoreRuntime.ModInfo> getRegisteredMods() {
        return QuantifiedCoreRuntime.getRegisteredMods();
    }
}