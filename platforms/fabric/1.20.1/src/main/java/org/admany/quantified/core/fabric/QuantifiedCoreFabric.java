package org.admany.quantified.core.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.fabric.commands.QuantifiedFabricCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantifiedCoreFabric implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreFabric.class);

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(loader.getGameDir(), loader.getConfigDir())
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            QuantifiedFabricCommand.register(dispatcher);
            LOGGER.debug("Quantified Fabric commands registered.");
        });

        ServerLifecycleEvents.SERVER_STARTING.register(QuantifiedCoreRuntime::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> QuantifiedCoreRuntime.onServerStopping());
    }
}
