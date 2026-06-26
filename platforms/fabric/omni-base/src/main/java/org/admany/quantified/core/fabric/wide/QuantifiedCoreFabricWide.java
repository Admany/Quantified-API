package org.admany.quantified.core.fabric.wide;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.fabric.commands.QuantifiedFabricCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantifiedCoreFabricWide implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreFabricWide.class);

    @Override
    public void onInitialize() {
        switch (FabricMcEra.current()) {
            case LEGACY -> startLegacy();
            case MID -> startMid();
            case MODERN -> startModern();
        }
    }

    private static void startModern() {
        try {
            Class<?> bootstrap = Class.forName("org.admany.quantified.core.fabric.v26.QuantifiedCoreFabric26");
            bootstrap.getMethod("startMain").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to start Quantified Fabric 26 bootstrap", e);
        }
    }

    private static void startMid() {
        org.admany.quantified.core.common.util.LwjglRuntimeTuning.ensureConfigured();
        FabricLoader loader = FabricLoader.getInstance();
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(loader.getGameDir(), loader.getConfigDir())
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            QuantifiedFabricCommand.register(dispatcher);
            LOGGER.debug("Quantified Fabric 1.21.x commands registered.");
        });

        ServerLifecycleEvents.SERVER_STARTING.register(QuantifiedCoreRuntime::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> QuantifiedCoreRuntime.onServerStopping());
    }

    private static void startLegacy() {
        invokeLegacy("org.admany.quantified.core.fabric.legacy.FabricLegacyEntrypoint", "startMain");
    }

    private static void invokeLegacy(String className, String methodName) {
        try {
            Class<?> bootstrap = Class.forName(className);
            bootstrap.getMethod(methodName).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to start Quantified Fabric legacy bootstrap: " + className, e);
        }
    }
}
