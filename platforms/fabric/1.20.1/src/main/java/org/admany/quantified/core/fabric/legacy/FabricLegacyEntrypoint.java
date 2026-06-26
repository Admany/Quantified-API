package org.admany.quantified.core.fabric.legacy;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.fabric.commands.QuantifiedFabricCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FabricLegacyEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricLegacyEntrypoint.class);

    private FabricLegacyEntrypoint() {
    }

    public static void startMain() {
        org.admany.quantified.core.common.util.LwjglRuntimeTuning.ensureConfigured();
        FabricLoader loader = FabricLoader.getInstance();
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(loader.getGameDir(), loader.getConfigDir())
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            QuantifiedFabricCommand.register(dispatcher);
            LOGGER.debug("Quantified Fabric legacy commands registered.");
        });

        ServerLifecycleEvents.SERVER_STARTING.register(QuantifiedCoreRuntime::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> QuantifiedCoreRuntime.onServerStopping());
    }

    public static void startClient() {
        QuantifiedCoreRuntime.onClientSetup(LOGGER);
        ClientTickEvents.START_CLIENT_TICK.register(client -> QuantifiedCoreRuntime.onRenderTickStart(LOGGER));
    }
}
