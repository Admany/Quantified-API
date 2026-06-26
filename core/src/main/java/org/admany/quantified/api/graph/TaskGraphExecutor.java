package org.admany.quantified.api;

import org.admany.quantified.api.graph.QuantifiedTaskGraph;
import org.admany.quantified.core.common.async.task.PriorityTaskType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
        if (builder.executionMode() == QuantifiedTaskGraph.ExecutionMode.WAVEFRONT) {
            return run.startTerminalWavefront(terminal.name());
        }
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
        if (builder.executionMode() == QuantifiedTaskGraph.ExecutionMode.WAVEFRONT) {
            return run.startAllWavefront();
        }
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
        private final RuntimeNode[] orderedNodes;
        private final Map<String, Integer> nodeIndexes;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final CompletableFuture<Map<String, Object>> allResults = new CompletableFuture<>();

        private GraphRun(QuantifiedHandle handle,
                         QuantifiedTaskGraph.Builder builder,
                         Set<String> includedNames) {
            this.handle = handle;
            this.builder = builder;
            this.nodeIndexes = new LinkedHashMap<>(includedNames.size());
            this.orderedNodes = new RuntimeNode[includedNames.size()];

            int index = 0;
            for (String name : includedNames) {
                QuantifiedTaskGraph.NodeHandle<?> node = builder.node(name);
                if (node == null) {
                    continue;
                }
                nodeIndexes.put(name, index);
                orderedNodes[index] = new RuntimeNode(
                    index,
                    node,
                    resolveAffinity(builder, node),
                    toPriorityType(node.priority())
                );
                index++;
            }

            if (index != orderedNodes.length) {
                orderedNodes[index] = null;
            }

            RuntimeNode[] compact = index == orderedNodes.length ? orderedNodes : Arrays.copyOf(orderedNodes, index);
            for (int i = 0; i < compact.length; i++) {
                orderedNodes[i] = compact[i];
            }
            if (compact != orderedNodes) {
                System.arraycopy(compact, 0, orderedNodes, 0, compact.length);
            }

            for (RuntimeNode node : compact) {
                int depCount = 0;
                for (String dependencyName : node.handle.dependencyNames()) {
                    if (nodeIndexes.containsKey(dependencyName)) {
                        depCount++;
                    }
                }
                node.dependencyIndexes = new int[depCount];
                node.dependencyNames = new String[depCount];
                node.dependencyResultMap = depCount == 0 ? Collections.emptyMap() : null;
                int cursor = 0;
                for (String dependencyName : node.handle.dependencyNames()) {
                    Integer dependencyIndex = nodeIndexes.get(dependencyName);
                    if (dependencyIndex == null) {
                        continue;
                    }
                    node.dependencyIndexes[cursor] = dependencyIndex;
                    node.dependencyNames[cursor] = dependencyName;
                    cursor++;
                }
                node.remainingDependencies = depCount;
            }

            int[] dependentCounts = new int[compact.length];
            for (RuntimeNode node : compact) {
                for (int dependencyIndex : node.dependencyIndexes) {
                    dependentCounts[dependencyIndex]++;
                }
            }
            for (RuntimeNode node : compact) {
                node.dependentIndexes = new int[dependentCounts[node.index]];
            }
            int[] dependentCursors = new int[compact.length];
            for (RuntimeNode node : compact) {
                for (int dependencyIndex : node.dependencyIndexes) {
                    RuntimeNode dependency = compact[dependencyIndex];
                    dependency.dependentIndexes[dependentCursors[dependencyIndex]++] = node.index;
                }
                node.context = new GraphNodeContext(node, compact, nodeIndexes);
            }

            if (compact.length == 0) {
                allResults.complete(Collections.emptyMap());
            }
        }

        private <T> CompletableFuture<T> startTerminal(String terminalName) {
            Integer terminalIndex = nodeIndexes.get(terminalName);
            if (terminalIndex == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown terminal node: " + terminalName));
            }
            return submitScheduledTask().thenApply(results -> cast(results.get(terminalName)));
        }

        private CompletableFuture<Map<String, Object>> startAll() {
            return submitScheduledTask();
        }

        private <T> CompletableFuture<T> startTerminalWavefront(String terminalName) {
            Integer terminalIndex = nodeIndexes.get(terminalName);
            if (terminalIndex == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown terminal node: " + terminalName));
            }
            return submitWavefrontTask().thenApply(results -> cast(results.get(terminalName)));
        }

        private CompletableFuture<Map<String, Object>> startAllWavefront() {
            return submitWavefrontTask();
        }

        private CompletableFuture<Map<String, Object>> submitWavefrontTask() {
            if (!started.compareAndSet(false, true)) {
                return allResults;
            }
            if (orderedNodes.length == 0) {
                return CompletableFuture.completedFuture(Collections.emptyMap());
            }
            CompletableFuture<Map<String, Object>> scheduled = allNodesThreadSafe()
                ? executeWavefrontAsync(buildReadyQueue(), 0)
                : handle.submitRuntimeTask(
                    graphTaskName("wavefront"),
                    highestPriorityType(),
                    highestPriority() == ExecutionPriority.AUTO,
                    false,
                    graphTimeout(),
                    resolveGraphAffinity(),
                    this::executeWavefrontSequential
                );
            scheduled.whenComplete((result, error) -> {
                if (error != null) {
                    failGraph(error);
                    return;
                }
                allResults.complete(result);
            });
            return allResults;
        }

        private CompletableFuture<Map<String, Object>> submitScheduledTask() {
            if (!started.compareAndSet(false, true)) {
                return allResults;
            }
            if (orderedNodes.length == 0) {
                return CompletableFuture.completedFuture(Collections.emptyMap());
            }
            CompletableFuture<Map<String, Object>> scheduled = handle.submitRuntimeTask(
                graphTaskName("scheduled"),
                highestPriorityType(),
                highestPriority() == ExecutionPriority.AUTO,
                allNodesThreadSafe(),
                graphTimeout(),
                resolveGraphAffinity(),
                this::executeScheduled
            );
            scheduled.whenComplete((result, error) -> {
                if (error != null) {
                    failGraph(error);
                }
            });
            return scheduled;
        }

        private Map<String, Object> executeScheduled() {
            ArrayDeque<Integer> readyQueue = buildReadyQueue();
            int completed = 0;
            while (completed < orderedNodes.length) {
                Integer nodeIndex = readyQueue.pollFirst();
                if (nodeIndex == null) {
                    throw new IllegalStateException("Task graph scheduled execution stalled; check dependencies");
                }
                RuntimeNode node = orderedNodes[nodeIndex];
                node.value = executeNode(node);
                node.completed = true;
                enqueueDependents(node, readyQueue);
                completed++;
            }
            Map<String, Object> results = snapshotResults();
            allResults.complete(results);
            return results;
        }

        private CompletableFuture<Map<String, Object>> executeWavefrontAsync(ArrayDeque<Integer> readyQueue, int completed) {
            if (completed >= orderedNodes.length) {
                return CompletableFuture.completedFuture(snapshotResults());
            }
            if (readyQueue.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Task graph wavefront stalled; check dependencies"));
            }

            int waveSize = readyQueue.size();
            @SuppressWarnings("unchecked")
            CompletableFuture<NodeResult>[] waveFutures = new CompletableFuture[waveSize];
            for (int i = 0; i < waveSize; i++) {
                int nodeIndex = readyQueue.removeFirst();
                waveFutures[i] = scheduleWavefrontNode(orderedNodes[nodeIndex]);
            }

            return CompletableFuture.allOf(waveFutures)
                .thenCompose(ignored -> {
                    int nextCompleted = completed;
                    for (CompletableFuture<NodeResult> waveFuture : waveFutures) {
                        NodeResult result = waveFuture.join();
                        RuntimeNode node = result.node();
                        node.value = result.value();
                        node.completed = true;
                        enqueueDependents(node, readyQueue);
                        nextCompleted++;
                    }
                    if (nextCompleted >= orderedNodes.length) {
                        return CompletableFuture.completedFuture(snapshotResults());
                    }
                    return executeWavefrontAsync(readyQueue, nextCompleted);
                });
        }

        private CompletableFuture<NodeResult> scheduleWavefrontNode(RuntimeNode node) {
            return handle.submitRuntimeTask(
                graphTaskName("wave/" + node.handle.name()),
                node.priorityType,
                node.handle.priority() == ExecutionPriority.AUTO,
                true,
                node.handle.timeout(),
                node.affinity,
                () -> executeWavefrontNode(node)
            );
        }

        private Map<String, Object> executeWavefrontSequential() {
            int completed = 0;
            ArrayDeque<Integer> readyQueue = buildReadyQueue();
            while (completed < orderedNodes.length) {
                if (readyQueue.isEmpty()) {
                    throw new IllegalStateException("Task graph wavefront stalled; check dependencies");
                }
                int waveSize = readyQueue.size();
                for (int i = 0; i < waveSize; i++) {
                    RuntimeNode node = orderedNodes[readyQueue.removeFirst()];
                    NodeResult result = executeWavefrontNode(node);
                    node.value = result.value();
                    node.completed = true;
                    enqueueDependents(node, readyQueue);
                    completed++;
                }
            }
            return snapshotResults();
        }

        private ArrayDeque<Integer> buildReadyQueue() {
            ArrayDeque<Integer> ready = new ArrayDeque<>();
            for (RuntimeNode node : orderedNodes) {
                if (!node.scheduled && node.remainingDependencies == 0) {
                    node.scheduled = true;
                    ready.addLast(node.index);
                }
            }
            return ready;
        }

        private NodeResult executeWavefrontNode(RuntimeNode node) {
            try {
                return new NodeResult(node, executeNode(node));
            } catch (CompletionException completionException) {
                throw completionException;
            } catch (Throwable throwable) {
                throw new CompletionException(throwable);
            }
        }

        private Map<String, Object> snapshotResults() {
            LinkedHashMap<String, Object> resultMap = new LinkedHashMap<>(orderedNodes.length);
            for (RuntimeNode node : orderedNodes) {
                resultMap.put(node.handle.name(), node.value);
            }
            return Collections.unmodifiableMap(resultMap);
        }

        private Object executeNode(RuntimeNode node) {
            try {
                return node.handle.work().execute(node.context);
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Exception exception) {
                throw new RuntimeException("Graph node failed: " + node.handle.name(), exception);
            }
        }

        private void failGraph(Throwable throwable) {
            Throwable root = unwrap(throwable);
            if (!failed.compareAndSet(false, true)) {
                return;
            }
            allResults.completeExceptionally(root);
        }

        private void enqueueDependents(RuntimeNode node, ArrayDeque<Integer> readyQueue) {
            for (int dependentIndex : node.dependentIndexes) {
                RuntimeNode dependent = orderedNodes[dependentIndex];
                if (--dependent.remainingDependencies == 0) {
                    dependent.scheduled = true;
                    readyQueue.addLast(dependentIndex);
                }
            }
        }

        private static String resolveAffinity(QuantifiedTaskGraph.Builder builder, QuantifiedTaskGraph.NodeHandle<?> handle) {
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

        private String resolveGraphAffinity() {
            String locality = normalize(builder.localityKey());
            if (locality == null) {
                locality = normalize(builder.graphName());
            }
            if (locality == null) {
                return "graph|" + sanitize(builder.graphName()) + "|" + Long.toUnsignedString(builder.graphKey());
            }
            return "graph|" + sanitize(builder.graphName()) + "|" + sanitize(locality) + "|" + Long.toUnsignedString(builder.graphKey());
        }

        private String graphTaskName(String suffix) {
            return builder.graphName() + "/" + Long.toUnsignedString(builder.graphKey()) + "/" + suffix;
        }

        private boolean allNodesThreadSafe() {
            for (RuntimeNode node : orderedNodes) {
                if (!node.handle.threadSafe()) {
                    return false;
                }
            }
            return true;
        }

        private Duration graphTimeout() {
            Duration longest = null;
            for (RuntimeNode node : orderedNodes) {
                Duration timeout = node.handle.timeout();
                if (timeout != null && (longest == null || timeout.compareTo(longest) > 0)) {
                    longest = timeout;
                }
            }
            return longest;
        }

        private ExecutionPriority highestPriority() {
            ExecutionPriority highest = ExecutionPriority.AUTO;
            for (RuntimeNode node : orderedNodes) {
                ExecutionPriority priority = node.handle.priority();
                if (priority == ExecutionPriority.CRITICAL) {
                    return priority;
                }
                if (priority == ExecutionPriority.FOREGROUND) {
                    highest = priority;
                } else if (priority == ExecutionPriority.BACKGROUND && highest == ExecutionPriority.AUTO) {
                    highest = priority;
                }
            }
            return highest;
        }

        private PriorityTaskType highestPriorityType() {
            return toPriorityType(highestPriority());
        }
    }

    private static final class RuntimeNode {
        private final int index;
        private final QuantifiedTaskGraph.NodeHandle<?> handle;
        private final String affinity;
        private final PriorityTaskType priorityType;
        private GraphNodeContext context;
        private int[] dependencyIndexes = new int[0];
        private String[] dependencyNames = new String[0];
        private int[] dependentIndexes = new int[0];
        private int remainingDependencies;
        private boolean scheduled;
        private volatile Object value;
        private volatile boolean completed;
        private volatile Map<String, Object> dependencyResultMap;

        private RuntimeNode(int index,
                            QuantifiedTaskGraph.NodeHandle<?> handle,
                            String affinity,
                            PriorityTaskType priorityType) {
            this.index = index;
            this.handle = handle;
            this.affinity = affinity;
            this.priorityType = priorityType;
        }
    }

    private record NodeResult(RuntimeNode node, Object value) {
    }

    private static final class GraphNodeContext implements QuantifiedTaskGraph.NodeContext {
        private final RuntimeNode current;
        private final RuntimeNode[] nodes;
        private final Map<String, Integer> nodeIndexes;

        private GraphNodeContext(RuntimeNode current,
                                 RuntimeNode[] nodes,
                                 Map<String, Integer> nodeIndexes) {
            this.current = current;
            this.nodes = nodes;
            this.nodeIndexes = nodeIndexes;
        }

        @Override
        public <T> T result(QuantifiedTaskGraph.NodeHandle<T> node) {
            Objects.requireNonNull(node, "node");
            if (node.owner() != current.handle.owner()) {
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
            Map<String, Object> cached = current.dependencyResultMap;
            if (cached != null) {
                return cached;
            }
            if (current.dependencyIndexes.length == 0) {
                current.dependencyResultMap = Collections.emptyMap();
                return current.dependencyResultMap;
            }
            LinkedHashMap<String, Object> results = new LinkedHashMap<>(current.dependencyIndexes.length);
            for (int i = 0; i < current.dependencyIndexes.length; i++) {
                results.put(current.dependencyNames[i], resolveIndex(current.dependencyIndexes[i]));
            }
            Map<String, Object> built = Collections.unmodifiableMap(results);
            current.dependencyResultMap = built;
            return built;
        }

        private Object resolve(String nodeName) {
            if (nodeName == null || nodeName.isBlank()) {
                throw new IllegalArgumentException("Dependency name must not be blank");
            }
            Integer nodeIndex = nodeIndexes.get(nodeName);
            if (nodeIndex == null || !isDependency(nodeIndex)) {
                throw new IllegalArgumentException("Node '" + current.handle.name() + "' cannot read non-dependency '" + nodeName + "'");
            }
            return resolveIndex(nodeIndex);
        }

        private Object resolveIndex(int nodeIndex) {
            RuntimeNode node = nodes[nodeIndex];
            if (!node.completed) {
                throw new IllegalStateException("Dependency result is not available yet: " + node.handle.name());
            }
            return node.value;
        }

        private boolean isDependency(int nodeIndex) {
            for (int dependencyIndex : current.dependencyIndexes) {
                if (dependencyIndex == nodeIndex) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object value) {
            return (T) value;
        }
    }

    private static PriorityTaskType toPriorityType(ExecutionPriority priority) {
        if (priority == ExecutionPriority.BACKGROUND) {
            return PriorityTaskType.BACKGROUND;
        }
        if (priority == ExecutionPriority.CRITICAL || priority == ExecutionPriority.FOREGROUND) {
            return PriorityTaskType.FOREGROUND;
        }
        return PriorityTaskType.OTHER;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
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
