package org.admany.quantified.core.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.admany.quantified.core.common.network.NetworkManager;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.neoforge.commands.QuantifiedNeoForgeCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

@Mod(QuantifiedCoreNeoForge.MODID)
public final class QuantifiedCoreNeoForge {

    public static final String MODID = QuantifiedCoreRuntime.MODID;
    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreNeoForge.class);

    public QuantifiedCoreNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        NeoForge.EVENT_BUS.register(this);
        bootstrapCore();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        bootstrapCore();
    }

    private static void bootstrapCore() {
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get())
        );
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        QuantifiedCoreRuntime.onClientSetup(LOGGER);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> QuantifiedCoreRuntime.onRenderTickStart(LOGGER));
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        QuantifiedCoreRuntime.onServerStarting(event.getServer());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        QuantifiedNeoForgeCommand.register(event.getDispatcher());
        LOGGER.debug("Quantified NeoForge commands registered.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        QuantifiedCoreRuntime.onServerStopping();
    }

    public static NetworkManager getNetworkManager() {
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
