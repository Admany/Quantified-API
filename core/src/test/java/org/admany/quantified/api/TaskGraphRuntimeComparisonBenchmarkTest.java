package org.admany.quantified.api;

import org.admany.quantified.api.graph.QuantifiedTaskGraph;
import org.admany.quantified.api.model.QuantifiedTask;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TaskGraphRuntimeComparisonBenchmarkTest {

    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    static void setUpAll() {
        testExecutor = Executors.newScheduledThreadPool(4);
        AsyncManager.initialise(AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors()), testExecutor);
    }

    @AfterAll
    static void tearDownAll() {
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void compareLegacyAndUnifiedScheduledGraphExecution() {
        requireBenchmarksEnabled();
        QuantifiedHandle handle = new QuantifiedHandle("bench_graph", "1.0.0");

        long legacyNanos = measureNanos(() -> runLegacyGraph(handle, createGraph("legacy")).join());
        long modernNanos = measureNanos(() -> TaskGraphExecutor.submitAll(handle, createGraph("modern")).join());

        System.out.println("graph.scheduled.legacyMs=" + (legacyNanos / 1_000_000L));
        System.out.println("graph.scheduled.modernMs=" + (modernNanos / 1_000_000L));

        Map<String, Object> legacyResults = runLegacyGraph(handle, createGraph("legacy_check")).join();
        Map<String, Object> modernResults = TaskGraphExecutor.submitAll(handle, createGraph("modern_check")).join();
        assertThat(modernResults).isEqualTo(legacyResults);
        assertThat(modernNanos).isLessThanOrEqualTo((long) (legacyNanos * 1.15d));
    }

    private static QuantifiedTaskGraph.Builder createGraph(String name) {
        QuantifiedTaskGraph.Builder graph = QuantifiedTaskGraph.builder("bench_graph", name, name.hashCode())
            .localityKey("chunk-region");

        List<QuantifiedTaskGraph.NodeHandle<Integer>> nodes = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            final int value = i;
            QuantifiedTaskGraph.NodeHandle<Integer> node = graph.node("n" + i, context -> {
                int base = value * 3 + 7;
                int dependencyMix = 0;
                for (Object dependency : context.dependencyResults().values()) {
                    dependencyMix += ((Number) dependency).intValue();
                }
                return Integer.rotateLeft(base + dependencyMix, 1);
            }).priority(i < 4 ? ExecutionPriority.FOREGROUND : ExecutionPriority.AUTO);
            nodes.add(node);
        }

        for (int i = 2; i < nodes.size(); i++) {
            nodes.get(i).dependsOn(nodes.get(i - 1), nodes.get(i - 2));
        }
        return graph;
    }

    private static CompletableFuture<Map<String, Object>> runLegacyGraph(QuantifiedHandle handle,
                                                                         QuantifiedTaskGraph.Builder builder) {
        LinkedHashMap<String, LegacyNode> nodes = new LinkedHashMap<>();
        for (QuantifiedTaskGraph.NodeHandle<?> node : builder.nodes()) {
            nodes.put(node.name(), new LegacyNode(node));
        }
        for (LegacyNode node : nodes.values()) {
            for (String dependencyName : node.handle.dependencyNames()) {
                LegacyNode dependency = nodes.get(dependencyName);
                if (dependency != null) {
                    node.remainingDependencies.incrementAndGet();
                    dependency.dependents.add(node);
                }
            }
        }

        CompletableFuture<Map<String, Object>> allResults = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(nodes.size());
        AtomicBoolean failed = new AtomicBoolean(false);

        class LegacyScheduler {
            void schedule(LegacyNode node) {
                if (failed.get()) {
                    return;
                }
                ExecutionPriority priority = node.handle.priority();
                PriorityTaskType type = priority == ExecutionPriority.BACKGROUND
                    ? PriorityTaskType.BACKGROUND
                    : priority == ExecutionPriority.FOREGROUND || priority == ExecutionPriority.CRITICAL
                    ? PriorityTaskType.FOREGROUND
                    : PriorityTaskType.OTHER;
                QuantifiedTask.Builder<Object> taskBuilder = QuantifiedTask.builder(
                    handle.modId(),
                    builder.graphName() + "/" + node.handle.name(),
                    () -> {
                        try {
                            return node.handle.work().execute(new LegacyContext(node.handle, nodes));
                        } catch (RuntimeException runtimeException) {
                            throw runtimeException;
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }
                );
                if (priority == ExecutionPriority.AUTO) {
                    taskBuilder.priorityAuto();
                } else {
                    taskBuilder.priority(type);
                }
                taskBuilder.threadSafe(node.handle.threadSafe());
                if (node.handle.timeout() != null) {
                    taskBuilder.timeout(node.handle.timeout());
                }
                taskBuilder.batchKey("graph|" + builder.graphName() + "|" + node.handle.name());

                handle.submitTask(taskBuilder.build()).whenComplete((value, error) -> {
                    if (error != null) {
                        if (failed.compareAndSet(false, true)) {
                            allResults.completeExceptionally(error);
                        }
                        return;
                    }
                    node.value = value;
                    for (LegacyNode dependent : node.dependents) {
                        if (dependent.remainingDependencies.decrementAndGet() == 0) {
                            schedule(dependent);
                        }
                    }
                    if (remaining.decrementAndGet() == 0 && !failed.get()) {
                        LinkedHashMap<String, Object> results = new LinkedHashMap<>();
                        for (LegacyNode completed : nodes.values()) {
                            results.put(completed.handle.name(), completed.value);
                        }
                        allResults.complete(results);
                    }
                });
            }
        }

        LegacyScheduler scheduler = new LegacyScheduler();
        for (LegacyNode node : nodes.values()) {
            if (node.remainingDependencies.get() == 0) {
                scheduler.schedule(node);
            }
        }
        return allResults;
    }

    private static long measureNanos(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static void requireBenchmarksEnabled() {
        boolean enabled = Boolean.getBoolean("quantified.benchmarks")
            || "true".equalsIgnoreCase(System.getenv("QUANTIFIED_BENCHMARKS"));
        Assumptions.assumeTrue(enabled, "benchmark mode disabled");
    }

    private static final class LegacyNode {
        private final QuantifiedTaskGraph.NodeHandle<?> handle;
        private final AtomicInteger remainingDependencies = new AtomicInteger();
        private final List<LegacyNode> dependents = new ArrayList<>();
        private volatile Object value;

        private LegacyNode(QuantifiedTaskGraph.NodeHandle<?> handle) {
            this.handle = handle;
        }
    }

    private static final class LegacyContext implements QuantifiedTaskGraph.NodeContext {
        private final QuantifiedTaskGraph.NodeHandle<?> current;
        private final Map<String, LegacyNode> nodes;

        private LegacyContext(QuantifiedTaskGraph.NodeHandle<?> current, Map<String, LegacyNode> nodes) {
            this.current = current;
            this.nodes = nodes;
        }

        @Override
        public <T> T result(QuantifiedTaskGraph.NodeHandle<T> node) {
            return cast(resolve(node.name()));
        }

        @Override
        public Object result(String nodeName) {
            return resolve(nodeName);
        }

        @Override
        public Map<String, Object> dependencyResults() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (String dependencyName : current.dependencyNames()) {
                values.put(dependencyName, resolve(dependencyName));
            }
            return values;
        }

        private Object resolve(String nodeName) {
            LegacyNode node = nodes.get(nodeName);
            if (node == null) {
                throw new IllegalStateException("Missing dependency " + nodeName);
            }
            return node.value;
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object value) {
            return (T) value;
        }
    }
}
