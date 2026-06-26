package org.admany.quantified.core.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.forge.commands.QuantifiedCommand;
import org.slf4j.Logger;

final class QuantifiedForgeBootstrap {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QuantifiedForgeBootstrap.class);
    private static volatile boolean installed;

    private QuantifiedForgeBootstrap() {
    }

    static void install(FMLJavaModLoadingContext context) {
        if (installed) {
            return;
        }
        synchronized (QuantifiedForgeBootstrap.class) {
            if (installed) {
                return;
            }
            var modBus = context.getModEventBus();
            modBus.addListener(QuantifiedForgeBootstrap::onCommonSetup);
            modBus.addListener(QuantifiedForgeBootstrap::onClientSetup);
            MinecraftForge.EVENT_BUS.register(LegacyForgeEvents.INSTANCE);
            bootstrapCore();
            installed = true;
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        bootstrapCore();
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        QuantifiedCoreRuntime.onClientSetup(LOGGER);
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent renderEvent) -> {
            if (renderEvent.phase == TickEvent.Phase.START) {
                QuantifiedCoreRuntime.onRenderTickStart(LOGGER);
            }
        });
    }

    private static void bootstrapCore() {
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get())
        );
    }

    private static final class LegacyForgeEvents {
        private static final LegacyForgeEvents INSTANCE = new LegacyForgeEvents();

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void onServerStarting(ServerStartingEvent event) {
            QuantifiedCoreRuntime.onServerStarting(event.getServer());
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void onRegisterCommands(RegisterCommandsEvent event) {
            QuantifiedCommand.register(event.getDispatcher());
            LOGGER.debug("Quantified commands registered.");
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void onServerStopping(ServerStoppingEvent event) {
            QuantifiedCoreRuntime.onServerStopping();
        }
    }
}
