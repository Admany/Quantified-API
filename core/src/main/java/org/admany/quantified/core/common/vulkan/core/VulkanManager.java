package org.admany.quantified.core.common.vulkan.core;

import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.util.VulkanLoaderIsolation;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFloatControlsProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.QuantifiedVkBootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VulkanManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanManager.class);
    private static final long EXECUTION_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int MAX_PENDING_TASKS = 256;
    private static final int MAX_IN_FLIGHT_WORKSPACES = 3;
    private static final long MAX_ACCEPTED_VRAM_BYTES = 512L * 1024L * 1024L;
    private static final long INIT_RETRY_COOLDOWN_MS = 30_000L;
    private static final long DEVICE_LOCAL_TRANSFER_THRESHOLD_BYTES = 256L * 1024L;
    private static final int VECTOR_LOCAL_SIZE_X = 256;
    private static final int VECTOR_ELEMENTS_PER_INVOCATION = 8;
    private static final int TERRAIN_LOCAL_SIZE_X = 256;
    private static final int TERRAIN_OUTPUT_COMPONENTS = 4;
    private static final int MC_DENSITY_LOCAL_SIZE_X = 256;
    private static final int MC_DENSITY_PROGRAM_STRIDE = 4;
    private static final int MC_DENSITY_MAX_INSTRUCTIONS = 256;
    private static final String VECTOR_ADD_SHADER_RESOURCE = "/quantified/shaders/vulkan/vector_add.comp.spv";
    private static final String MATRIX_MULTIPLY_SHADER_RESOURCE = "/quantified/shaders/vulkan/matrix_multiply.comp.spv";
    private static final String MONTE_CARLO_PI_SHADER_RESOURCE = "/quantified/shaders/vulkan/monte_carlo_pi.comp.spv";
    private static final String TERRAIN_GENERATION_SHADER_RESOURCE = "/quantified/shaders/vulkan/terrain_generation.comp.spv";
    private static final String MC_DENSITY_FUNCTIONS_SHADER_RESOURCE = "/quantified/shaders/vulkan/mc_density_functions.comp.spv";
    private static final String AUTO_RUNTIME_INIT_PROPERTY = "quantified.vulkan.autoRuntimeInit";
    private static final String REQUIRE_DETERMINISTIC_FLOAT32_PROPERTY = "quantified.vulkan.requireDeterministicFloat32";
    private static final boolean REQUIRE_DETERMINISTIC_FLOAT32 =
        Boolean.parseBoolean(System.getProperty(REQUIRE_DETERMINISTIC_FLOAT32_PROPERTY, "true"));

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final Object INIT_MUTEX = new Object();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(MAX_IN_FLIGHT_WORKSPACES, new VulkanThreadFactory());
    private static final ExecutorService INIT_EXECUTOR = Executors.newSingleThreadExecutor(
        runnable -> LwjglRuntimeTuning.newDaemonThread(
            runnable,
            "Quantified-Vulkan-Init",
            LwjglRuntimeTuning.gpuThreadStackSizeKb()
        )
    );
    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor(
        runnable -> LwjglRuntimeTuning.newDaemonThread(
            runnable,
            "Quantified-Vulkan-Probe",
            LwjglRuntimeTuning.probeThreadStackSizeKb()
        )
    );
    private static final AtomicReference<String> PREFERRED_DEVICE = new AtomicReference<>();
    private static final AtomicReference<CompletableFuture<Boolean>> ACTIVE_PROBE = new AtomicReference<>();
    private static final AtomicReference<CompletableFuture<Boolean>> ACTIVE_INIT = new AtomicReference<>();
    private static final AtomicBoolean VULKAN_PROBING = new AtomicBoolean(false);
    private static final AtomicBoolean DEFERRED_RUNTIME_INIT_LOGGED = new AtomicBoolean(false);

    private static volatile State state;
    private static volatile RuntimeStatus lastStatus = RuntimeStatus.failed("Vulkan not initialized");
    private static volatile long nextInitRetryMs = 0L;

    private VulkanManager() {
    }

    public static boolean isAvailable() {
        return INITIALIZED.get() && state != null;
    }

    public static RuntimeStatus runtimeStatus() {
        if (INITIALIZED.get() && state != null) {
            return RuntimeStatus.available();
        }
        VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.snapshot();
        if (!snapshot.available()) {
            return RuntimeStatus.failed(snapshot.failureReason());
        }
        return lastStatus;
    }

    public static void notePending(String reason) {
        String detail = reason != null && !reason.isBlank() ? reason : "Vulkan probe pending";
        lastStatus = RuntimeStatus.failed(detail);
    }

    public static boolean isProbeRunning() {
        CompletableFuture<Boolean> active = ACTIVE_PROBE.get();
        return VULKAN_PROBING.get() || (active != null && !active.isDone());
    }

    public static boolean isRuntimeWarmupRunning() {
        CompletableFuture<Boolean> active = ACTIVE_INIT.get();
        return active != null && !active.isDone();
    }

    public static CompletableFuture<Boolean> forceProbe() {
        CompletableFuture<Boolean> existing = ACTIVE_PROBE.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!ACTIVE_PROBE.compareAndSet(existing, future)) {
            CompletableFuture<Boolean> concurrent = ACTIVE_PROBE.get();
            return concurrent != null ? concurrent : future;
        }
        PROBE_EXECUTOR.submit(() -> {
            try {
                future.complete(runManagedProbe());
            } catch (Throwable thr) {
                future.completeExceptionally(thr);
            } finally {
                ACTIVE_PROBE.compareAndSet(future, null);
            }
        });
        return future;
    }

    private static boolean runManagedProbe() {
        if (INITIALIZED.get() && state != null) {
            return true;
        }
        if (!VULKAN_PROBING.compareAndSet(false, true)) {
            LOGGER.debug("Skipping Vulkan probe because another probe is already running");
            return INITIALIZED.get() && state != null;
        }
        notePending("Vulkan probe in progress");
        try {
            VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.reprobe();
            if (snapshot.available()) {
                lastStatus = RuntimeStatus.failed("Vulkan probe succeeded; runtime initialization deferred until first use");
                LOGGER.info("Vulkan probe succeeded for {} device(s); runtime initialization deferred until first use",
                    snapshot.devices().size());
                DeveloperOverlayManager.recordApiLog("[Vulkan] Probe succeeded - " + deviceName());
                return true;
            }
            String reason = snapshot.failureReason();
            if (reason != null && !reason.isBlank()) {
                LOGGER.warn("Vulkan acceleration unavailable: " + reason);
                DeveloperOverlayManager.recordApiLog("[Vulkan] Probe failed - " + reason);
            } else {
                LOGGER.warn("Vulkan acceleration unavailable");
                DeveloperOverlayManager.recordApiLog("[Vulkan] Probe failed - Unknown reason");
            }
            return false;
        } finally {
            VULKAN_PROBING.set(false);
        }
    }

    public static boolean forceProbeSynchronous() {
        if (INITIALIZED.get() && state != null) {
            return true;
        }
        Thread current = Thread.currentThread();
        LOGGER.info("[Vulkan] forceProbeSynchronous() on thread '" + current.getName() + "' (group=" + current.getThreadGroup().getName() + ")");
        notePending("Vulkan probe in progress");
        // Run a fresh probe on this (large-stack) thread and update the cache.
        // Do NOT call invalidate() first — that clears the snapshot and races with any
        // thread calling isAvailable() / snapshot(), which would then lazily trigger
        // vkCreateInstance on a small-stack thread (render thread etc.).
        VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.reprobe();
        if (snapshot.available()) {
            lastStatus = RuntimeStatus.failed("Vulkan probe succeeded; runtime initialization deferred until first use");
            LOGGER.info("Vulkan probe succeeded for {} device(s); runtime initialization deferred until first use",
                snapshot.devices().size());
            DeveloperOverlayManager.recordApiLog("[Vulkan] Probe succeeded - " + deviceName());
            return true;
        }
        String reason = snapshot.failureReason();
        if (reason != null && !reason.isBlank()) {
            LOGGER.warn("Vulkan acceleration unavailable: " + reason);
            DeveloperOverlayManager.recordApiLog("[Vulkan] Probe failed - " + reason);
        } else {
            LOGGER.warn("Vulkan acceleration unavailable");
            DeveloperOverlayManager.recordApiLog("[Vulkan] Probe failed - Unknown reason");
        }
        return false;
    }

    public static CompletableFuture<Boolean> warmupAsync() {
        return warmupAsync("manual");
    }

    public static CompletableFuture<Boolean> warmupAsync(String reason) {
        if (INITIALIZED.get() && state != null) {
            return CompletableFuture.completedFuture(true);
        }
        if (isInitRetryCoolingDown()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> existing = ACTIVE_INIT.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!ACTIVE_INIT.compareAndSet(existing, future)) {
            CompletableFuture<Boolean> concurrent = ACTIVE_INIT.get();
            return concurrent != null ? concurrent : future;
        }

        String triggerReason = reason != null && !reason.isBlank() ? reason : "manual";
        lastStatus = RuntimeStatus.failed("Vulkan runtime initialization queued (" + triggerReason + ")");
        LOGGER.info("Vulkan runtime initialization queued (" + triggerReason + ")");
        DeveloperOverlayManager.recordApiLog("[Vulkan] Runtime warmup queued (" + triggerReason + ")");
        INIT_EXECUTOR.submit(() -> {
            try {
                boolean initialized = ensureInitialised();
                if (initialized) {
                    DEFERRED_RUNTIME_INIT_LOGGED.set(false);
                    DeveloperOverlayManager.recordApiLog("[Vulkan] Runtime ready - " + deviceName());
                }
                future.complete(initialized);
            } catch (Throwable throwable) {
                String detail = describeThrowable(throwable);
                lastStatus = RuntimeStatus.failed("Vulkan runtime warmup failed: " + detail);
                LOGGER.warn("Vulkan runtime warmup failed (" + triggerReason + ")", throwable);
                DeveloperOverlayManager.recordApiLog("[Vulkan] Runtime warmup failed (" + triggerReason + ") - " + detail);
                future.complete(false);
            } finally {
                ACTIVE_INIT.compareAndSet(future, null);
            }
        });
        return future;
    }

    public static boolean ensureInitialised() {
        if (INITIALIZED.get() && state != null) {
            return true;
        }
        if (isInitRetryCoolingDown()) {
            return false;
        }
        synchronized (INIT_MUTEX) {
            if (INITIALIZED.get() && state != null) {
                return true;
            }
            if (isInitRetryCoolingDown()) {
                return false;
            }
            VulkanRuntime.AvailabilitySnapshot runtime = VulkanRuntime.snapshot();
            if (!runtime.available()) {
                lastStatus = RuntimeStatus.failed(runtime.failureReason());
                return false;
            }
            try {
                state = createState(runtime.selectedApiVersion());
                INITIALIZED.set(true);
                nextInitRetryMs = 0L;
                lastStatus = RuntimeStatus.available();
                LOGGER.info("Vulkan initialized on device: " + deviceName());
                return true;
            } catch (Throwable throwable) {
                LOGGER.warn("Vulkan initialization failed", throwable);
                cleanupState(state);
                state = null;
                INITIALIZED.set(false);
                nextInitRetryMs = System.currentTimeMillis() + INIT_RETRY_COOLDOWN_MS;
                lastStatus = RuntimeStatus.failed("Vulkan initialization failed: " + describeThrowable(throwable));
                return false;
            }
        }
    }

    public static void setPreferredDevice(String preferredDevice) {
        String normalized = normalizeDevicePreference(preferredDevice);
        String previous = PREFERRED_DEVICE.getAndSet(normalized);
        if (!Objects.equals(previous, normalized)) {
            shutdown();
        }
    }

    public static void clearPreferredDevice() {
        setPreferredDevice(null);
    }

    public static List<VulkanDeviceInfo> listDevices() {
        LwjglRuntimeTuning.ensureConfigured();
        VulkanRuntime.AvailabilitySnapshot runtime = VulkanRuntime.snapshot();
        if (!runtime.bindingPresent()) {
            return List.of();
        }
        if (!runtime.devices().isEmpty()) {
            List<VulkanDeviceInfo> devices = new ArrayList<>(runtime.devices().size());
            for (VulkanRuntime.ProbeDeviceInfo device : runtime.devices()) {
                devices.add(new VulkanDeviceInfo(
                    device.id(),
                    sanitizeDeviceName(device.name()),
                    device.vendor(),
                    device.localMemoryBytes(),
                    device.deviceType(),
                    device.softwareAdapter()
                ));
            }
            return dedupeDeviceInfos(devices);
        }
        int apiVersion = preferredApiVersion(runtime);
        if (apiVersion == 0) {
            return List.of();
        }

        VkInstance instance = null;
        try {
            instance = createInstance(apiVersion);
            List<PhysicalSelection> selections = enumeratePhysicalDevices(instance);
            List<VulkanDeviceInfo> devices = new ArrayList<>(selections.size());
            for (PhysicalSelection selection : selections) {
                devices.add(new VulkanDeviceInfo(
                    selection.id(),
                    selection.deviceName(),
                    selection.vendorName(),
                    selection.localMemoryBytes(),
                    selection.deviceType(),
                    selection.softwareAdapter()
                ));
            }
            return dedupeDeviceInfos(devices);
        } catch (Throwable throwable) {
            String reason = describeThrowable(throwable);
            lastStatus = RuntimeStatus.failed(reason);
            LOGGER.debug("Failed to enumerate Vulkan devices", throwable);
            return List.of();
        } finally {
            if (instance != null) {
                try {
                    VK10.vkDestroyInstance(instance, null);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static <T> CompletableFuture<T> executeOnGpu(VulkanTask<T> task) {
        Objects.requireNonNull(task, "task");
        return CompletableFuture.supplyAsync(() -> {
            if (!ensureInitialised()) {
                throw new IllegalStateException(runtimeStatus().failureReason());
            }
            return task.executeOnGPU(new VulkanContext(VulkanManagerHolder.INSTANCE));
        }, EXECUTOR);
    }

    public static boolean canAcceptTask(VulkanTask<?> task) {
        if (task == null) {
            return false;
        }
        if (task.estimatedVramBytes() > MAX_ACCEPTED_VRAM_BYTES) {
            return false;
        }
        if (!INITIALIZED.get() || state == null) {
            if (isInitRetryCoolingDown() || !VulkanRuntime.isAvailable()) {
                return false;
            }
            if (Boolean.getBoolean(AUTO_RUNTIME_INIT_PROPERTY)) {
                warmupAsync("auto-runtime-init");
            } else if (DEFERRED_RUNTIME_INIT_LOGGED.compareAndSet(false, true)) {
                String message = "Vulkan probe is available, but the runtime isn't ready yet, deffering tasks to the CPU."
                    + "Set -D" + AUTO_RUNTIME_INIT_PROPERTY + "=true to allow automatic runtime warmup.";
                lastStatus = RuntimeStatus.failed(message);
                LOGGER.info(message);
                DeveloperOverlayManager.recordApiLog("[Vulkan] Runtime init has been deferred. Batches will fall back to the CPU until runtime is ready.");
            }
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        if (EXECUTOR instanceof java.util.concurrent.ThreadPoolExecutor executor) {
            return executor.getQueue().size() < MAX_PENDING_TASKS;
        }
        return true;
    }

    public static String deviceName() {
        State local = state;
        if (local != null) {
            return local.deviceName;
        }
        VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.snapshot();
        if (!snapshot.devices().isEmpty()) {
            return snapshot.devices().get(0).name();
        }
        return "Unavailable";
    }

    public static void shutdown() {
        synchronized (INIT_MUTEX) {
            cleanupState(state);
            state = null;
            INITIALIZED.set(false);
            nextInitRetryMs = 0L;
            lastStatus = RuntimeStatus.failed("Vulkan shutdown");
            VulkanRuntime.invalidate();
        }
    }

    float[] executeVectorAdd(float[] a, float[] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths must match");
        }
        State local = requireState();
        Program program = local.programs.get("vector_add");
        try (WorkspaceLease lease = acquireWorkspace(local, program, "vector_add:" + a.length,
            WorkloadProfile.BANDWIDTH,
            groupCount(a.length, VECTOR_LOCAL_SIZE_X * VECTOR_ELEMENTS_PER_INVOCATION), 1, 1,
            2,
            new int[]{a.length},
            (long) a.length * Float.BYTES,
            (long) b.length * Float.BYTES,
            (long) a.length * Float.BYTES)) {
            DispatchWorkspace workspace = lease.workspace();
            writeFloatArray(workspace.buffers[0], a);
            writeFloatArray(workspace.buffers[1], b);
            dispatch(local, workspace);
            return readFloatArray(workspace.buffers[2], a.length);
        }
    }

    float[][] executeMatrixMultiply(float[][] a, float[][] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.length == 0 || b.length == 0 || a[0].length != b.length) {
            throw new IllegalArgumentException("Invalid matrix dimensions for multiplication");
        }
        int m = a.length;
        int p = a[0].length;
        int n = b[0].length;
        float[] flatA = flattenMatrix(a);
        float[] flatB = flattenMatrix(b);
        State local = requireState();
        Program program = local.programs.get("matrix_multiply");
        try (WorkspaceLease lease = acquireWorkspace(local, program,
            "matrix_multiply:" + m + ":" + n + ":" + p,
            WorkloadProfile.COMPUTE_DENSE,
            groupCount(m, 16), groupCount(n, 16), 1,
            2,
            new int[]{m, n, p},
            (long) flatA.length * Float.BYTES,
            (long) flatB.length * Float.BYTES,
            (long) m * n * Float.BYTES)) {
            DispatchWorkspace workspace = lease.workspace();
            writeFloatArray(workspace.buffers[0], flatA);
            writeFloatArray(workspace.buffers[1], flatB);
            dispatch(local, workspace);
            return reconstructMatrix(readFloatArray(workspace.buffers[2], m * n), m, n);
        }
    }

    double executeMonteCarloPi(int samples) {
        if (samples <= 0) {
            throw new IllegalArgumentException("Samples must be positive");
        }
        State local = requireState();
        Program program = local.programs.get("monte_carlo_pi");
        try (WorkspaceLease lease = acquireWorkspace(local, program, "monte_carlo_pi:" + samples,
            WorkloadProfile.REDUCTION,
            groupCount(samples, 256), 1, 1,
            0,
            new int[]{samples},
            Integer.BYTES)) {
            DispatchWorkspace workspace = lease.workspace();
            writeInt(workspace.buffers[0], 0);
            dispatch(local, workspace);
            int hits = readInt(workspace.buffers[0]);
            return 4.0d * hits / samples;
        }
    }

    float[] executeTerrainGeneration(float[] inputCoords) {
        Objects.requireNonNull(inputCoords, "inputCoords");
        if (inputCoords.length % 3 != 0) {
            throw new IllegalArgumentException("Terrain generation input must be packed xyz triples");
        }
        if (inputCoords.length == 0) {
            return new float[0];
        }
        int sampleCount = inputCoords.length / 3;
        State local = requireState();
        Program program = local.programs.get("terrain_generation");
        try (WorkspaceLease lease = acquireWorkspace(local, program, "terrain_generation:" + sampleCount,
            WorkloadProfile.COMPUTE_DENSE,
            groupCount(sampleCount, TERRAIN_LOCAL_SIZE_X), 1, 1,
            1,
            new int[]{sampleCount},
            (long) inputCoords.length * Float.BYTES,
            (long) sampleCount * TERRAIN_OUTPUT_COMPONENTS * Float.BYTES)) {
            DispatchWorkspace workspace = lease.workspace();
            writeFloatArray(workspace.buffers[0], inputCoords);
            dispatch(local, workspace);
            return readFloatArray(workspace.buffers[1], sampleCount * TERRAIN_OUTPUT_COMPONENTS);
        }
    }

    float[] executeMcDensityFunctions(float[] packedCoords, float[] encodedProgram, int instructionCount) {
        return executeMcDensityFunctions(packedCoords, encodedProgram, instructionCount, new float[0], 0);
    }

    float[] executeMcDensityFunctions(float[] packedCoords,
                                      float[] encodedProgram,
                                      int instructionCount,
                                      float[] auxValues,
                                      int auxValueCount) {
        Objects.requireNonNull(packedCoords, "packedCoords");
        Objects.requireNonNull(encodedProgram, "encodedProgram");
        Objects.requireNonNull(auxValues, "auxValues");
        if (packedCoords.length % 3 != 0) {
            throw new IllegalArgumentException("Packed coordinate array must be xyz triples");
        }
        int sampleCount = packedCoords.length / 3;
        if (sampleCount == 0) {
            return new float[0];
        }
        int availableInstructions = encodedProgram.length / MC_DENSITY_PROGRAM_STRIDE;
        if (instructionCount <= 0 || instructionCount > availableInstructions) {
            throw new IllegalArgumentException("Invalid density instruction count: " + instructionCount
                + " available=" + availableInstructions);
        }
        if (instructionCount > MC_DENSITY_MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException("Density instruction count exceeds "
                + MC_DENSITY_MAX_INSTRUCTIONS + ": " + instructionCount);
        }
        if (auxValueCount < 0) {
            throw new IllegalArgumentException("Aux value count must be non-negative: " + auxValueCount);
        }
        if (auxValues.length < auxValueCount * sampleCount) {
            throw new IllegalArgumentException("Aux values are shorter than aux count: "
                + auxValues.length + " floats for " + auxValueCount + " aux values and " + sampleCount + " samples");
        }

        State local = requireState();
        Program program = local.programs.get("mc_density_functions");
        float[] programSlice = Arrays.copyOf(encodedProgram, instructionCount * MC_DENSITY_PROGRAM_STRIDE);
        try (WorkspaceLease lease = prepareMcDensityDispatch(local, program, packedCoords, sampleCount,
            programSlice, instructionCount, auxValues, auxValueCount)) {
            dispatch(local, lease.workspace());
            return readFloatArray(lease.workspace().buffers[3], sampleCount);
        }
    }

    float[][] executeMcDensityFunctionBatch(List<McDensityVulkanTask> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return new float[0][];
        }

        float[][] results = new float[tasks.size()][];
        List<McDensityBatchGroup> groups = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            McDensityVulkanTask task = Objects.requireNonNull(tasks.get(i), "tasks[" + i + "]");
            if (task.sampleCount() == 0) {
                results[i] = new float[0];
                continue;
            }
            McDensityBatchGroup group = null;
            for (McDensityBatchGroup candidate : groups) {
                if (candidate.matches(task)) {
                    group = candidate;
                    break;
                }
            }
            if (group == null) {
                group = new McDensityBatchGroup(task.encodedProgram(), task.instructionCount(), task.auxValueCount());
                groups.add(group);
            }
            group.add(i, task);
        }

        List<PreparedMcDensityGroup> prepared = new ArrayList<>(groups.size());
        try {
            State local = requireState();
            Program program = local.programs.get("mc_density_functions");
            for (McDensityBatchGroup group : groups) {
                float[] packedCoords = new float[group.totalSamples * 3];
                int coordOffset = 0;
                for (McDensityVulkanTask task : group.tasks) {
                    float[] taskCoords = task.packedCoords();
                    System.arraycopy(taskCoords, 0, packedCoords, coordOffset, taskCoords.length);
                    coordOffset += taskCoords.length;
                }

                float[] auxValues = group.combineAuxValues();
                WorkspaceLease lease = prepareMcDensityDispatch(local, program, packedCoords, group.totalSamples,
                    group.encodedProgram, group.instructionCount, auxValues, group.auxValueCount);
                prepared.add(new PreparedMcDensityGroup(group, lease));
            }

            List<DispatchWorkspace> workspaces = new ArrayList<>(prepared.size());
            for (PreparedMcDensityGroup preparedGroup : prepared) {
                workspaces.add(preparedGroup.lease.workspace());
            }
            dispatch(local, workspaces);

            for (PreparedMcDensityGroup preparedGroup : prepared) {
                McDensityBatchGroup group = preparedGroup.group;
                float[] combined = readFloatArray(preparedGroup.lease.workspace().buffers[3], group.totalSamples);
                int outputOffset = 0;
                for (int i = 0; i < group.tasks.size(); i++) {
                    McDensityVulkanTask task = group.tasks.get(i);
                    int sampleCount = task.sampleCount();
                    results[group.indices.get(i)] = Arrays.copyOfRange(combined, outputOffset, outputOffset + sampleCount);
                    outputOffset += sampleCount;
                }
            }
        } finally {
            for (PreparedMcDensityGroup preparedGroup : prepared) {
                preparedGroup.lease.close();
            }
        }
        return results;
    }

    private State requireState() {
        State local = state;
        if (local == null && !ensureInitialised()) {
            throw new IllegalStateException(runtimeStatus().failureReason());
        }
        return state;
    }

    private static State createState(int apiVersion) {
        LwjglRuntimeTuning.ensureConfigured();
        VkInstance instance = createInstance(apiVersion);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PhysicalSelection selection = pickPhysicalDevice(instance);
            VkPhysicalDevice physicalDevice = new VkPhysicalDevice(selection.physicalDeviceHandle(), instance);
            FloatControlSummary floatControls = queryFloatControls(physicalDevice, apiVersion, stack);
            LOGGER.info("[Vulkan] Float controls: {}", floatControls.summary());
            if (REQUIRE_DETERMINISTIC_FLOAT32 && !floatControls.deterministicFloat32()) {
                throw new IllegalStateException("Vulkan device does not expose strict float32 controls required for deterministic compute: "
                    + floatControls.summary() + ". Set -D" + REQUIRE_DETERMINISTIC_FLOAT32_PROPERTY
                    + "=false to allow faster-but-less-strict GPU math.");
            }
            FloatBuffer priorities = stack.floats(1.0f);
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(selection.computeQueueFamily)
                .pQueuePriorities(priorities);

            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueInfo);

            PointerBuffer devicePtr = stack.mallocPointer(1);
            checkVk(VK10.vkCreateDevice(physicalDevice, deviceInfo, null, devicePtr), "vkCreateDevice");
            VkDevice device = QuantifiedVkBootstrap.wrapDevice(devicePtr.get(0), physicalDevice, deviceInfo, apiVersion);

            PointerBuffer queuePtr = stack.mallocPointer(1);
            VK10.vkGetDeviceQueue(device, selection.computeQueueFamily, 0, queuePtr);
            VkQueue queue = new VkQueue(queuePtr.get(0), device);

            LongBuffer poolPtr = stack.mallocLong(1);
            org.lwjgl.vulkan.VkCommandPoolCreateInfo poolInfo = org.lwjgl.vulkan.VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(selection.computeQueueFamily)
                .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            checkVk(VK10.vkCreateCommandPool(device, poolInfo, null, poolPtr), "vkCreateCommandPool");

            State created = new State(instance, physicalDevice, device, queue,
                poolPtr.get(0), selection.deviceName,
                selection.deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU);
            created.programs.put("vector_add", createProgram(created, "vector_add", VECTOR_ADD_SHADER_RESOURCE, 3, Integer.BYTES));
            created.programs.put("matrix_multiply", createProgram(created, "matrix_multiply", MATRIX_MULTIPLY_SHADER_RESOURCE, 3, Integer.BYTES * 3));
            created.programs.put("monte_carlo_pi", createProgram(created, "monte_carlo_pi", MONTE_CARLO_PI_SHADER_RESOURCE, 1, Integer.BYTES));
            created.programs.put("terrain_generation", createProgram(created, "terrain_generation", TERRAIN_GENERATION_SHADER_RESOURCE, 2, Integer.BYTES));
            created.programs.put("mc_density_functions", createProgram(created, "mc_density_functions", MC_DENSITY_FUNCTIONS_SHADER_RESOURCE, 4, Integer.BYTES * 2));
            return created;
        } catch (Throwable throwable) {
            try {
                VK10.vkDestroyInstance(instance, null);
            } catch (Throwable ignored) {
            }
            throw throwable;
        }
    }

    private static PhysicalSelection pickPhysicalDevice(VkInstance instance) {
        List<PhysicalSelection> selections = enumeratePhysicalDevices(instance);
        if (selections.isEmpty()) {
            throw new IllegalStateException("No Vulkan device with a compute queue found");
        }

        String preferred = PREFERRED_DEVICE.get();
        if (preferred != null && !preferred.isBlank()) {
            for (PhysicalSelection selection : selections) {
                if (matchesDevicePreference(selection, preferred)) {
                    return selection;
                }
            }
            throw new IllegalStateException("Preferred Vulkan device not found: " + preferred);
        }

        return autoSelectCandidates(selections).stream()
            .max((left, right) -> Double.compare(left.score(), right.score()))
            .orElseThrow(() -> new IllegalStateException("No Vulkan device with a compute queue found"));
    }

    private static List<PhysicalSelection> enumeratePhysicalDevices(VkInstance instance) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer deviceCount = stack.ints(0);
            checkVk(VK10.vkEnumeratePhysicalDevices(instance, deviceCount, null), "vkEnumeratePhysicalDevices");
            if (deviceCount.get(0) <= 0) {
                return List.of();
            }

            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            checkVk(VK10.vkEnumeratePhysicalDevices(instance, deviceCount, devices), "vkEnumeratePhysicalDevices");

            List<PhysicalSelection> selections = new ArrayList<>(devices.capacity());
            for (int i = 0; i < devices.capacity(); i++) {
                long physicalDeviceHandle = devices.get(i);
                VkPhysicalDevice physicalDevice = new VkPhysicalDevice(physicalDeviceHandle, instance);
                int computeQueueFamily = findComputeQueueFamily(physicalDevice, stack);
                if (computeQueueFamily < 0) {
                    continue;
                }

                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);
                String rawDeviceName = properties.deviceNameString();
                int deviceType = properties.deviceType();
                int vendorId = properties.vendorID();
                String vendorName = vendorName(vendorId, rawDeviceName);
                long localMemoryBytes = queryDeviceLocalMemory(physicalDevice, stack);
                int maxComputeInvocations = properties.limits().maxComputeWorkGroupInvocations();
                int maxComputeSharedMemoryBytes = properties.limits().maxComputeSharedMemorySize();
                boolean softwareAdapter = isSoftwareAdapter(rawDeviceName, vendorName, deviceType);
                String deviceName = sanitizeDeviceName(rawDeviceName);
                double score = scoreDevice(deviceType, vendorName, localMemoryBytes, maxComputeInvocations, maxComputeSharedMemoryBytes, softwareAdapter);
                String deviceId = buildDeviceId(vendorName, deviceName);
                selections.add(new PhysicalSelection(
                    physicalDeviceHandle,
                    computeQueueFamily,
                    deviceName,
                    vendorName,
                    deviceId,
                    deviceType,
                    localMemoryBytes,
                    softwareAdapter,
                    score
                ));
            }
            return dedupeSelections(selections);
        }
    }

    private static int findComputeQueueFamily(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        if (count.get(0) <= 0) {
            return -1;
        }
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) != 0) {
                return i;
            }
        }
        return -1;
    }

    private static long queryDeviceLocalMemory(VkPhysicalDevice physicalDevice, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
        long bytes = 0L;
        for (int i = 0; i < properties.memoryHeapCount(); i++) {
            if ((properties.memoryHeaps(i).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                bytes += properties.memoryHeaps(i).size();
            }
        }
        return bytes;
    }

    private static FloatControlSummary queryFloatControls(VkPhysicalDevice physicalDevice,
                                                          int apiVersion,
                                                          MemoryStack stack) {
        if (apiVersion < VK12.VK_API_VERSION_1_2) {
            return FloatControlSummary.unavailable("Vulkan API < 1.2");
        }

        VkPhysicalDeviceFloatControlsProperties floatControls = VkPhysicalDeviceFloatControlsProperties.calloc(stack)
            .sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FLOAT_CONTROLS_PROPERTIES);
        VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack)
            .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
            .pNext(floatControls.address());
        VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties2);

        return new FloatControlSummary(
            true,
            floatControls.shaderRoundingModeRTEFloat32(),
            floatControls.shaderSignedZeroInfNanPreserveFloat32(),
            floatControls.shaderDenormPreserveFloat32(),
            floatControls.shaderDenormFlushToZeroFloat32(),
            floatControls.denormBehaviorIndependence(),
            floatControls.roundingModeIndependence(),
            null
        );
    }

    private static VkInstance createInstance(int apiVersion) {
        return VulkanLoaderIsolation.runWithImplicitLayersDisabled(
            LOGGER,
            "Vulkan instance creation",
            () -> {
                VkApplicationInfo appInfo = null;
                VkInstanceCreateInfo instanceInfo = null;
                ByteBuffer applicationName = null;
                ByteBuffer engineName = null;
                PointerBuffer instancePtr = null;
                try {
                    applicationName = MemoryUtil.memUTF8("Quantified");
                    engineName = MemoryUtil.memUTF8("QuantifiedVulkan");
                    appInfo = VkApplicationInfo.calloc()
                        .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                        .pApplicationName(applicationName)
                        .applicationVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                        .pEngineName(engineName)
                        .engineVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                        .apiVersion(apiVersion);

                    instanceInfo = VkInstanceCreateInfo.calloc()
                        .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                        .pApplicationInfo(appInfo);

                    instancePtr = MemoryUtil.memAllocPointer(1);
                    checkVk(VK10.vkCreateInstance(instanceInfo, null, instancePtr), "vkCreateInstance");
                    return QuantifiedVkBootstrap.wrapInstance(instancePtr.get(0), instanceInfo);
                } finally {
                    if (instancePtr != null) {
                        MemoryUtil.memFree(instancePtr);
                    }
                    if (instanceInfo != null) {
                        instanceInfo.free();
                    }
                    if (appInfo != null) {
                        appInfo.free();
                    }
                    if (engineName != null) {
                        MemoryUtil.memFree(engineName);
                    }
                    if (applicationName != null) {
                        MemoryUtil.memFree(applicationName);
                    }
                }
            });
    }

    private static boolean isInitRetryCoolingDown() {
        long blockedUntil = nextInitRetryMs;
        if (blockedUntil <= 0L) {
            return false;
        }
        if (System.currentTimeMillis() >= blockedUntil) {
            nextInitRetryMs = 0L;
            return false;
        }
        return true;
    }

    private static int preferredApiVersion(VulkanRuntime.AvailabilitySnapshot runtime) {
        if (runtime.selectedApiVersion() != 0) {
            return runtime.selectedApiVersion();
        }
        if (compareApiVersion(runtime.maxApiVersion(), 1, 3, 0) >= 0) {
            return VK10.VK_MAKE_VERSION(1, 3, 0);
        }
        if (compareApiVersion(runtime.maxApiVersion(), 1, 2, 0) >= 0) {
            return VK10.VK_MAKE_VERSION(1, 2, 0);
        }
        return 0;
    }

    private static int compareApiVersion(int apiVersion, int major, int minor, int patch) {
        return Integer.compareUnsigned(apiVersion, VK10.VK_MAKE_VERSION(major, minor, patch));
    }

    private static String normalizeDevicePreference(String preferredDevice) {
        if (preferredDevice == null) {
            return null;
        }
        String trimmed = preferredDevice.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean matchesDevicePreference(PhysicalSelection selection, String preference) {
        String normalizedPreference = normalizeDeviceKey(preference);
        return normalizeDeviceKey(selection.deviceName()).equals(normalizedPreference)
            || normalizeDeviceKey(selection.vendorName() + " " + selection.deviceName()).equals(normalizedPreference)
            || normalizeDeviceKey(selection.id()).equals(normalizedPreference)
            || normalizeDeviceKey(selection.deviceName()).contains(normalizedPreference)
            || normalizeDeviceKey(selection.vendorName() + " " + selection.deviceName()).contains(normalizedPreference);
    }

    private static String normalizeDeviceKey(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String buildDeviceId(String vendorName, String deviceName) {
        String combined = ((vendorName != null ? vendorName : "") + "-" + (deviceName != null ? deviceName : "")).trim();
        String normalized = normalizeDeviceKey(combined);
        return normalized.isBlank() ? "unknown-vulkan-device" : normalized;
    }

    private static String sanitizeDeviceName(String deviceName) {
        if (deviceName == null) {
            return "Unknown Vulkan Device";
        }
        String sanitized = deviceName
            .replaceAll("(?i)\\bDirect3D12\\b", "")
            .replaceAll("(?i)\\bD3D12\\b", "")
            .replaceAll("(?i)\\bDX12\\b", "")
            .replaceAll("\\s+", " ")
            .trim();
        return sanitized.isEmpty() ? deviceName.trim() : sanitized;
    }

    private static List<PhysicalSelection> autoSelectCandidates(List<PhysicalSelection> selections) {
        List<PhysicalSelection> discrete = selections.stream()
            .filter(selection -> !selection.softwareAdapter())
            .filter(selection -> selection.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)
            .toList();
        return discrete.isEmpty() ? selections : discrete;
    }

    private static List<PhysicalSelection> dedupeSelections(List<PhysicalSelection> selections) {
        java.util.LinkedHashMap<String, PhysicalSelection> unique = new java.util.LinkedHashMap<>();
        for (PhysicalSelection selection : selections) {
            String key = normalizeDeviceKey(selection.deviceName());
            PhysicalSelection existing = unique.get(key);
            if (existing == null || preferSelection(selection, existing)) {
                unique.put(key, selection);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static List<VulkanDeviceInfo> dedupeDeviceInfos(List<VulkanDeviceInfo> devices) {
        java.util.LinkedHashMap<String, VulkanDeviceInfo> unique = new java.util.LinkedHashMap<>();
        for (VulkanDeviceInfo device : devices) {
            String key = normalizeDeviceKey(device.name());
            VulkanDeviceInfo existing = unique.get(key);
            if (existing == null || preferDeviceInfo(device, existing)) {
                unique.put(key, device);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static boolean preferSelection(PhysicalSelection selection, PhysicalSelection existing) {
        if (selection.softwareAdapter() != existing.softwareAdapter()) {
            return !selection.softwareAdapter();
        }
        if (selection.deviceType() != existing.deviceType()) {
            return deviceTypeRank(selection.deviceType()) > deviceTypeRank(existing.deviceType());
        }
        if (selection.localMemoryBytes() != existing.localMemoryBytes()) {
            return selection.localMemoryBytes() > existing.localMemoryBytes();
        }
        return selection.score() > existing.score();
    }

    private static boolean preferDeviceInfo(VulkanDeviceInfo device, VulkanDeviceInfo existing) {
        if (device.softwareAdapter() != existing.softwareAdapter()) {
            return !device.softwareAdapter();
        }
        if (device.deviceType() != existing.deviceType()) {
            return deviceTypeRank(device.deviceType()) > deviceTypeRank(existing.deviceType());
        }
        if (device.localMemoryBytes() != existing.localMemoryBytes()) {
            return device.localMemoryBytes() > existing.localMemoryBytes();
        }
        return device.id().compareTo(existing.id()) > 0;
    }

    private static int deviceTypeRank(int deviceType) {
        return switch (deviceType) {
            case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 4;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 3;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 2;
            default -> 1;
        };
    }

    private static String vendorName(int vendorId, String deviceName) {
        return switch (vendorId) {
            case 0x10DE -> "NVIDIA";
            case 0x1002, 0x1022 -> "AMD";
            case 0x8086 -> "Intel";
            case 0x1414 -> "Microsoft";
            default -> inferVendorFromName(deviceName);
        };
    }

    private static String inferVendorFromName(String deviceName) {
        String lower = deviceName != null ? deviceName.toLowerCase(java.util.Locale.ROOT) : "";
        if (lower.contains("nvidia") || lower.contains("geforce") || lower.contains("quadro")) {
            return "NVIDIA";
        }
        if (lower.contains("radeon") || lower.contains("amd")) {
            return "AMD";
        }
        if (lower.contains("intel") || lower.contains("iris")) {
            return "Intel";
        }
        if (lower.contains("microsoft")) {
            return "Microsoft";
        }
        return "Unknown";
    }

    private static boolean isSoftwareAdapter(String deviceName, String vendorName, int deviceType) {
        String lowerName = deviceName != null ? deviceName.toLowerCase(java.util.Locale.ROOT) : "";
        String lowerVendor = vendorName != null ? vendorName.toLowerCase(java.util.Locale.ROOT) : "";
        return deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_CPU
            || lowerVendor.contains("microsoft")
            || lowerName.contains("microsoft basic render")
            || lowerName.contains("direct3d12")
            || lowerName.contains("swiftshader")
            || lowerName.contains("llvmpipe")
            || lowerName.contains("lavapipe")
            || lowerName.contains("dozen")
            || lowerName.contains("d3d12");
    }

    private static double scoreDevice(int deviceType,
                                      String vendorName,
                                      long localMemoryBytes,
                                      int maxComputeInvocations,
                                      int maxComputeSharedMemoryBytes,
                                      boolean softwareAdapter) {
        if (softwareAdapter) {
            return -1_000_000_000_000_000d + localMemoryBytes;
        }
        double typeScore = switch (deviceType) {
            case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 1_000_000_000_000_000d;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 100_000_000_000_000d;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 10_000_000_000_000d;
            default -> 1_000_000_000_000d;
        };
        double vendorScore = isDedicatedGpuVendor(vendorName) ? 10_000_000_000_000d : 0d;
        double computeScore = (double) Math.max(0, maxComputeInvocations) * 1_000_000_000d
            + (double) Math.max(0, maxComputeSharedMemoryBytes) * 1_000_000d;
        return typeScore + vendorScore + computeScore + localMemoryBytes;
    }

    private static boolean isDedicatedGpuVendor(String vendorName) {
        String vendor = vendorName != null ? vendorName.toLowerCase(java.util.Locale.ROOT) : "";
        return vendor.contains("nvidia") || vendor.contains("amd");
    }

    private static String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "Unknown Vulkan failure";
        }
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            if (message.toLowerCase().contains("out of stack space")) {
                return message + " | Increase quantified.gpuThreadStackKb if a third-party driver still needs more stack";
            }
            return message;
        }
        return throwable.getClass().getSimpleName();
    }

    private static Program createProgram(State state, String name, String resourcePath, int storageBufferCount, int pushConstantBytes) {
        ByteBuffer spirv = loadShaderBinary(name, resourcePath);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer shaderModulePtr = stack.mallocLong(1);
            VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spirv);
            checkVk(VK10.vkCreateShaderModule(state.device, shaderInfo, null, shaderModulePtr), "vkCreateShaderModule");
            long shaderModule = shaderModulePtr.get(0);
            try {
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(storageBufferCount, stack);
                for (int i = 0; i < storageBufferCount; i++) {
                    bindings.get(i)
                        .binding(i)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                }

                LongBuffer descriptorLayoutPtr = stack.mallocLong(1);
                VkDescriptorSetLayoutCreateInfo descriptorLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
                checkVk(VK10.vkCreateDescriptorSetLayout(state.device, descriptorLayoutInfo, null, descriptorLayoutPtr),
                    "vkCreateDescriptorSetLayout");

                long descriptorSetLayout = descriptorLayoutPtr.get(0);
                try {
                    LongBuffer pipelineLayoutPtr = stack.mallocLong(1);
                    VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                        .pSetLayouts(stack.longs(descriptorSetLayout));
                    if (pushConstantBytes > 0) {
                        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                            .offset(0)
                            .size(pushConstantBytes);
                        pipelineLayoutInfo.pPushConstantRanges(pushRange);
                    }
                    checkVk(VK10.vkCreatePipelineLayout(state.device, pipelineLayoutInfo, null, pipelineLayoutPtr),
                        "vkCreatePipelineLayout");

                    long pipelineLayout = pipelineLayoutPtr.get(0);
                    try {
                        org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo stageInfo =
                            org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo.calloc(stack)
                                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                                .module(shaderModule)
                                .pName(stack.UTF8("main"));

                        VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                            .stage(stageInfo)
                            .layout(pipelineLayout);

                        LongBuffer pipelinePtr = stack.mallocLong(1);
                        checkVk(VK10.vkCreateComputePipelines(state.device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pipelinePtr),
                            "vkCreateComputePipelines");
                        return new Program(name, pipelinePtr.get(0), pipelineLayout, descriptorSetLayout, storageBufferCount, pushConstantBytes);
                    } catch (Throwable throwable) {
                        VK10.vkDestroyPipelineLayout(state.device, pipelineLayout, null);
                        throw throwable;
                    }
                } catch (Throwable throwable) {
                    VK10.vkDestroyDescriptorSetLayout(state.device, descriptorSetLayout, null);
                    throw throwable;
                }
            } finally {
                VK10.vkDestroyShaderModule(state.device, shaderModule, null);
            }
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private static WorkspaceLease acquireWorkspace(State state,
                                                   Program program,
                                                   String workspaceKey,
                                                   WorkloadProfile workloadProfile,
                                                   int groupCountX,
                                                   int groupCountY,
                                                   int groupCountZ,
                                                   int inputBufferCount,
                                                   int[] pushConstants,
                                                   long... bufferSizes) {
        DispatchWorkspacePool pool = state.workspaces.computeIfAbsent(workspaceKey,
            ignored -> createWorkspacePool(state, program, workloadProfile, groupCountX, groupCountY, groupCountZ,
                inputBufferCount, pushConstants, bufferSizes));
        return new WorkspaceLease(pool, pool.borrow());
    }

    private static WorkspaceLease prepareMcDensityDispatch(State state,
                                                           Program program,
                                                           float[] packedCoords,
                                                           int sampleCount,
                                                           float[] programSlice,
                                                           int instructionCount,
                                                           float[] auxValues,
                                                           int auxValueCount) {
        int sampleCapacity = alignUp(sampleCount, MC_DENSITY_LOCAL_SIZE_X);
        int programHash = Arrays.hashCode(programSlice);
        WorkspaceLease lease = acquireWorkspace(state, program,
            "mc_density_functions:" + sampleCapacity + ":" + instructionCount + ":" + auxValueCount + ":"
                + Integer.toHexString(programHash),
            WorkloadProfile.COMPUTE_DENSE,
            groupCount(sampleCapacity, MC_DENSITY_LOCAL_SIZE_X), 1, 1,
            3,
            new int[]{sampleCapacity, instructionCount},
            (long) sampleCapacity * 3L * Float.BYTES,
            (long) programSlice.length * Float.BYTES,
            Math.max(1L, (long) auxValueCount * sampleCapacity) * Float.BYTES,
            (long) sampleCapacity * Float.BYTES);
        boolean success = false;
        try {
            DispatchWorkspace workspace = lease.workspace();
            writeFloatArrayPadded(workspace.buffers[0], packedCoords, sampleCapacity * 3);
            writeFloatArray(workspace.buffers[1], programSlice);
            writeMcDensityAuxValues(workspace.buffers[2], auxValues, auxValueCount, sampleCount, sampleCapacity);
            success = true;
            return lease;
        } finally {
            if (!success) {
                lease.close();
            }
        }
    }

    private static void writeMcDensityAuxValues(AllocatedBuffer buffer,
                                                float[] values,
                                                int auxValueCount,
                                                int sampleCount,
                                                int sampleCapacity) {
        int paddedLength = Math.max(1, auxValueCount * sampleCapacity);
        FloatBuffer mapped = MemoryUtil.memFloatBuffer(buffer.mappedPointer, paddedLength);
        mapped.clear();
        if (auxValueCount <= 0) {
            mapped.put(0.0f);
            mapped.flip();
            return;
        }
        for (int auxIndex = 0; auxIndex < auxValueCount; auxIndex++) {
            int srcOffset = auxIndex * sampleCount;
            mapped.put(values, srcOffset, sampleCount);
            for (int i = sampleCount; i < sampleCapacity; i++) {
                mapped.put(0.0f);
            }
        }
        mapped.flip();
    }

    private static DispatchWorkspacePool createWorkspacePool(State state,
                                                             Program program,
                                                             WorkloadProfile workloadProfile,
                                                             int groupCountX,
                                                             int groupCountY,
                                                             int groupCountZ,
                                                             int inputBufferCount,
                                                             int[] pushConstants,
                                                             long... bufferSizes) {
        DispatchWorkspace[] workspaces = new DispatchWorkspace[MAX_IN_FLIGHT_WORKSPACES];
        try {
            for (int i = 0; i < workspaces.length; i++) {
                workspaces[i] = createWorkspace(state, program, workloadProfile, groupCountX, groupCountY, groupCountZ,
                    inputBufferCount, pushConstants, bufferSizes);
            }
            return new DispatchWorkspacePool(workspaces);
        } catch (Throwable throwable) {
            for (DispatchWorkspace workspace : workspaces) {
                destroyWorkspace(state, workspace);
            }
            throw throwable;
        }
    }

    private static DispatchWorkspace createWorkspace(State state,
                                                     Program program,
                                                     WorkloadProfile workloadProfile,
                                                     int groupCountX,
                                                     int groupCountY,
                                                     int groupCountZ,
                                                     int inputBufferCount,
                                                     int[] pushConstants,
                                                     long... bufferSizes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AllocatedBuffer[] buffers = new AllocatedBuffer[bufferSizes.length];
            try {
                for (int i = 0; i < bufferSizes.length; i++) {
                    BufferRole role = i < inputBufferCount ? BufferRole.INPUT : BufferRole.OUTPUT;
                    buffers[i] = createStorageBuffer(state, workloadProfile, role, bufferSizes[i]);
                }

                LongBuffer descriptorPoolPtr = stack.mallocLong(1);
                VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(program.storageBufferCount);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSize)
                    .maxSets(1);
                checkVk(VK10.vkCreateDescriptorPool(state.device, poolInfo, null, descriptorPoolPtr), "vkCreateDescriptorPool");
                long descriptorPool = descriptorPoolPtr.get(0);

                try {
                    LongBuffer descriptorSetPtr = stack.mallocLong(1);
                    VkDescriptorSetAllocateInfo descriptorAlloc = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                        .descriptorPool(descriptorPool)
                        .pSetLayouts(stack.longs(program.descriptorSetLayout));
                    checkVk(VK10.vkAllocateDescriptorSets(state.device, descriptorAlloc, descriptorSetPtr), "vkAllocateDescriptorSets");
                    long descriptorSet = descriptorSetPtr.get(0);

                    VkDescriptorBufferInfo.Buffer bufferInfos = VkDescriptorBufferInfo.calloc(buffers.length, stack);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(buffers.length, stack);
                    for (int i = 0; i < buffers.length; i++) {
                        bufferInfos.get(i)
                            .buffer(buffers[i].descriptorBuffer())
                            .offset(0)
                            .range(buffers[i].sizeBytes);
                        writes.get(i)
                            .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                            .dstSet(descriptorSet)
                            .dstBinding(i)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1)
                            .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).put(0, bufferInfos.get(i)));
                    }
                    VK10.vkUpdateDescriptorSets(state.device, writes, null);

                    PointerBuffer commandBufferPtr = stack.mallocPointer(1);
                    VkCommandBufferAllocateInfo commandAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(state.commandPool)
                        .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1);
                    checkVk(VK10.vkAllocateCommandBuffers(state.device, commandAlloc, commandBufferPtr), "vkAllocateCommandBuffers");
                    VkCommandBuffer commandBuffer = new VkCommandBuffer(commandBufferPtr.get(0), state.device);

                    LongBuffer fencePtr = stack.mallocLong(1);
                    VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
                    checkVk(VK10.vkCreateFence(state.device, fenceInfo, null, fencePtr), "vkCreateFence");
                    long fence = fencePtr.get(0);

                    DispatchWorkspace workspace = new DispatchWorkspace(program, descriptorPool, descriptorSet, commandBuffer, fence,
                        groupCountX, groupCountY, groupCountZ, inputBufferCount, buffers);
                    recordWorkspaceCommandBuffer(workspace, pushConstants);
                    return workspace;
                } catch (Throwable throwable) {
                    VK10.vkDestroyDescriptorPool(state.device, descriptorPool, null);
                    throw throwable;
                }
            } catch (Throwable throwable) {
                for (AllocatedBuffer buffer : buffers) {
                    destroyBuffer(state, buffer);
                }
                throw throwable;
            }
        }
    }

    private static void recordWorkspaceCommandBuffer(DispatchWorkspace workspace, int[] pushConstants) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            checkVk(VK10.vkBeginCommandBuffer(workspace.commandBuffer, beginInfo), "vkBeginCommandBuffer");

            boolean hasStagedInputs = false;
            for (int i = 0; i < workspace.inputBufferCount; i++) {
                if (workspace.buffers[i].staged) {
                    hasStagedInputs = true;
                    VK10.vkCmdCopyBuffer(workspace.commandBuffer,
                        workspace.buffers[i].hostBuffer,
                        workspace.buffers[i].deviceBuffer,
                        VkBufferCopy.calloc(1, stack)
                            .srcOffset(0)
                            .dstOffset(0)
                            .size(workspace.buffers[i].sizeBytes));
                }
            }
            if (hasStagedInputs) {
                VkMemoryBarrier.Buffer uploadBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
                VK10.vkCmdPipelineBarrier(workspace.commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    uploadBarrier,
                    null,
                    null);
            }

            VK10.vkCmdBindPipeline(workspace.commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, workspace.program.pipeline);
            VK10.vkCmdBindDescriptorSets(workspace.commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                workspace.program.pipelineLayout, 0, stack.longs(workspace.descriptorSet), null);
            if (pushConstants != null && pushConstants.length > 0) {
                ByteBuffer pushBuffer = intsToBytes(pushConstants);
                try {
                    VK10.vkCmdPushConstants(workspace.commandBuffer, workspace.program.pipelineLayout,
                        VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBuffer);
                } finally {
                    MemoryUtil.memFree(pushBuffer);
                }
            }
            VK10.vkCmdDispatch(workspace.commandBuffer,
                Math.max(1, workspace.groupCountX),
                Math.max(1, workspace.groupCountY),
                Math.max(1, workspace.groupCountZ));

            boolean hasStagedOutputs = false;
            for (int i = workspace.inputBufferCount; i < workspace.buffers.length; i++) {
                if (workspace.buffers[i].staged) {
                    hasStagedOutputs = true;
                    break;
                }
            }

            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(hasStagedOutputs
                    ? (VK10.VK_ACCESS_TRANSFER_READ_BIT | VK10.VK_ACCESS_HOST_READ_BIT | VK10.VK_ACCESS_HOST_WRITE_BIT)
                    : (VK10.VK_ACCESS_HOST_READ_BIT | VK10.VK_ACCESS_HOST_WRITE_BIT));
            VK10.vkCmdPipelineBarrier(workspace.commandBuffer,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                hasStagedOutputs ? (VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_HOST_BIT) : VK10.VK_PIPELINE_STAGE_HOST_BIT,
                0,
                barrier,
                null,
                null);

            if (hasStagedOutputs) {
                for (int i = workspace.inputBufferCount; i < workspace.buffers.length; i++) {
                    if (!workspace.buffers[i].staged) {
                        continue;
                    }
                    VK10.vkCmdCopyBuffer(workspace.commandBuffer,
                        workspace.buffers[i].deviceBuffer,
                        workspace.buffers[i].hostBuffer,
                        VkBufferCopy.calloc(1, stack)
                            .srcOffset(0)
                            .dstOffset(0)
                            .size(workspace.buffers[i].sizeBytes));
                }
                VkMemoryBarrier.Buffer readbackBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
                VK10.vkCmdPipelineBarrier(workspace.commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0,
                    readbackBarrier,
                    null,
                    null);
            }
            checkVk(VK10.vkEndCommandBuffer(workspace.commandBuffer), "vkEndCommandBuffer");
        }
    }

    private static AllocatedBuffer createStorageBuffer(State state,
                                                       WorkloadProfile workloadProfile,
                                                       BufferRole role,
                                                       long sizeBytes) {
        if (!state.prefersDeviceLocalTransfers) {
            return createHostVisibleStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
        }

        boolean useDeviceLocal = sizeBytes >= DEVICE_LOCAL_TRANSFER_THRESHOLD_BYTES;
        return switch (workloadProfile) {
            case BANDWIDTH -> {
                if (useDeviceLocal) {
                    yield createStagedStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
                }
                yield createHostVisibleStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
            }
            case COMPUTE_DENSE -> {
                if (useDeviceLocal) {
                    yield createStagedStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
                }
                yield createHostVisibleStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
            }
            case REDUCTION -> {
                if (role == BufferRole.INPUT && useDeviceLocal) {
                    yield createStagedStorageBuffer(state, sizeBytes, false);
                }
                yield createHostVisibleStorageBuffer(state, sizeBytes, true);
            }
        };
    }

    private static AllocatedBuffer createStagedStorageBuffer(State state, long sizeBytes, boolean hostCached) {
        RawBuffer hostBuffer = null;
        RawBuffer deviceBuffer = null;
        try {
            hostBuffer = createRawBuffer(state,
                sizeBytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                hostCached ? VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT : 0,
                true);
            deviceBuffer = createRawBuffer(state,
                sizeBytes,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                0,
                false);
            return new AllocatedBuffer(deviceBuffer.buffer, deviceBuffer.memory, hostBuffer.buffer, hostBuffer.memory,
                sizeBytes, hostBuffer.mappedPointer, true);
        } catch (Throwable throwable) {
            destroyRawBuffer(state, hostBuffer);
            destroyRawBuffer(state, deviceBuffer);
            throw throwable;
        }
    }

    private static AllocatedBuffer createHostVisibleStorageBuffer(State state, long sizeBytes, boolean preferHostCached) {
        RawBuffer buffer = createRawBuffer(state,
            sizeBytes,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            preferHostCached ? VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT : 0,
            true);
        return new AllocatedBuffer(buffer.buffer, buffer.memory, buffer.buffer, buffer.memory,
            sizeBytes, buffer.mappedPointer, false);
    }

    private static RawBuffer createRawBuffer(State state,
                                             long sizeBytes,
                                             int usageFlags,
                                             int requiredFlags,
                                             int preferredFlags,
                                             boolean mapMemory) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(sizeBytes)
                .usage(usageFlags)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer bufferPtr = stack.mallocLong(1);
            checkVk(VK10.vkCreateBuffer(state.device, bufferInfo, null, bufferPtr), "vkCreateBuffer");
            long buffer = bufferPtr.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(state.device, buffer, requirements);

            int memoryType = findMemoryType(state.physicalDevice, requirements.memoryTypeBits(), requiredFlags, preferredFlags, stack);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryType);

            LongBuffer memoryPtr = stack.mallocLong(1);
            try {
                checkVk(VK10.vkAllocateMemory(state.device, allocInfo, null, memoryPtr), "vkAllocateMemory");
            } catch (Throwable throwable) {
                VK10.vkDestroyBuffer(state.device, buffer, null);
                throw throwable;
            }

            long memory = memoryPtr.get(0);
            try {
                checkVk(VK10.vkBindBufferMemory(state.device, buffer, memory, 0), "vkBindBufferMemory");
                long mappedPointer = MemoryUtil.NULL;
                if (mapMemory) {
                    PointerBuffer mappedPtr = stack.mallocPointer(1);
                    checkVk(VK10.vkMapMemory(state.device, memory, 0, sizeBytes, 0, mappedPtr), "vkMapMemory");
                    mappedPointer = mappedPtr.get(0);
                }
                return new RawBuffer(buffer, memory, sizeBytes, mappedPointer);
            } catch (Throwable throwable) {
                VK10.vkFreeMemory(state.device, memory, null);
                VK10.vkDestroyBuffer(state.device, buffer, null);
                throw throwable;
            }
        }
    }

    private static void destroyBuffer(State state, AllocatedBuffer buffer) {
        if (state == null || buffer == null) {
            return;
        }
        destroyRawBuffer(state, new RawBuffer(buffer.hostBuffer, buffer.hostMemory, buffer.sizeBytes, buffer.mappedPointer));
        if (buffer.staged && (buffer.deviceBuffer != buffer.hostBuffer || buffer.deviceMemory != buffer.hostMemory)) {
            destroyRawBuffer(state, new RawBuffer(buffer.deviceBuffer, buffer.deviceMemory, buffer.sizeBytes, MemoryUtil.NULL));
        }
    }

    private static void destroyRawBuffer(State state, RawBuffer buffer) {
        if (state == null || buffer == null) {
            return;
        }
        try {
            if (buffer.mappedPointer != MemoryUtil.NULL) {
                VK10.vkUnmapMemory(state.device, buffer.memory);
            }
        } catch (Throwable ignored) {
        }
        try {
            VK10.vkDestroyBuffer(state.device, buffer.buffer, null);
        } catch (Throwable ignored) {
        }
        try {
            VK10.vkFreeMemory(state.device, buffer.memory, null);
        } catch (Throwable ignored) {
        }
    }

    private static int findMemoryType(VkPhysicalDevice physicalDevice,
                                      int typeBits,
                                      int requiredFlags,
                                      int preferredFlags,
                                      MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
        if (preferredFlags != 0) {
            int preferredMask = requiredFlags | preferredFlags;
            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                boolean supported = (typeBits & (1 << i)) != 0;
                if (!supported) {
                    continue;
                }
                if ((properties.memoryTypes(i).propertyFlags() & preferredMask) == preferredMask) {
                    return i;
                }
            }
        }
        for (int i = 0; i < properties.memoryTypeCount(); i++) {
            boolean supported = (typeBits & (1 << i)) != 0;
            if (!supported) {
                continue;
            }
            if ((properties.memoryTypes(i).propertyFlags() & requiredFlags) == requiredFlags) {
                return i;
            }
        }
        throw new IllegalStateException("Unable to find matching Vulkan memory type");
    }

    private static void writeFloatArray(AllocatedBuffer buffer, float[] values) {
        FloatBuffer mapped = MemoryUtil.memFloatBuffer(buffer.mappedPointer, values.length);
        mapped.clear();
        mapped.put(values);
        mapped.flip();
    }

    private static void writeFloatArrayPadded(AllocatedBuffer buffer, float[] values, int paddedLength) {
        if (values.length > paddedLength) {
            throw new IllegalArgumentException("Input exceeds padded buffer length: " + values.length + " > " + paddedLength);
        }
        FloatBuffer mapped = MemoryUtil.memFloatBuffer(buffer.mappedPointer, paddedLength);
        mapped.clear();
        mapped.put(values);
        for (int i = values.length; i < paddedLength; i++) {
            mapped.put(0.0f);
        }
        mapped.flip();
    }

    private static void writeInt(AllocatedBuffer buffer, int value) {
        java.nio.IntBuffer mapped = MemoryUtil.memIntBuffer(buffer.mappedPointer, 1);
        mapped.put(0, value);
    }

    private static float[] readFloatArray(AllocatedBuffer buffer, int length) {
        float[] out = new float[length];
        FloatBuffer mapped = MemoryUtil.memFloatBuffer(buffer.mappedPointer, length);
        mapped.rewind();
        mapped.get(out);
        return out;
    }

    private static int readInt(AllocatedBuffer buffer) {
        java.nio.IntBuffer mapped = MemoryUtil.memIntBuffer(buffer.mappedPointer, 1);
        return mapped.get(0);
    }

    private static void dispatch(State state,
                                 DispatchWorkspace workspace) {
        dispatch(state, List.of(workspace));
    }

    private static void dispatch(State state,
                                 List<DispatchWorkspace> workspaces) {
        if (workspaces == null || workspaces.isEmpty()) {
            return;
        }
        DispatchWorkspace fenceOwner = workspaces.get(0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            checkVk(VK10.vkResetFences(state.device, stack.longs(fenceOwner.fence)), "vkResetFences");
            PointerBuffer commandBufferPtr = stack.mallocPointer(workspaces.size());
            for (int i = 0; i < workspaces.size(); i++) {
                commandBufferPtr.put(i, workspaces.get(i).commandBuffer.address());
            }
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(commandBufferPtr);
            synchronized (state.computeQueueLock) {
                checkVk(VK10.vkQueueSubmit(state.computeQueue, submitInfo, fenceOwner.fence), "vkQueueSubmit");
            }
            checkVk(VK10.vkWaitForFences(state.device, stack.longs(fenceOwner.fence), true, EXECUTION_TIMEOUT_NANOS), "vkWaitForFences");
        }
    }

    private static ByteBuffer loadShaderBinary(String name, String resourcePath) {
        try (InputStream input = VulkanManager.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing precompiled SPIR-V resource for " + name + ": " + resourcePath);
            }
            byte[] bytes = input.readAllBytes();
            if (bytes.length == 0) {
                throw new IllegalStateException("Empty precompiled SPIR-V resource for " + name + ": " + resourcePath);
            }
            ByteBuffer copy = MemoryUtil.memAlloc(bytes.length);
            copy.put(bytes);
            copy.flip();
            return copy;
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading precompiled SPIR-V resource for " + name + ": " + resourcePath, e);
        }
    }

    private static ByteBuffer intsToBytes(int... values) {
        ByteBuffer buffer = MemoryUtil.memAlloc(values.length * Integer.BYTES);
        for (int value : values) {
            buffer.putInt(value);
        }
        buffer.flip();
        return buffer;
    }

    private static void cleanupState(State state) {
        if (state == null) {
            return;
        }
        for (DispatchWorkspacePool workspacePool : state.workspaces.values()) {
            for (DispatchWorkspace workspace : workspacePool.workspaces()) {
                destroyWorkspace(state, workspace);
            }
        }
        state.workspaces.clear();
        for (Program program : state.programs.values()) {
            try {
                VK10.vkDestroyPipeline(state.device, program.pipeline, null);
            } catch (Throwable ignored) {
            }
            try {
                VK10.vkDestroyPipelineLayout(state.device, program.pipelineLayout, null);
            } catch (Throwable ignored) {
            }
            try {
                VK10.vkDestroyDescriptorSetLayout(state.device, program.descriptorSetLayout, null);
            } catch (Throwable ignored) {
            }
        }
        state.programs.clear();
        try {
            VK10.vkDestroyCommandPool(state.device, state.commandPool, null);
        } catch (Throwable ignored) {
        }
        try {
            VK10.vkDestroyDevice(state.device, null);
        } catch (Throwable ignored) {
        }
        try {
            VK10.vkDestroyInstance(state.instance, null);
        } catch (Throwable ignored) {
        }
    }

    private static void destroyWorkspace(State state, DispatchWorkspace workspace) {
        if (state == null || workspace == null) {
            return;
        }
        try {
            VK10.vkDestroyFence(state.device, workspace.fence, null);
        } catch (Throwable ignored) {
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkFreeCommandBuffers(state.device, state.commandPool, stack.pointers(workspace.commandBuffer.address()));
        } catch (Throwable ignored) {
        }
        try {
            VK10.vkDestroyDescriptorPool(state.device, workspace.descriptorPool, null);
        } catch (Throwable ignored) {
        }
        for (AllocatedBuffer buffer : workspace.buffers) {
            destroyBuffer(state, buffer);
        }
    }

    private static float[] flattenMatrix(float[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        float[] out = new float[rows * cols];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(matrix[row], 0, out, row * cols, cols);
        }
        return out;
    }

    private static float[][] reconstructMatrix(float[] values, int rows, int cols) {
        float[][] out = new float[rows][cols];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(values, row * cols, out[row], 0, cols);
        }
        return out;
    }

    private static int groupCount(int valueCount, int localSize) {
        return Math.max(1, (valueCount + localSize - 1) / localSize);
    }

    private static int alignUp(int value, int alignment) {
        if (value <= 0) {
            return 0;
        }
        return ((value + alignment - 1) / alignment) * alignment;
    }

    private static void checkVk(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }

    public static final class RuntimeStatus {
        private final boolean available;
        private final String failureReason;

        private RuntimeStatus(boolean available, String failureReason) {
            this.available = available;
            this.failureReason = failureReason;
        }

        public boolean isAvailable() {
            return available;
        }

        public String failureReason() {
            return failureReason;
        }

        public static RuntimeStatus available() {
            return new RuntimeStatus(true, null);
        }

        public static RuntimeStatus failed(String reason) {
            return new RuntimeStatus(false, reason);
        }
    }

    private static final class VulkanManagerHolder {
        private static final VulkanManager INSTANCE = new VulkanManager();
    }

    public record VulkanDeviceInfo(
        String id,
        String name,
        String vendor,
        long localMemoryBytes,
        int deviceType,
        boolean softwareAdapter
    ) {
    }

    private record PhysicalSelection(
        long physicalDeviceHandle,
        int computeQueueFamily,
        String deviceName,
        String vendorName,
        String id,
        int deviceType,
        long localMemoryBytes,
        boolean softwareAdapter,
        double score
    ) {
    }

    private record FloatControlSummary(
        boolean available,
        boolean roundToNearestEvenFloat32,
        boolean signedZeroInfNanPreserveFloat32,
        boolean denormPreserveFloat32,
        boolean denormFlushToZeroFloat32,
        int denormBehaviorIndependence,
        int roundingModeIndependence,
        String unavailableReason
    ) {
        private static FloatControlSummary unavailable(String reason) {
            return new FloatControlSummary(false, false, false, false, false, 0, 0, reason);
        }

        private boolean deterministicFloat32() {
            return available && roundToNearestEvenFloat32 && signedZeroInfNanPreserveFloat32;
        }

        private String summary() {
            if (!available) {
                return "unavailable (" + unavailableReason + ")";
            }
            return "rte32=" + roundToNearestEvenFloat32
                + ", preserveZeroInfNan32=" + signedZeroInfNanPreserveFloat32
                + ", denormPreserve32=" + denormPreserveFloat32
                + ", denormFtz32=" + denormFlushToZeroFloat32
                + ", denormIndependence=" + independenceName(denormBehaviorIndependence)
                + ", roundingIndependence=" + independenceName(roundingModeIndependence)
                + ", strictFloat32=" + deterministicFloat32();
        }

        private static String independenceName(int value) {
            if (value == VK12.VK_SHADER_FLOAT_CONTROLS_INDEPENDENCE_ALL) {
                return "all";
            }
            if (value == VK12.VK_SHADER_FLOAT_CONTROLS_INDEPENDENCE_32_BIT_ONLY) {
                return "32bit-only";
            }
            if (value == VK12.VK_SHADER_FLOAT_CONTROLS_INDEPENDENCE_NONE) {
                return "none";
            }
            return Integer.toString(value);
        }
    }

    private record Program(String name,
                           long pipeline,
                           long pipelineLayout,
                           long descriptorSetLayout,
                           int storageBufferCount,
                           int pushConstantBytes) {
    }

    private record DispatchWorkspace(Program program,
                                     long descriptorPool,
                                     long descriptorSet,
                                     VkCommandBuffer commandBuffer,
                                     long fence,
                                     int groupCountX,
                                     int groupCountY,
                                     int groupCountZ,
                                     int inputBufferCount,
                                     AllocatedBuffer[] buffers) {
    }

    private static final class DispatchWorkspacePool {
        private final DispatchWorkspace[] workspaces;
        private final BlockingQueue<DispatchWorkspace> available;

        private DispatchWorkspacePool(DispatchWorkspace[] workspaces) {
            this.workspaces = workspaces;
            this.available = new ArrayBlockingQueue<>(workspaces.length);
            for (DispatchWorkspace workspace : workspaces) {
                this.available.add(workspace);
            }
        }

        private DispatchWorkspace borrow() {
            try {
                return available.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Vulkan workspace", e);
            }
        }

        private void release(DispatchWorkspace workspace) {
            if (workspace != null) {
                available.offer(workspace);
            }
        }

        private DispatchWorkspace[] workspaces() {
            return workspaces;
        }
    }

    private record WorkspaceLease(DispatchWorkspacePool pool, DispatchWorkspace workspace) implements AutoCloseable {
        @Override
        public void close() {
            pool.release(workspace);
        }
    }

    private record AllocatedBuffer(long deviceBuffer,
                                   long deviceMemory,
                                   long hostBuffer,
                                   long hostMemory,
                                   long sizeBytes,
                                   long mappedPointer,
                                   boolean staged) {
        private long descriptorBuffer() {
            return staged ? deviceBuffer : hostBuffer;
        }
    }

    private record RawBuffer(long buffer, long memory, long sizeBytes, long mappedPointer) {
    }

    private enum WorkloadProfile {
        BANDWIDTH,
        COMPUTE_DENSE,
        REDUCTION
    }

    private enum BufferRole {
        INPUT,
        OUTPUT
    }

    private static final class McDensityBatchGroup {
        private final float[] encodedProgram;
        private final int instructionCount;
        private final int auxValueCount;
        private final List<Integer> indices = new ArrayList<>();
        private final List<McDensityVulkanTask> tasks = new ArrayList<>();
        private int totalSamples;

        private McDensityBatchGroup(float[] encodedProgram, int instructionCount, int auxValueCount) {
            this.encodedProgram = encodedProgram;
            this.instructionCount = instructionCount;
            this.auxValueCount = auxValueCount;
        }

        private boolean matches(McDensityVulkanTask task) {
            return instructionCount == task.instructionCount()
                && auxValueCount == task.auxValueCount()
                && Arrays.equals(encodedProgram, task.encodedProgram());
        }

        private void add(int index, McDensityVulkanTask task) {
            indices.add(index);
            tasks.add(task);
            totalSamples += task.sampleCount();
        }

        private float[] combineAuxValues() {
            if (auxValueCount <= 0) {
                return new float[0];
            }
            float[] combined = new float[auxValueCount * totalSamples];
            for (int auxIndex = 0; auxIndex < auxValueCount; auxIndex++) {
                int outputOffset = auxIndex * totalSamples;
                for (McDensityVulkanTask task : tasks) {
                    float[] taskAux = task.auxValues();
                    int sampleCount = task.sampleCount();
                    System.arraycopy(taskAux, auxIndex * sampleCount, combined, outputOffset, sampleCount);
                    outputOffset += sampleCount;
                }
            }
            return combined;
        }
    }

    private record PreparedMcDensityGroup(McDensityBatchGroup group, WorkspaceLease lease) {
    }

    private static final class State {
        private final VkInstance instance;
        private final VkPhysicalDevice physicalDevice;
        private final VkDevice device;
        private final VkQueue computeQueue;
        private final Object computeQueueLock = new Object();
        private final long commandPool;
        private final String deviceName;
        private final boolean prefersDeviceLocalTransfers;
        private final Map<String, Program> programs = new ConcurrentHashMap<>();
        private final Map<String, DispatchWorkspacePool> workspaces = new ConcurrentHashMap<>();

        private State(VkInstance instance,
                      VkPhysicalDevice physicalDevice,
                      VkDevice device,
                      VkQueue computeQueue,
                      long commandPool,
                      String deviceName,
                      boolean prefersDeviceLocalTransfers) {
            this.instance = instance;
            this.physicalDevice = physicalDevice;
            this.device = device;
            this.computeQueue = computeQueue;
            this.commandPool = commandPool;
            this.deviceName = deviceName;
            this.prefersDeviceLocalTransfers = prefersDeviceLocalTransfers;
        }
    }

    private static final class VulkanThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            return LwjglRuntimeTuning.newDaemonThread(runnable, "Quantified-Vulkan",
                LwjglRuntimeTuning.gpuThreadStackSizeKb());
        }
    }
}
