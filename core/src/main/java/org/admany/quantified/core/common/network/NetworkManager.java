package org.admany.quantified.core.common.network;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.network.transport.DataTransport;

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NetworkManager {

    private static final Logger LOGGER = Logger.getLogger(NetworkManager.class.getName());

    private static final int MAX_ENVELOPE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 1 * 1024 * 1024;
    private static final long MAX_HANDSHAKE_AGE_MS = 10_000;
    private static final long KEEPALIVE_INTERVAL_MS = 10_000;
    private static final long IDLE_TIMEOUT_MS = 30_000;
    private static final long MAX_BYTES_PER_SECOND = 512 * 1024;

    private final ConcurrentHashMap<UUID, SecureChannel> channels;
    private final ConcurrentHashMap<PacketSerializer.PacketType, BiConsumer<UUID, PacketSerializer.Packet>> packetHandlers;
    private final ConcurrentHashMap<UUID, Long> lastSeen;
    private final ConcurrentHashMap<UUID, RateLimiter> rateLimiters;
    private final ConcurrentHashMap<UUID, Long> pendingHandshakes;

    private volatile Consumer<byte[]> packetSender;
    private volatile DataTransport transport;
    private volatile BiPredicate<UUID, PublicKey> trustVerifier = (id, key) -> true;

    public NetworkManager() {
        this.channels = new ConcurrentHashMap<>();
        this.packetHandlers = new ConcurrentHashMap<>();
        this.lastSeen = new ConcurrentHashMap<>();
        this.rateLimiters = new ConcurrentHashMap<>();
        this.pendingHandshakes = new ConcurrentHashMap<>();
    }

    public void initialize() {
        registerDefaultHandlers();
        LOGGER.fine("Network manager initialized");
    }

    public void setPacketSender(Consumer<byte[]> sender) {
        this.packetSender = sender;
    }

    public void attachTransport(DataTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport cannot be null");
        }
        this.transport = transport;
        transport.setReceiveHandler(this::receivePacket);
        setPacketSender(bytes -> {
            try {
                transport.send(bytes);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to send via transport", e);
            }
        });
    }

    public void detachTransport() {
        DataTransport t = this.transport;
        this.transport = null;
        if (t != null) {
            try {
                t.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void registerHandler(PacketSerializer.PacketType type, BiConsumer<UUID, PacketSerializer.Packet> handler) {
        packetHandlers.put(type, handler);
    }

    public BiConsumer<UUID, PacketSerializer.Packet> getHandler(PacketSerializer.PacketType type) {
        return packetHandlers.get(type);
    }

    public void wrapHandler(PacketSerializer.PacketType type,
                            Function<BiConsumer<UUID, PacketSerializer.Packet>, BiConsumer<UUID, PacketSerializer.Packet>> wrapper) {
        if (type == null || wrapper == null) {
            return;
        }
        packetHandlers.compute(type, (t, existing) -> wrapper.apply(existing));
    }

    public void wrapHandler(PacketSerializer.PacketType type, BiPredicate<UUID, PacketSerializer.Packet> pre) {
        if (type == null || pre == null) {
            return;
        }
        packetHandlers.computeIfPresent(type, (t, existing) -> (id, packet) -> {
            if (pre.test(id, packet)) {
                existing.accept(id, packet);
            }
        });
    }

    public void setTrustVerifier(BiPredicate<UUID, PublicKey> verifier) {
        if (verifier != null) {
            this.trustVerifier = verifier;
        }
    }

    public CompletableFuture<SecureChannel> createChannel() {
        return AsyncManager.submitSync(
            UUID.randomUUID().getMostSignificantBits(),
            PriorityTaskType.OTHER,
            0.7,
            () -> {
                UUID channelId = UUID.randomUUID();
                SecureChannel channel = new SecureChannel(channelId);
                channels.put(channelId, channel);
                return channel;
            },
            "quantified-network"
        );
    }

    public CompletableFuture<Void> sendPacket(UUID channelId, PacketSerializer.Packet packet) {
        if (packet == null) {
            return CompletableFuture.completedFuture(null);
        }
        return AsyncManager.submitSync(
            channelId.getMostSignificantBits(),
            PriorityTaskType.OTHER,
            0.8,
            () -> {
                try {
                    SecureChannel channel = channels.get(channelId);
                    if (channel == null) {
                        throw new IllegalArgumentException("Unknown channel: " + channelId);
                    }

                    byte[] serialized = PacketSerializer.serialize(packet);
                    if (serialized.length > MAX_PAYLOAD_BYTES) {
                        throw new IllegalArgumentException("Payload exceeds limit: " + serialized.length);
                    }
                    if (!allowBytes(channelId, serialized.length)) {
                        LOGGER.warning("Rate limit exceeded for channel " + channelId);
                        return null;
                    }
                    boolean encryptPayload = channel.getState() == SecureChannel.ChannelState.ESTABLISHED
                        && packet.getType() != PacketSerializer.PacketType.HANDSHAKE_INIT
                        && packet.getType() != PacketSerializer.PacketType.HANDSHAKE_RESPONSE;

                    if (packetSender != null) {
                        ByteBuffer envelope;
                        if (encryptPayload) {
                            EncryptedPacket encrypted = channel.encrypt(serialized);
                            byte[] encryptedBytes = encrypted.toBytes();
                            envelope = ByteBuffer.allocate(1 + 16 + encryptedBytes.length);
                            envelope.put((byte)1);
                            envelope.putLong(channelId.getMostSignificantBits());
                            envelope.putLong(channelId.getLeastSignificantBits());
                            envelope.put(encryptedBytes);
                        } else {
                            envelope = ByteBuffer.allocate(1 + 16 + serialized.length);
                            envelope.put((byte)0);
                            envelope.putLong(channelId.getMostSignificantBits());
                            envelope.putLong(channelId.getLeastSignificantBits());
                            envelope.put(serialized);
                        }
                        if (envelope.capacity() > MAX_ENVELOPE_BYTES) {
                            throw new IllegalArgumentException("Envelope exceeds limit: " + envelope.capacity());
                        }
                        packetSender.accept(envelope.array());
                        touch(channelId);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failed to send packet", e);
                }
                return null;
            },
            "quantified-network"
        );
    }
    
    public CompletableFuture<Void> broadcast(PacketSerializer.Packet packet) {
        if (packet == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<?>[] futures = channels.keySet().stream()
            .map(channelId -> sendPacket(channelId, packet))
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    public void receivePacket(byte[] encryptedData) {
        AsyncManager.submitSync(
            System.nanoTime(),
            PriorityTaskType.OTHER,
            0.9,
            () -> {
                try {
                    if (encryptedData == null || encryptedData.length == 0 || encryptedData.length > MAX_ENVELOPE_BYTES) {
                        throw new IllegalArgumentException("Invalid envelope length");
                    }
                    ByteBuffer envelope = ByteBuffer.wrap(encryptedData);
                    if (envelope.remaining() < 17) {
                        throw new IllegalArgumentException("Packet too short");
                    }
                    byte flag = envelope.get();
                    UUID channelId = new UUID(envelope.getLong(), envelope.getLong());
                    SecureChannel channel = channels.computeIfAbsent(channelId, SecureChannel::new);

                    byte[] remaining = new byte[envelope.remaining()];
                    envelope.get(remaining);

                    if (!allowBytes(channelId, remaining.length)) {
                        LOGGER.warning("Rate limit exceeded for channel " + channelId);
                        return null;
                    }

                    byte[] payload;
                    if (flag == 1) {
                        EncryptedPacket encrypted = EncryptedPacket.fromBytes(remaining);
                        payload = channel.decrypt(encrypted);
                    } else {
                        payload = remaining;
                    }
                    if (payload.length > MAX_PAYLOAD_BYTES) {
                        throw new IllegalArgumentException("Payload exceeds limit");
                    }

                    PacketSerializer.Packet packet = PacketSerializer.deserialize(payload);
                    touch(channelId);

                    BiConsumer<UUID, PacketSerializer.Packet> handler = packetHandlers.get(packet.getType());
                    if (handler != null) {
                        handler.accept(channelId, packet);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to process received packet", e);
                }
                return null;
            },
            "quantified-network"
        );
    }

    public CompletableFuture<Void> initiateHandshake(UUID channelId) {
        return AsyncManager.submitSync(
            channelId.getMostSignificantBits(),
            PriorityTaskType.OTHER,
            0.6,
            () -> {
                try {
                    SecureChannel channel = channels.get(channelId);
                    if (channel == null) {
                        throw new IllegalArgumentException("Unknown channel: " + channelId);
                    }

                    SecureChannel.KeyExchangeData keyData = channel.initialize();
                    UUID clientId = UUID.randomUUID();

                    PacketSerializer.HandshakeInitPacket handshake = new PacketSerializer.HandshakeInitPacket(
                        clientId, channelId, keyData.publicKey.getEncoded(), System.currentTimeMillis());

                    pendingHandshakes.put(channelId, System.currentTimeMillis());
                    sendPacket(channelId, handshake).join();

                } catch (NoSuchAlgorithmException e) {
                    LOGGER.log(Level.SEVERE, "Failed to initialize key exchange", e);
                }
                return null;
            },
            "quantified-network"
        );
    }

    public SecureChannel getChannel(UUID channelId) {
        return channels.get(channelId);
    }

    public void shutdown() {
        detachTransport();
        channels.values().forEach(SecureChannel::close);
        channels.clear();
    }

    private void registerDefaultHandlers() {
        registerHandler(PacketSerializer.PacketType.HANDSHAKE_INIT, this::handleHandshakeInit);
        registerHandler(PacketSerializer.PacketType.HANDSHAKE_RESPONSE, this::handleHandshakeResponse);
        registerHandler(PacketSerializer.PacketType.DATA_SYNC, this::handleDataSync);
        registerHandler(PacketSerializer.PacketType.COMMAND_EXEC, this::handleCommandExec);
        registerHandler(PacketSerializer.PacketType.TELEMETRY_UPDATE, this::handleTelemetryUpdate);
        registerHandler(PacketSerializer.PacketType.CACHE_INVALIDATE, this::handleCacheInvalidate);
        registerHandler(PacketSerializer.PacketType.KEEPALIVE, this::handleKeepAlive);
    }

    private void handleHandshakeInit(UUID channelId, PacketSerializer.Packet packet) {
        PacketSerializer.HandshakeInitPacket handshake = (PacketSerializer.HandshakeInitPacket) packet;
        LOGGER.fine("Received handshake init from " + handshake.getClientId());

        try {
            if (Math.abs(System.currentTimeMillis() - handshake.getTimestamp()) > MAX_HANDSHAKE_AGE_MS) {
                throw new SecurityException("Stale handshake init");
            }
            SecureChannel channel = channels.computeIfAbsent(handshake.getChannelId(), SecureChannel::new);
            SecureChannel.KeyExchangeData keyData = channel.initialize();
            PublicKey clientPub = SecureChannel.decodePublicKey(handshake.getPublicKey());
            if (!trustVerifier.test(handshake.getClientId(), clientPub)) {
                throw new SecurityException("Untrusted client key");
            }
            channel.completeKeyExchange(keyData, clientPub);

            PacketSerializer.HandshakeResponsePacket response = new PacketSerializer.HandshakeResponsePacket(
                UUID.randomUUID(), handshake.getChannelId(), keyData.publicKey.getEncoded(), System.currentTimeMillis());
            sendPacket(handshake.getChannelId(), response);
            touch(handshake.getChannelId());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to respond to handshake", e);
        }
    }

    private void handleHandshakeResponse(UUID channelId, PacketSerializer.Packet packet) {
        PacketSerializer.HandshakeResponsePacket response = (PacketSerializer.HandshakeResponsePacket) packet;
        LOGGER.fine("Received handshake response from " + response.getServerId());

        try {
            SecureChannel channel = channels.get(response.getChannelId());
            if (channel == null) {
                throw new IllegalStateException("Missing channel for handshake response " + response.getChannelId());
            }
            Long sentAt = pendingHandshakes.remove(response.getChannelId());
            if (sentAt == null || (System.currentTimeMillis() - sentAt) > MAX_HANDSHAKE_AGE_MS) {
                throw new SecurityException("Handshake response timed out");
            }
            SecureChannel.KeyExchangeData local = channel.getPendingKeys();
            if (local == null) {
                throw new IllegalStateException("No pending key data for channel " + response.getChannelId());
            }
            PublicKey serverPub = SecureChannel.decodePublicKey(response.getPublicKey());
            if (!trustVerifier.test(response.getServerId(), serverPub)) {
                throw new SecurityException("Untrusted server key");
            }
            channel.completeKeyExchange(local, serverPub);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to complete key exchange", e);
        }
    }

    private void handleDataSync(UUID channelId, PacketSerializer.Packet packet) {
        PacketSerializer.DataSyncPacket dataPacket = (PacketSerializer.DataSyncPacket) packet;
        LOGGER.fine("Received data sync: " + dataPacket.getDataType() + " (" + dataPacket.getData().length + " bytes)");
    }

    private void handleCommandExec(UUID channelId, PacketSerializer.Packet packet) {
        if (packet instanceof PacketSerializer.CommandExecPacket cmd) {
            if (!cmd.getCommand().isEmpty()) {
                LOGGER.warning("Ignoring COMMAND_EXEC from " + channelId + ": " + cmd.getCommand());
            }
        }
    }

    private void handleTelemetryUpdate(UUID channelId, PacketSerializer.Packet packet) {
        if (packet instanceof PacketSerializer.TelemetryUpdatePacket t) {
            if (!t.getMetric().isEmpty()) {
                LOGGER.fine("Telemetry update from " + channelId + ": " + t.getMetric() + "=" + t.getValue());
            }
        }
    }

    private void handleCacheInvalidate(UUID channelId, PacketSerializer.Packet packet) {
        if (packet instanceof PacketSerializer.CacheInvalidatePacket c) {
            CacheManager.clearAllCaches();
            if (c.isAllCaches() || c.getCacheName().isEmpty()) {
                LOGGER.fine("Cache invalidate from " + channelId + ": all");
            } else {
                LOGGER.fine("Cache invalidate from " + channelId + ": " + c.getCacheName());
            }
        }
    }

    private void handleKeepAlive(UUID channelId, PacketSerializer.Packet packet) {
        touch(channelId);
    }

    public void pumpKeepAlives() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : lastSeen.entrySet()) {
            UUID id = entry.getKey();
            long last = entry.getValue();
            if (now - last > KEEPALIVE_INTERVAL_MS && (now - last) < IDLE_TIMEOUT_MS) {
                sendPacket(id, new PacketSerializer.KeepAlivePacket(Instant.now().toEpochMilli()));
            }
        }
    }

    private void touch(UUID channelId) {
        lastSeen.put(channelId, System.currentTimeMillis());
    }

    private boolean allowBytes(UUID channelId, int length) {
        RateLimiter limiter = rateLimiters.computeIfAbsent(channelId, k -> new RateLimiter(MAX_BYTES_PER_SECOND));
        return limiter.tryConsume(length);
    }

    private static final class RateLimiter {
        private final long ratePerSecond;
        private double tokens;
        private long lastRefill;

        RateLimiter(long ratePerSecond) {
            this.ratePerSecond = Math.max(1024, ratePerSecond);
            this.tokens = this.ratePerSecond;
            this.lastRefill = System.nanoTime();
        }

        synchronized boolean tryConsume(int bytes) {
            refill();
            if (bytes > ratePerSecond) {
                return false;
            }
            if (tokens >= bytes) {
                tokens -= bytes;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefill) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(ratePerSecond, tokens + elapsedSeconds * ratePerSecond);
                lastRefill = now;
            }
        }
    }
}
