package org.admany.quantified.core.fabric.v26;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantifiedCoreFabricClient26 implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedCoreFabricClient26.class);

    @Override
    public void onInitializeClient() {
        if (!FabricMcVersions.isModern()) {
            return;
        }
        startClient();
    }

    public static void startClient() {
        QuantifiedCoreRuntime.onClientSetup(LOGGER);
        ClientTickEvents.START_CLIENT_TICK.register(client -> QuantifiedCoreRuntime.onRenderTickStart(LOGGER));
    }
}
