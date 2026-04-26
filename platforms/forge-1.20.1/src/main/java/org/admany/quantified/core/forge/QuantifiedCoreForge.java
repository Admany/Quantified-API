package org.admany.quantified.core.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.forge.commands.QuantifiedCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

@Mod(QuantifiedCoreForge.MODID)
public final class QuantifiedCoreForge {

    public static final String MODID = QuantifiedCoreRuntime.MODID;
    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreForge.class);

    public QuantifiedCoreForge(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        MinecraftForge.EVENT_BUS.register(this);
        bootstrapCore();
    }

    @SuppressWarnings("removal")
    public QuantifiedCoreForge() {
        this(FMLJavaModLoadingContext.get().getModEventBus());
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
        MinecraftForge.EVENT_BUS.addListener((TickEvent e) -> {
            if (e.type == TickEvent.Type.RENDER && e.phase == TickEvent.Phase.START) {
                QuantifiedCoreRuntime.onRenderTickStart(LOGGER);
            }
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        QuantifiedCoreRuntime.onServerStarting(event.getServer());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        QuantifiedCommand.register(event.getDispatcher());
        LOGGER.debug("Quantified commands registered.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        QuantifiedCoreRuntime.onServerStopping();
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
