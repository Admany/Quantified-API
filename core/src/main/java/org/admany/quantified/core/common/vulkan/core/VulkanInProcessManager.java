package org.admany.quantified.core.common.vulkan.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.util.VulkanLoaderIsolation;
import org.admany.quantified.core.common.vulkan.core.VulkanContext;
import org.admany.quantified.core.common.vulkan.core.VulkanIsolatedExecutor;
import org.admany.quantified.core.common.vulkan.core.VulkanTask;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.CustomBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
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
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceFloatControlsProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VulkanInProcessManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanInProcessManager.class);
    private static final long EXECUTION_TIMEOUT_NANOS = 30000000000L;
    private static final int MAX_PENDING_TASKS = 256;
    private static final int MAX_IN_FLIGHT_WORKSPACES = 3;
    private static final int MAX_CUSTOM_PROGRAMS = 64;
    private static final long MAX_ACCEPTED_VRAM_BYTES = 0x20000000L;
    private static final long INLINE_VRAM_BYTES_LIMIT = 0x2000000L;
    private static final int INLINE_COMPUTE_UNITS_LIMIT = 1500000;
    private static final long INIT_RETRY_COOLDOWN_MS = 30000L;
    private static final long DEVICE_LOCAL_TRANSFER_THRESHOLD_BYTES = 262144L;
    private static final long DEFAULT_SLAB_BYTES = 0x10000000L;
    private static final long MIN_SLAB_BYTES = 0x1000000L;
    private static final String SLAB_BYTES_PROPERTY = "quantified.vulkan.slabBytes";
    private static final String INLINE_EXECUTION_PROPERTY = "quantified.vulkan.allowInlineExecution";
    private static final int VECTOR_LOCAL_SIZE_X = 256;
    private static final int VECTOR_ELEMENTS_PER_INVOCATION = 8;
    private static final int MONTE_CARLO_SAMPLES_PER_INVOCATION = 32;
    private static final int TERRAIN_LOCAL_SIZE_X = 256;
    private static final int TERRAIN_OUTPUT_COMPONENTS = 4;
    private static final String VECTOR_ADD_SHADER_RESOURCE = "/quantified/shaders/vulkan/vector_add.comp.spv";
    private static final String MATRIX_MULTIPLY_SHADER_RESOURCE = "/quantified/shaders/vulkan/matrix_multiply.comp.spv";
    private static final String MONTE_CARLO_PI_SHADER_RESOURCE = "/quantified/shaders/vulkan/monte_carlo_pi.comp.spv";
    private static final String TERRAIN_GENERATION_SHADER_RESOURCE = "/quantified/shaders/vulkan/terrain_generation.comp.spv";
    private static final String TERRAIN_GENERATION_LEGACY_SHADER_RESOURCE = "/quantified/shaders/vulkan/terrain_generation_legacy.comp.spv";
    private static final String AUTO_RUNTIME_INIT_PROPERTY = "quantified.vulkan.autoRuntimeInit";
    private static final String PROBE_ONLY_PROPERTY = "quantified.vulkan.probeOnly";
    private static final String TIMELINE_SEMAPHORES_PROPERTY = "quantified.vulkan.timelineSemaphores";
    private static final String REQUIRE_DETERMINISTIC_FLOAT32_PROPERTY = "quantified.vulkan.requireDeterministicFloat32";
    private static final boolean REQUIRE_DETERMINISTIC_FLOAT32 = Boolean.parseBoolean(System.getProperty("quantified.vulkan.requireDeterministicFloat32", "true"));
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final Object INIT_MUTEX = new Object();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3, new VulkanThreadFactory());
    private static final ExecutorService INIT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> LwjglRuntimeTuning.newDaemonThread(runnable, "Quantified-Vulkan-Init", LwjglRuntimeTuning.gpuThreadStackSizeKb()));
    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> LwjglRuntimeTuning.newDaemonThread(runnable, "Quantified-Vulkan-Probe", LwjglRuntimeTuning.probeThreadStackSizeKb()));
    private static final AtomicReference<String> PREFERRED_DEVICE = new AtomicReference();
    private static final AtomicReference<CompletableFuture<Boolean>> ACTIVE_PROBE = new AtomicReference();
    private static final AtomicReference<CompletableFuture<Boolean>> ACTIVE_INIT = new AtomicReference();
    private static final AtomicBoolean VULKAN_PROBING = new AtomicBoolean(false);
    private static final AtomicBoolean DEFERRED_RUNTIME_INIT_LOGGED = new AtomicBoolean(false);
    private static final AtomicLong VRAM_PRESSURE_COOLDOWN_UNTIL_MS = new AtomicLong(0L);
    private static final AtomicLong LAST_PRESSURE_LOG_MS = new AtomicLong(0L);
    private static volatile State state;
    private static volatile RuntimeStatus lastStatus;
    private static volatile long nextInitRetryMs;

    private VulkanInProcessManager() {
    }

    public static boolean isAvailable() {
        return INITIALIZED.get() && state != null;
    }

    public static boolean hasExecutableRuntime() {
        return VulkanInProcessManager.isAvailable() || VulkanIsolatedExecutor.canExecute();
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
        return VULKAN_PROBING.get() || active != null && !active.isDone();
    }

    public static boolean isRuntimeWarmupRunning() {
        CompletableFuture<Boolean> active = ACTIVE_INIT.get();
        return active != null && !active.isDone();
    }

    public static boolean isInVramPressureCooldown() {
        long now = System.currentTimeMillis();
        long until = VRAM_PRESSURE_COOLDOWN_UNTIL_MS.get();
        if (until <= now) {
            return false;
        }
        VulkanResidencyManager.notePressureCooldownHit();
        long lastLog = LAST_PRESSURE_LOG_MS.get();
        if (now - lastLog > VulkanResidencyManager.pressureLogIntervalMs() && LAST_PRESSURE_LOG_MS.compareAndSet(lastLog, now)) {
            long seconds = Math.max(1L, (until - now) / 1000L);
            DeveloperOverlayManager.recordApiLog("[Vulkan] VRAM spike - routing GPU work to CPU for ~" + seconds + "s");
        }
        return true;
    }

    public static Map<String, Object> residencySnapshot() {
        State local = state;
        long reservedBytes = 0L;
        long localMemoryBytes = 0L;
        int slabCount = 0;
        int workspacePoolCount = 0;
        long workspacePoolBytes = 0L;
        if (local != null) {
            reservedBytes = local.memoryAllocator.reservedBytes();
            localMemoryBytes = local.localMemoryBytes;
            slabCount = local.memoryAllocator.slabCount();
            synchronized (local.workspaceMutex) {
                workspacePoolCount = local.workspaces.size();
                for (DispatchWorkspacePool pool : local.workspaces.values()) {
                    workspacePoolBytes += pool.totalBytes();
                }
            }
        }
        long now = System.currentTimeMillis();
        long cooldownUntil = VRAM_PRESSURE_COOLDOWN_UNTIL_MS.get();
        boolean cooldownActive = cooldownUntil > now;
        long cooldownRemainingMs = cooldownActive ? Math.max(0L, cooldownUntil - now) : 0L;
        return VulkanResidencyManager.snapshot(
            reservedBytes,
            localMemoryBytes,
            slabCount,
            workspacePoolCount,
            workspacePoolBytes,
            cooldownActive,
            cooldownRemainingMs
        ).toMap();
    }

    public static long activeTaskVramBytes() {
        return VulkanRuntimeActivityTracker.activeVramBytes();
    }

    public static int activeTaskComputeUnits() {
        return VulkanRuntimeActivityTracker.activeComputeUnits();
    }

    public static long lastTaskActivityMs() {
        return VulkanRuntimeActivityTracker.lastTaskActivityMs();
    }

    private static void queueAutomaticWarmup(String reason) {
        if (INITIALIZED.get() && state != null) {
            return;
        }
        if (VulkanInProcessManager.isInitRetryCoolingDown() || !VulkanRuntime.isAvailable()) {
            return;
        }
        DEFERRED_RUNTIME_INIT_LOGGED.set(false);
        VulkanInProcessManager.warmupAsync(reason);
    }

    public static CompletableFuture<Boolean> forceProbe() {
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            lastStatus = RuntimeStatus.failed("GPU acceleration disabled in configuration");
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> existing = ACTIVE_PROBE.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>();
        if (!ACTIVE_PROBE.compareAndSet(existing, future)) {
            CompletableFuture<Boolean> concurrent = ACTIVE_PROBE.get();
            return concurrent != null ? concurrent : future;
        }
        PROBE_EXECUTOR.submit(() -> {
            try {
                future.complete(VulkanInProcessManager.runManagedProbe());
            }
            catch (Throwable thr) {
                future.completeExceptionally(thr);
            }
            finally {
                ACTIVE_PROBE.compareAndSet(future, null);
            }
        });
        return future;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static boolean runManagedProbe() {
        if (INITIALIZED.get() && state != null) {
            return true;
        }
        VulkanRuntime.AvailabilitySnapshot cachedSnapshot = VulkanRuntime.cachedSnapshot();
        if (VulkanInProcessManager.isUsableProbeSnapshot(cachedSnapshot)) {
            if (cachedSnapshot.available() && !Boolean.getBoolean(PROBE_ONLY_PROPERTY)) {
                VulkanInProcessManager.queueAutomaticWarmup("probe-cached");
            }
            return true;
        }
        if (!VULKAN_PROBING.compareAndSet(false, true)) {
            LOGGER.debug("Skipping Vulkan probe because another probe is already running");
            return INITIALIZED.get() && state != null;
        }
        VulkanInProcessManager.notePending("Vulkan probe in progress");
        try {
            VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.reprobe();
            if (snapshot.available()) {
                LOGGER.info("Vulkan probe succeeded for {} device(s); queueing runtime warmup", (Object)snapshot.devices().size());
                DeveloperOverlayManager.recordApiLog("[Vulkan] Probe succeeded - " + VulkanInProcessManager.deviceName());
                if (!Boolean.getBoolean(PROBE_ONLY_PROPERTY)) {
                    VulkanInProcessManager.queueAutomaticWarmup("probe-succeeded");
                }
                boolean bl = true;
                return bl;
            }
            String reason = snapshot.failureReason();
            if (reason != null && !reason.isBlank()) {
                LOGGER.warn("Vulkan acceleration unavailable: " + reason);
                DeveloperOverlayManager.recordApiLog("[Vulkan] Probe failed - " + reason);
            } else {
                LOGGER.warn("Vulkan acceleration unavailable");
                DeveloperOverlayManager.recordApiLog("[Vulkan] Probe failed - Unknown reason");
            }
            boolean bl = false;
            return bl;
        }
        finally {
            VULKAN_PROBING.set(false);
        }
    }

    public static boolean forceProbeSynchronous() {
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            lastStatus = RuntimeStatus.failed("GPU acceleration disabled in configuration");
            return false;
        }
        if (INITIALIZED.get() && state != null) {
            return true;
        }
        VulkanRuntime.AvailabilitySnapshot cachedSnapshot = VulkanRuntime.cachedSnapshot();
        if (VulkanInProcessManager.isUsableProbeSnapshot(cachedSnapshot)) {
            if (cachedSnapshot.available() && !Boolean.getBoolean(PROBE_ONLY_PROPERTY)) {
                VulkanInProcessManager.queueAutomaticWarmup("probe-cached-sync");
            }
            return true;
        }
        Thread current = Thread.currentThread();
        LOGGER.info("[Vulkan] forceProbeSynchronous() on thread '" + current.getName() + "' (group=" + current.getThreadGroup().getName() + ")");
        VulkanInProcessManager.notePending("Vulkan probe in progress");
        VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.reprobe();
        if (snapshot.available()) {
            LOGGER.info("Vulkan probe succeeded for {} device(s); queueing runtime warmup", (Object)snapshot.devices().size());
            DeveloperOverlayManager.recordApiLog("[Vulkan] Probe succeeded - " + VulkanInProcessManager.deviceName());
            if (!Boolean.getBoolean(PROBE_ONLY_PROPERTY)) {
                VulkanInProcessManager.queueAutomaticWarmup("probe-succeeded-sync");
            }
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

    private static boolean isUsableProbeSnapshot(VulkanRuntime.AvailabilitySnapshot snapshot) {
        return snapshot != null && (snapshot.available() || VulkanRuntime.runtimeMode() == VulkanRuntime.RuntimeMode.ISOLATED && !snapshot.devices().isEmpty());
    }

    public static CompletableFuture<Boolean> warmupAsync() {
        return VulkanInProcessManager.warmupAsync("manual");
    }

    public static CompletableFuture<Boolean> warmupAsync(String reason) {
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            lastStatus = RuntimeStatus.failed("GPU acceleration disabled in configuration");
            return CompletableFuture.completedFuture(false);
        }
        if (INITIALIZED.get() && state != null) {
            return CompletableFuture.completedFuture(true);
        }
        if (VulkanInProcessManager.isInitRetryCoolingDown()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> existing = ACTIVE_INIT.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>();
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
                boolean initialized = VulkanInProcessManager.ensureInitialised();
                if (initialized) {
                    DEFERRED_RUNTIME_INIT_LOGGED.set(false);
                    DeveloperOverlayManager.recordApiLog("[Vulkan] Runtime ready - " + VulkanInProcessManager.deviceName());
                }
                future.complete(initialized);
            }
            catch (Throwable throwable) {
                String detail = VulkanInProcessManager.describeThrowable(throwable);
                lastStatus = RuntimeStatus.failed("Vulkan runtime warmup failed: " + detail);
                LOGGER.warn("Vulkan runtime warmup failed (" + triggerReason + ")", throwable);
                DeveloperOverlayManager.recordApiLog("[Vulkan] Runtime warmup failed (" + triggerReason + ") - " + detail);
                future.complete(false);
            }
            finally {
                ACTIVE_INIT.compareAndSet(future, null);
            }
        });
        return future;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static boolean ensureInitialised() {
        if (!MultithreadingConfig.isGpuAccelerationEnabled()) {
            lastStatus = RuntimeStatus.failed("GPU acceleration disabled in configuration");
            return false;
        }
        if (INITIALIZED.get() && state != null) {
            return true;
        }
        if (VulkanInProcessManager.isInitRetryCoolingDown()) {
            return false;
        }
        Object object = INIT_MUTEX;
        synchronized (object) {
            if (INITIALIZED.get() && state != null) {
                return true;
            }
            if (VulkanInProcessManager.isInitRetryCoolingDown()) {
                return false;
            }
            LwjglRuntimeTuning.ensureConfigured();
            LwjglRuntimeTuning.ensureThreadLocalStack();
            VulkanRuntime.AvailabilitySnapshot runtime = VulkanRuntime.snapshot();
            if (!runtime.available() && !VulkanInProcessManager.isUsableProbeSnapshot(runtime)) {
                lastStatus = RuntimeStatus.failed(runtime.failureReason());
                return false;
            }
            try {
                state = VulkanInProcessManager.createState(runtime.selectedApiVersion());
                INITIALIZED.set(true);
                nextInitRetryMs = 0L;
                lastStatus = RuntimeStatus.available();
                LOGGER.info("Vulkan initialized on device: " + VulkanInProcessManager.deviceName());
                return true;
            }
            catch (Throwable throwable) {
                LOGGER.warn("Vulkan initialization failed", throwable);
                VulkanInProcessManager.cleanupState(state);
                state = null;
                INITIALIZED.set(false);
                nextInitRetryMs = System.currentTimeMillis() + 30000L;
                lastStatus = RuntimeStatus.failed("Vulkan initialization failed: " + VulkanInProcessManager.describeThrowable(throwable));
                return false;
            }
        }
    }

    public static void setPreferredDevice(String preferredDevice) {
        String normalized = VulkanInProcessManager.normalizeDevicePreference(preferredDevice);
        String previous = PREFERRED_DEVICE.getAndSet(normalized);
        if (!Objects.equals(previous, normalized)) {
            VulkanInProcessManager.shutdown();
        }
    }

    public static void clearPreferredDevice() {
        VulkanInProcessManager.setPreferredDevice(null);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public static List<VulkanDeviceInfo> listDevices() {
        List<VulkanDeviceInfo> list;
        LwjglRuntimeTuning.ensureConfigured();
        VulkanRuntime.AvailabilitySnapshot runtime = VulkanRuntime.snapshot();
        if (!runtime.bindingPresent()) {
            return List.of();
        }
        if (!runtime.devices().isEmpty()) {
            ArrayList<VulkanDeviceInfo> devices = new ArrayList<VulkanDeviceInfo>(runtime.devices().size());
            Iterator<VulkanRuntime.ProbeDeviceInfo> iterator = runtime.devices().iterator();
            while (iterator.hasNext()) {
                VulkanRuntime.ProbeDeviceInfo device = iterator.next();
                devices.add(new VulkanDeviceInfo(device.id(), VulkanInProcessManager.sanitizeDeviceName(device.name()), device.vendor(), device.localMemoryBytes(), device.deviceType(), device.softwareAdapter()));
            }
            return VulkanInProcessManager.dedupeDeviceInfos(devices);
        }
        int apiVersion = VulkanInProcessManager.preferredApiVersion(runtime);
        if (apiVersion == 0) {
            return List.of();
        }
        VkInstance instance = null;
        try {
            instance = VulkanInProcessManager.createInstance(apiVersion);
            List<PhysicalSelection> selections = VulkanInProcessManager.enumeratePhysicalDevices(instance);
            ArrayList<VulkanDeviceInfo> devices = new ArrayList<VulkanDeviceInfo>(selections.size());
            for (PhysicalSelection selection : selections) {
                devices.add(new VulkanDeviceInfo(selection.id(), selection.deviceName(), selection.vendorName(), selection.localMemoryBytes(), selection.deviceType(), selection.softwareAdapter()));
            }
            list = VulkanInProcessManager.dedupeDeviceInfos(devices);
            if (instance == null) return list;
        }
        catch (Throwable throwable) {
            try {
                String reason = VulkanInProcessManager.describeThrowable(throwable);
                lastStatus = RuntimeStatus.failed(reason);
                LOGGER.debug("Failed to enumerate Vulkan devices", throwable);
                List<VulkanDeviceInfo> list2 = List.of();
                return list2;
            }
            catch (Throwable throwable2) {
                throw throwable2;
            }
            finally {
                if (instance != null) {
                    try {
                        VK10.vkDestroyInstance((VkInstance)instance, null);
                    }
                    catch (Throwable throwable3) {}
                }
            }
        }
        try {
            VK10.vkDestroyInstance((VkInstance)instance, null);
            return list;
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return list;
    }

    public static <T> CompletableFuture<T> executeOnGpu(VulkanTask<T> task) {
        Objects.requireNonNull(task, "task");
        if (!INITIALIZED.get() || state == null) {
            if (!VulkanInProcessManager.isInitRetryCoolingDown() && VulkanRuntime.isAvailable()) {
                VulkanInProcessManager.queueAutomaticWarmup("execute-on-gpu");
            }
            return CompletableFuture.completedFuture(task.cpuFallback().get());
        }
        if (VulkanInProcessManager.shouldExecuteInline(task)) {
            try {
                if (!VulkanInProcessManager.ensureInitialised()) {
                    return CompletableFuture.completedFuture(task.cpuFallback().get());
                }
                return CompletableFuture.completedFuture(VulkanInProcessManager.executeTracked(task, () -> task.executeOnGPU(VulkanManagerHolder.CONTEXT)));
            }
            catch (Throwable throwable) {
                LOGGER.debug("Inline Vulkan execution failed, falling back to async for task {}", (Object)task.name(), (Object)throwable);
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!VulkanInProcessManager.ensureInitialised()) {
                return task.cpuFallback().get();
            }
            return VulkanInProcessManager.executeTracked(task, () -> task.executeOnGPU(VulkanManagerHolder.CONTEXT));
        }, EXECUTOR);
    }

    private static <T> T executeTracked(VulkanTask<?> task, CheckedSupplier<T> supplier) {
        VulkanRuntimeActivityTracker.TaskSample sample = VulkanRuntimeActivityTracker.beginTask(
            task.estimatedVramBytes(),
            task.estimatedComputeUnits()
        );
        try {
            return supplier.get();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw new RuntimeException("Vulkan workload execution failed", throwable);
        } finally {
            VulkanRuntimeActivityTracker.endTask(sample);
        }
    }

    private static boolean shouldExecuteInline(VulkanTask<?> task) {
        if (!Boolean.parseBoolean(System.getProperty(INLINE_EXECUTION_PROPERTY, "false"))) {
            return false;
        }
        if (task.timeout().isPresent()) {
            return false;
        }
        if (Thread.currentThread().getName().startsWith("Quantified-Vulkan")) {
            return false;
        }
        if (VulkanResidencyManager.isFrameSensitiveThread(Thread.currentThread())) {
            return false;
        }
        if (task.estimatedVramBytes() > 0x2000000L) {
            return false;
        }
        return task.estimatedComputeUnits() <= 1500000;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Throwable;
    }

    public static boolean canAcceptTask(VulkanTask<?> task) {
        if (task == null) {
            return false;
        }
        if (task.estimatedVramBytes() > 0x20000000L) {
            return false;
        }
        if (VulkanInProcessManager.isInVramPressureCooldown()) {
            return false;
        }
        if (!INITIALIZED.get() || state == null) {
            if (VulkanInProcessManager.isInitRetryCoolingDown() || !VulkanRuntime.isAvailable()) {
                return false;
            }
            VulkanInProcessManager.queueAutomaticWarmup("task-submission");
            if (DEFERRED_RUNTIME_INIT_LOGGED.compareAndSet(false, true)) {
                String message = "Vulkan probe found a device and runtime warmup has been queued. Tasks will fall back to the CPU until the runtime is ready.";
                LOGGER.info(message);
                DeveloperOverlayManager.recordApiLog("[Vulkan] Runtime warmup queued automatically. Batches will fall back to the CPU until runtime is ready.");
            }
            return false;
        }
        if (!VulkanInProcessManager.isAvailable()) {
            return false;
        }
        State local = state;
        if (local != null && !VulkanInProcessManager.ensureCapacityForTask(local, task)) {
            return false;
        }
        ExecutorService executorService = EXECUTOR;
        if (executorService instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor)executorService;
            return executor.getQueue().size() < 256;
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

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void shutdown() {
        Object object = INIT_MUTEX;
        synchronized (object) {
            VulkanInProcessManager.cleanupState(state);
            state = null;
            INITIALIZED.set(false);
            nextInitRetryMs = 0L;
            VRAM_PRESSURE_COOLDOWN_UNTIL_MS.set(0L);
            lastStatus = RuntimeStatus.failed("Vulkan shutdown");
            VulkanRuntime.invalidate();
        }
    }

    public static void trimIdleResources(String reason, boolean aggressive) {
        State local = state;
        if (local == null) {
            return;
        }
        long before = local.memoryAllocator.reservedBytes();
        VulkanResidencyManager.TrimResult trimResult;
        synchronized (INIT_MUTEX) {
            if (state != local) {
                return;
            }
            VulkanResidencyManager.TrimLevel trimLevel = VulkanResidencyManager.trimLevel(aggressive);
            trimResult = VulkanInProcessManager.trimIdleWorkspacePools(local, trimLevel)
                .merge(local.memoryAllocator.trimFreeSlabs(trimLevel));
        }
        long after = local.memoryAllocator.reservedBytes();
        VulkanResidencyManager.noteTrim(trimResult);
        if (after < before) {
            LOGGER.info("Trimmed Vulkan idle resources (reason={}, aggressive={}, freed={} bytes)", reason, aggressive, before - after);
            DeveloperOverlayManager.recordApiLog("[Vulkan] Trimmed idle resources (" + reason + "), freed " + ((before - after) / (1024 * 1024)) + " MiB");
        }
    }

    float[] executeVectorAdd(float[] a, float[] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths must match");
        }
        State local = this.requireState();
        Program program = local.programs.get("vector_add");
        try (WorkspaceLease lease = VulkanInProcessManager.acquireWorkspace(local, program, "vector_add:" + a.length, WorkloadProfile.BANDWIDTH, VulkanInProcessManager.groupCount(a.length, 2048), 1, 1, 2, new int[]{a.length}, (long)a.length * 4L, (long)b.length * 4L, (long)a.length * 4L);){
            DispatchWorkspace workspace = lease.workspace();
            VulkanInProcessManager.writeFloatArray(workspace.buffers[0], a);
            VulkanInProcessManager.writeFloatArray(workspace.buffers[1], b);
            VulkanInProcessManager.dispatch(local, workspace);
            float[] fArray = VulkanInProcessManager.readFloatArray(workspace.buffers[2], a.length);
            return fArray;
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
        float[] flatA = VulkanInProcessManager.flattenMatrix(a);
        float[] flatB = VulkanInProcessManager.flattenMatrix(b);
        State local = this.requireState();
        Program program = local.programs.get("matrix_multiply");
        try (WorkspaceLease lease = VulkanInProcessManager.acquireWorkspace(local, program, "matrix_multiply:" + m + ":" + n + ":" + p, WorkloadProfile.COMPUTE_DENSE, VulkanInProcessManager.groupCount(n, 16), VulkanInProcessManager.groupCount(m, 16), 1, 2, new int[]{m, n, p}, (long)flatA.length * 4L, (long)flatB.length * 4L, (long)m * (long)n * 4L);){
            DispatchWorkspace workspace = lease.workspace();
            VulkanInProcessManager.writeFloatArray(workspace.buffers[0], flatA);
            VulkanInProcessManager.writeFloatArray(workspace.buffers[1], flatB);
            VulkanInProcessManager.dispatch(local, workspace);
            float[][] fArray = VulkanInProcessManager.reconstructMatrix(VulkanInProcessManager.readFloatArray(workspace.buffers[2], m * n), m, n);
            return fArray;
        }
    }

    double executeMonteCarloPi(int samples) {
        if (samples <= 0) {
            throw new IllegalArgumentException("Samples must be positive");
        }
        State local = this.requireState();
        Program program = local.programs.get("monte_carlo_pi");
        try (WorkspaceLease lease = VulkanInProcessManager.acquireWorkspace(local, program, "monte_carlo_pi:" + samples, WorkloadProfile.REDUCTION, VulkanInProcessManager.groupCount(samples, 8192), 1, 1, 0, new int[]{samples}, 4L);){
            DispatchWorkspace workspace = lease.workspace();
            VulkanInProcessManager.writeInt(workspace.buffers[0], 0);
            VulkanInProcessManager.dispatch(local, workspace);
            int hits = VulkanInProcessManager.readInt(workspace.buffers[0]);
            double d = 4.0 * (double)hits / (double)samples;
            return d;
        }
    }

    float[] executeTerrainGeneration(float[] inputCoords) {
        Objects.requireNonNull(inputCoords, "inputCoords");
        if (inputCoords.length == 0) {
            return new float[0];
        }
        if (VulkanInProcessManager.isLegacyTerrainGenerationInput(inputCoords)) {
            return this.executeLegacyTerrainGeneration(inputCoords);
        }
        if (inputCoords.length % 3 != 0) {
            throw new IllegalArgumentException("Terrain generation input must be packed xyz triples or legacy 25-float LC2H region blocks");
        }
        int sampleCount = inputCoords.length / 3;
        State local = this.requireState();
        Program program = local.programs.get("terrain_generation");
        try (WorkspaceLease lease = VulkanInProcessManager.acquireWorkspace(local, program, "terrain_generation:" + sampleCount, WorkloadProfile.COMPUTE_DENSE, VulkanInProcessManager.groupCount(sampleCount, 256), 1, 1, 1, new int[]{sampleCount}, (long)inputCoords.length * 4L, (long)sampleCount * 4L * 4L);){
            DispatchWorkspace workspace = lease.workspace();
            VulkanInProcessManager.writeFloatArray(workspace.buffers[0], inputCoords);
            VulkanInProcessManager.dispatch(local, workspace);
            float[] fArray = VulkanInProcessManager.readFloatArray(workspace.buffers[1], sampleCount * 4);
            return fArray;
        }
    }

    private float[] executeLegacyTerrainGeneration(float[] inputCoords) {
        int sampleCount = inputCoords.length;
        State local = this.requireState();
        Program program = local.programs.get("terrain_generation_legacy");
        try (WorkspaceLease lease = VulkanInProcessManager.acquireWorkspace(local, program, "terrain_generation_legacy:" + sampleCount, WorkloadProfile.COMPUTE_DENSE, VulkanInProcessManager.groupCount(sampleCount, 256), 1, 1, 1, new int[]{sampleCount}, (long)inputCoords.length * 4L, (long)sampleCount * 4L * 4L);){
            DispatchWorkspace workspace = lease.workspace();
            VulkanInProcessManager.writeFloatArray(workspace.buffers[0], inputCoords);
            VulkanInProcessManager.dispatch(local, workspace);
            return VulkanInProcessManager.readFloatArray(workspace.buffers[1], sampleCount * 4);
        }
    }

    private static boolean isLegacyTerrainGenerationInput(float[] inputCoords) {
        return inputCoords.length % 25 == 0;
    }

    private State requireState() {
        State local = state;
        if (local == null && !VulkanInProcessManager.ensureInitialised()) {
            throw new IllegalStateException(VulkanInProcessManager.runtimeStatus().failureReason());
        }
        return state;
    }

    private static State createState(int apiVersion) {
        State state;
        block12: {
            LwjglRuntimeTuning.ensureConfigured();
            LwjglRuntimeTuning.ensureThreadLocalStack();
            VkInstance instance = VulkanInProcessManager.createInstance(apiVersion);
            MemoryStack stack = LwjglRuntimeTuning.pushMemoryStack();
            try {
                PhysicalSelection selection = VulkanInProcessManager.pickPhysicalDevice(instance);
                VkPhysicalDevice physicalDevice = new VkPhysicalDevice(selection.physicalDeviceHandle(), instance);
                FloatControlSummary floatControls = VulkanInProcessManager.queryFloatControls(physicalDevice, apiVersion, stack);
                LOGGER.info("[Vulkan] Float controls: {}", (Object)floatControls.summary());
                if (REQUIRE_DETERMINISTIC_FLOAT32 && !floatControls.deterministicFloat32()) {
                    throw new IllegalStateException("Vulkan device does not expose strict float32 controls required for deterministic compute: " + floatControls.summary() + ". Set -Dquantified.vulkan.requireDeterministicFloat32=false to allow faster-but-less-strict GPU math.");
                }
                boolean timelineSemaphoreSupported = Boolean.parseBoolean(System.getProperty(TIMELINE_SEMAPHORES_PROPERTY, "true")) && VulkanInProcessManager.supportsTimelineSemaphores(physicalDevice, apiVersion, stack);
                FloatBuffer priorities = stack.floats(1.0f);
                VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc((int)1, (MemoryStack)stack).sType(2).queueFamilyIndex(selection.computeQueueFamily).pQueuePriorities(priorities);
                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc((MemoryStack)stack).sType(3).pQueueCreateInfos(queueInfo);
                VkPhysicalDeviceVulkan12Features enabledVulkan12Features = null;
                if (timelineSemaphoreSupported) {
                    enabledVulkan12Features = VkPhysicalDeviceVulkan12Features.calloc((MemoryStack)stack).sType(51).timelineSemaphore(true);
                    deviceInfo.pNext(enabledVulkan12Features.address());
                }
                PointerBuffer devicePtr = stack.mallocPointer(1);
                VulkanInProcessManager.checkVk(VK10.vkCreateDevice((VkPhysicalDevice)physicalDevice, (VkDeviceCreateInfo)deviceInfo, null, (PointerBuffer)devicePtr), "vkCreateDevice");
                VkDevice device = new VkDevice(devicePtr.get(0), physicalDevice, deviceInfo, apiVersion);
                PointerBuffer queuePtr = stack.mallocPointer(1);
                VK10.vkGetDeviceQueue((VkDevice)device, (int)selection.computeQueueFamily, (int)0, (PointerBuffer)queuePtr);
                VkQueue queue = new VkQueue(queuePtr.get(0), device);
                LongBuffer poolPtr = stack.mallocLong(1);
                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc((MemoryStack)stack).sType(39).queueFamilyIndex(selection.computeQueueFamily).flags(2);
                VulkanInProcessManager.checkVk(VK10.vkCreateCommandPool((VkDevice)device, (VkCommandPoolCreateInfo)poolInfo, null, (LongBuffer)poolPtr), "vkCreateCommandPool");
                State created = new State(instance, physicalDevice, device, queue, poolPtr.get(0), selection.deviceName, selection.localMemoryBytes, selection.deviceType == 2, new VulkanMemoryAllocator(device), timelineSemaphoreSupported ? VulkanInProcessManager.createTimelineSemaphore(device, stack) : 0L);
                created.programs.put("vector_add", VulkanInProcessManager.createProgram(created, "vector_add", VECTOR_ADD_SHADER_RESOURCE, 3, 4));
                created.programs.put("matrix_multiply", VulkanInProcessManager.createProgram(created, "matrix_multiply", MATRIX_MULTIPLY_SHADER_RESOURCE, 3, 12));
                created.programs.put("monte_carlo_pi", VulkanInProcessManager.createProgram(created, "monte_carlo_pi", MONTE_CARLO_PI_SHADER_RESOURCE, 1, 4));
                created.programs.put("terrain_generation", VulkanInProcessManager.createProgram(created, "terrain_generation", TERRAIN_GENERATION_SHADER_RESOURCE, 2, 4));
                created.programs.put("terrain_generation_legacy", VulkanInProcessManager.createProgram(created, "terrain_generation_legacy", TERRAIN_GENERATION_LEGACY_SHADER_RESOURCE, 2, 4));
                state = created;
                if (stack == null) break block12;
            }
            catch (Throwable throwable) {
                try {
                    if (stack != null) {
                        try {
                            stack.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                catch (Throwable throwable3) {
                    try {
                        VK10.vkDestroyInstance((VkInstance)instance, null);
                    }
                    catch (Throwable throwable4) {
                        // empty catch block
                    }
                    throw throwable3;
                }
            }
            stack.close();
        }
        return state;
    }

    private static PhysicalSelection pickPhysicalDevice(VkInstance instance) {
        List<PhysicalSelection> selections = VulkanInProcessManager.enumeratePhysicalDevices(instance);
        if (selections.isEmpty()) {
            throw new IllegalStateException("No Vulkan device with a compute queue found");
        }
        String preferred = PREFERRED_DEVICE.get();
        if (preferred != null && !preferred.isBlank()) {
            for (PhysicalSelection selection : selections) {
                if (!VulkanInProcessManager.matchesDevicePreference(selection, preferred)) continue;
                return selection;
            }
            throw new IllegalStateException("Preferred Vulkan device not found: " + preferred);
        }
        return (PhysicalSelection)VulkanInProcessManager.autoSelectCandidates(selections).stream().max((left, right) -> Double.compare(left.score(), right.score())).orElseThrow(() -> new IllegalStateException("No Vulkan device with a compute queue found"));
    }

    private static boolean supportsTimelineSemaphores(VkPhysicalDevice physicalDevice, int apiVersion, MemoryStack stack) {
        if (VulkanInProcessManager.compareApiVersion(apiVersion, 1, 2, 0) < 0) {
            return false;
        }
        VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc((MemoryStack)stack).sType(51);
        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc((MemoryStack)stack).sType(1000059000).pNext(features12.address());
        VK11.vkGetPhysicalDeviceFeatures2((VkPhysicalDevice)physicalDevice, (VkPhysicalDeviceFeatures2)features2);
        return features12.timelineSemaphore();
    }

    private static long createTimelineSemaphore(VkDevice device, MemoryStack stack) {
        VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc((MemoryStack)stack).sType(1000207002).semaphoreType(1).initialValue(0L);
        VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc((MemoryStack)stack).sType(9).pNext(typeInfo.address());
        LongBuffer semaphorePtr = stack.mallocLong(1);
        VulkanInProcessManager.checkVk(VK10.vkCreateSemaphore((VkDevice)device, (VkSemaphoreCreateInfo)semaphoreInfo, null, (LongBuffer)semaphorePtr), "vkCreateSemaphore");
        return semaphorePtr.get(0);
    }

    private static List<PhysicalSelection> enumeratePhysicalDevices(VkInstance instance) {
        try (MemoryStack stack = MemoryStack.stackPush();){
            IntBuffer deviceCount = stack.ints(0);
            VulkanInProcessManager.checkVk(VK10.vkEnumeratePhysicalDevices((VkInstance)instance, (IntBuffer)deviceCount, null), "vkEnumeratePhysicalDevices");
            if (deviceCount.get(0) <= 0) {
                List<PhysicalSelection> list = List.of();
                return list;
            }
            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            VulkanInProcessManager.checkVk(VK10.vkEnumeratePhysicalDevices((VkInstance)instance, (IntBuffer)deviceCount, (PointerBuffer)devices), "vkEnumeratePhysicalDevices");
            ArrayList<PhysicalSelection> selections = new ArrayList<PhysicalSelection>(devices.capacity());
            for (int i = 0; i < devices.capacity(); ++i) {
                long physicalDeviceHandle = devices.get(i);
                VkPhysicalDevice physicalDevice = new VkPhysicalDevice(physicalDeviceHandle, instance);
                int computeQueueFamily = VulkanInProcessManager.findComputeQueueFamily(physicalDevice, stack);
                if (computeQueueFamily < 0) continue;
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc((MemoryStack)stack);
                VK10.vkGetPhysicalDeviceProperties((VkPhysicalDevice)physicalDevice, (VkPhysicalDeviceProperties)properties);
                String rawDeviceName = properties.deviceNameString();
                int deviceType = properties.deviceType();
                int vendorId = properties.vendorID();
                String vendorName = VulkanInProcessManager.vendorName(vendorId, rawDeviceName);
                long localMemoryBytes = VulkanInProcessManager.queryDeviceLocalMemory(physicalDevice, stack);
                int maxComputeInvocations = properties.limits().maxComputeWorkGroupInvocations();
                int maxComputeSharedMemoryBytes = properties.limits().maxComputeSharedMemorySize();
                boolean softwareAdapter = VulkanInProcessManager.isSoftwareAdapter(rawDeviceName, vendorName, deviceType);
                String deviceName = VulkanInProcessManager.sanitizeDeviceName(rawDeviceName);
                double score = VulkanInProcessManager.scoreDevice(deviceType, vendorName, localMemoryBytes, maxComputeInvocations, maxComputeSharedMemoryBytes, softwareAdapter);
                String deviceId = VulkanInProcessManager.buildDeviceId(vendorName, deviceName);
                selections.add(new PhysicalSelection(physicalDeviceHandle, computeQueueFamily, deviceName, vendorName, deviceId, deviceType, localMemoryBytes, softwareAdapter, score));
            }
            List<PhysicalSelection> list = VulkanInProcessManager.dedupeSelections(selections);
            return list;
        }
    }

    private static int findComputeQueueFamily(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties((VkPhysicalDevice)device, (IntBuffer)count, null);
        if (count.get(0) <= 0) {
            return -1;
        }
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc((int)count.get(0), (MemoryStack)stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties((VkPhysicalDevice)device, (IntBuffer)count, (VkQueueFamilyProperties.Buffer)families);
        for (int i = 0; i < families.capacity(); ++i) {
            if ((((VkQueueFamilyProperties)families.get(i)).queueFlags() & 2) == 0) continue;
            return i;
        }
        return -1;
    }

    private static long queryDeviceLocalMemory(VkPhysicalDevice physicalDevice, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc((MemoryStack)stack);
        VK10.vkGetPhysicalDeviceMemoryProperties((VkPhysicalDevice)physicalDevice, (VkPhysicalDeviceMemoryProperties)properties);
        long bytes = 0L;
        for (int i = 0; i < properties.memoryHeapCount(); ++i) {
            if ((properties.memoryHeaps(i).flags() & 1) == 0) continue;
            bytes += properties.memoryHeaps(i).size();
        }
        return bytes;
    }

    private static FloatControlSummary queryFloatControls(VkPhysicalDevice physicalDevice, int apiVersion, MemoryStack stack) {
        if (apiVersion < VK12.VK_API_VERSION_1_2) {
            return FloatControlSummary.unavailable("Vulkan API < 1.2");
        }
        VkPhysicalDeviceFloatControlsProperties floatControls = VkPhysicalDeviceFloatControlsProperties.calloc((MemoryStack)stack).sType(1000197000);
        VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc((MemoryStack)stack).sType(1000059001).pNext(floatControls.address());
        VK11.vkGetPhysicalDeviceProperties2((VkPhysicalDevice)physicalDevice, (VkPhysicalDeviceProperties2)properties2);
        return new FloatControlSummary(true, floatControls.shaderRoundingModeRTEFloat32(), floatControls.shaderSignedZeroInfNanPreserveFloat32(), floatControls.shaderDenormPreserveFloat32(), floatControls.shaderDenormFlushToZeroFloat32(), floatControls.denormBehaviorIndependence(), floatControls.roundingModeIndependence(), null);
    }

    private static VkInstance createInstance(int apiVersion) {
        LwjglRuntimeTuning.ensureConfigured();
        LwjglRuntimeTuning.ensureThreadLocalStack();
        return VulkanLoaderIsolation.runWithImplicitLayersDisabled(LOGGER, "Vulkan instance creation", () -> {
            VkInstance vkInstance;
            ByteBuffer engineName;
            ByteBuffer applicationName;
            VkInstanceCreateInfo instanceInfo;
            VkApplicationInfo appInfo;
            block11: {
                appInfo = null;
                instanceInfo = null;
                applicationName = null;
                engineName = null;
                PointerBuffer instancePtr = null;
                try {
                    applicationName = MemoryUtil.memUTF8((CharSequence)"Quantified");
                    engineName = MemoryUtil.memUTF8((CharSequence)"QuantifiedVulkan");
                    appInfo = VkApplicationInfo.calloc().sType(0).pApplicationName(applicationName).applicationVersion(VK10.VK_MAKE_VERSION((int)1, (int)0, (int)0)).pEngineName(engineName).engineVersion(VK10.VK_MAKE_VERSION((int)1, (int)0, (int)0)).apiVersion(apiVersion);
                    instanceInfo = VkInstanceCreateInfo.calloc().sType(1).pApplicationInfo(appInfo);
                    instancePtr = MemoryUtil.memAllocPointer((int)1);
                    VulkanInProcessManager.checkVk(VK10.vkCreateInstance((VkInstanceCreateInfo)instanceInfo, null, (PointerBuffer)instancePtr), "vkCreateInstance");
                    try (MemoryStack ignored = LwjglRuntimeTuning.pushMemoryStack();){
                        vkInstance = new VkInstance(instancePtr.get(0), instanceInfo);
                    }
                    if (instancePtr == null) break block11;
                }
                catch (Throwable throwable) {
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
                        MemoryUtil.memFree((Buffer)engineName);
                    }
                    if (applicationName != null) {
                        MemoryUtil.memFree((Buffer)applicationName);
                    }
                    throw throwable;
                }
                MemoryUtil.memFree((CustomBuffer)instancePtr);
            }
            if (instanceInfo != null) {
                instanceInfo.free();
            }
            if (appInfo != null) {
                appInfo.free();
            }
            if (engineName != null) {
                MemoryUtil.memFree((Buffer)engineName);
            }
            if (applicationName != null) {
                MemoryUtil.memFree((Buffer)applicationName);
            }
            return vkInstance;
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
        if (VulkanInProcessManager.compareApiVersion(runtime.maxApiVersion(), 1, 3, 0) >= 0) {
            return VK10.VK_MAKE_VERSION((int)1, (int)3, (int)0);
        }
        if (VulkanInProcessManager.compareApiVersion(runtime.maxApiVersion(), 1, 2, 0) >= 0) {
            return VK10.VK_MAKE_VERSION((int)1, (int)2, (int)0);
        }
        return 0;
    }

    private static int compareApiVersion(int apiVersion, int major, int minor, int patch) {
        return Integer.compareUnsigned(apiVersion, VK10.VK_MAKE_VERSION((int)major, (int)minor, (int)patch));
    }

    private static String normalizeDevicePreference(String preferredDevice) {
        if (preferredDevice == null) {
            return null;
        }
        String trimmed = preferredDevice.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean matchesDevicePreference(PhysicalSelection selection, String preference) {
        String normalizedPreference = VulkanInProcessManager.normalizeDeviceKey(preference);
        return VulkanInProcessManager.normalizeDeviceKey(selection.deviceName()).equals(normalizedPreference) || VulkanInProcessManager.normalizeDeviceKey(selection.vendorName() + " " + selection.deviceName()).equals(normalizedPreference) || VulkanInProcessManager.normalizeDeviceKey(selection.id()).equals(normalizedPreference) || VulkanInProcessManager.normalizeDeviceKey(selection.deviceName()).contains(normalizedPreference) || VulkanInProcessManager.normalizeDeviceKey(selection.vendorName() + " " + selection.deviceName()).contains(normalizedPreference);
    }

    private static String normalizeDeviceKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String buildDeviceId(String vendorName, String deviceName) {
        String combined = ((vendorName != null ? vendorName : "") + "-" + (deviceName != null ? deviceName : "")).trim();
        String normalized = VulkanInProcessManager.normalizeDeviceKey(combined);
        return normalized.isBlank() ? "unknown-vulkan-device" : normalized;
    }

    private static String sanitizeDeviceName(String deviceName) {
        if (deviceName == null) {
            return "Unknown Vulkan Device";
        }
        String sanitized = deviceName.replaceAll("(?i)\\bDirect3D12\\b", "").replaceAll("(?i)\\bD3D12\\b", "").replaceAll("(?i)\\bDX12\\b", "").replaceAll("\\s+", " ").trim();
        return sanitized.isEmpty() ? deviceName.trim() : sanitized;
    }

    private static List<PhysicalSelection> autoSelectCandidates(List<PhysicalSelection> selections) {
        List<PhysicalSelection> discrete = selections.stream().filter(selection -> !selection.softwareAdapter()).filter(selection -> selection.deviceType() == 2).toList();
        return discrete.isEmpty() ? selections : discrete;
    }

    private static List<PhysicalSelection> dedupeSelections(List<PhysicalSelection> selections) {
        LinkedHashMap<String, PhysicalSelection> unique = new LinkedHashMap<String, PhysicalSelection>();
        for (PhysicalSelection selection : selections) {
            String key = VulkanInProcessManager.normalizeDeviceKey(selection.deviceName());
            PhysicalSelection existing = (PhysicalSelection)unique.get(key);
            if (existing != null && !VulkanInProcessManager.preferSelection(selection, existing)) continue;
            unique.put(key, selection);
        }
        return new ArrayList<PhysicalSelection>(unique.values());
    }

    private static List<VulkanDeviceInfo> dedupeDeviceInfos(List<VulkanDeviceInfo> devices) {
        LinkedHashMap<String, VulkanDeviceInfo> unique = new LinkedHashMap<String, VulkanDeviceInfo>();
        for (VulkanDeviceInfo device : devices) {
            String key = VulkanInProcessManager.normalizeDeviceKey(device.name());
            VulkanDeviceInfo existing = (VulkanDeviceInfo)unique.get(key);
            if (existing != null && !VulkanInProcessManager.preferDeviceInfo(device, existing)) continue;
            unique.put(key, device);
        }
        return new ArrayList<VulkanDeviceInfo>(unique.values());
    }

    private static boolean preferSelection(PhysicalSelection selection, PhysicalSelection existing) {
        if (selection.softwareAdapter() != existing.softwareAdapter()) {
            return !selection.softwareAdapter();
        }
        if (selection.deviceType() != existing.deviceType()) {
            return VulkanInProcessManager.deviceTypeRank(selection.deviceType()) > VulkanInProcessManager.deviceTypeRank(existing.deviceType());
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
            return VulkanInProcessManager.deviceTypeRank(device.deviceType()) > VulkanInProcessManager.deviceTypeRank(existing.deviceType());
        }
        if (device.localMemoryBytes() != existing.localMemoryBytes()) {
            return device.localMemoryBytes() > existing.localMemoryBytes();
        }
        return device.id().compareTo(existing.id()) > 0;
    }

    private static int deviceTypeRank(int deviceType) {
        return switch (deviceType) {
            case 2 -> 4;
            case 1 -> 3;
            case 3 -> 2;
            default -> 1;
        };
    }

    private static String vendorName(int vendorId, String deviceName) {
        return switch (vendorId) {
            case 4318 -> "NVIDIA";
            case 4098, 4130 -> "AMD";
            case 32902 -> "Intel";
            case 5140 -> "Microsoft";
            default -> VulkanInProcessManager.inferVendorFromName(deviceName);
        };
    }

    private static String inferVendorFromName(String deviceName) {
        String lower;
        String string = lower = deviceName != null ? deviceName.toLowerCase(Locale.ROOT) : "";
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
        String lowerName = deviceName != null ? deviceName.toLowerCase(Locale.ROOT) : "";
        String lowerVendor = vendorName != null ? vendorName.toLowerCase(Locale.ROOT) : "";
        return deviceType == 4 || lowerVendor.contains("microsoft") || lowerName.contains("microsoft basic render") || lowerName.contains("direct3d12") || lowerName.contains("swiftshader") || lowerName.contains("llvmpipe") || lowerName.contains("lavapipe") || lowerName.contains("dozen") || lowerName.contains("d3d12");
    }

    private static double scoreDevice(int deviceType, String vendorName, long localMemoryBytes, int maxComputeInvocations, int maxComputeSharedMemoryBytes, boolean softwareAdapter) {
        if (softwareAdapter) {
            return -1.0E15 + (double)localMemoryBytes;
        }
        double typeScore = switch (deviceType) {
            case 2 -> 1.0E15;
            case 1 -> 1.0E14;
            case 3 -> 1.0E13;
            default -> 1.0E12;
        };
        double vendorScore = VulkanInProcessManager.isDedicatedGpuVendor(vendorName) ? 1.0E13 : 0.0;
        double computeScore = (double)Math.max(0, maxComputeInvocations) * 1.0E9 + (double)Math.max(0, maxComputeSharedMemoryBytes) * 1000000.0;
        return typeScore + vendorScore + computeScore + (double)localMemoryBytes;
    }

    private static boolean isDedicatedGpuVendor(String vendorName) {
        String vendor = vendorName != null ? vendorName.toLowerCase(Locale.ROOT) : "";
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
        ByteBuffer spirv = VulkanInProcessManager.loadShaderBinary(name, resourcePath);
        try {
            return VulkanInProcessManager.createProgramFromBuffer(state, name, spirv, storageBufferCount, pushConstantBytes);
        }
        finally {
            MemoryUtil.memFree((Buffer)spirv);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static Program createProgramFromBytes(State state, String name, byte[] spirv, int storageBufferCount, int pushConstantBytes) {
        ByteBuffer buffer = MemoryUtil.memAlloc((int)spirv.length);
        try {
            buffer.put(spirv).flip();
            Program program = VulkanInProcessManager.createProgramFromBuffer(state, name, buffer, storageBufferCount, pushConstantBytes);
            return program;
        }
        finally {
            MemoryUtil.memFree((Buffer)buffer);
        }
    }

    private static Program createProgramFromBuffer(State state, String name, ByteBuffer spirv, int storageBufferCount, int pushConstantBytes) {
        long shaderModule = 0L;
        long descriptorSetLayout = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = LwjglRuntimeTuning.pushMemoryStack();){
            LongBuffer handlePtr = stack.mallocLong(1);
            VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc((MemoryStack)stack).sType(16).pCode(spirv);
            VulkanInProcessManager.checkVk(VK10.vkCreateShaderModule((VkDevice)state.device, (VkShaderModuleCreateInfo)shaderInfo, null, (LongBuffer)handlePtr), "vkCreateShaderModule:" + name);
            shaderModule = handlePtr.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc((int)storageBufferCount, (MemoryStack)stack);
            for (int i = 0; i < storageBufferCount; ++i) {
                ((VkDescriptorSetLayoutBinding)bindings.get(i)).binding(i).descriptorType(7).descriptorCount(1).stageFlags(32);
            }
            VkDescriptorSetLayoutCreateInfo descriptorLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc((MemoryStack)stack).sType(32).pBindings(bindings);
            VulkanInProcessManager.checkVk(VK10.vkCreateDescriptorSetLayout((VkDevice)state.device, (VkDescriptorSetLayoutCreateInfo)descriptorLayoutInfo, null, (LongBuffer)handlePtr), "vkCreateDescriptorSetLayout:" + name);
            descriptorSetLayout = handlePtr.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc((MemoryStack)stack).sType(30).pSetLayouts(stack.longs(descriptorSetLayout));
            if (pushConstantBytes > 0) {
                VkPushConstantRange.Buffer pushRanges = VkPushConstantRange.calloc((int)1, (MemoryStack)stack).stageFlags(32).offset(0).size(pushConstantBytes);
                pipelineLayoutInfo.pPushConstantRanges(pushRanges);
            }
            VulkanInProcessManager.checkVk(VK10.vkCreatePipelineLayout((VkDevice)state.device, (VkPipelineLayoutCreateInfo)pipelineLayoutInfo, null, (LongBuffer)handlePtr), "vkCreatePipelineLayout:" + name);
            pipelineLayout = handlePtr.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc((MemoryStack)stack).sType(18).stage(32).module(shaderModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfos = VkComputePipelineCreateInfo.calloc((int)1, (MemoryStack)stack);
            ((VkComputePipelineCreateInfo)pipelineInfos.get(0)).sType(28).stage(stageInfo).layout(pipelineLayout);
            VulkanInProcessManager.checkVk(VK10.vkCreateComputePipelines((VkDevice)state.device, (long)0L, (VkComputePipelineCreateInfo.Buffer)pipelineInfos, null, (LongBuffer)handlePtr), "vkCreateComputePipelines:" + name);
            pipeline = handlePtr.get(0);

            Program program = new Program(name, pipeline, pipelineLayout, descriptorSetLayout, storageBufferCount, pushConstantBytes);
            VK10.vkDestroyShaderModule((VkDevice)state.device, (long)shaderModule, null);
            shaderModule = 0L;
            return program;
        }
        catch (Throwable throwable) {
            if (pipeline != 0L) {
                VK10.vkDestroyPipeline((VkDevice)state.device, (long)pipeline, null);
            }
            if (pipelineLayout != 0L) {
                VK10.vkDestroyPipelineLayout((VkDevice)state.device, (long)pipelineLayout, null);
            }
            if (descriptorSetLayout != 0L) {
                VK10.vkDestroyDescriptorSetLayout((VkDevice)state.device, (long)descriptorSetLayout, null);
            }
            if (shaderModule != 0L) {
                VK10.vkDestroyShaderModule((VkDevice)state.device, (long)shaderModule, null);
            }
            throw throwable;
        }
    }

    private static WorkspaceLease acquireWorkspace(State state, Program program, String workspaceKey, WorkloadProfile workloadProfile, int groupCountX, int groupCountY, int groupCountZ, int inputBufferCount, int[] pushConstants, long ... bufferSizes) {
        DispatchWorkspacePool pool;
        synchronized (state.workspaceMutex) {
            pool = state.workspaces.get(workspaceKey);
            if (pool == null || pool.isClosed()) {
                pool = VulkanInProcessManager.createWorkspacePool(state, program, workloadProfile, groupCountX, groupCountY, groupCountZ, inputBufferCount, pushConstants, bufferSizes);
                state.workspaces.put(workspaceKey, pool);
            }
        }
        return new WorkspaceLease(pool, pool.borrow());
    }

    float[] executeSpirv(String programKey, byte[] spirv, int storageBufferCount,
                         int pushConstantBytes, float[][] inputBuffers, int outputFloatCount,
                         int[] pushConstants, int groupCountX, int groupCountY, int groupCountZ) {
        Objects.requireNonNull(programKey, "programKey");
        Objects.requireNonNull(spirv, "spirv");
        Objects.requireNonNull(inputBuffers, "inputBuffers");
        Objects.requireNonNull(pushConstants, "pushConstants");
        if (programKey.isBlank()) {
            throw new IllegalArgumentException("programKey must not be blank");
        }
        if (spirv.length < 4 || spirv.length > 16 * 1024 * 1024 || (spirv.length & 3) != 0
                || (spirv[0] & 0xff) != 0x03 || (spirv[1] & 0xff) != 0x02
                || (spirv[2] & 0xff) != 0x23 || (spirv[3] & 0xff) != 0x07) {
            throw new IllegalArgumentException("spirv must contain a valid little-endian SPIR-V module");
        }
        if (storageBufferCount < 1 || storageBufferCount > 32
                || storageBufferCount != inputBuffers.length + 1) {
            throw new IllegalArgumentException("storageBufferCount must equal inputBuffers.length + 1 and be between 1 and 32");
        }
        if (pushConstantBytes < 0 || pushConstantBytes > 128 || (pushConstantBytes & 3) != 0
                || pushConstantBytes != pushConstants.length * Integer.BYTES) {
            throw new IllegalArgumentException("pushConstantBytes must match pushConstants and be 4-byte aligned");
        }
        if (outputFloatCount <= 0 || groupCountX <= 0 || groupCountY <= 0 || groupCountZ <= 0) {
            throw new IllegalArgumentException("outputFloatCount and dispatch group counts must be positive");
        }

        long[] bufferSizes = new long[storageBufferCount];
        long totalBytes = (long) outputFloatCount * Float.BYTES;
        for (int i = 0; i < inputBuffers.length; ++i) {
            float[] input = Objects.requireNonNull(inputBuffers[i], "inputBuffers[" + i + "]");
            if (input.length == 0) {
                throw new IllegalArgumentException("input buffers must not be empty");
            }
            bufferSizes[i] = (long) input.length * Float.BYTES;
            totalBytes = Math.addExact(totalBytes, bufferSizes[i]);
        }
        bufferSizes[storageBufferCount - 1] = (long) outputFloatCount * Float.BYTES;
        if (totalBytes > MAX_ACCEPTED_VRAM_BYTES) {
            throw new IllegalArgumentException("custom dispatch buffer footprint exceeds the 512 MiB safety limit");
        }

        State local = this.requireState();
        String digest = sha256Hex(spirv);
        String internalProgramKey = "custom:" + programKey + ':' + digest + ':' + storageBufferCount + ':' + pushConstantBytes;
        Program program;
        synchronized (local.workspaceMutex) {
            program = local.programs.get(internalProgramKey);
            if (program == null) {
                long customPrograms = local.programs.keySet().stream().filter(key -> key.startsWith("custom:")).count();
                if (customPrograms >= MAX_CUSTOM_PROGRAMS) {
                    throw new IllegalStateException("custom SPIR-V program limit reached (" + MAX_CUSTOM_PROGRAMS + ")");
                }
                program = createProgramFromBytes(local, internalProgramKey, spirv, storageBufferCount, pushConstantBytes);
                local.programs.put(internalProgramKey, program);
            }
        }

        StringBuilder workspaceKey = new StringBuilder(internalProgramKey)
                .append(':').append(groupCountX).append('x').append(groupCountY).append('x').append(groupCountZ)
                .append(':').append(outputFloatCount).append(':').append(java.util.Arrays.hashCode(pushConstants));
        for (float[] input : inputBuffers) {
            workspaceKey.append(':').append(input.length);
        }
        try (WorkspaceLease lease = acquireWorkspace(local, program, workspaceKey.toString(), WorkloadProfile.COMPUTE_DENSE,
                groupCountX, groupCountY, groupCountZ, inputBuffers.length, pushConstants, bufferSizes)) {
            DispatchWorkspace workspace = lease.workspace();
            for (int i = 0; i < inputBuffers.length; ++i) {
                writeFloatArray(workspace.buffers[i], inputBuffers[i]);
            }
            dispatch(local, workspace);
            return readFloatArray(workspace.buffers[storageBufferCount - 1], outputFloatCount);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static DispatchWorkspacePool createWorkspacePool(State state, Program program, WorkloadProfile workloadProfile, int groupCountX, int groupCountY, int groupCountZ, int inputBufferCount, int[] pushConstants, long ... bufferSizes) {
        DispatchWorkspace[] workspaces = new DispatchWorkspace[3];
        long workspaceBytes = 0L;
        for (long bufferSize : bufferSizes) {
            workspaceBytes += Math.max(0L, bufferSize);
        }
        try {
            for (int i = 0; i < workspaces.length; ++i) {
                workspaces[i] = VulkanInProcessManager.createWorkspace(state, program, workloadProfile, groupCountX, groupCountY, groupCountZ, inputBufferCount, pushConstants, bufferSizes);
            }
            return new DispatchWorkspacePool(workspaces, workspaceBytes * workspaces.length);
        }
        catch (Throwable throwable) {
            for (DispatchWorkspace workspace : workspaces) {
                VulkanInProcessManager.destroyWorkspace(state, workspace);
            }
            throw throwable;
        }
    }

    private static DispatchWorkspace createWorkspace(State state, Program program, WorkloadProfile workloadProfile, int groupCountX, int groupCountY, int groupCountZ, int inputBufferCount, int[] pushConstants, long ... bufferSizes) {
        try (MemoryStack stack = MemoryStack.stackPush();){
            AllocatedBuffer[] buffers = new AllocatedBuffer[bufferSizes.length];
            for (int i = 0; i < bufferSizes.length; ++i) {
                BufferRole role = i < inputBufferCount ? BufferRole.INPUT : BufferRole.OUTPUT;
                buffers[i] = VulkanInProcessManager.createStorageBuffer(state, workloadProfile, role, bufferSizes[i]);
            }
            LongBuffer descriptorPoolPtr = stack.mallocLong(1);
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc((int)1, (MemoryStack)stack).type(7).descriptorCount(program.storageBufferCount);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc((MemoryStack)stack).sType(33).pPoolSizes(poolSize).maxSets(1);
            VulkanInProcessManager.checkVk(VK10.vkCreateDescriptorPool((VkDevice)state.device, (VkDescriptorPoolCreateInfo)poolInfo, null, (LongBuffer)descriptorPoolPtr), "vkCreateDescriptorPool");
            long descriptorPool = descriptorPoolPtr.get(0);
            try {
                LongBuffer descriptorSetPtr = stack.mallocLong(1);
                VkDescriptorSetAllocateInfo descriptorAlloc = VkDescriptorSetAllocateInfo.calloc((MemoryStack)stack).sType(34).descriptorPool(descriptorPool).pSetLayouts(stack.longs(program.descriptorSetLayout));
                VulkanInProcessManager.checkVk(VK10.vkAllocateDescriptorSets((VkDevice)state.device, (VkDescriptorSetAllocateInfo)descriptorAlloc, (LongBuffer)descriptorSetPtr), "vkAllocateDescriptorSets");
                long descriptorSet = descriptorSetPtr.get(0);
                VkDescriptorBufferInfo.Buffer bufferInfos = VkDescriptorBufferInfo.calloc((int)buffers.length, (MemoryStack)stack);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc((int)buffers.length, (MemoryStack)stack);
                for (int i = 0; i < buffers.length; ++i) {
                    ((VkDescriptorBufferInfo)bufferInfos.get(i)).buffer(buffers[i].descriptorBuffer()).offset(0L).range(buffers[i].sizeBytes);
                    ((VkWriteDescriptorSet)writes.get(i)).sType(35).dstSet(descriptorSet).dstBinding(i).descriptorType(7).descriptorCount(1).pBufferInfo((VkDescriptorBufferInfo.Buffer)VkDescriptorBufferInfo.calloc((int)1, (MemoryStack)stack).put(0, (VkDescriptorBufferInfo)bufferInfos.get(i)));
                }
                VK10.vkUpdateDescriptorSets((VkDevice)state.device, (VkWriteDescriptorSet.Buffer)writes, null);
                PointerBuffer commandBufferPtr = stack.mallocPointer(1);
                VkCommandBufferAllocateInfo commandAlloc = VkCommandBufferAllocateInfo.calloc((MemoryStack)stack).sType(40).commandPool(state.commandPool).level(0).commandBufferCount(1);
                VulkanInProcessManager.checkVk(VK10.vkAllocateCommandBuffers((VkDevice)state.device, (VkCommandBufferAllocateInfo)commandAlloc, (PointerBuffer)commandBufferPtr), "vkAllocateCommandBuffers");
                VkCommandBuffer commandBuffer = new VkCommandBuffer(commandBufferPtr.get(0), state.device);
                LongBuffer fencePtr = stack.mallocLong(1);
                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc((MemoryStack)stack).sType(8);
                VulkanInProcessManager.checkVk(VK10.vkCreateFence((VkDevice)state.device, (VkFenceCreateInfo)fenceInfo, null, (LongBuffer)fencePtr), "vkCreateFence");
                long fence = fencePtr.get(0);
                DispatchWorkspace workspace = new DispatchWorkspace(program, descriptorPool, descriptorSet, commandBuffer, fence, groupCountX, groupCountY, groupCountZ, inputBufferCount, buffers);
                VulkanInProcessManager.recordWorkspaceCommandBuffer(workspace, pushConstants);
                DispatchWorkspace dispatchWorkspace = workspace;
                return dispatchWorkspace;
            }
            catch (Throwable throwable) {
                try {
                    VK10.vkDestroyDescriptorPool((VkDevice)state.device, (long)descriptorPool, null);
                    throw throwable;
                }
                catch (Throwable throwable2) {
                    for (AllocatedBuffer buffer : buffers) {
                        VulkanInProcessManager.destroyBuffer(state, buffer);
                    }
                    throw throwable2;
                }
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void recordWorkspaceCommandBuffer(DispatchWorkspace workspace, int[] pushConstants) {
        try (MemoryStack stack = MemoryStack.stackPush();){
            AllocatedBuffer buffer;
            int i;
            AllocatedBuffer buffer2;
            int i2;
            int barrierIndex;
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc((MemoryStack)stack).sType(42);
            VulkanInProcessManager.checkVk(VK10.vkBeginCommandBuffer((VkCommandBuffer)workspace.commandBuffer, (VkCommandBufferBeginInfo)beginInfo), "vkBeginCommandBuffer");
            boolean hasStagedInputs = false;
            for (int i3 = 0; i3 < workspace.inputBufferCount; ++i3) {
                if (!workspace.buffers[i3].staged) continue;
                hasStagedInputs = true;
                VK10.vkCmdCopyBuffer((VkCommandBuffer)workspace.commandBuffer, (long)workspace.buffers[i3].hostBuffer, (long)workspace.buffers[i3].deviceBuffer, (VkBufferCopy.Buffer)VkBufferCopy.calloc((int)1, (MemoryStack)stack).srcOffset(0L).dstOffset(0L).size(workspace.buffers[i3].sizeBytes));
            }
            int stagedInputCount = 0;
            int hostInputCount = 0;
            for (int i4 = 0; i4 < workspace.inputBufferCount; ++i4) {
                if (workspace.buffers[i4].staged) {
                    ++stagedInputCount;
                    continue;
                }
                ++hostInputCount;
            }
            if (hostInputCount > 0) {
                VkBufferMemoryBarrier.Buffer hostInputBarriers = VkBufferMemoryBarrier.calloc((int)hostInputCount, (MemoryStack)stack);
                barrierIndex = 0;
                for (i2 = 0; i2 < workspace.inputBufferCount; ++i2) {
                    buffer2 = workspace.buffers[i2];
                    if (buffer2.staged) continue;
                    ((VkBufferMemoryBarrier)hostInputBarriers.get(barrierIndex++)).sType(44).srcAccessMask(16384).dstAccessMask(32).srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).buffer(buffer2.hostBuffer).offset(0L).size(buffer2.sizeBytes);
                }
                VK10.vkCmdPipelineBarrier((VkCommandBuffer)workspace.commandBuffer, (int)16384, (int)2048, (int)0, null, (VkBufferMemoryBarrier.Buffer)hostInputBarriers, null);
            }
            if (stagedInputCount > 0) {
                VkBufferMemoryBarrier.Buffer uploadBarriers = VkBufferMemoryBarrier.calloc((int)stagedInputCount, (MemoryStack)stack);
                barrierIndex = 0;
                for (i2 = 0; i2 < workspace.inputBufferCount; ++i2) {
                    buffer2 = workspace.buffers[i2];
                    if (!buffer2.staged) continue;
                    ((VkBufferMemoryBarrier)uploadBarriers.get(barrierIndex++)).sType(44).srcAccessMask(4096).dstAccessMask(32).srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).buffer(buffer2.deviceBuffer).offset(0L).size(buffer2.sizeBytes);
                }
                VK10.vkCmdPipelineBarrier((VkCommandBuffer)workspace.commandBuffer, (int)4096, (int)2048, (int)0, null, (VkBufferMemoryBarrier.Buffer)uploadBarriers, null);
            }
            VK10.vkCmdBindPipeline((VkCommandBuffer)workspace.commandBuffer, (int)1, (long)workspace.program.pipeline);
            VK10.vkCmdBindDescriptorSets((VkCommandBuffer)workspace.commandBuffer, (int)1, (long)workspace.program.pipelineLayout, (int)0, (LongBuffer)stack.longs(workspace.descriptorSet), null);
            if (pushConstants != null && pushConstants.length > 0) {
                ByteBuffer pushBuffer = VulkanInProcessManager.intsToBytes(pushConstants);
                try {
                    VK10.vkCmdPushConstants((VkCommandBuffer)workspace.commandBuffer, (long)workspace.program.pipelineLayout, (int)32, (int)0, (ByteBuffer)pushBuffer);
                }
                finally {
                    MemoryUtil.memFree((Buffer)pushBuffer);
                }
            }
            VK10.vkCmdDispatch((VkCommandBuffer)workspace.commandBuffer, (int)Math.max(1, workspace.groupCountX), (int)Math.max(1, workspace.groupCountY), (int)Math.max(1, workspace.groupCountZ));
            int stagedOutputCount = 0;
            int hostOutputCount = 0;
            for (i2 = workspace.inputBufferCount; i2 < workspace.buffers.length; ++i2) {
                if (workspace.buffers[i2].staged) {
                    ++stagedOutputCount;
                    continue;
                }
                ++hostOutputCount;
            }
            if (stagedOutputCount > 0) {
                VkBufferMemoryBarrier.Buffer stagedOutputBarriers = VkBufferMemoryBarrier.calloc((int)stagedOutputCount, (MemoryStack)stack);
                int barrierIndex2 = 0;
                for (i = workspace.inputBufferCount; i < workspace.buffers.length; ++i) {
                    buffer = workspace.buffers[i];
                    if (!buffer.staged) continue;
                    ((VkBufferMemoryBarrier)stagedOutputBarriers.get(barrierIndex2++)).sType(44).srcAccessMask(64).dstAccessMask(2048).srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).buffer(buffer.deviceBuffer).offset(0L).size(buffer.sizeBytes);
                }
                VK10.vkCmdPipelineBarrier((VkCommandBuffer)workspace.commandBuffer, (int)2048, (int)4096, (int)0, null, (VkBufferMemoryBarrier.Buffer)stagedOutputBarriers, null);
            }
            if (hostOutputCount > 0) {
                VkBufferMemoryBarrier.Buffer hostOutputBarriers = VkBufferMemoryBarrier.calloc((int)hostOutputCount, (MemoryStack)stack);
                int barrierIndex3 = 0;
                for (i = workspace.inputBufferCount; i < workspace.buffers.length; ++i) {
                    buffer = workspace.buffers[i];
                    if (buffer.staged) continue;
                    ((VkBufferMemoryBarrier)hostOutputBarriers.get(barrierIndex3++)).sType(44).srcAccessMask(64).dstAccessMask(8192).srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).buffer(buffer.hostBuffer).offset(0L).size(buffer.sizeBytes);
                }
                VK10.vkCmdPipelineBarrier((VkCommandBuffer)workspace.commandBuffer, (int)2048, (int)16384, (int)0, null, (VkBufferMemoryBarrier.Buffer)hostOutputBarriers, null);
            }
            if (stagedOutputCount > 0) {
                for (int i5 = workspace.inputBufferCount; i5 < workspace.buffers.length; ++i5) {
                    if (!workspace.buffers[i5].staged) continue;
                    VK10.vkCmdCopyBuffer((VkCommandBuffer)workspace.commandBuffer, (long)workspace.buffers[i5].deviceBuffer, (long)workspace.buffers[i5].hostBuffer, (VkBufferCopy.Buffer)VkBufferCopy.calloc((int)1, (MemoryStack)stack).srcOffset(0L).dstOffset(0L).size(workspace.buffers[i5].sizeBytes));
                }
                VkBufferMemoryBarrier.Buffer readbackBarriers = VkBufferMemoryBarrier.calloc((int)stagedOutputCount, (MemoryStack)stack);
                int barrierIndex4 = 0;
                for (i = workspace.inputBufferCount; i < workspace.buffers.length; ++i) {
                    buffer = workspace.buffers[i];
                    if (!buffer.staged) continue;
                    ((VkBufferMemoryBarrier)readbackBarriers.get(barrierIndex4++)).sType(44).srcAccessMask(4096).dstAccessMask(8192).srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).buffer(buffer.hostBuffer).offset(0L).size(buffer.sizeBytes);
                }
                VK10.vkCmdPipelineBarrier((VkCommandBuffer)workspace.commandBuffer, (int)4096, (int)16384, (int)0, null, (VkBufferMemoryBarrier.Buffer)readbackBarriers, null);
            }
            VulkanInProcessManager.checkVk(VK10.vkEndCommandBuffer((VkCommandBuffer)workspace.commandBuffer), "vkEndCommandBuffer");
        }
    }

    private static AllocatedBuffer createStorageBuffer(State state, WorkloadProfile workloadProfile, BufferRole role, long sizeBytes) {
        if (!state.prefersDeviceLocalTransfers) {
            return VulkanInProcessManager.createHostVisibleStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
        }
        boolean useDeviceLocal = sizeBytes >= 262144L;
        return switch (workloadProfile.ordinal()) {
            default -> throw new IncompatibleClassChangeError();
            case 0 -> VulkanInProcessManager.createHostVisibleStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
            case 1 -> {
                if (useDeviceLocal) {
                    yield VulkanInProcessManager.createStagedStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
                }
                yield VulkanInProcessManager.createHostVisibleStorageBuffer(state, sizeBytes, role == BufferRole.OUTPUT);
            }
            case 2 -> role == BufferRole.INPUT && useDeviceLocal ? VulkanInProcessManager.createStagedStorageBuffer(state, sizeBytes, false) : VulkanInProcessManager.createHostVisibleStorageBuffer(state, sizeBytes, true);
        };
    }

    private static AllocatedBuffer createStagedStorageBuffer(State state, long sizeBytes, boolean hostCached) {
        RawBuffer hostBuffer = null;
        RawBuffer deviceBuffer = null;
        try {
            hostBuffer = VulkanInProcessManager.createRawBuffer(state, sizeBytes, 3, 6, hostCached ? 8 : 0, true);
            deviceBuffer = VulkanInProcessManager.createRawBuffer(state, sizeBytes, 35, 1, 0, false);
            return new AllocatedBuffer(deviceBuffer.buffer, deviceBuffer.memory, deviceBuffer.allocation, hostBuffer.buffer, hostBuffer.memory, hostBuffer.allocation, sizeBytes, hostBuffer.mappedPointer, true);
        }
        catch (Throwable throwable) {
            VulkanInProcessManager.destroyRawBuffer(state, hostBuffer);
            VulkanInProcessManager.destroyRawBuffer(state, deviceBuffer);
            throw throwable;
        }
    }

    private static AllocatedBuffer createHostVisibleStorageBuffer(State state, long sizeBytes, boolean preferHostCached) {
        RawBuffer buffer = VulkanInProcessManager.createRawBuffer(state, sizeBytes, 32, 6, preferHostCached ? 8 : 0, true);
        return new AllocatedBuffer(buffer.buffer, buffer.memory, buffer.allocation, buffer.buffer, buffer.memory, buffer.allocation, sizeBytes, buffer.mappedPointer, false);
    }

    private static RawBuffer createRawBuffer(State state, long sizeBytes, int usageFlags, int requiredFlags, int preferredFlags, boolean mapMemory) {
        try (MemoryStack stack = MemoryStack.stackPush();){
            MemoryAllocation allocation;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc((MemoryStack)stack).sType(12).size(sizeBytes).usage(usageFlags).sharingMode(0);
            LongBuffer bufferPtr = stack.mallocLong(1);
            VulkanInProcessManager.checkVk(VK10.vkCreateBuffer((VkDevice)state.device, (VkBufferCreateInfo)bufferInfo, null, (LongBuffer)bufferPtr), "vkCreateBuffer");
            long buffer = bufferPtr.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc((MemoryStack)stack);
            VK10.vkGetBufferMemoryRequirements((VkDevice)state.device, (long)buffer, (VkMemoryRequirements)requirements);
            int memoryType = VulkanInProcessManager.findMemoryType(state.physicalDevice, requirements.memoryTypeBits(), requiredFlags, preferredFlags, stack);
            try {
                allocation = state.memoryAllocator.allocate(memoryType, requirements.size(), requirements.alignment(), mapMemory);
            }
            catch (Throwable throwable) {
                VK10.vkDestroyBuffer((VkDevice)state.device, (long)buffer, null);
                throw throwable;
            }
            try {
                VulkanInProcessManager.checkVk(VK10.vkBindBufferMemory((VkDevice)state.device, (long)buffer, (long)allocation.memory, (long)allocation.offset), "vkBindBufferMemory");
                long mappedPointer = allocation.mappedBase == 0L ? 0L : allocation.mappedBase + allocation.offset;
                RawBuffer rawBuffer = new RawBuffer(buffer, allocation.memory, allocation.offset, sizeBytes, mappedPointer, allocation);
                return rawBuffer;
            }
            catch (Throwable throwable) {
                state.memoryAllocator.free(allocation);
                VK10.vkDestroyBuffer((VkDevice)state.device, (long)buffer, null);
                throw throwable;
            }
        }
    }

    private static void destroyBuffer(State state, AllocatedBuffer buffer) {
        if (state == null || buffer == null) {
            return;
        }
        VulkanInProcessManager.destroyRawBuffer(state, new RawBuffer(buffer.hostBuffer, buffer.hostMemory, buffer.hostAllocation.offset, buffer.sizeBytes, buffer.mappedPointer, buffer.hostAllocation));
        if (buffer.staged && (buffer.deviceBuffer != buffer.hostBuffer || buffer.deviceMemory != buffer.hostMemory)) {
            VulkanInProcessManager.destroyRawBuffer(state, new RawBuffer(buffer.deviceBuffer, buffer.deviceMemory, buffer.deviceAllocation.offset, buffer.sizeBytes, 0L, buffer.deviceAllocation));
        }
    }

    private static void destroyRawBuffer(State state, RawBuffer buffer) {
        if (state == null || buffer == null) {
            return;
        }
        try {
            VK10.vkDestroyBuffer((VkDevice)state.device, (long)buffer.buffer, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            state.memoryAllocator.free(buffer.allocation);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static int findMemoryType(VkPhysicalDevice physicalDevice, int typeBits, int requiredFlags, int preferredFlags, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc((MemoryStack)stack);
        VK10.vkGetPhysicalDeviceMemoryProperties((VkPhysicalDevice)physicalDevice, (VkPhysicalDeviceMemoryProperties)properties);
        if (preferredFlags != 0) {
            int preferredMask = requiredFlags | preferredFlags;
            for (int i = 0; i < properties.memoryTypeCount(); ++i) {
                boolean supported;
                boolean bl = supported = (typeBits & 1 << i) != 0;
                if (!supported || (properties.memoryTypes(i).propertyFlags() & preferredMask) != preferredMask) continue;
                return i;
            }
        }
        for (int i = 0; i < properties.memoryTypeCount(); ++i) {
            boolean supported;
            boolean bl = supported = (typeBits & 1 << i) != 0;
            if (!supported || (properties.memoryTypes(i).propertyFlags() & requiredFlags) != requiredFlags) continue;
            return i;
        }
        throw new IllegalStateException("Unable to find matching Vulkan memory type");
    }

    private static void writeFloatArray(AllocatedBuffer buffer, float[] values) {
        FloatBuffer mapped = MemoryUtil.memFloatBuffer((long)buffer.mappedPointer, (int)values.length);
        mapped.clear();
        mapped.put(values);
        mapped.flip();
    }

    private static void writeFloatArrayOrDefault(AllocatedBuffer buffer, float[] values, int length) {
        int i;
        int writeLen = Math.max(1, length);
        FloatBuffer mapped = MemoryUtil.memFloatBuffer((long)buffer.mappedPointer, (int)writeLen);
        mapped.clear();
        int copyLen = Math.min(values.length, length);
        for (i = 0; i < copyLen; ++i) {
            mapped.put(values[i]);
        }
        for (i = copyLen; i < writeLen; ++i) {
            mapped.put(0.0f);
        }
        mapped.flip();
    }

    private static void writeFloatArrayPadded(AllocatedBuffer buffer, float[] values, int paddedLength) {
        if (values.length > paddedLength) {
            throw new IllegalArgumentException("Input exceeds padded buffer length: " + values.length + " > " + paddedLength);
        }
        FloatBuffer mapped = MemoryUtil.memFloatBuffer((long)buffer.mappedPointer, (int)paddedLength);
        mapped.clear();
        mapped.put(values);
        for (int i = values.length; i < paddedLength; ++i) {
            mapped.put(0.0f);
        }
        mapped.flip();
    }

    private static void writeInt(AllocatedBuffer buffer, int value) {
        IntBuffer mapped = MemoryUtil.memIntBuffer((long)buffer.mappedPointer, (int)1);
        mapped.put(0, value);
    }

    private static float[] readFloatArray(AllocatedBuffer buffer, int length) {
        float[] out = new float[length];
        FloatBuffer mapped = MemoryUtil.memFloatBuffer((long)buffer.mappedPointer, (int)length);
        mapped.rewind();
        mapped.get(out);
        return out;
    }

    private static int readInt(AllocatedBuffer buffer) {
        IntBuffer mapped = MemoryUtil.memIntBuffer((long)buffer.mappedPointer, (int)1);
        return mapped.get(0);
    }

    private static void dispatch(State state, DispatchWorkspace workspace) {
        VulkanInProcessManager.dispatch(state, List.of(workspace));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void dispatch(State state, List<DispatchWorkspace> workspaces) {
        if (workspaces == null || workspaces.isEmpty()) {
            return;
        }
        if (state.timelineSemaphore != 0L) {
            VulkanInProcessManager.dispatchTimeline(state, workspaces);
            return;
        }
        DispatchWorkspace fenceOwner = workspaces.get(0);
        try (MemoryStack stack = MemoryStack.stackPush();){
            VulkanInProcessManager.checkVk(VK10.vkResetFences((VkDevice)state.device, (LongBuffer)stack.longs(fenceOwner.fence)), "vkResetFences");
            PointerBuffer commandBufferPtr = stack.mallocPointer(workspaces.size());
            for (int i = 0; i < workspaces.size(); ++i) {
                commandBufferPtr.put(i, workspaces.get((int)i).commandBuffer.address());
            }
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc((MemoryStack)stack).sType(4).pCommandBuffers(commandBufferPtr);
            Object object = state.computeQueueLock;
            synchronized (object) {
                VulkanInProcessManager.checkVk(VK10.vkQueueSubmit((VkQueue)state.computeQueue, (VkSubmitInfo)submitInfo, (long)fenceOwner.fence), "vkQueueSubmit");
            }
            VulkanInProcessManager.checkVk(VK10.vkWaitForFences((VkDevice)state.device, (LongBuffer)stack.longs(fenceOwner.fence), (boolean)true, (long)30000000000L), "vkWaitForFences");
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void dispatchTimeline(State state, List<DispatchWorkspace> workspaces) {
        DispatchWorkspace fenceOwner = workspaces.get(0);
        long signalValue = state.nextTimelineValue.incrementAndGet();
        try (MemoryStack stack = MemoryStack.stackPush();){
            VulkanInProcessManager.checkVk(VK10.vkResetFences((VkDevice)state.device, (LongBuffer)stack.longs(fenceOwner.fence)), "vkResetFences");
            PointerBuffer commandBufferPtr = stack.mallocPointer(workspaces.size());
            for (int i = 0; i < workspaces.size(); ++i) {
                commandBufferPtr.put(i, workspaces.get((int)i).commandBuffer.address());
            }
            VkTimelineSemaphoreSubmitInfo timelineInfo = VkTimelineSemaphoreSubmitInfo.calloc((MemoryStack)stack).sType(1000207003).pSignalSemaphoreValues(stack.longs(signalValue));
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc((MemoryStack)stack).sType(4).pNext(timelineInfo.address()).pCommandBuffers(commandBufferPtr).pSignalSemaphores(stack.longs(state.timelineSemaphore));
            Object object = state.computeQueueLock;
            synchronized (object) {
                VulkanInProcessManager.checkVk(VK10.vkQueueSubmit((VkQueue)state.computeQueue, (VkSubmitInfo)submitInfo, (long)fenceOwner.fence), "vkQueueSubmit");
            }
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc((MemoryStack)stack).sType(1000207004).pSemaphores(stack.longs(state.timelineSemaphore)).pValues(stack.longs(signalValue));
            VulkanInProcessManager.checkVk(VK12.vkWaitSemaphores((VkDevice)state.device, (VkSemaphoreWaitInfo)waitInfo, (long)30000000000L), "vkWaitSemaphores");
            VulkanInProcessManager.checkVk(VK10.vkWaitForFences((VkDevice)state.device, (LongBuffer)stack.longs(fenceOwner.fence), (boolean)true, (long)30000000000L), "vkWaitForFences");
        }
    }

    private static ByteBuffer loadShaderBinary(String name, String resourcePath) {
        ByteBuffer byteBuffer;
        block10: {
            InputStream input = VulkanInProcessManager.class.getResourceAsStream(resourcePath);
            try {
                if (input == null) {
                    throw new IllegalStateException("Missing precompiled SPIR-V resource for " + name + ": " + resourcePath);
                }
                byte[] bytes = input.readAllBytes();
                if (bytes.length == 0) {
                    throw new IllegalStateException("Empty precompiled SPIR-V resource for " + name + ": " + resourcePath);
                }
                ByteBuffer copy = MemoryUtil.memAlloc((int)bytes.length);
                copy.put(bytes);
                copy.flip();
                byteBuffer = copy;
                if (input == null) break block10;
            }
            catch (Throwable throwable) {
                try {
                    if (input != null) {
                        try {
                            input.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                catch (IOException e) {
                    throw new IllegalStateException("Failed reading precompiled SPIR-V resource for " + name + ": " + resourcePath, e);
                }
            }
            try {
                input.close();
            }
            catch (IOException e) {
                throw new IllegalStateException("Failed reading precompiled SPIR-V resource for " + name + ": " + resourcePath, e);
            }
        }
        return byteBuffer;
    }

    private static ByteBuffer intsToBytes(int ... values) {
        ByteBuffer buffer = MemoryUtil.memAlloc((int)(values.length * 4));
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
                VulkanInProcessManager.destroyWorkspace(state, workspace);
            }
        }
        state.workspaces.clear();
        for (Program program : state.programs.values()) {
            try {
                VK10.vkDestroyPipeline((VkDevice)state.device, (long)program.pipeline, null);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            try {
                VK10.vkDestroyPipelineLayout((VkDevice)state.device, (long)program.pipelineLayout, null);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            try {
                VK10.vkDestroyDescriptorSetLayout((VkDevice)state.device, (long)program.descriptorSetLayout, null);
            }
            catch (Throwable throwable) {}
        }
        state.programs.clear();
        try {
            state.memoryAllocator.destroy();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            if (state.timelineSemaphore != 0L) {
                VK10.vkDestroySemaphore((VkDevice)state.device, (long)state.timelineSemaphore, null);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            VK10.vkDestroyCommandPool((VkDevice)state.device, (long)state.commandPool, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            VK10.vkDestroyDevice((VkDevice)state.device, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            VK10.vkDestroyInstance((VkInstance)state.instance, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static void destroyProgram(State state, Program program) {
        if (state == null || program == null) {
            return;
        }
        try {
            VK10.vkDestroyPipeline((VkDevice)state.device, (long)program.pipeline, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            VK10.vkDestroyPipelineLayout((VkDevice)state.device, (long)program.pipelineLayout, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            VK10.vkDestroyDescriptorSetLayout((VkDevice)state.device, (long)program.descriptorSetLayout, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static void destroyWorkspace(State state, DispatchWorkspace workspace) {
        if (state == null || workspace == null) {
            return;
        }
        try {
            VK10.vkDestroyFence((VkDevice)state.device, (long)workspace.fence, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try (MemoryStack stack = MemoryStack.stackPush();){
            VK10.vkFreeCommandBuffers((VkDevice)state.device, (long)state.commandPool, (PointerBuffer)stack.pointers(workspace.commandBuffer.address()));
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            VK10.vkDestroyDescriptorPool((VkDevice)state.device, (long)workspace.descriptorPool, null);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        for (AllocatedBuffer buffer : workspace.buffers) {
            VulkanInProcessManager.destroyBuffer(state, buffer);
        }
    }

    private static float[] flattenMatrix(float[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        float[] out = new float[rows * cols];
        for (int row = 0; row < rows; ++row) {
            System.arraycopy(matrix[row], 0, out, row * cols, cols);
        }
        return out;
    }

    private static float[][] reconstructMatrix(float[] values, int rows, int cols) {
        float[][] out = new float[rows][cols];
        for (int row = 0; row < rows; ++row) {
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
        return (value + alignment - 1) / alignment * alignment;
    }

    private static long alignUp(long value, long alignment) {
        if (value <= 0L) {
            return 0L;
        }
        return (value + alignment - 1L) / alignment * alignment;
    }

    private static long configuredSlabBytes() {
        long configured = Long.getLong(SLAB_BYTES_PROPERTY, 0x10000000L);
        return Math.max(0x1000000L, configured);
    }

    private static void checkVk(int result, String operation) {
        if (result != 0) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }

    static VulkanContext sharedContext() {
        if (!VulkanInProcessManager.ensureInitialised()) {
            throw new IllegalStateException(VulkanInProcessManager.runtimeStatus().failureReason());
        }
        return VulkanManagerHolder.CONTEXT;
    }

    static {
        lastStatus = RuntimeStatus.failed("Vulkan not initialized");
        nextInitRetryMs = 0L;
    }

    private static final class State {
        private final VkInstance instance;
        private final VkPhysicalDevice physicalDevice;
        private final VkDevice device;
        private final VkQueue computeQueue;
        private final Object computeQueueLock = new Object();
        private final long commandPool;
        private final String deviceName;
        private final long localMemoryBytes;
        private final boolean prefersDeviceLocalTransfers;
        private final VulkanMemoryAllocator memoryAllocator;
        private final long timelineSemaphore;
        private final AtomicLong nextTimelineValue = new AtomicLong(0L);
        private final Map<String, Program> programs = new ConcurrentHashMap<String, Program>();
        private final Map<String, DispatchWorkspacePool> workspaces = new ConcurrentHashMap<String, DispatchWorkspacePool>();
        private final Object workspaceMutex = new Object();

        private State(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, long commandPool, String deviceName, long localMemoryBytes, boolean prefersDeviceLocalTransfers, VulkanMemoryAllocator memoryAllocator, long timelineSemaphore) {
            this.instance = instance;
            this.physicalDevice = physicalDevice;
            this.device = device;
            this.computeQueue = computeQueue;
            this.commandPool = commandPool;
            this.deviceName = deviceName;
            this.localMemoryBytes = localMemoryBytes;
            this.prefersDeviceLocalTransfers = prefersDeviceLocalTransfers;
            this.memoryAllocator = memoryAllocator;
            this.timelineSemaphore = timelineSemaphore;
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
            return this.available;
        }

        public String failureReason() {
            return this.failureReason;
        }

        public static RuntimeStatus available() {
            return new RuntimeStatus(true, null);
        }

        public static RuntimeStatus failed(String reason) {
            return new RuntimeStatus(false, reason);
        }
    }

    public record VulkanDeviceInfo(String id, String name, String vendor, long localMemoryBytes, int deviceType, boolean softwareAdapter) {
    }

    private record PhysicalSelection(long physicalDeviceHandle, int computeQueueFamily, String deviceName, String vendorName, String id, int deviceType, long localMemoryBytes, boolean softwareAdapter, double score) {
    }

    private static final class VulkanManagerHolder {
        private static final VulkanInProcessManager INSTANCE = new VulkanInProcessManager();
        private static final VulkanContext CONTEXT = new VulkanContext(INSTANCE);

        private VulkanManagerHolder() {
        }
    }

    private record Program(String name, long pipeline, long pipelineLayout, long descriptorSetLayout, int storageBufferCount, int pushConstantBytes) {
    }

    private static enum WorkloadProfile {
        BANDWIDTH,
        COMPUTE_DENSE,
        REDUCTION;

    }

    private record WorkspaceLease(DispatchWorkspacePool pool, DispatchWorkspace workspace) implements AutoCloseable
    {
        @Override
        public void close() {
            this.pool.release(this.workspace);
        }
    }

    private record DispatchWorkspace(Program program, long descriptorPool, long descriptorSet, VkCommandBuffer commandBuffer, long fence, int groupCountX, int groupCountY, int groupCountZ, int inputBufferCount, AllocatedBuffer[] buffers) {
    }

    private record AllocatedBuffer(long deviceBuffer, long deviceMemory, MemoryAllocation deviceAllocation, long hostBuffer, long hostMemory, MemoryAllocation hostAllocation, long sizeBytes, long mappedPointer, boolean staged) {
        private long descriptorBuffer() {
            return this.staged ? this.deviceBuffer : this.hostBuffer;
        }
    }

    private record FloatControlSummary(boolean available, boolean roundToNearestEvenFloat32, boolean signedZeroInfNanPreserveFloat32, boolean denormPreserveFloat32, boolean denormFlushToZeroFloat32, int denormBehaviorIndependence, int roundingModeIndependence, String unavailableReason) {
        private static FloatControlSummary unavailable(String reason) {
            return new FloatControlSummary(false, false, false, false, false, 0, 0, reason);
        }

        private boolean deterministicFloat32() {
            return this.available && this.roundToNearestEvenFloat32 && this.signedZeroInfNanPreserveFloat32;
        }

        private String summary() {
            if (!this.available) {
                return "unavailable (" + this.unavailableReason + ")";
            }
            return "rte32=" + this.roundToNearestEvenFloat32 + ", preserveZeroInfNan32=" + this.signedZeroInfNanPreserveFloat32 + ", denormPreserve32=" + this.denormPreserveFloat32 + ", denormFtz32=" + this.denormFlushToZeroFloat32 + ", denormIndependence=" + FloatControlSummary.independenceName(this.denormBehaviorIndependence) + ", roundingIndependence=" + FloatControlSummary.independenceName(this.roundingModeIndependence) + ", strictFloat32=" + this.deterministicFloat32();
        }

        private static String independenceName(int value) {
            if (value == 1) {
                return "all";
            }
            if (value == 0) {
                return "32bit-only";
            }
            if (value == 2) {
                return "none";
            }
            return Integer.toString(value);
        }
    }

    private static final class VulkanMemoryAllocator {
        private final VkDevice device;
        private final List<MemorySlab> slabs = new ArrayList<MemorySlab>();
        private long reservedBytes;

        private VulkanMemoryAllocator(VkDevice device) {
            this.device = device;
        }

        private synchronized MemoryAllocation allocate(int memoryType, long size, long alignment, boolean mapped) {
            long slabSize;
            long alignedSize = VulkanInProcessManager.alignUp(size, Math.max(1L, alignment));
            if (alignedSize > (slabSize = VulkanInProcessManager.configuredSlabBytes()) / 2L) {
                return this.allocateDedicated(memoryType, alignedSize, mapped);
            }
            for (MemorySlab slab : this.slabs) {
                MemoryAllocation allocation;
                if (slab.memoryType != memoryType || slab.mapped != mapped || (allocation = slab.tryAllocate(alignedSize, Math.max(1L, alignment))) == null) continue;
                return allocation;
            }
            MemorySlab slab = this.createSlab(memoryType, Math.max(slabSize, alignedSize), mapped);
            this.slabs.add(slab);
            MemoryAllocation allocation = slab.tryAllocate(alignedSize, Math.max(1L, alignment));
            if (allocation == null) {
                throw new IllegalStateException("Unable to suballocate Vulkan memory slab");
            }
            return allocation;
        }

        private MemoryAllocation allocateDedicated(int memoryType, long size, boolean mapped) {
            try (MemoryStack stack = MemoryStack.stackPush();){
                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc((MemoryStack)stack).sType(5).allocationSize(size).memoryTypeIndex(memoryType);
                LongBuffer memoryPtr = stack.mallocLong(1);
                VulkanInProcessManager.checkVk(VK10.vkAllocateMemory((VkDevice)this.device, (VkMemoryAllocateInfo)allocInfo, null, (LongBuffer)memoryPtr), "vkAllocateMemory");
                long memory = memoryPtr.get(0);
                long mappedBase = 0L;
                if (mapped) {
                    PointerBuffer mappedPtr = stack.mallocPointer(1);
                    try {
                        VulkanInProcessManager.checkVk(VK10.vkMapMemory((VkDevice)this.device, (long)memory, (long)0L, (long)size, (int)0, (PointerBuffer)mappedPtr), "vkMapMemory");
                        mappedBase = mappedPtr.get(0);
                    }
                    catch (Throwable throwable) {
                        VK10.vkFreeMemory((VkDevice)this.device, (long)memory, null);
                        throw throwable;
                    }
                }
                MemoryAllocation memoryAllocation = new MemoryAllocation(null, memory, 0L, size, mappedBase, true);
                this.reservedBytes += size;
                return memoryAllocation;
            }
        }

        private MemorySlab createSlab(int memoryType, long size, boolean mapped) {
            long allocationSize = Math.max(0x1000000L, size);
            while (allocationSize >= size) {
                try {
                    return this.createSlabAttempt(memoryType, allocationSize, mapped);
                }
                catch (Throwable throwable) {
                    if (allocationSize == size) {
                        throw throwable;
                    }
                    allocationSize = Math.max(size, allocationSize / 2L);
                }
            }
            throw new IllegalStateException("Unable to create Vulkan memory slab");
        }

        private MemorySlab createSlabAttempt(int memoryType, long size, boolean mapped) {
            try (MemoryStack stack = MemoryStack.stackPush();){
                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc((MemoryStack)stack).sType(5).allocationSize(size).memoryTypeIndex(memoryType);
                LongBuffer memoryPtr = stack.mallocLong(1);
                VulkanInProcessManager.checkVk(VK10.vkAllocateMemory((VkDevice)this.device, (VkMemoryAllocateInfo)allocInfo, null, (LongBuffer)memoryPtr), "vkAllocateMemory");
                long memory = memoryPtr.get(0);
                long mappedBase = 0L;
                if (mapped) {
                    PointerBuffer mappedPtr = stack.mallocPointer(1);
                    try {
                        VulkanInProcessManager.checkVk(VK10.vkMapMemory((VkDevice)this.device, (long)memory, (long)0L, (long)size, (int)0, (PointerBuffer)mappedPtr), "vkMapMemory");
                        mappedBase = mappedPtr.get(0);
                    }
                    catch (Throwable throwable) {
                        VK10.vkFreeMemory((VkDevice)this.device, (long)memory, null);
                        throw throwable;
                    }
                }
                MemorySlab memorySlab = new MemorySlab(this.device, memoryType, memory, size, mappedBase, mapped);
                this.reservedBytes += size;
                return memorySlab;
            }
        }

        private synchronized void free(MemoryAllocation allocation) {
            if (allocation == null || allocation.freed) {
                return;
            }
            allocation.freed = true;
            if (allocation.dedicated) {
                if (allocation.mappedBase != 0L) {
                    VK10.vkUnmapMemory((VkDevice)this.device, (long)allocation.memory);
                }
                VK10.vkFreeMemory((VkDevice)this.device, (long)allocation.memory, null);
                this.reservedBytes = Math.max(0L, this.reservedBytes - allocation.size);
                return;
            }
            allocation.slab.free(allocation.offset, allocation.size);
        }

        private synchronized VulkanResidencyManager.TrimResult trimFreeSlabs(VulkanResidencyManager.TrimLevel trimLevel) {
            Map<String, Integer> retained = new LinkedHashMap<String, Integer>();
            long slabsFreed = 0L;
            long bytesFreed = 0L;
            Iterator<MemorySlab> iterator = this.slabs.iterator();
            while (iterator.hasNext()) {
                MemorySlab slab = iterator.next();
                if (!slab.isCompletelyFree() || !slab.canTrim(trimLevel)) {
                    continue;
                }
                String key = slab.memoryType + ":" + slab.mapped;
                int kept = retained.getOrDefault(key, 0);
                if (kept >= trimLevel.keepFreeSlabsPerClass()) {
                    slab.destroy();
                    this.reservedBytes = Math.max(0L, this.reservedBytes - slab.size);
                    ++slabsFreed;
                    bytesFreed += slab.size;
                    iterator.remove();
                    continue;
                }
                retained.put(key, kept + 1);
            }
            return VulkanResidencyManager.TrimResult.of(0L, 0L, slabsFreed, bytesFreed);
        }

        private synchronized long reservedBytes() {
            return this.reservedBytes;
        }

        private synchronized int slabCount() {
            return this.slabs.size();
        }

        private synchronized void destroy() {
            for (MemorySlab slab : this.slabs) {
                slab.destroy();
            }
            this.slabs.clear();
            this.reservedBytes = 0L;
        }
    }

    private static final class DispatchWorkspacePool {
        private final DispatchWorkspace[] workspaces;
        private final BlockingQueue<DispatchWorkspace> available;
        private final long totalBytes;
        private boolean closed;
        private int borrowedCount;
        private volatile long lastBorrowedNanos;
        private volatile long lastReleasedNanos;

        private DispatchWorkspacePool(DispatchWorkspace[] workspaces, long totalBytes) {
            this.workspaces = workspaces;
            this.totalBytes = totalBytes;
            this.available = new ArrayBlockingQueue<DispatchWorkspace>(workspaces.length);
            for (DispatchWorkspace workspace : workspaces) {
                this.available.add(workspace);
            }
            long now = System.nanoTime();
            this.lastBorrowedNanos = now;
            this.lastReleasedNanos = now;
        }

        private DispatchWorkspace borrow() {
            synchronized (this) {
                if (this.closed) {
                    throw new IllegalStateException("Vulkan workspace pool is closed");
                }
                ++this.borrowedCount;
                this.lastBorrowedNanos = System.nanoTime();
            }
            try {
                return this.available.take();
            }
            catch (InterruptedException e) {
                synchronized (this) {
                    --this.borrowedCount;
                }
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Vulkan workspace", e);
            }
        }

        private void release(DispatchWorkspace workspace) {
            if (workspace == null) {
                return;
            }
            synchronized (this) {
                --this.borrowedCount;
                if (this.closed) {
                    return;
                }
                this.lastReleasedNanos = System.nanoTime();
            }
            this.available.offer(workspace);
        }

        private DispatchWorkspace[] workspaces() {
            return this.workspaces;
        }

        private synchronized boolean isClosed() {
            return this.closed;
        }

        private synchronized DispatchWorkspace[] closeIfIdle(VulkanResidencyManager.TrimLevel trimLevel) {
            if (this.closed || this.borrowedCount != 0 || this.available.size() != this.workspaces.length) {
                return null;
            }
            long idleNanos = System.nanoTime() - Math.max(this.lastBorrowedNanos, this.lastReleasedNanos);
            if (idleNanos < trimLevel.workspaceIdleNanos()) {
                return null;
            }
            this.closed = true;
            this.available.clear();
            return this.workspaces;
        }

        private long totalBytes() {
            return this.totalBytes;
        }
    }

    private static enum BufferRole {
        INPUT,
        OUTPUT;

    }

    private record RawBuffer(long buffer, long memory, long memoryOffset, long sizeBytes, long mappedPointer, MemoryAllocation allocation) {
    }

    private static final class MemoryAllocation {
        private final MemorySlab slab;
        private final long memory;
        private final long offset;
        private final long size;
        private final long mappedBase;
        private final boolean dedicated;
        private boolean freed;

        private MemoryAllocation(MemorySlab slab, long memory, long offset, long size, long mappedBase, boolean dedicated) {
            this.slab = slab;
            this.memory = memory;
            this.offset = offset;
            this.size = size;
            this.mappedBase = mappedBase;
            this.dedicated = dedicated;
        }
    }

    private static final class VulkanThreadFactory
    implements ThreadFactory {
        private VulkanThreadFactory() {
        }

        @Override
        public Thread newThread(Runnable runnable) {
            return LwjglRuntimeTuning.newDaemonThread(runnable, "Quantified-Vulkan", LwjglRuntimeTuning.gpuThreadStackSizeKb());
        }
    }

    private static final class FreeRange {
        private long offset;
        private long size;

        private FreeRange(long offset, long size) {
            this.offset = offset;
            this.size = size;
        }
    }

    private static final class MemorySlab {
        private final VkDevice device;
        private final int memoryType;
        private final long memory;
        private final long size;
        private final long mappedBase;
        private final boolean mapped;
        private final List<FreeRange> freeRanges = new ArrayList<FreeRange>();
        private volatile long lastTouchedNanos;

        private MemorySlab(VkDevice device, int memoryType, long memory, long size, long mappedBase, boolean mapped) {
            this.device = device;
            this.memoryType = memoryType;
            this.memory = memory;
            this.size = size;
            this.mappedBase = mappedBase;
            this.mapped = mapped;
            this.freeRanges.add(new FreeRange(0L, size));
            this.lastTouchedNanos = System.nanoTime();
        }

        private MemoryAllocation tryAllocate(long requestSize, long alignment) {
            for (int i = 0; i < this.freeRanges.size(); ++i) {
                FreeRange range = this.freeRanges.get(i);
                long alignedOffset = VulkanInProcessManager.alignUp(range.offset, alignment);
                long padding = alignedOffset - range.offset;
                long required = padding + requestSize;
                if (required > range.size) continue;
                long suffixOffset = alignedOffset + requestSize;
                long suffixSize = range.offset + range.size - suffixOffset;
                if (padding > 0L && suffixSize > 0L) {
                    range.size = padding;
                    this.freeRanges.add(i + 1, new FreeRange(suffixOffset, suffixSize));
                } else if (padding > 0L) {
                    range.size = padding;
                } else if (suffixSize > 0L) {
                    range.offset = suffixOffset;
                    range.size = suffixSize;
                } else {
                    this.freeRanges.remove(i);
                }
                this.lastTouchedNanos = System.nanoTime();
                return new MemoryAllocation(this, this.memory, alignedOffset, requestSize, this.mappedBase, false);
            }
            return null;
        }

        private void free(long offset, long size) {
            this.freeRanges.add(new FreeRange(offset, size));
            this.freeRanges.sort((left, right) -> Long.compare(left.offset, right.offset));
            for (int i = 0; i < this.freeRanges.size() - 1; ++i) {
                FreeRange current = this.freeRanges.get(i);
                FreeRange next = this.freeRanges.get(i + 1);
                if (current.offset + current.size < next.offset) continue;
                long end = Math.max(current.offset + current.size, next.offset + next.size);
                current.size = end - current.offset;
                this.freeRanges.remove(i + 1);
                --i;
            }
            this.lastTouchedNanos = System.nanoTime();
        }

        private boolean isCompletelyFree() {
            return this.freeRanges.size() == 1 && this.freeRanges.get(0).offset == 0L && this.freeRanges.get(0).size == this.size;
        }

        private boolean canTrim(VulkanResidencyManager.TrimLevel trimLevel) {
            return System.nanoTime() - this.lastTouchedNanos >= trimLevel.slabIdleNanos();
        }

        private void destroy() {
            if (this.mappedBase != 0L) {
                VK10.vkUnmapMemory((VkDevice)this.device, (long)this.memory);
            }
            VK10.vkFreeMemory((VkDevice)this.device, (long)this.memory, null);
        }
    }

    private static boolean ensureCapacityForTask(State state, VulkanTask<?> task) {
        long localMemoryBytes = state.localMemoryBytes;
        if (localMemoryBytes <= 0L) {
            return true;
        }
        long reservedBytes = state.memoryAllocator.reservedBytes();
        long projectedBytes = reservedBytes + Math.max(0L, task.estimatedVramBytes());
        long softLimit = VulkanResidencyManager.softLimitBytes(localMemoryBytes, DEFAULT_SLAB_BYTES);
        if (projectedBytes <= softLimit) {
            return true;
        }
        VulkanInProcessManager.trimIdleResources("capacity-check", false);
        reservedBytes = state.memoryAllocator.reservedBytes();
        projectedBytes = reservedBytes + Math.max(0L, task.estimatedVramBytes());
        long hardLimit = VulkanResidencyManager.hardLimitBytes(localMemoryBytes, DEFAULT_SLAB_BYTES);
        if (projectedBytes <= hardLimit) {
            return true;
        }
        VulkanInProcessManager.trimIdleResources("capacity-check-aggressive", true);
        reservedBytes = state.memoryAllocator.reservedBytes();
        projectedBytes = reservedBytes + Math.max(0L, task.estimatedVramBytes());
        if (projectedBytes <= hardLimit) {
            return true;
        }
        VulkanResidencyManager.notePressureReject();
        VulkanInProcessManager.enterVramPressureCooldown(state, reservedBytes, task.estimatedVramBytes());
        return false;
    }

    private static void enterVramPressureCooldown(State state, long reservedBytes, long requestedBytes) {
        VulkanResidencyManager.notePressureCooldown();
        long until = System.currentTimeMillis() + VulkanResidencyManager.pressureCooldownMs();
        VRAM_PRESSURE_COOLDOWN_UNTIL_MS.set(until);
        long lastLog = LAST_PRESSURE_LOG_MS.get();
        long now = System.currentTimeMillis();
        if (now - lastLog > VulkanResidencyManager.pressureLogIntervalMs() && LAST_PRESSURE_LOG_MS.compareAndSet(lastLog, now)) {
            long localMiB = Math.max(1L, state.localMemoryBytes / (1024 * 1024));
            long reservedMiB = Math.max(0L, reservedBytes / (1024 * 1024));
            long requestedMiB = Math.max(0L, requestedBytes / (1024 * 1024));
            String detail = "Vulkan VRAM pressure detected - trimmed idle resources and paused new GPU work briefly"
                + " (reserved=" + reservedMiB + " MiB, requested=" + requestedMiB + " MiB, device=" + localMiB + " MiB)";
            LOGGER.warn(detail);
            DeveloperOverlayManager.recordApiLog("[Vulkan] " + detail);
        }
    }

    private static VulkanResidencyManager.TrimResult trimIdleWorkspacePools(State state, VulkanResidencyManager.TrimLevel trimLevel) {
        long poolsFreed = 0L;
        long bytesFreed = 0L;
        synchronized (state.workspaceMutex) {
            Iterator<Map.Entry<String, DispatchWorkspacePool>> iterator = state.workspaces.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, DispatchWorkspacePool> entry = iterator.next();
                DispatchWorkspace[] idle = entry.getValue().closeIfIdle(trimLevel);
                if (idle == null) {
                    continue;
                }
                iterator.remove();
                ++poolsFreed;
                bytesFreed += entry.getValue().totalBytes();
                for (DispatchWorkspace workspace : idle) {
                    VulkanInProcessManager.destroyWorkspace(state, workspace);
                }
            }
        }
        return VulkanResidencyManager.TrimResult.of(poolsFreed, bytesFreed, 0L, 0L);
    }
}
