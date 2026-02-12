package org.admany.quantified.core.common.async.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoBatchControllerTest {

    @Test
    void respectsRuntimeTuningBounds() {
        AutoBatchController controller = new AutoBatchController();
        controller.applyRuntimeTuning(
            TimeUnit.MICROSECONDS.toNanos(50L),
            TimeUnit.MICROSECONDS.toNanos(80L),
            100,
            -2
        );

        controller.recordExecution(true, TimeUnit.MILLISECONDS.toNanos(2L));
        controller.recordExecution(false, TimeUnit.MILLISECONDS.toNanos(3L));

        int fg = controller.recommendedAdditional(true, 300, 0.35d, 0.80d);
        int bg = controller.recommendedAdditional(false, 300, 0.35d, 0.80d);

        assertTrue(fg >= 1 && fg <= 24);
        assertTrue(bg >= 1 && bg <= 16);
    }
}
