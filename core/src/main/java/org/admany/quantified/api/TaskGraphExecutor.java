package org.admany.quantified.api;

import org.admany.quantified.api.builders.QuantifiedTaskBuilder;
import org.admany.quantified.api.graph.QuantifiedTaskGraph;
import org.admany.quantified.api.model.QuantifiedTask;
import org.admany.quantified.core.common.async.task.PriorityTaskType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class TaskGraphExecutor {

    private TaskGraphExecutor() {
    }

    static <T> CompletableFuture<T> submit(QuantifiedHandle handle,
                                           QuantifiedTaskGraph.Builder builder,
                                           QuantifiedTaskGraph.NodeHandle<T> terminal) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(terminal, "terminal");

        if (terminal.owner() != builder) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Terminal node must belong to the submitted graph"));
        }

        ValidationResult validation = validate(builder);
        if (validation.failure() != null) {
            return CompletableFuture.failedFuture(validation.failure());
        }

        LinkedHashSet<String> required = new LinkedHashSet<>();
        collectDependencies(builder, terminal.name(), required);
        GraphRun run = new GraphRun(handle, builder, required);
        return run.startTerminal(terminal.name());
    }

    static CompletableFuture<Map<String, Object>> submitAll(QuantifiedHandle handle,
                                                            QuantifiedTaskGraph.Builder builder) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(builder, "builder");

        ValidationResult validation = validate(builder);
        if (validation.failure() != null) {
            return CompletableFuture.failedFuture(validation.failure());
        }

        LinkedHashSet<String> included = new LinkedHashSet<>();
        for (QuantifiedTaskGraph.NodeHandle<?> node : builder.nodes()) {
            included.add(node.name());
        }
        GraphRun run = new GraphRun(handle, builder, included);
        return run.startAll();
    }

    private static void collectDependencies(QuantifiedTaskGraph.Builder builder,
                                            String nodeName,
                                            Set<String> into) {
        if (nodeName == null || !into.add(nodeName)) {
            return;
        }
        QuantifiedTaskGraph.NodeHandle<?> node = builder.node(nodeName);
        if (node == null) {
            return;
        }
        for (String dependency : node.dependencyNames()) {
            collectDependencies(builder, dependency, into);
        }
    }

    private static ValidationResult validate(QuantifiedTaskGraph.Builder builder) {
        Map<String, QuantifiedTaskGraph.NodeHandle<?>> nodes = new LinkedHashMap<>();
        for (QuantifiedTaskGraph.NodeHandle<?> node : builder.nodes()) {
            nodes.put(node.name(), node);
        }

        for (QuantifiedTaskGraph.NodeHandle<?> node : nodes.values()) {
            for (String dependency : node.dependencyNames()) {
                if (!nodes.containsKey(dependency)) {
                    return new ValidationResult(new IllegalStateException(
                        "Graph node '" + node.name() + "' depends on missing node '" + dependency + "'"));
                }
            }
        }

        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        for (String nodeName : nodes.keySet()) {
            IllegalStateException cycle = detectCycle(nodes, nodeName, visiting, visited);
            if (cycle != null) {
                return new ValidationResult(cycle);
            }
        }

        return ValidationResult.OK;
    }

    private static IllegalStateException detectCycle(Map<String, QuantifiedTaskGraph.NodeHandle<?>> nodes,
                                                     String nodeName,
                                                     Set<String> visiting,
                                                     Set<String> visited) {
        if (visited.contains(nodeName)) {
            return null;
        }
        if (!visiting.add(nodeName)) {
            return new IllegalStateException("Task graph contains a cycle involving '" + nodeName + "'");
        }
        QuantifiedTaskGraph.NodeHandle<?> node = nodes.get(nodeName);
        if (node != null) {
            for (String dependency : node.dependencyNames()) {
                IllegalStateException cycle = detectCycle(nodes, dependency, visiting, visited);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        visiting.remove(nodeName);
        visited.add(nodeName);
        return null;
    }

    private record ValidationResult(IllegalStateException failure) {
        private static final ValidationResult OK = new ValidationResult(null);
    }

    private static final class GraphRun {
        private final QuantifiedHandle handle;
        private final QuantifiedTaskGraph.Builder builder;
        private final LinkedHashMap<String, RuntimeNode> nodes = new LinkedHashMap<>();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final AtomicInteger remaining;
        private final CompletableFuture<Map<String, Object>> allResults = new CompletableFuture<>();

        private GraphRun(QuantifiedHandle handle,
                         QuantifiedTaskGraph.Builder builder,
                         Set<String> includedNames) {
            this.handle = handle;
            this.builder = builder;
            for (String name : includedNames) {
                QuantifiedTaskGraph.NodeHandle<?> node = builder.node(name);
                if (node != null) {
                    nodes.put(name, new RuntimeNode(node));
                }
            }
            for (RuntimeNode node : nodes.values()) {
                for (String dependencyName : node.handle.dependencyNames()) {
                    RuntimeNode dependency = nodes.get(dependencyName);
                    if (dependency == null) {
                        continue;
                    }
                    node.remainingDependencies.incrementAndGet();
                    dependency.dependents.add(node);
                }
            }
            this.remaining = new AtomicInteger(nodes.size());
            if (nodes.isEmpty()) {
                allResults.complete(Collections.emptyMap());
            }
        }

        private <T> CompletableFuture<T> startTerminal(String terminalName) {
            RuntimeNode terminal = nodes.get(terminalName);
            if (terminal == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown terminal node: " + terminalName));
            }
            start();
            return terminal.future.thenApply(value -> cast(value));
        }

        private CompletableFuture<Map<String, Object>> startAll() {
            start();
            return allResults;
        }

        private void start() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            if (nodes.isEmpty()) {
                return;
            }
            for (RuntimeNode node : nodes.values()) {
                if (node.remainingDependencies.get() == 0) {
                    schedule(node);
                }
            }
        }

        private void schedule(RuntimeNode node) {
            if (failed.get()) {
                return;
            }

            QuantifiedTask.Builder<Object> taskBuilder = QuantifiedTask.builder(
                handle.modId(),
                builder.graphName() + "/" + node.handle.name(),
                () -> executeNode(node)
            );

            QuantifiedTaskBuilder.Priority priority = node.handle.priority();
            if (priority == QuantifiedTaskBuilder.Priority.AUTO) {
                taskBuilder.priorityAuto();
            } else if (priority == QuantifiedTaskBuilder.Priority.BACKGROUND) {
                taskBuilder.priority(PriorityTaskType.BACKGROUND);
            } else {
                taskBuilder.priority(PriorityTaskType.FOREGROUND);
            }

            taskBuilder.threadSafe(node.handle.threadSafe());
            if (node.handle.timeout() != null) {
                taskBuilder.timeout(node.handle.timeout());
            }

            String affinity = resolveAffinity(node.handle);
            if (affinity != null && !affinity.isBlank()) {
                taskBuilder.batchKey(affinity);
            }

            handle.submitTask(taskBuilder.build()).whenComplete((result, error) -> {
                if (error != null) {
                    failGraph(error);
                    return;
                }
                node.value = result;
                node.future.complete(result);
                if (failed.get()) {
                    return;
                }
                for (RuntimeNode dependent : node.dependents) {
                    if (dependent.remainingDependencies.decrementAndGet() == 0) {
                        schedule(dependent);
                    }
                }
                if (remaining.decrementAndGet() == 0) {
                    completeAllResults();
                }
            });
        }

        private Object executeNode(RuntimeNode node) {
            try {
                return node.handle.work().execute(new GraphNodeContext(node.handle, nodes));
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Exception exception) {
                throw new RuntimeException("Graph node failed: " + node.handle.name(), exception);
            }
        }

        private void completeAllResults() {
            if (failed.get()) {
                return;
            }
            LinkedHashMap<String, Object> resultMap = new LinkedHashMap<>();
            for (RuntimeNode node : nodes.values()) {
                resultMap.put(node.handle.name(), node.value);
            }
            allResults.complete(Collections.unmodifiableMap(resultMap));
        }

        private void failGraph(Throwable throwable) {
            Throwable root = unwrap(throwable);
            if (!failed.compareAndSet(false, true)) {
                return;
            }
            allResults.completeExceptionally(root);
            for (RuntimeNode node : nodes.values()) {
                node.future.completeExceptionally(root);
            }
        }

        private String resolveAffinity(QuantifiedTaskGraph.NodeHandle<?> handle) {
            String explicitBatchKey = normalize(handle.batchKey());
            if (explicitBatchKey != null) {
                return explicitBatchKey;
            }

            String locality = normalize(handle.localityKey());
            if (locality == null) {
                locality = normalize(builder.localityKey());
            }
            if (locality == null) {
                locality = normalize(builder.graphName());
            }
            if (locality == null) {
                return null;
            }
            return "graph|" + sanitize(builder.graphName()) + "|" + sanitize(locality);
        }

        private static Throwable unwrap(Throwable throwable) {
            Throwable current = throwable;
            while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object value) {
            return (T) value;
        }
    }

    private static final class RuntimeNode {
        private final QuantifiedTaskGraph.NodeHandle<?> handle;
        private final AtomicInteger remainingDependencies = new AtomicInteger(0);
        private final List<RuntimeNode> dependents = new ArrayList<>();
        private final CompletableFuture<Object> future = new CompletableFuture<>();
        private volatile Object value;

        private RuntimeNode(QuantifiedTaskGraph.NodeHandle<?> handle) {
            this.handle = handle;
        }
    }

    private static final class GraphNodeContext implements QuantifiedTaskGraph.NodeContext {
        private final QuantifiedTaskGraph.NodeHandle<?> current;
        private final Map<String, RuntimeNode> nodes;

        private GraphNodeContext(QuantifiedTaskGraph.NodeHandle<?> current, Map<String, RuntimeNode> nodes) {
            this.current = current;
            this.nodes = nodes;
        }

        @Override
        public <T> T result(QuantifiedTaskGraph.NodeHandle<T> node) {
            Objects.requireNonNull(node, "node");
            if (node.owner() != current.owner()) {
                throw new IllegalArgumentException("Graph context can only resolve nodes from the same builder");
            }
            return cast(resolve(node.name()));
        }

        @Override
        public Object result(String nodeName) {
            return resolve(nodeName);
        }

        @Override
        public Map<String, Object> dependencyResults() {
            LinkedHashMap<String, Object> results = new LinkedHashMap<>();
            for (String dependencyName : current.dependencyNames()) {
                results.put(dependencyName, resolve(dependencyName));
            }
            return Collections.unmodifiableMap(results);
        }

        private Object resolve(String nodeName) {
            if (nodeName == null || nodeName.isBlank()) {
                throw new IllegalArgumentException("Dependency name must not be blank");
            }
            if (!current.dependencyNames().contains(nodeName)) {
                throw new IllegalArgumentException("Node '" + current.name() + "' cannot read non-dependency '" + nodeName + "'");
            }
            RuntimeNode node = nodes.get(nodeName);
            if (node == null) {
                throw new IllegalStateException("Missing dependency node: " + nodeName);
            }
            if (!node.future.isDone() || node.future.isCompletedExceptionally()) {
                throw new IllegalStateException("Dependency result is not available yet: " + nodeName);
            }
            return node.value;
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object value) {
            return (T) value;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }
}
