package org.admany.quantified.core.common.util;

import org.admany.quantified.api.interfaces.ConnectedMod;
import org.admany.quantified.api.interfaces.ModConnectionListener;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class QuantifiedConnectionListener implements ModConnectionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedConnectionListener.class);

    private final ConcurrentMap<String, ConnectedModImpl> connectedMods = new ConcurrentHashMap<>();

    @Override
    public ConnectedMod onModConnecting(String modId, String version, String displayName) {
        // Check if API is ready
        if (!AsyncManager.isInitialised()) {
            return null; // API not ready yet
        }

        // Create or get existing connected mod
        ConnectedModImpl mod = connectedMods.computeIfAbsent(modId,
            id -> new ConnectedModImpl(id, version, displayName));

        // Update version if changed
        mod.updateVersion(version);
        mod.updateDisplayName(displayName);

        return mod;
    }

    @Override
    public void onModConnected(ConnectedMod mod) {
        LOGGER.debug("Mod connected: {}", mod.getModId());
    }

    @Override
    public void onModDisconnected(ConnectedMod mod) {
        LOGGER.debug("Mod disconnected: {}", mod.getModId());
        disconnectMod(mod.getModId());
    }

    /**
     * Disconnects a mod.
     */
    public void disconnectMod(String modId) {
        ConnectedModImpl mod = connectedMods.remove(modId);
        if (mod != null) {
            mod.disconnect();
        }
    }
}