package org.admany.quantified.core.common.network;

import javax.crypto.KeyAgreement;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class SecureChannel {

    private static final String ECDH_ALGORITHM = "ECDH";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UUID channelId;
    private final AtomicReference<ChannelState> state;
    private final AtomicReference<KeyExchangeData> pendingKeys;
    private final AtomicReference<SessionKey> sessionKey;
    private final AtomicLong sequenceCounter;
    private final AtomicLong lastSequence;

    public SecureChannel(UUID channelId) {
        this.channelId = channelId;
        this.state = new AtomicReference<>(ChannelState.INITIALIZING);
        this.pendingKeys = new AtomicReference<>(null);
        this.sessionKey = new AtomicReference<>(null);
        this.sequenceCounter = new AtomicLong(0);
        this.lastSequence = new AtomicLong(0);
    }

    public UUID getChannelId() {
        return channelId;
    }

    public KeyExchangeData initialize() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ECDH_ALGORITHM);
        keyGen.initialize(256, RANDOM);
        KeyPair keyPair = keyGen.generateKeyPair();

        KeyExchangeData keys = new KeyExchangeData(channelId, keyPair.getPublic(), keyPair.getPrivate());
        pendingKeys.set(keys);
        state.set(ChannelState.HANDSHAKING);
        return keys;
    }

    public void completeKeyExchange(KeyExchangeData localKeys, PublicKey remotePublicKey) throws Exception {
        KeyAgreement keyAgreement = KeyAgreement.getInstance(ECDH_ALGORITHM);
        keyAgreement.init(localKeys.privateKey);
        keyAgreement.doPhase(remotePublicKey, true);

        byte[] sharedSecret = keyAgreement.generateSecret();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(sharedSecret);
        digest.update("aes".getBytes());
        byte[] aesKey = Arrays.copyOf(digest.digest(), 32);

        digest.reset();
        digest.update(sharedSecret);
        digest.update("hmac".getBytes());
        byte[] hmacKey = Arrays.copyOf(digest.digest(), 32);

        SessionKey key = new SessionKey(aesKey, hmacKey);
        sessionKey.set(key);
        state.set(ChannelState.ESTABLISHED);

        Arrays.fill(sharedSecret, (byte) 0);
    }

    public EncryptedPacket encrypt(byte[] data) throws Exception {
        SessionKey key = sessionKey.get();
        if (key == null || state.get() != ChannelState.ESTABLISHED) {
            throw new IllegalStateException("Channel not established");
        }
        long seq = sequenceCounter.incrementAndGet();
        return EncryptedPacket.encrypt(data, key.aesKey, key.hmacKey, seq);
    }

    public byte[] decrypt(EncryptedPacket packet) throws Exception {
        SessionKey key = sessionKey.get();
        if (key == null || state.get() != ChannelState.ESTABLISHED) {
            throw new IllegalStateException("Channel not established");
        }
        byte[] result = packet.decrypt(key.aesKey, key.hmacKey, lastSequence.get());
        lastSequence.set(packet.getSequenceNumber());
        return result;
    }

    public ChannelState getState() {
        return state.get();
    }

    public void rotateKey() {
        SessionKey oldKey = sessionKey.get();
        if (oldKey != null) {
            SessionKey newSessionKey = new SessionKey();
            sessionKey.set(newSessionKey);
            Arrays.fill(oldKey.aesKey, (byte) 0);
            Arrays.fill(oldKey.hmacKey, (byte) 0);
        }
    }

    public void close() {
        SessionKey key = sessionKey.getAndSet(null);
        if (key != null) {
            Arrays.fill(key.aesKey, (byte) 0);
            Arrays.fill(key.hmacKey, (byte) 0);
        }
        pendingKeys.set(null);
        lastSequence.set(0);
        state.set(ChannelState.CLOSED);
    }

    public KeyExchangeData getPendingKeys() {
        return pendingKeys.get();
    }

    public static PublicKey decodePublicKey(byte[] encoded) throws Exception {
        if (encoded == null || encoded.length == 0) {
            throw new IllegalArgumentException("Public key bytes missing");
        }
        KeyFactory kf = KeyFactory.getInstance(ECDH_ALGORITHM);
        return kf.generatePublic(new X509EncodedKeySpec(encoded));
    }

    public enum ChannelState {
        INITIALIZING,
        HANDSHAKING,
        ESTABLISHED,
        CLOSED
    }

    public static final class KeyExchangeData {
        public final UUID channelId;
        public final PublicKey publicKey;
        public final PrivateKey privateKey;

        public KeyExchangeData(UUID channelId, PublicKey publicKey, PrivateKey privateKey) {
            this.channelId = channelId;
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }
    }

    private static final class SessionKey {
        final byte[] aesKey;
        final byte[] hmacKey;

        SessionKey(byte[] aesKey, byte[] hmacKey) {
            this.aesKey = aesKey.clone();
            this.hmacKey = hmacKey.clone();
        }

        SessionKey() {
            this.aesKey = new byte[32];
            RANDOM.nextBytes(this.aesKey);
            this.hmacKey = new byte[32];
            RANDOM.nextBytes(this.hmacKey);
        }
    }
}
