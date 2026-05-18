import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class JarApiPerfBench {

    private static final String MOD_ID = "benchie_mod";
    private static final String VERSION = "1.0.0";
    private static final int WARMUPS = Integer.getInteger("quantified.jarbench.warmups", 3);
    private static final int ITERS = Integer.getInteger("quantified.jarbench.iters", 8);
    private static final int UNIQUE_TASK_COUNT = Integer.getInteger("quantified.jarbench.uniqueTasks", 4096);
    private static final int DUPLICATE_SUBMISSIONS = Integer.getInteger("quantified.jarbench.duplicateSubmissions", 2048);
    private static final int PARALLEL_MICRO_COUNT = Integer.getInteger("quantified.jarbench.parallelMicroCount", 2048);
    private static final int PARALLEL_MEDIUM_COUNT = Integer.getInteger("quantified.jarbench.parallelMediumCount", 8192);
    private static final int DAG_NODE_COUNT = Integer.getInteger("quantified.jarbench.dagNodes", 24);

    private JarApiPerfBench() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: JarApiPerfBench <jar-path> [label]");
            System.exit(2);
        }

        String jarPath = new File(args[0]).getAbsolutePath();
        String label = args.length > 1 ? args[1] : new File(jarPath).getName();

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
        ApiHarness harness = new ApiHarness(executor);
        try {
            harness.initialiseRuntime();
            harness.register();

            LinkedHashMap<String, ScenarioStats> stats = new LinkedHashMap<>();
            stats.put("tiny_unique_burst", measure(() -> benchmarkTinyUniqueBurst(harness), UNIQUE_TASK_COUNT));
            stats.put("duplicate_burst", measure(() -> benchmarkDuplicateBurst(harness), DUPLICATE_SUBMISSIONS));
            stats.put("parallel_micro", measure(() -> benchmarkParallel(harness, PARALLEL_MICRO_COUNT, 8), PARALLEL_MICRO_COUNT));
            stats.put("parallel_medium", measure(() -> benchmarkParallel(harness, PARALLEL_MEDIUM_COUNT, 32), PARALLEL_MEDIUM_COUNT));
            stats.put("dag_micro", measure(() -> benchmarkDag(harness), DAG_NODE_COUNT));

            String json = toJson(label, jarPath, stats);
            printHuman(label, jarPath, stats);
            System.out.println("RESULT_JSON=" + json);
        } finally {
            try {
                harness.shutdownRuntime();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static ScenarioStats measure(ThrowingSupplier<ScenarioRun> scenario, int unitCount) throws Exception {
        for (int i = 0; i < WARMUPS; i++) {
            scenario.get();
        }

        ArrayList<Double> samplesMs = new ArrayList<>(ITERS);
        ArrayList<Long> extraSamples = new ArrayList<>(ITERS);
        for (int i = 0; i < ITERS; i++) {
            ScenarioRun run = scenario.get();
            samplesMs.add(run.nanos() / 1_000_000.0d);
            extraSamples.add(run.extraMetric());
        }

        samplesMs.sort(Double::compareTo);
        extraSamples.sort(Long::compareTo);

        double sum = 0.0d;
        for (double sample : samplesMs) {
            sum += sample;
        }
        double medianMs = samplesMs.get(samplesMs.size() / 2);
        double meanMs = sum / samplesMs.size();
        double minMs = samplesMs.get(0);
        double maxMs = samplesMs.get(samplesMs.size() - 1);
        double throughput = medianMs <= 0.0d ? 0.0d : unitCount / (medianMs / 1000.0d);
        long medianExtra = extraSamples.get(extraSamples.size() / 2);
        return new ScenarioStats(medianMs, meanMs, minMs, maxMs, throughput, medianExtra);
    }

    private static ScenarioRun benchmarkTinyUniqueBurst(ApiHarness harness) throws Exception {
        ArrayList<CompletableFuture<?>> futures = new ArrayList<>(UNIQUE_TASK_COUNT);
        long start = System.nanoTime();
        for (int i = 0; i < UNIQUE_TASK_COUNT; i++) {
            String taskName = "tiny-unique-" + i + "-" + System.nanoTime();
            futures.add(harness.submitTask(taskName, true, "unique|" + i, trivialSupplier(i)));
        }
        joinAll(futures);
        return new ScenarioRun(System.nanoTime() - start, UNIQUE_TASK_COUNT);
    }

    private static ScenarioRun benchmarkDuplicateBurst(ApiHarness harness) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Gate gate = new Gate();
        ArrayList<CompletableFuture<?>> futures = new ArrayList<>(DUPLICATE_SUBMISSIONS);
        String batchKey = "dupe|" + System.nanoTime();
        String taskName = "duplicate-burst";
        long start = System.nanoTime();
        for (int i = 0; i < DUPLICATE_SUBMISSIONS; i++) {
            futures.add(harness.submitTask(taskName, true, batchKey, () -> {
                executions.incrementAndGet();
                gate.await();
                return 7;
            }));
        }
        gate.open();
        joinAll(futures);
        return new ScenarioRun(System.nanoTime() - start, executions.get());
    }

    private static ScenarioRun benchmarkParallel(ApiHarness harness, int count, int mathRounds) throws Exception {
        long taskKey = 90_000L + count + mathRounds;
        long start = System.nanoTime();
        List<?> results = harness.submitParallel("parallel-" + count + "-" + mathRounds + "-" + System.nanoTime(), taskKey, count, mathRounds);
        if (results.size() != count) {
            throw new IllegalStateException("Parallel result size mismatch: " + results.size() + " != " + count);
        }
        return new ScenarioRun(System.nanoTime() - start, results.size());
    }

    private static ScenarioRun benchmarkDag(ApiHarness harness) throws Exception {
        long start = System.nanoTime();
        Map<?, ?> result = harness.submitDag("dag-" + System.nanoTime(), 24_000L + ThreadLocalRandom.current().nextInt(10_000), DAG_NODE_COUNT);
        if (result.size() != DAG_NODE_COUNT) {
            throw new IllegalStateException("DAG result size mismatch: " + result.size() + " != " + DAG_NODE_COUNT);
        }
        return new ScenarioRun(System.nanoTime() - start, result.size());
    }

    private static Supplier<Integer> trivialSupplier(int value) {
        return () -> Integer.rotateLeft(value * 31 + 17, 1);
    }

    private static int expensiveMath(int seed, int rounds) {
        int value = seed;
        for (int i = 0; i < rounds; i++) {
            value = Integer.rotateLeft(value * 31 + 17, 3) ^ (i * 13);
        }
        return value;
    }

    private static void joinAll(List<CompletableFuture<?>> futures) {
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private static void printHuman(String label, String jarPath, Map<String, ScenarioStats> stats) {
        System.out.println("QAPI jar benchmark");
        System.out.println("label=" + label);
        System.out.println("jar=" + jarPath);
        for (Map.Entry<String, ScenarioStats> entry : stats.entrySet()) {
            ScenarioStats stat = entry.getValue();
            System.out.printf(Locale.ROOT,
                "%s median=%.3fms mean=%.3fms min=%.3fms max=%.3fms throughput=%.1f/s extra=%d%n",
                entry.getKey(),
                stat.medianMs(),
                stat.meanMs(),
                stat.minMs(),
                stat.maxMs(),
                stat.throughputPerSecond(),
                stat.extraMetricMedian());
        }
    }

    private static String toJson(String label, String jarPath, Map<String, ScenarioStats> stats) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        appendJsonField(json, "label", label).append(',');
        appendJsonField(json, "jar", jarPath).append(',');
        appendJsonField(json, "javaVersion", System.getProperty("java.version")).append(',');
        json.append("\"processors\":").append(Runtime.getRuntime().availableProcessors()).append(',');
        json.append("\"scenarios\":{");

        boolean first = true;
        for (Map.Entry<String, ScenarioStats> entry : stats.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append('"').append(':');
            ScenarioStats stat = entry.getValue();
            json.append('{')
                .append("\"medianMs\":").append(format(stat.medianMs())).append(',')
                .append("\"meanMs\":").append(format(stat.meanMs())).append(',')
                .append("\"minMs\":").append(format(stat.minMs())).append(',')
                .append("\"maxMs\":").append(format(stat.maxMs())).append(',')
                .append("\"throughputPerSec\":").append(format(stat.throughputPerSecond())).append(',')
                .append("\"extraMetricMedian\":").append(stat.extraMetricMedian())
                .append('}');
        }

        json.append("}}");
        return json.toString();
    }

    private static StringBuilder appendJsonField(StringBuilder json, String key, String value) {
        return json.append('"').append(escape(key)).append('"').append(':')
            .append('"').append(escape(value)).append('"');
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record ScenarioRun(long nanos, long extraMetric) {
    }

    private record ScenarioStats(double medianMs,
                                 double meanMs,
                                 double minMs,
                                 double maxMs,
                                 double throughputPerSecond,
                                 long extraMetricMedian) {
    }

    private static final class Gate {
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        private void open() {
            latch.countDown();
        }

        private void await() {
            try {
                latch.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interruptedException);
            }
        }
    }

    private static final class ApiHarness {
        private final ScheduledExecutorService executor;
        private final Class<?> quantifiedApiClass;
        private final Class<?> quantifiedHandleClass;
        private final Class<?> quantifiedTaskClass;
        private final Class<?> quantifiedTaskBuilderClass;
        private final Class<?> parallelComputeClass;
        private final Class<?> parallelBuilderClass;
        private final Class<?> graphClass;
        private final Class<?> graphBuilderClass;
        private final Class<?> nodeHandleClass;
        private final Class<?> nodeWorkClass;
        private final Class<?> nodeContextClass;
        private final Class<?> asyncManagerClass;
        private final Class<?> asyncManagerBootstrapClass;
        private final Method registerMethod;
        private final Constructor<?> handleConstructor;
        private final Method submitTaskMethod;
        private final Method taskBuilderFactory;
        private final Method taskThreadSafeMethod;
        private final Method taskBatchKeyMethod;
        private final Method taskBuildMethod;
        private final Method parallelBuilderFactory;
        private final Method parallelSlicesMethod;
        private final Method parallelSliceExecutorMethod;
        private final Method parallelMaxParallelismMethod;
        private final Method parallelSubmitMethod;
        private final Method graphBuilderFactory;
        private final Method graphNodeMethod;
        private final Method graphSubmitAllMethod;
        private final Method nodeDependsOnMethod;
        private final Method initAsyncManagerMethod;
        private final Method shutdownAsyncManagerMethod;
        private final Method defaultsBootstrapMethod;
        private final Method withQueueBoundMethod;
        private final Method setMaxTasksForModMethod;

        private final Object handle;

        private ApiHarness(ScheduledExecutorService executor) throws Exception {
            this.executor = Objects.requireNonNull(executor, "executor");
            quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
            quantifiedHandleClass = Class.forName("org.admany.quantified.api.QuantifiedHandle");
            quantifiedTaskClass = Class.forName("org.admany.quantified.api.model.QuantifiedTask");
            quantifiedTaskBuilderClass = Class.forName("org.admany.quantified.api.model.QuantifiedTask$Builder");
            parallelComputeClass = Class.forName("org.admany.quantified.api.parallel.ParallelCompute");
            parallelBuilderClass = Class.forName("org.admany.quantified.api.parallel.ParallelCompute$Builder");
            graphClass = Class.forName("org.admany.quantified.api.graph.QuantifiedTaskGraph");
            graphBuilderClass = Class.forName("org.admany.quantified.api.graph.QuantifiedTaskGraph$Builder");
            nodeHandleClass = Class.forName("org.admany.quantified.api.graph.QuantifiedTaskGraph$NodeHandle");
            nodeWorkClass = Class.forName("org.admany.quantified.api.graph.QuantifiedTaskGraph$NodeWork");
            nodeContextClass = Class.forName("org.admany.quantified.api.graph.QuantifiedTaskGraph$NodeContext");
            asyncManagerClass = Class.forName("org.admany.quantified.core.common.async.core.AsyncManager");
            asyncManagerBootstrapClass = Class.forName("org.admany.quantified.core.common.async.core.AsyncManagerBootstrap");
            Class<?> modPriorityManagerClass = Class.forName("org.admany.quantified.core.common.async.task.ModPriorityManager");

            registerMethod = quantifiedApiClass.getMethod("register", String.class, String.class, String.class);
            handleConstructor = quantifiedHandleClass.getConstructor(String.class, String.class);
            submitTaskMethod = quantifiedHandleClass.getDeclaredMethod("submitTask", quantifiedTaskClass);
            submitTaskMethod.setAccessible(true);
            taskBuilderFactory = quantifiedTaskClass.getMethod("builder", String.class, String.class, Supplier.class);
            taskThreadSafeMethod = quantifiedTaskBuilderClass.getMethod("threadSafe");
            taskBatchKeyMethod = quantifiedTaskBuilderClass.getMethod("batchKey", String.class);
            taskBuildMethod = quantifiedTaskBuilderClass.getMethod("build");

            parallelBuilderFactory = parallelComputeClass.getMethod("builder", String.class, String.class, long.class);
            parallelSlicesMethod = parallelBuilderClass.getMethod("slices", Supplier.class);
            parallelSliceExecutorMethod = parallelBuilderClass.getMethod("sliceExecutor", java.util.function.Function.class);
            parallelMaxParallelismMethod = parallelBuilderClass.getMethod("maxParallelism", int.class);
            parallelSubmitMethod = parallelBuilderClass.getMethod("submit");

            graphBuilderFactory = graphClass.getMethod("builder", String.class, String.class, long.class);
            graphNodeMethod = graphBuilderClass.getMethod("node", String.class, nodeWorkClass);
            graphSubmitAllMethod = graphBuilderClass.getMethod("submitAll");
            nodeDependsOnMethod = nodeHandleClass.getMethod("dependsOn", Array.newInstance(nodeHandleClass, 0).getClass());

            initAsyncManagerMethod = asyncManagerClass.getMethod("initialise", asyncManagerBootstrapClass, ScheduledExecutorService.class);
            shutdownAsyncManagerMethod = asyncManagerClass.getMethod("shutdown");
            defaultsBootstrapMethod = asyncManagerBootstrapClass.getMethod("defaults", int.class);
            withQueueBoundMethod = asyncManagerBootstrapClass.getMethod("withQueueBound", int.class);
            setMaxTasksForModMethod = modPriorityManagerClass.getMethod("setMaxTasksForMod", String.class, long.class);

            handle = handleConstructor.newInstance(MOD_ID, VERSION);
        }

        private void initialiseRuntime() throws Exception {
            Object bootstrap = defaultsBootstrapMethod.invoke(null, Math.max(2, Runtime.getRuntime().availableProcessors()));
            bootstrap = withQueueBoundMethod.invoke(bootstrap, 32768);
            initAsyncManagerMethod.invoke(null, bootstrap, executor);
            setMaxTasksForModMethod.invoke(null, MOD_ID, 32768L);
        }

        private void shutdownRuntime() throws Exception {
            shutdownAsyncManagerMethod.invoke(null);
        }

        private void register() throws Exception {
            registerMethod.invoke(null, MOD_ID, "Bench Mod", VERSION);
        }

        private CompletableFuture<?> submitTask(String taskName,
                                                boolean threadSafe,
                                                String batchKey,
                                                Supplier<?> supplier) throws Exception {
            Object builder = taskBuilderFactory.invoke(null, MOD_ID, taskName, supplier);
            if (threadSafe) {
                taskThreadSafeMethod.invoke(builder);
            }
            if (batchKey != null) {
                taskBatchKeyMethod.invoke(builder, batchKey);
            }
            Object task = taskBuildMethod.invoke(builder);
            return castFuture(submitTaskMethod.invoke(handle, task));
        }

        private List<?> submitParallel(String taskName, long taskKey, int count, int rounds) throws Exception {
            Object builder = parallelBuilderFactory.invoke(null, MOD_ID, taskName, taskKey);
            Supplier<List<Integer>> slices = () -> {
                ArrayList<Integer> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    values.add(i);
                }
                return values;
            };
            java.util.function.Function<Integer, Integer> fn = value -> expensiveMath(value, rounds);
            parallelSlicesMethod.invoke(builder, slices);
            parallelSliceExecutorMethod.invoke(builder, fn);
            parallelMaxParallelismMethod.invoke(builder, Math.max(2, Runtime.getRuntime().availableProcessors()));
            Object result = castFuture(parallelSubmitMethod.invoke(builder)).join();
            if (!(result instanceof List<?> list)) {
                throw new IllegalStateException("Parallel benchmark did not return a List");
            }
            return list;
        }

        private Map<?, ?> submitDag(String graphName, long graphKey, int nodeCount) throws Exception {
            Object builder = graphBuilderFactory.invoke(null, MOD_ID, graphName, graphKey);
            ArrayList<Object> nodes = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                final int value = i;
                InvocationHandler handler = (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        Object context = args[0];
                        Method dependencyResults = nodeContextClass.getMethod("dependencyResults");
                        Object mapObj = dependencyResults.invoke(context);
                        int dependencyMix = 0;
                        if (mapObj instanceof Map<?, ?> map) {
                            for (Object dependency : map.values()) {
                                dependencyMix += ((Number) dependency).intValue();
                            }
                        }
                        int base = value * 3 + 7;
                        return Integer.rotateLeft(base + dependencyMix, 1);
                    }
                    throw new UnsupportedOperationException(method.toString());
                };
                Object work = java.lang.reflect.Proxy.newProxyInstance(
                    nodeWorkClass.getClassLoader(),
                    new Class<?>[]{nodeWorkClass},
                    handler
                );
                Object node = graphNodeMethod.invoke(builder, "n" + i, work);
                nodes.add(node);
            }
            for (int i = 2; i < nodes.size(); i++) {
                Object dependencies = Array.newInstance(nodeHandleClass, 2);
                Array.set(dependencies, 0, nodes.get(i - 1));
                Array.set(dependencies, 1, nodes.get(i - 2));
                nodeDependsOnMethod.invoke(nodes.get(i), dependencies);
            }
            Object result = castFuture(graphSubmitAllMethod.invoke(builder)).join();
            if (!(result instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Graph benchmark did not return a Map");
            }
            return map;
        }

        @SuppressWarnings("unchecked")
        private static CompletableFuture<Object> castFuture(Object future) {
            return (CompletableFuture<Object>) future;
        }
    }
}
