package org.admany.quantified.api;

import org.admany.quantified.api.graph.QuantifiedTaskGraph;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QuantifiedTaskGraphTest {

    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    static void setUpAll() {
        testExecutor = Executors.newScheduledThreadPool(2);
        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors());
        AsyncManager.initialise(bootstrap, testExecutor);
        QuantifiedAPI.register("graph_test", "Graph Test", "1.0.0");
    }

    @AfterAll
    static void tearDownAll() {
        QuantifiedAPI.disconnect("graph_test");
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void graphResolvesDependencyOutputsInOrder() {
        ConcurrentLinkedQueue<String> executionOrder = new ConcurrentLinkedQueue<>();

        QuantifiedTaskGraph.Builder graph = QuantifiedAPI.graph("graph_test", "pipeline")
            .localityKey("region-12-8");

        QuantifiedTaskGraph.NodeHandle<Integer> seed = graph.node("seed", () -> {
            executionOrder.add("seed");
            return 4;
        }).priority(ExecutionPriority.FOREGROUND);

        QuantifiedTaskGraph.NodeHandle<Integer> doubled = graph.node("doubled", context -> {
            executionOrder.add("doubled");
            return context.result(seed) * 2;
        }).dependsOn(seed);

        QuantifiedTaskGraph.NodeHandle<Integer> incremented = graph.node("incremented", context -> {
            executionOrder.add("incremented");
            return context.result(seed) + 3;
        }).dependsOn(seed);

        QuantifiedTaskGraph.NodeHandle<Integer> combined = graph.node("combined", context -> {
            executionOrder.add("combined");
            return context.result(doubled) + context.result(incremented);
        }).dependsOn(doubled, incremented);

        Integer result = graph.submit(combined).join();

        assertThat(result).isEqualTo(15);
        assertThat(executionOrder).containsExactlyInAnyOrder("seed", "doubled", "incremented", "combined");

        List<String> order = List.copyOf(executionOrder);
        int seedIndex = order.indexOf("seed");
        int doubledIndex = order.indexOf("doubled");
        int incrementedIndex = order.indexOf("incremented");
        int combinedIndex = order.indexOf("combined");

        assertThat(seedIndex).isLessThan(doubledIndex);
        assertThat(seedIndex).isLessThan(incrementedIndex);
        assertThat(combinedIndex).isGreaterThan(doubledIndex);
        assertThat(combinedIndex).isGreaterThan(incrementedIndex);
    }

    @Test
    void submitAllReturnsResolvedNodeMap() {
        QuantifiedTaskGraph.Builder graph = QuantifiedAPI.graph("graph_test", "submit-all");

        QuantifiedTaskGraph.NodeHandle<Integer> a = graph.node("a", () -> 2);
        graph.node("b", context -> context.result(a) + 5).dependsOn(a);

        Map<String, Object> results = graph.submitAll().join();

        assertThat(results.get("a")).isEqualTo(2);
        assertThat(results.get("b")).isEqualTo(7);
    }

    @Test
    void wavefrontGraphResolvesDependenciesWithSingleGraphAdmission() {
        ConcurrentHashMap<String, String> threads = new ConcurrentHashMap<>();
        AtomicInteger executed = new AtomicInteger();

        QuantifiedTaskGraph.Builder graph = QuantifiedAPI.graph("graph_test", "wavefront")
            .localityKey("region-wave")
            .wavefront();

        QuantifiedTaskGraph.NodeHandle<Integer> a = graph.node("a", () -> {
            threads.put("a", Thread.currentThread().getName());
            executed.incrementAndGet();
            return 2;
        }).priority(ExecutionPriority.FOREGROUND);

        QuantifiedTaskGraph.NodeHandle<Integer> b = graph.node("b", () -> {
            threads.put("b", Thread.currentThread().getName());
            executed.incrementAndGet();
            return 3;
        }).priority(ExecutionPriority.FOREGROUND);

        QuantifiedTaskGraph.NodeHandle<Integer> sum = graph.node("sum", context -> {
            threads.put("sum", Thread.currentThread().getName());
            executed.incrementAndGet();
            return context.result(a) + context.result(b);
        }).dependsOn(a, b);

        Integer result = graph.submit(sum).join();

        assertThat(result).isEqualTo(5);
        assertThat(executed.get()).isEqualTo(3);
        assertThat(threads.keySet()).containsExactlyInAnyOrder("a", "b", "sum");
    }

    @Test
    void cycleValidationFailsFast() {
        QuantifiedTaskGraph.Builder graph = QuantifiedAPI.graph("graph_test", "cycle");
        QuantifiedTaskGraph.NodeHandle<Integer> a = graph.node("a", () -> 1);
        QuantifiedTaskGraph.NodeHandle<Integer> b = graph.node("b", context -> context.result(a)).dependsOn(a);
        a.dependsOn(b);

        CompletableFuture<Map<String, Object>> future = graph.submitAll();

        assertThat(future).isCompletedExceptionally();
    }
}
