package org.admany.quantified.api.model;

import org.admany.quantified.core.common.network.PacketSerializer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class QuantifiedPacket {

    private final PacketSerializer.Packet delegate;

    private QuantifiedPacket(PacketSerializer.Packet delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static QuantifiedPacket from(PacketSerializer.Packet packet) {
        return new QuantifiedPacket(packet);
    }

    public static QuantifiedPacket dataSync(String dataType, byte[] payload) {
        return new QuantifiedPacket(new PacketSerializer.DataSyncPacket(dataType, payload.clone()));
    }

    public static QuantifiedPacket dataSync(String dataType, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return dataSync(dataType, bytes);
    }

    public PacketSerializer.Packet toPacket(String modId, String channelName) {
        return delegate;
    }
}