package org.admany.quantified.core.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

@Mod(QuantifiedCoreForge.MODID)
public final class QuantifiedCoreForge {

    public static final String MODID = QuantifiedCoreRuntime.MODID;
    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreForge.class);

    public QuantifiedCoreForge(FMLJavaModLoadingContext context) {
        QuantifiedForgeBootstrap.install(context);
    }

    @SuppressWarnings("removal")
    public QuantifiedCoreForge() {
        QuantifiedForgeBootstrap.install(FMLJavaModLoadingContext.get());
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
