package org.admany.quantified.api.interfaces;

public interface ModConnectionListener {
    void onModConnected(ConnectedMod mod);
    void onModDisconnected(ConnectedMod mod);
    ConnectedMod onModConnecting(String modId, String version, String displayName);
}