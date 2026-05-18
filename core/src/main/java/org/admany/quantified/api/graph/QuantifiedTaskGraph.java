package org.admany.quantified.api.graph;

import org.admany.quantified.api.ExecutionPriority;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.StableTaskKeys;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class QuantifiedTaskGraph {

    private QuantifiedTaskGraph() {
    }

    public static Builder builder(String modId, String graphName) {
        return new Builder(modId, graphName, StableTaskKeys.of("graph", modId, graphName));
    }

    public static Builder builder(String modId, String graphName, long graphKey) {
        return new Builder(modId, graphName, graphKey);
    }

    @FunctionalInterface
    public interface NodeWork<T> {
        T execute(NodeContext context) throws Exception;
    }

    public interface NodeContext {
        <T> T result(NodeHandle<T> node);
        Object result(String nodeName);
        Map<String, Object> dependencyResults();
    }

    public enum ExecutionMode {
        SCHEDULED,
        WAVEFRONT
    }

    public static final class Builder {
        private final String modId;
        private final String graphName;
        private long graphKey;
        private final LinkedHashMap<String, NodeHandle<?>> nodes = new LinkedHashMap<>();
        private String localityKey;
        private ExecutionMode executionMode = ExecutionMode.SCHEDULED;

        private Builder(String modId, String graphName, long graphKey) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.graphName = Objects.requireNonNull(graphName, "graphName");
            this.graphKey = graphKey;
        }

        public Builder key(long graphKey) {
            this.graphKey = graphKey;
            return this;
        }

        public Builder key(String graphKey) {
            this.graphKey = StableTaskKeys.named("graph", modId, graphName, graphKey);
            return this;
        }

        public Builder localityKey(String localityKey) {
            this.localityKey = localityKey;
            return this;
        }

        public Builder locality(String localityKey) {
            return localityKey(localityKey);
        }

        public Builder executionMode(ExecutionMode executionMode) {
            this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
            return this;
        }

        public Builder wavefront() {
            return executionMode(ExecutionMode.WAVEFRONT);
        }

        public Builder scheduled() {
            return executionMode(ExecutionMode.SCHEDULED);
        }

        public <T> NodeHandle<T> node(String name, Supplier<T> work) {
            Objects.requireNonNull(work, "work");
            return node(name, context -> work.get());
        }

        public <T> NodeHandle<T> node(String name, NodeWork<T> work) {
            Objects.requireNonNull(work, "work");
            String normalized = Objects.requireNonNull(name, "name").trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Node name must not be blank");
            }
            if (nodes.containsKey(normalized)) {
                throw new IllegalArgumentException("Duplicate graph node: " + normalized);
            }
            NodeHandle<T> handle = new NodeHandle<>(this, normalized, work);
            nodes.put(normalized, handle);
            return handle;
        }

        public String modId() {
            return modId;
        }

        public String graphName() {
            return graphName;
        }

        public long graphKey() {
            return graphKey;
        }

        public String localityKey() {
            return localityKey;
        }

        public ExecutionMode executionMode() {
            return executionMode;
        }

        public Collection<NodeHandle<?>> nodes() {
            return Collections.unmodifiableCollection(nodes.values());
        }

        public NodeHandle<?> node(String name) {
            if (name == null) {
                return null;
            }
            return nodes.get(name);
        }

        public <T> CompletableFuture<T> submit(NodeHandle<T> terminal) {
            return QuantifiedAPI.submitGraph(this, terminal);
        }

        public CompletableFuture<Map<String, Object>> submitAll() {
            return QuantifiedAPI.submitGraph(this);
        }
    }

    public static final class NodeHandle<T> {
        private final Builder owner;
        private final String name;
        private final NodeWork<T> work;
        private final LinkedHashSet<String> dependencyNames = new LinkedHashSet<>();
        private ExecutionPriority priority = ExecutionPriority.AUTO;
        private boolean threadSafe = true;
        private Duration timeout;
        private String batchKey;
        private String localityKey;

        private NodeHandle(Builder owner, String name, NodeWork<T> work) {
            this.owner = owner;
            this.name = name;
            this.work = work;
        }

        public NodeHandle<T> dependsOn(NodeHandle<?>... dependencies) {
            if (dependencies == null) {
                return this;
            }
            for (NodeHandle<?> dependency : dependencies) {
                if (dependency == null) {
                    continue;
                }
                if (dependency.owner != owner) {
                    throw new IllegalArgumentException("Graph dependencies must belong to the same builder");
                }
                if (dependency == this) {
                    throw new IllegalArgumentException("Graph node cannot depend on itself: " + name);
                }
                dependencyNames.add(dependency.name);
            }
            return this;
        }

        public NodeHandle<T> priority(ExecutionPriority priority) {
            this.priority = Objects.requireNonNull(priority, "priority");
            return this;
        }

        public NodeHandle<T> foreground() {
            return priority(ExecutionPriority.FOREGROUND);
        }

        public NodeHandle<T> background() {
            return priority(ExecutionPriority.BACKGROUND);
        }

        public NodeHandle<T> critical() {
            return priority(ExecutionPriority.CRITICAL);
        }

        public NodeHandle<T> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public NodeHandle<T> threadSafe(boolean threadSafe) {
            this.threadSafe = threadSafe;
            return this;
        }

        public NodeHandle<T> batchKey(String batchKey) {
            this.batchKey = batchKey;
            return this;
        }

        public NodeHandle<T> localityKey(String localityKey) {
            this.localityKey = localityKey;
            return this;
        }

        public Builder owner() {
            return owner;
        }

        public String name() {
            return name;
        }

        public Collection<String> dependencyNames() {
            return Collections.unmodifiableSet(dependencyNames);
        }

        public ExecutionPriority priority() {
            return priority;
        }

        public boolean threadSafe() {
            return threadSafe;
        }

        public Duration timeout() {
            return timeout;
        }

        public String batchKey() {
            return batchKey;
        }

        public String localityKey() {
            return localityKey;
        }

        public NodeWork<T> work() {
            return work;
        }
    }
}
