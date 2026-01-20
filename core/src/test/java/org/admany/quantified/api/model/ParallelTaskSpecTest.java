package org.admany.quantified.api.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class ParallelTaskSpecTest {

    @Test
    void constructorAndAccessors() {
        Supplier<List<Integer>> slices = () -> List.of(1, 2, 3);
        Function<Integer, CompletableFuture<String>> exec = i -> CompletableFuture.completedFuture("v" + i);
        Function<List<String>, String> reducer = l -> String.join(",", l);
        Consumer<String> listener = s -> { /* no-op */ };

        ParallelTaskSpec<Integer, String, String> spec = new ParallelTaskSpec<>(
            "m", "task", 100L, slices, exec, reducer, listener, null, 0, null
        );

        assertThat(spec.modId()).isEqualTo("m");
        assertThat(spec.taskName()).isEqualTo("task");
        assertThat(spec.taskKey()).isEqualTo(100L);
        assertThat(spec.sliceSupplier().get()).containsExactly(1, 2, 3);
        assertThat(spec.sliceExecutor()).isEqualTo(exec);
        assertThat(spec.reducer()).isEqualTo(reducer);
        assertThat(spec.sliceListener()).isEqualTo(listener);
        assertThat(spec.maxParallelism()).isEqualTo(1);
        assertThat(spec.failurePolicy()).isNotNull();
    }
}
