package org.admany.quantified.core.fabric.wide;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantifiedCoreFabricClientWide implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreFabricClientWide.class);

    @Override
    public void onInitializeClient() {
        switch (FabricMcEra.current()) {
            case LEGACY -> startLegacyClient();
            case MID -> startMidClient();
            case MODERN -> startModernClient();
        }
    }

    private static void startModernClient() {
        try {
            Class<?> bootstrap = Class.forName("org.admany.quantified.core.fabric.v26.QuantifiedCoreFabricClient26");
            bootstrap.getMethod("startClient").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to start Quantified Fabric 26 client bootstrap", e);
        }
    }

    private static void startMidClient() {
        QuantifiedCoreRuntime.onClientSetup(LOGGER);
        ClientTickEvents.START_CLIENT_TICK.register(client -> QuantifiedCoreRuntime.onRenderTickStart(LOGGER));
    }

    private static void startLegacyClient() {
        try {
            Class<?> bootstrap = Class.forName("org.admany.quantified.core.fabric.legacy.FabricLegacyEntrypoint");
            bootstrap.getMethod("startClient").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to start Quantified Fabric legacy client bootstrap", e);
        }
    }
}
