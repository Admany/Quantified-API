package org.admany.quantified.core.common.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class PacketSerializer {

    public enum PacketType {
        HANDSHAKE_INIT(1),
        HANDSHAKE_RESPONSE(2),
        DATA_SYNC(3),
        COMMAND_EXEC(4),
        TELEMETRY_UPDATE(5),
        CACHE_INVALIDATE(6),
        KEEPALIVE(7);

        private final int id;

        PacketType(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        public static PacketType fromId(int id) {
            for (PacketType type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown packet type: " + id);
        }
    }

    public interface Packet {
        PacketType getType();
        void serialize(DataOutputStream out) throws IOException;
    }

    public static final class HandshakeInitPacket implements Packet {
        private final UUID clientId;
        private final UUID channelId;
        private final byte[] publicKey;
        private final long timestamp;

        public HandshakeInitPacket(UUID clientId, UUID channelId, byte[] publicKey, long timestamp) {
            this.clientId = clientId;
            this.channelId = channelId;
            this.publicKey = publicKey.clone();
            this.timestamp = timestamp;
        }

        @Override
        public PacketType getType() {
            return PacketType.HANDSHAKE_INIT;
        }

        @Override
        public void serialize(DataOutputStream out) throws IOException {
            out.writeLong(clientId.getMostSignificantBits());
            out.writeLong(clientId.getLeastSignificantBits());
            out.writeLong(channelId.getMostSignificantBits());
            out.writeLong(channelId.getLeastSignificantBits());
            out.writeInt(publicKey.length);
            out.write(publicKey);
            out.writeLong(timestamp);
        }

        public static HandshakeInitPacket deserialize(DataInputStream in) throws IOException {
            long msb = in.readLong();
            long lsb = in.readLong();
            long chMsb = in.readLong();
            long chLsb = in.readLong();
            int keyLen = in.readInt();
            byte[] pk = new byte[keyLen];
            in.readFully(pk);
            long timestamp = in.readLong();
            return new HandshakeInitPacket(new UUID(msb, lsb), new UUID(chMsb, chLsb), pk, timestamp);
        }

        public UUID getClientId() { return clientId; }
        public UUID getChannelId() { return channelId; }
        public byte[] getPublicKey() { return publicKey.clone(); }
        public long getTimestamp() { return timestamp; }
    }

    public static final class HandshakeResponsePacket implements Packet {
        private final UUID serverId;
        private final UUID channelId;
        private final byte[] publicKey;
        private final long timestamp;

        public HandshakeResponsePacket(UUID serverId, UUID channelId, byte[] publicKey, long timestamp) {
            this.serverId = serverId;
            this.channelId = channelId;
            this.publicKey = publicKey.clone();
            this.timestamp = timestamp;
        }

        @Override
        public PacketType getType() {
            return PacketType.HANDSHAKE_RESPONSE;
        }

        @Override
        public void serialize(DataOutputStream out) throws IOException {
            out.writeLong(serverId.getMostSignificantBits());
            out.writeLong(serverId.getLeastSignificantBits());
            out.writeLong(channelId.getMostSignificantBits());
            out.writeLong(channelId.getLeastSignificantBits());
            out.writeInt(publicKey.length);
            out.write(publicKey);
            out.writeLong(timestamp);
        }

        public static HandshakeResponsePacket deserialize(DataInputStream in) throws IOException {
            long msb = in.readLong();
            long lsb = in.readLong();
            long chMsb = in.readLong();
            long chLsb = in.readLong();
            int keyLen = in.readInt();
            byte[] publicKey = new byte[keyLen];
            in.readFully(publicKey);
            long timestamp = in.readLong();
            return new HandshakeResponsePacket(new UUID(msb, lsb), new UUID(chMsb, chLsb), publicKey, timestamp);
        }

        public UUID getServerId() { return serverId; }
        public UUID getChannelId() { return channelId; }
        public byte[] getPublicKey() { return publicKey.clone(); }
        public long getTimestamp() { return timestamp; }
    }

    public static final class DataSyncPacket implements Packet {
        private final String dataType;
        private final byte[] data;

        public DataSyncPacket(String dataType, byte[] data) {
            this.dataType = dataType;
            this.data = data.clone();
        }

        @Override
        public PacketType getType() {
            return PacketType.DATA_SYNC;
        }

        @Override
        public void serialize(DataOutputStream out) throws IOException {
            out.writeUTF(dataType);
            out.writeInt(data.length);
            out.write(data);
        }

        public static DataSyncPacket deserialize(DataInputStream in) throws IOException {
            String dataType = in.readUTF();
            int dataLen = in.readInt();
            byte[] data = new byte[dataLen];
            in.readFully(data);
            return new DataSyncPacket(dataType, data);
        }

        public String getDataType() { return dataType; }
        public byte[] getData() { return data.clone(); }
    }

    public static final class CommandExecPacket implements Packet {
        private final String command;
        private final byte[] payload;
        private final long timestamp;

        public CommandExecPacket(String command, byte[] payload, long timestamp) {
            this.command = command == null ? "" : command;
            this.payload = payload == null ? new byte[0] : payload.clone();
            this.timestamp = timestamp;
        }

        @Override
        public PacketType getType() {
            return PacketType.COMMAND_EXEC;
        }

        @Override
        public void serialize(DataOutputStream out) throws IOException {
            out.writeUTF(command);
            out.writeLong(timestamp);
            out.writeInt(payload.length);
            out.write(payload);
        }

        public static CommandExecPacket deserialize(DataInputStream in) throws IOException {
            String command = in.readUTF();
            long timestamp = in.readLong();
            int len = in.readInt();
            if (len < 0) {
                throw new IllegalArgumentException("Invalid payload length");
            }
            byte[] payload = new byte[len];
            in.readFully(payload);
            return new CommandExecPacket(command, payload, timestamp);
        }

        public String getCommand() { return command; }
        public byte[] getPayload() { return payload.clone(); }
        public long getTimestamp() { return timestamp; }
    }

    public static final class TelemetryUpdatePacket implements Packet {
        private final String metric;
        private final double value;
        private final long timestamp;

        public TelemetryUpdatePacket(String metric, double value, long timestamp) {
            this.metric = metric == null ? "" : metric;
            this.value = value;
            this.timestamp = timestamp;
        }

        @Override
        public PacketType getType() {
            return PacketType.TELEMETRY_UPDATE;
        }

        @Override
        public void serialize(DataOutputStream out) throws IOException {
            out.writeUTF(metric);
            out.writeDouble(value);
            out.writeLong(timestamp);
        }

        public static TelemetryUpdatePacket deserialize(DataInputStream in) throws IOException {
            String metric = in.readUTF();
            double value = in.readDouble();
            long timestamp = in.readLong();
            return new TelemetryUpdatePacket(metric, value, timestamp);
        }

        public String getMetric() { return metric; }
        public double getValue() { return value; }
        public long getTimestamp() { return timestamp; }
    }

    public static final class CacheInvalidatePacket implements Packet {
        private final String cacheName;
        private final boolean allCaches;
        private final long timestamp;

        public CacheInvalidatePacket(String cacheName, boolean allCaches, long timestamp) {
            this.cacheName = cacheName == null ? "" : cacheName;
            this.allCaches = allCaches;
            this.timestamp = timestamp;
        }

        @Override
        public PacketType getType() {
            return PacketType.CACHE_INVALIDATE;
        }

        @Override
        public void serialize(DataOutputStream out) throws IOException {
            out.writeUTF(cacheName);
            out.writeBoolean(allCaches);
            out.writeLong(timestamp);
        }

        public static CacheInvalidatePacket deserialize(DataInputStream in) throws IOException {
            String cacheName = in.readUTF();
            boolean allCaches = in.readBoolean();
            long timestamp = in.readLong();
            return new CacheInvalidatePacket(cacheName, allCaches, timestamp);
        }

        public String getCacheName() { return cacheName; }
        public boolean isAllCaches() { return allCaches; }
        public long getTimestamp() { return timestamp; }
    }

    public static final class KeepAlivePacket implements Packet {
        private final long timestamp;

        public KeepAlivePacket(long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public PacketType getType() {
            return PacketType.KEEPALIVE;
        }

        @Override
        public void serialize(DataOutputStream out) throws IOException {
            out.writeLong(timestamp);
        }

        public static KeepAlivePacket deserialize(DataInputStream in) throws IOException {
            long ts = in.readLong();
            return new KeepAlivePacket(ts);
        }

        public long getTimestamp() { return timestamp; }
    }

    public static byte[] serialize(Packet packet) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(packet.getType().getId());
        packet.serialize(dos);

        return baos.toByteArray();
    }

    public static Packet deserialize(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);

        int typeId = dis.readInt();
        PacketType type = PacketType.fromId(typeId);

        return switch (type) {
            case HANDSHAKE_INIT -> HandshakeInitPacket.deserialize(dis);
            case HANDSHAKE_RESPONSE -> HandshakeResponsePacket.deserialize(dis);
            case DATA_SYNC -> DataSyncPacket.deserialize(dis);
            case COMMAND_EXEC -> CommandExecPacket.deserialize(dis);
            case TELEMETRY_UPDATE -> TelemetryUpdatePacket.deserialize(dis);
            case CACHE_INVALIDATE -> CacheInvalidatePacket.deserialize(dis);
            case KEEPALIVE -> KeepAlivePacket.deserialize(dis);
            default -> throw new IllegalArgumentException("Unsupported packet type: " + type);
        };
    }
}
