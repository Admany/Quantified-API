package org.admany.quantified.api.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SpirvComputeProgramTest {

    @Test
    void keepsAnImmutableValidatedProgramDescription() {
        byte[] module = minimalHeader();
        SpirvComputeProgram program = SpirvComputeProgram.of("lc2h:test", module, 3, 8, 256);
        module[0] = 0;

        assertEquals("lc2h:test", program.key());
        assertEquals(3, program.storageBufferCount());
        assertEquals(8, program.pushConstantBytes());
        assertEquals(256, program.localSizeX());
        assertArrayEquals(minimalHeader(), program.spirv());

        byte[] exported = program.spirv();
        exported[1] = 0;
        assertArrayEquals(minimalHeader(), program.spirv());
    }

    @Test
    void rejectsMalformedModulesAndLayouts() {
        assertThrows(IllegalArgumentException.class,
            () -> SpirvComputeProgram.of("bad", new byte[20], 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> SpirvComputeProgram.of("bad", minimalHeader(), 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> SpirvComputeProgram.of("bad", minimalHeader(), 1, 6, 1));
        assertThrows(IllegalArgumentException.class,
            () -> SpirvComputeProgram.of("bad", minimalHeader(), 1, 0, 0));
    }

    @Test
    void reportsMissingResources() {
        assertThrows(IllegalArgumentException.class,
            () -> SpirvComputeProgram.fromResource("missing", getClass(), "/missing.spv", 1, 0, 1));
    }

    @Test
    void preparedDispatchCountsInvocationsInsteadOfOnlyWorkgroups() {
        SpirvComputeProgram program = SpirvComputeProgram.of("prepared", minimalHeader(), 2, 12, 256);
        QuantifiedVulkan.Dispatch dispatch = new QuantifiedVulkan.Dispatch(
            new float[][] {new float[32]}, 16, new int[] {4, 8, -64}, 3, 2, 1);

        assertEquals(1536, dispatch.computeUnits(program));
        assertEquals(128L, dispatch.inputBytes());
    }

    private static byte[] minimalHeader() {
        return new byte[] {
            0x03, 0x02, 0x23, 0x07,
            0x00, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
    }
}
