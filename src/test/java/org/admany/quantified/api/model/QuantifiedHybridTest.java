package org.admany.quantified.api.model;

import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class QuantifiedHybridTest {

    @Test
    void hybridRequiresCacheKey() {

        QuantifiedHybrid.Builder<Integer> b = QuantifiedHybrid.builder("m", "op", () -> 1).cache("mycache");
        assertThatThrownBy(b::build).isInstanceOf(IllegalStateException.class).hasMessageContaining("cache key");
    }

    @Test
    void hybridPropertiesFlowToTask() {
        QuantifiedHybrid<Integer> h = QuantifiedHybrid.builder("mod", "op", () -> 7)
            .cache("c")
            .cacheKey("ck")
            .ttl(Duration.ofSeconds(10))
            .maximumSize(1024)
            .priorityForeground()
            .threadSafe()
            .build();

        assertThat(h.cacheName()).isEqualTo("c");
        assertThat(h.cacheKey()).isEqualTo("ck");
        assertThat(h.ttl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(h.maximumSize()).isEqualTo(1024);
        assertThat(h.priority()).isEqualTo(PriorityTaskType.FOREGROUND);
        assertThat(h.threadSafe()).isTrue();

        QuantifiedTask<Integer> t = h.toTask();
        assertThat(t.name()).isEqualTo("op");
        assertThat(t.modId()).isEqualTo("mod");
    }
}
