package org.admany.quantified.core.fabric.v26;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantifiedCoreFabric26 implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreFabric26.class);

    @Override
    public void onInitialize() {
        if (!FabricMcVersions.isModern()) {
            return;
        }
        startMain();
    }

    public static void startMain() {
        org.admany.quantified.core.common.util.LwjglRuntimeTuning.ensureConfigured();
        FabricLoader loader = FabricLoader.getInstance();
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(loader.getGameDir(), loader.getConfigDir())
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            QuantifiedFabricCommand26.register(dispatcher);
            LOGGER.debug("Quantified Fabric commands registered.");
        });

        ServerLifecycleEvents.SERVER_STARTING.register(QuantifiedCoreRuntime::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> QuantifiedCoreRuntime.onServerStopping());
    }
}
