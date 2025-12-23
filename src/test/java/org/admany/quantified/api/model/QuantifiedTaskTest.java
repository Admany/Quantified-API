package org.admany.quantified.api.model;

import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class QuantifiedTaskTest {

    @Test
    void builderDefaultsAndSetters() {
        QuantifiedTask<Integer> task = QuantifiedTask.builder("mod-a", "simple", () -> 42).build();

        assertThat(task.modId()).isEqualTo("mod-a");
        assertThat(task.name()).isEqualTo("simple");
        assertThat(task.work().get()).isEqualTo(42);
        assertThat(task.priority()).isEqualTo(PriorityTaskType.OTHER);
        assertThat(task.autoPriority()).isTrue();
        assertThat(task.threadSafe()).isTrue();
        assertThat(task.timeout()).isEmpty();
        assertThat(task.gpuPreferred()).isFalse();
        assertThat(task.gpuRequired()).isFalse();
    }

    @Test
    void priorityAndThreadSafetyMutations() {
        QuantifiedTask<Integer> t = QuantifiedTask.builder("m", "n", () -> 1)
            .priorityForeground()
            .notThreadSafe()
            .timeout(Duration.ofSeconds(2))
            .gpuPreferred()
            .build();

        assertThat(t.priority()).isEqualTo(PriorityTaskType.FOREGROUND);
        assertThat(t.autoPriority()).isFalse();
        assertThat(t.threadSafe()).isFalse();
        assertThat(t.timeout()).isPresent();
        assertThat(t.gpuPreferred()).isTrue();
    }

    @Test
    void priorityBackgroundShortcut() {
        QuantifiedTask<String> t = QuantifiedTask.builder("x", "y", () -> "hi").priorityBackground().build();
        assertThat(t.priority()).isEqualTo(PriorityTaskType.BACKGROUND);
    }

    @Test
    void builderSuppliesWorkLazily() {
        AtomicBoolean ran = new AtomicBoolean(false);
        QuantifiedTask<Void> t = QuantifiedTask.<Void>builder("mod", "do", () -> { ran.set(true); return null; }).build();
        assertThat(ran.get()).isFalse();
        t.work().get();
        assertThat(ran.get()).isTrue();
    }
}
