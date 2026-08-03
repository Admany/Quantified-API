package org.admany.quantified.api.vulkan;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable description of a precompiled Vulkan compute program.
 *
 * <p>The shader must expose storage buffers at consecutive bindings starting
 * at zero. Inputs occupy the first bindings and the final binding is the
 * output buffer. Push constants are supplied as 32-bit words.</p>
 */
public final class SpirvComputeProgram {

    private static final int SPIRV_MAGIC = 0x07230203;
    private static final int MAX_SHADER_BYTES = 16 * 1024 * 1024;

    private final String key;
    private final byte[] spirv;
    private final int storageBufferCount;
    private final int pushConstantBytes;
    private final int localSizeX;
    private final int localSizeY;
    private final int localSizeZ;

    private SpirvComputeProgram(String key,
                                byte[] spirv,
                                int storageBufferCount,
                                int pushConstantBytes,
                                int localSizeX,
                                int localSizeY,
                                int localSizeZ) {
        this.key = requireKey(key);
        this.spirv = validateSpirv(spirv);
        if (storageBufferCount < 1 || storageBufferCount > 32) {
            throw new IllegalArgumentException("storageBufferCount must be between 1 and 32");
        }
        if (pushConstantBytes < 0 || pushConstantBytes > 128 || (pushConstantBytes & 3) != 0) {
            throw new IllegalArgumentException("pushConstantBytes must be a 4-byte aligned value between 0 and 128");
        }
        if (localSizeX < 1 || localSizeY < 1 || localSizeZ < 1) {
            throw new IllegalArgumentException("local work-group sizes must be positive");
        }
        this.storageBufferCount = storageBufferCount;
        this.pushConstantBytes = pushConstantBytes;
        this.localSizeX = localSizeX;
        this.localSizeY = localSizeY;
        this.localSizeZ = localSizeZ;
    }

    public static SpirvComputeProgram of(String key,
                                         byte[] spirv,
                                         int storageBufferCount,
                                         int pushConstantBytes,
                                         int localSizeX) {
        return new SpirvComputeProgram(key, spirv, storageBufferCount, pushConstantBytes, localSizeX, 1, 1);
    }

    public static SpirvComputeProgram of(String key,
                                         byte[] spirv,
                                         int storageBufferCount,
                                         int pushConstantBytes,
                                         int localSizeX,
                                         int localSizeY,
                                         int localSizeZ) {
        return new SpirvComputeProgram(key, spirv, storageBufferCount, pushConstantBytes,
            localSizeX, localSizeY, localSizeZ);
    }

    public static SpirvComputeProgram fromResource(String key,
                                                   Class<?> resourceAnchor,
                                                   String resourcePath,
                                                   int storageBufferCount,
                                                   int pushConstantBytes,
                                                   int localSizeX) {
        Objects.requireNonNull(resourceAnchor, "resourceAnchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream input = resourceAnchor.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing SPIR-V resource: " + resourcePath);
            }
            return of(key, input.readAllBytes(), storageBufferCount, pushConstantBytes, localSizeX);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed reading SPIR-V resource: " + resourcePath, exception);
        }
    }

    public String key() {
        return key;
    }

    public byte[] spirv() {
        return spirv.clone();
    }

    byte[] spirvUnsafe() {
        return spirv;
    }

    public int storageBufferCount() {
        return storageBufferCount;
    }

    public int pushConstantBytes() {
        return pushConstantBytes;
    }

    public int localSizeX() {
        return localSizeX;
    }

    public int localSizeY() {
        return localSizeY;
    }

    public int localSizeZ() {
        return localSizeZ;
    }

    private static String requireKey(String key) {
        String normalized = Objects.requireNonNull(key, "key").trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("SPIR-V program key must contain 1 to 160 characters");
        }
        return normalized;
    }

    private static byte[] validateSpirv(byte[] bytes) {
        Objects.requireNonNull(bytes, "spirv");
        if (bytes.length < 20 || bytes.length > MAX_SHADER_BYTES || (bytes.length & 3) != 0) {
            throw new IllegalArgumentException("Invalid SPIR-V byte length: " + bytes.length);
        }
        int magic = (bytes[0] & 0xff)
            | ((bytes[1] & 0xff) << 8)
            | ((bytes[2] & 0xff) << 16)
            | ((bytes[3] & 0xff) << 24);
        if (magic != SPIRV_MAGIC) {
            throw new IllegalArgumentException("Invalid SPIR-V magic: 0x" + Integer.toHexString(magic));
        }
        return Arrays.copyOf(bytes, bytes.length);
    }
}
