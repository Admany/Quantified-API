package org.admany.quantified.core.common.async.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAutoTunerTest {

    @Test
    void entersGuardrailOnCrashSignal() throws Exception {
        RuntimeAutoTuner tuner = new RuntimeAutoTuner();

        RuntimeAutoTuner.RuntimeTuning first = tuner.maybeTune(
            120, 200, 256, 0, 0, 0.40d, 0.80d
        );
        assertNotNull(first);

        Thread.sleep(2100L);
        RuntimeAutoTuner.RuntimeTuning guardrail = tuner.maybeTune(
            128, 220, 256, 0, 1, 0.45d, 0.80d
        );
        assertNotNull(guardrail);
        assertTrue(tuner.isGuardrailActive(), "Guardrail should activate after crash delta");
        assertTrue(tuner.currentLevelForTesting() <= -2, "Guardrail should clamp tuning level to conservative band");
    }

    @Test
    void clampsAggressiveLevelWithinBounds() throws Exception {
        RuntimeAutoTuner tuner = new RuntimeAutoTuner();

        for (int i = 0; i < 8; i++) {
            RuntimeAutoTuner.RuntimeTuning tuning = tuner.maybeTune(
                400, 400, 256, 0, 0, 0.40d, 0.90d
            );
            assertNotNull(tuning);
            Thread.sleep(2100L);
        }

        RuntimeAutoTuner.RuntimeTuning finalTuning = tuner.maybeTune(
            420, 420, 256, 0, 0, 0.45d, 0.90d
        );
        assertNotNull(finalTuning);
        assertTrue(tuner.currentLevelForTesting() <= 3);
        assertTrue(tuner.currentLevelForTesting() >= -3);
        assertTrue(finalTuning.foregroundMaxAdditional() <= 20);
        assertTrue(finalTuning.backgroundMaxAdditional() <= 10);
    }
}
