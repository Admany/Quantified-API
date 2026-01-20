package org.admany.quantified.core.common.network;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public final class EncryptedPacket {

    private static final int VERSION = 2;
    private static final int HEADER_SIZE = 4 + 4 + 8 + 16 + 12 + 32; // version + payloadLen + sequence + hmac + iv + tag
    private static final int GCM_TAG_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int version;
    private final long sequenceNumber;
    private final byte[] encryptedPayload;
    private final byte[] iv;
    private final byte[] authTag;
    private final byte[] hmac;

    private EncryptedPacket(int version, long sequenceNumber, byte[] encryptedPayload, byte[] iv, byte[] authTag, byte[] hmac) {
        this.version = version;
        this.sequenceNumber = sequenceNumber;
        this.encryptedPayload = encryptedPayload.clone();
        this.iv = iv.clone();
        this.authTag = authTag.clone();
        this.hmac = hmac.clone();
    }

    public static EncryptedPacket encrypt(byte[] plaintext, byte[] aesKey, byte[] hmacKey, long sequenceNumber) throws Exception {
        if (plaintext == null || aesKey == null || hmacKey == null || aesKey.length != 32 || hmacKey.length != 32) {
            throw new IllegalArgumentException("Invalid parameters for encryption");
        }

        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);

        // AES-GCM encryption
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        byte[] encrypted = cipher.doFinal(plaintext);

        // Split encrypted data and tag
        byte[] encryptedPayload = Arrays.copyOf(encrypted, encrypted.length - 16);
        byte[] tag = Arrays.copyOfRange(encrypted, encrypted.length - 16, encrypted.length);

        // Compute HMAC over header + encrypted payload + tag
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec hmacSpec = new SecretKeySpec(hmacKey, "HmacSHA256");
        mac.init(hmacSpec);
        mac.update(ByteBuffer.allocate(12).putInt(VERSION).putLong(sequenceNumber).array());
        mac.update(encryptedPayload);
        mac.update(tag);
        byte[] hmacValue = mac.doFinal();

        return new EncryptedPacket(VERSION, sequenceNumber, encryptedPayload, iv, tag, hmacValue);
    }

    public byte[] decrypt(byte[] aesKey, byte[] hmacKey, long expectedSequence) throws Exception {
        if (aesKey == null || hmacKey == null || aesKey.length != 32 || hmacKey.length != 32) {
            throw new IllegalArgumentException("Invalid key for decryption");
        }

        if (sequenceNumber <= expectedSequence) {
            throw new SecurityException("Replay attack detected: sequence number " + sequenceNumber + " <= " + expectedSequence);
        }

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec hmacSpec = new SecretKeySpec(hmacKey, "HmacSHA256");
        mac.init(hmacSpec);
        mac.update(ByteBuffer.allocate(12).putInt(version).putLong(sequenceNumber).array());
        mac.update(encryptedPayload);
        mac.update(authTag);
        byte[] computedHmac = mac.doFinal();
        if (!Arrays.equals(hmac, computedHmac)) {
            throw new SecurityException("HMAC verification failed");
        }

        // AES-GCM decryption
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        // Combine encrypted payload and tag
        byte[] encryptedWithTag = new byte[encryptedPayload.length + authTag.length];
        System.arraycopy(encryptedPayload, 0, encryptedWithTag, 0, encryptedPayload.length);
        System.arraycopy(authTag, 0, encryptedWithTag, encryptedPayload.length, authTag.length);

        return cipher.doFinal(encryptedWithTag);
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + encryptedPayload.length);
        buffer.putInt(version);
        buffer.putInt(encryptedPayload.length);
        buffer.putLong(sequenceNumber);
        buffer.put(hmac);
        buffer.put(iv);
        buffer.put(authTag);
        buffer.put(encryptedPayload);
        return buffer.array();
    }

    public static EncryptedPacket fromBytes(byte[] data) {
        if (data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Packet data too short");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        int version = buffer.getInt();
        int payloadLen = buffer.getInt();
        long sequenceNumber = buffer.getLong();

        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported packet version: " + version);
        }

        if (data.length != HEADER_SIZE + payloadLen) {
            throw new IllegalArgumentException("Invalid packet length");
        }

        byte[] hmac = new byte[32];
        buffer.get(hmac);

        byte[] iv = new byte[12];
        buffer.get(iv);

        byte[] tag = new byte[16];
        buffer.get(tag);

        byte[] encryptedPayload = new byte[payloadLen];
        buffer.get(encryptedPayload);

        return new EncryptedPacket(version, sequenceNumber, encryptedPayload, iv, tag, hmac);
    }

    public int getVersion() {
        return version;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public int getPayloadLength() {
        return encryptedPayload.length;
    }
}