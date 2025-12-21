package org.admany.quantified.core.common.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MotherNode {

    private static final Logger LOGGER = Logger.getLogger(MotherNode.class.getName());

    private static final long MAX_HANDSHAKE_AGE_MS = 10_000L;

    private final NetworkManager networkManager;
    private final ConcurrentHashMap<UUID, ChildProfile> childrenById;
    private final ConcurrentHashMap<UUID, ChildProfile> childrenByChannel;
    private final AtomicLong anomalyCounter;
    private final PacketValidator validator;

    private volatile boolean active;

    public MotherNode(NetworkManager networkManager) {
        this.networkManager = networkManager;
        this.childrenById = new ConcurrentHashMap<>();
        this.childrenByChannel = new ConcurrentHashMap<>();
        this.anomalyCounter = new AtomicLong();
        this.validator = new PacketValidator();
        this.active = false;
    }

    public void activate() {
        if (active) return;

        active = true;

        networkManager.wrapHandler(PacketSerializer.PacketType.HANDSHAKE_INIT, this::preHandleHandshake);
        networkManager.wrapHandler(PacketSerializer.PacketType.HANDSHAKE_RESPONSE, this::preHandlePacket);
        networkManager.wrapHandler(PacketSerializer.PacketType.DATA_SYNC, this::preHandlePacket);
        networkManager.wrapHandler(PacketSerializer.PacketType.COMMAND_EXEC, this::preHandlePacket);
        networkManager.wrapHandler(PacketSerializer.PacketType.TELEMETRY_UPDATE, this::preHandlePacket);
        networkManager.wrapHandler(PacketSerializer.PacketType.CACHE_INVALIDATE, this::preHandlePacket);
        networkManager.wrapHandler(PacketSerializer.PacketType.KEEPALIVE, this::preHandlePacket);

        LOGGER.fine("Mother node activated - monitoring child communications");
    }

    public void deactivate() {
        active = false;
        childrenById.clear();
        childrenByChannel.clear();
        LOGGER.fine("Mother node deactivated");
    }

    public void registerChild(UUID childId, String clientInfo) {
        childrenById.computeIfAbsent(childId, id -> new ChildProfile(id, clientInfo, System.currentTimeMillis()));
        LOGGER.fine("Registered child: " + childId + " (" + clientInfo + ")");
    }

    public void unregisterChild(UUID childId) {
        ChildProfile removed = childrenById.remove(childId);
        if (removed != null && removed.getChannelId() != null) {
            childrenByChannel.remove(removed.getChannelId(), removed);
        }
        LOGGER.fine("Unregistered child: " + childId);
    }

    public SecurityStats getSecurityStats() {
        long totalPackets = childrenById.values().stream()
            .mapToLong(ChildProfile::getPacketsProcessed)
            .sum();
        long anomalies = anomalyCounter.get();
        return new SecurityStats(totalPackets, anomalies, childrenById.size());
    }

    private boolean preHandleHandshake(UUID channelId, PacketSerializer.Packet packet) {
        if (!(packet instanceof PacketSerializer.HandshakeInitPacket handshake)) {
            return true;
        }

        if (!validator.validateHandshake(handshake)) {
            logAnomaly(channelId, "INVALID_HANDSHAKE", "Handshake validation failed");
            quarantineChannel(channelId, "invalid handshake");
            return false;
        }

        ChildProfile profile = childrenById.computeIfAbsent(
            handshake.getClientId(),
            id -> new ChildProfile(id, "channel:" + channelId, System.currentTimeMillis()));
        profile.setChannelId(channelId);
        childrenByChannel.put(channelId, profile);
        profile.onAcceptedPacket(handshake);
        return true;
    }

    private boolean preHandlePacket(UUID channelId, PacketSerializer.Packet packet) {
        ChildProfile profile = childrenByChannel.get(channelId);
        if (profile == null) {
            if (packet.getType() != PacketSerializer.PacketType.HANDSHAKE_INIT) {
                logAnomaly(channelId, "UNKNOWN_CHILD", "Packet from unregistered channel");
            }
            return packet.getType() == PacketSerializer.PacketType.HANDSHAKE_INIT;
        }

        if (profile.isQuarantined()) {
            return false;
        }

        if (profile.isRateLimited()) {
            logAnomaly(channelId, "RATE_LIMIT_EXCEEDED", "Too many packets in time window");
            profile.recordAnomaly();
            quarantineChannel(channelId, "rate limit");
            return false;
        }

        ValidationResult result = validator.validatePacket(packet, profile);
        if (!result.isValid()) {
            logAnomaly(channelId, result.getReason(), result.getDetails());
            profile.recordAnomaly();
            if (profile.shouldQuarantine()) {
                quarantineChannel(channelId, "validation: " + result.getReason());
            }
            return false;
        }

        profile.onAcceptedPacket(packet);
        return true;
    }

    private void logAnomaly(UUID channelId, String type, String details) {
        anomalyCounter.incrementAndGet();
        LOGGER.log(Level.WARNING, "SECURITY ANOMALY [{0}] Channel: {1} - {2}",
            new Object[]{type, channelId, details});
    }

    private void quarantineChannel(UUID channelId, String reason) {
        ChildProfile profile = childrenByChannel.get(channelId);
        if (profile != null) {
            profile.quarantine();
        }
        try {
            SecureChannel channel = networkManager.getChannel(channelId);
            if (channel != null) {
                channel.close();
            }
        } catch (Throwable ignored) {
        }
        LOGGER.log(Level.SEVERE, "QUARANTINED CHANNEL: {0} ({1})", new Object[]{channelId, reason});
    }

    private static final class PacketValidator {

        private final Map<String, PatternAnalyzer> analyzers;

        PacketValidator() {
            this.analyzers = Map.of(
                PacketSerializer.PacketType.HANDSHAKE_INIT.name(), new HandshakeInitAnalyzer(),
                PacketSerializer.PacketType.HANDSHAKE_RESPONSE.name(), new HandshakeResponseAnalyzer(),
                PacketSerializer.PacketType.DATA_SYNC.name(), new DataSyncAnalyzer(),
                PacketSerializer.PacketType.COMMAND_EXEC.name(), new CommandExecAnalyzer(),
                PacketSerializer.PacketType.TELEMETRY_UPDATE.name(), new TelemetryUpdateAnalyzer(),
                PacketSerializer.PacketType.CACHE_INVALIDATE.name(), new CacheInvalidateAnalyzer(),
                PacketSerializer.PacketType.KEEPALIVE.name(), new KeepAliveAnalyzer()
            );
        }

        boolean validateHandshake(PacketSerializer.HandshakeInitPacket handshake) {
            long now = System.currentTimeMillis();
            long age = now - handshake.getTimestamp();

            if (Math.abs(age) > MAX_HANDSHAKE_AGE_MS) {
                return false;
            }
            byte[] key = handshake.getPublicKey();
            if (key == null) {
                return false;
            }
            int len = key.length;
            if (len < 32 || len > 4096) {
                return false;
            }
            return true;
        }

        ValidationResult validatePacket(PacketSerializer.Packet packet, ChildProfile profile) {
            String packetType = packet.getType().name();
            PatternAnalyzer analyzer = analyzers.get(packetType);

            if (analyzer == null) {
                return ValidationResult.invalid("UNKNOWN_TYPE", "Unsupported packet type");
            }

            return analyzer.analyze(packet, profile);
        }
    }

    private interface PatternAnalyzer {
        ValidationResult analyze(PacketSerializer.Packet packet, ChildProfile profile);
    }

    private static final class HandshakeInitAnalyzer implements PatternAnalyzer {
        @Override
        public ValidationResult analyze(PacketSerializer.Packet packet, ChildProfile profile) {
            return ValidationResult.valid();
        }
    }

    private static final class HandshakeResponseAnalyzer implements PatternAnalyzer {
        @Override
        public ValidationResult analyze(PacketSerializer.Packet packet, ChildProfile profile) {
            return ValidationResult.valid();
        }
    }

    private static final class DataSyncAnalyzer implements PatternAnalyzer {
        @Override
        public ValidationResult analyze(PacketSerializer.Packet packet, ChildProfile profile) {
            PacketSerializer.DataSyncPacket dataPacket = (PacketSerializer.DataSyncPacket) packet;

            if (dataPacket.getData().length > 1024 * 1024) {
                return ValidationResult.invalid("OVERSIZED_PAYLOAD", "Data payload too large");
            }

            if (containsSuspiciousPatterns(dataPacket.getDataType(), dataPacket.getData())) {
                return ValidationResult.invalid("MALICIOUS_CONTENT", "Suspicious data patterns detected");
            }

            return ValidationResult.valid();
        }

        private boolean containsSuspiciousPatterns(String dataType, byte[] data) {
            if (data == null || data.length == 0) {
                return false;
            }
            String type = dataType == null ? "" : dataType.toLowerCase();
            boolean likelyText = type.contains("text") || type.contains("json") || type.contains("xml")
                || type.contains("html") || type.contains("cmd") || type.contains("script");
            if (!likelyText) {
                return false;
            }

            int scanLen = Math.min(data.length, 64 * 1024);
            String dataStr;
            try {
                dataStr = new String(data, 0, scanLen).toLowerCase();
            } catch (Throwable ignored) {
                return false;
            }
            return dataStr.contains("exploit")
                || dataStr.contains("hack")
                || dataStr.contains("<script")
                || dataStr.contains("javascript:");
        }
    }

    private static final class KeepAliveAnalyzer implements PatternAnalyzer {
        @Override
        public ValidationResult analyze(PacketSerializer.Packet packet, ChildProfile profile) {
            return ValidationResult.valid();
        }
    }

    private static final class CommandExecAnalyzer implements PatternAnalyzer {
        @Override
        public ValidationResult analyze(PacketSerializer.Packet packet, ChildProfile profile) {
            PacketSerializer.CommandExecPacket cmd = (PacketSerializer.CommandExecPacket) packet;
            if (cmd.getCommand().length() > 256) {
                return ValidationResult.invalid("COMMAND_TOO_LONG", "Command exceeds limit");
            }
            if (cmd.getPayload().length > 256 * 1024) {
                return ValidationResult.invalid("OVERSIZED_PAYLOAD", "Command payload too large");
            }
            long now = System.currentTimeMillis();
            if (Math.abs(now - cmd.getTimestamp()) > 60_000L) {
                return ValidationResult.invalid("STALE_TIMESTAMP", "Command timestamp out of range");
            }
            return ValidationResult.valid();
        }
    }

    private static final class TelemetryUpdateAnalyzer implements PatternAnalyzer {
        @Override
        public ValidationResult analyze(PacketSerializer.Packet packet, ChildProfile profile) {
            PacketSerializer.TelemetryUpdatePacket t = (PacketSerializer.TelemetryUpdatePacket) packet;
            if (t.getMetric().length() > 256) {
                return ValidationResult.invalid("METRIC_TOO_LONG", "Metric exceeds limit");
            }
            if (!Double.isFinite(t.getValue())) {
                return ValidationResult.invalid("INVALID_VALUE", "Telemetry value is not finite");
            }
            long now = System.currentTimeMillis();
            if (Math.abs(now - t.getTimestamp()) > 5 * 60_000L) {
                return ValidationResult.invalid("STALE_TIMESTAMP", "Telemetry timestamp out of range");
            }
            return ValidationResult.valid();
        }
    }

    private static final class CacheInvalidateAnalyzer implements PatternAnalyzer {
        @Override
        public ValidationResult analyze(PacketSerializer.Packet packet, ChildProfile profile) {
            PacketSerializer.CacheInvalidatePacket c = (PacketSerializer.CacheInvalidatePacket) packet;
            if (c.getCacheName().length() > 256) {
                return ValidationResult.invalid("CACHE_NAME_TOO_LONG", "Cache name exceeds limit");
            }
            long now = System.currentTimeMillis();
            if (Math.abs(now - c.getTimestamp()) > 60_000L) {
                return ValidationResult.invalid("STALE_TIMESTAMP", "Cache invalidate timestamp out of range");
            }
            return ValidationResult.valid();
        }
    }

    private static final class ValidationResult {
        private final boolean valid;
        private final String reason;
        private final String details;

        private ValidationResult(boolean valid, String reason, String details) {
            this.valid = valid;
            this.reason = reason;
            this.details = details;
        }

        static ValidationResult valid() {
            return new ValidationResult(true, null, null);
        }

        static ValidationResult invalid(String reason, String details) {
            return new ValidationResult(false, reason, details);
        }

        boolean isValid() { return valid; }
        String getReason() { return reason; }
        String getDetails() { return details; }
    }

    public static final class ChildProfile {
        private final UUID childId;
        private final String clientInfo;
        private final long registeredAt;
        private final AtomicLong packetsProcessed;
        private final AtomicLong anomaliesDetected;
        private volatile UUID channelId;
        private volatile boolean quarantined;
        private volatile long lastActivity;
        private volatile long lastPacketTime;
        private volatile int packetsInWindow;

        public ChildProfile(UUID childId, String clientInfo, long registeredAt) {
            this.childId = childId;
            this.clientInfo = clientInfo;
            this.registeredAt = registeredAt;
            this.packetsProcessed = new AtomicLong();
            this.anomaliesDetected = new AtomicLong();
            this.lastActivity = registeredAt;
            this.lastPacketTime = registeredAt;
            this.packetsInWindow = 0;
        }

        public void onAcceptedPacket(PacketSerializer.Packet packet) {
            packetsProcessed.incrementAndGet();
            lastActivity = System.currentTimeMillis();
            updateRateLimit();
        }

        private void updateRateLimit() {
            long now = System.currentTimeMillis();
            if (now - lastPacketTime > 60_000) {
                packetsInWindow = 1;
                lastPacketTime = now;
            } else {
                packetsInWindow++;
            }
        }

        public boolean isRateLimited() {
            return packetsInWindow > 100;
        }

        public void recordAnomaly() {
            anomaliesDetected.incrementAndGet();
        }

        public boolean shouldQuarantine() {
            long packets = packetsProcessed.get();
            long anomalies = anomaliesDetected.get();
            return (packets > 10 && (anomalies * 100.0 / packets) > 20.0) || isRateLimited();
        }

        public void quarantine() {
            quarantined = true;
        }

        public UUID getChildId() { return childId; }
        public String getClientInfo() { return clientInfo; }
        public long getRegisteredAt() { return registeredAt; }
        public long getPacketsProcessed() { return packetsProcessed.get(); }
        public long getAnomaliesDetected() { return anomaliesDetected.get(); }
        public boolean isQuarantined() { return quarantined; }
        public UUID getChannelId() { return channelId; }
        public long getLastActivity() { return lastActivity; }

        public void setChannelId(UUID channelId) { this.channelId = channelId; }
    }

    public static final class SecurityStats {
        public final long totalPacketsProcessed;
        public final long totalAnomalies;
        public final int activeChildren;

        public SecurityStats(long totalPacketsProcessed, long totalAnomalies, int activeChildren) {
            this.totalPacketsProcessed = totalPacketsProcessed;
            this.totalAnomalies = totalAnomalies;
            this.activeChildren = activeChildren;
        }
    }
}
