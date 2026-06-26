package org.admany.quantified.core.forge.v26;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.forge.commands.QuantifiedCommand;
import org.slf4j.Logger;

public final class QuantifiedForge26Bootstrap {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QuantifiedForge26Bootstrap.class);
    private static volatile boolean installed;

    private QuantifiedForge26Bootstrap() {
    }

    public static void install(FMLJavaModLoadingContext context) {
        if (installed) {
            return;
        }
        synchronized (QuantifiedForge26Bootstrap.class) {
            if (installed) {
                return;
            }

            BusGroup modBusGroup = context.getModBusGroup();
            FMLCommonSetupEvent.getBus(modBusGroup).addListener(QuantifiedForge26Bootstrap::onCommonSetup);
            FMLClientSetupEvent.getBus(modBusGroup).addListener(QuantifiedForge26Bootstrap::onClientSetup);
            ServerStartingEvent.BUS.addListener(QuantifiedForge26Bootstrap::onServerStarting);
            RegisterCommandsEvent.BUS.addListener(QuantifiedForge26Bootstrap::onRegisterCommands);
            ServerStoppingEvent.BUS.addListener(ignored -> QuantifiedCoreRuntime.onServerStopping());

            bootstrapCore();
            installed = true;
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        bootstrapCore();
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        QuantifiedCoreRuntime.onClientSetup(LOGGER);
        TickEvent.RenderTickEvent.Pre.BUS.addListener(ignored -> QuantifiedCoreRuntime.onRenderTickStart(LOGGER));
    }

    private static void onServerStarting(ServerStartingEvent event) {
        QuantifiedCoreRuntime.onServerStarting(event.getServer());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        QuantifiedCommand.register(event.getDispatcher());
        LOGGER.debug("Quantified commands registered.");
    }

    private static void bootstrapCore() {
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get())
        );
    }
}