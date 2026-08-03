package org.admany.quantified.core.common.vulkan.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.vulkan.core.VulkanContext;
import org.admany.quantified.core.common.vulkan.core.VulkanInProcessManager;

public final class VulkanIsolatedBridge {
    private static final Map<Class<?>, Field> SPEC_FIELDS = new ConcurrentHashMap();
    private static final Map<Class<?>, Method> WORKLOAD_METHODS = new ConcurrentHashMap();

    private VulkanIsolatedBridge() {
    }

    public static boolean isAvailable() {
        try {
            return VulkanIsolatedBridge.ensureRuntimeReady();
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    public static Object executeApiTask(Object parentApiTask) throws Exception {
        Objects.requireNonNull(parentApiTask, "parentApiTask");
        if (!VulkanIsolatedBridge.ensureRuntimeReady()) {
            throw new IllegalStateException("Isolated Vulkan runtime is unavailable");
        }
        VulkanContext context = VulkanInProcessManager.sharedContext();
        ExecutionBinding binding = VulkanIsolatedBridge.createBinding(parentApiTask.getClass().getClassLoader(), context);
        return VulkanIsolatedBridge.executeApiTask(parentApiTask, binding, new HashMap());
    }

    public static Object[] executeApiTasks(List<?> parentApiTasks) {
        Objects.requireNonNull(parentApiTasks, "parentApiTasks");
        if (!VulkanIsolatedBridge.ensureRuntimeReady()) {
            throw new IllegalStateException("Isolated Vulkan runtime is unavailable");
        }
        VulkanContext context = VulkanInProcessManager.sharedContext();
        Object[] results = new Object[parentApiTasks.size()];
        IdentityHashMap<ClassLoader, ExecutionBinding> bindings = new IdentityHashMap<ClassLoader, ExecutionBinding>();
        HashMap executeMethods = new HashMap();
        for (int i = 0; i < parentApiTasks.size(); ++i) {
            try {
                Object parentApiTask = parentApiTasks.get(i);
                ExecutionBinding binding = bindings.computeIfAbsent(parentApiTask.getClass().getClassLoader(), loader -> VulkanIsolatedBridge.createBinding(loader, context));
                results[i] = VulkanIsolatedBridge.executeApiTask(parentApiTask, binding, executeMethods);
                continue;
            }
            catch (Throwable throwable) {
                results[i] = throwable;
            }
        }
        return results;
    }

    public static Map<String, Object> residencySnapshot() {
        // Telemetry is observational.  In particular, a dashboard refresh must
        // never initialise an isolated LWJGL runtime on a render/server path:
        // that turns a one-time native-loader failure into periodic hitches.
        if (!VulkanInProcessManager.isAvailable()) {
            return Map.of();
        }
        return VulkanInProcessManager.residencySnapshot();
    }

    private static Object executeApiTask(Object parentApiTask, ExecutionBinding binding, Map<Class<?>, Method> executeMethods) throws Exception {
        Object workload = VulkanIsolatedBridge.extractWorkload(parentApiTask);
        Method execute = executeMethods.computeIfAbsent(workload.getClass(), workloadClass -> VulkanIsolatedBridge.findExecuteMethod(workloadClass, binding.contextInterface()));
        return execute.invoke(workload, binding.proxy());
    }

    private static ExecutionBinding createBinding(ClassLoader parentLoader, VulkanContext context) {
        try {
            Class<?> contextInterface = Class.forName("org.admany.quantified.api.vulkan.QuantifiedVulkan$Context", false, parentLoader);
            Object proxy = Proxy.newProxyInstance(parentLoader, new Class[]{contextInterface}, (InvocationHandler)new DirectContextInvocationHandler(context));
            return new ExecutionBinding(contextInterface, proxy);
        }
        catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Unable to resolve Quantified Vulkan context interface", exception);
        }
    }

    private static boolean ensureRuntimeReady() {
        LwjglRuntimeTuning.ensureConfigured();
        if (VulkanInProcessManager.isAvailable()) {
            return true;
        }
        VulkanRuntime.AvailabilitySnapshot snapshot = VulkanRuntime.snapshot();
        boolean hasUsableParentProbe = snapshot != null && (snapshot.available() || !snapshot.devices().isEmpty());
        if (!hasUsableParentProbe && !VulkanInProcessManager.forceProbeSynchronous()) {
            return false;
        }
        return VulkanInProcessManager.ensureInitialised();
    }

    private static Object extractWorkload(Object parentApiTask) throws Exception {
        Field specField = SPEC_FIELDS.computeIfAbsent(parentApiTask.getClass(), type -> {
            try {
                Field field = type.getDeclaredField("spec");
                field.setAccessible(true);
                return field;
            }
            catch (NoSuchFieldException exception) {
                throw new IllegalStateException("Quantified Vulkan task has no spec field", exception);
            }
        });
        Object spec = specField.get(parentApiTask);
        Method workloadMethod = WORKLOAD_METHODS.computeIfAbsent(spec.getClass(), type -> {
            try {
                return type.getMethod("workload", new Class[0]);
            }
            catch (NoSuchMethodException exception) {
                throw new IllegalStateException("Quantified Vulkan task spec has no workload() method", exception);
            }
        });
        Object workload = workloadMethod.invoke(spec, new Object[0]);
        if (workload == null) {
            throw new IllegalStateException("Quantified Vulkan task has no workload attached");
        }
        return workload;
    }

    private static Method findExecuteMethod(Class<?> workloadClass, Class<?> contextType) {
        for (Method method : workloadClass.getMethods()) {
            Class<?> parameter;
            if (!"execute".equals(method.getName()) || method.getParameterCount() != 1 || !(parameter = method.getParameterTypes()[0]).isAssignableFrom(contextType) && !contextType.isAssignableFrom(parameter)) continue;
            method.setAccessible(true);
            return method;
        }
        throw new IllegalStateException("Unable to resolve Vulkan workload execute(Context) on " + workloadClass.getName());
    }

    private record ExecutionBinding(Class<?> contextInterface, Object proxy) {
    }

    private static final class DirectContextInvocationHandler
    implements InvocationHandler {
        private final VulkanContext context;

        private DirectContextInvocationHandler(VulkanContext context) {
            this.context = context;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "toString" -> "VulkanIsolatedContextProxy[" + this.context.deviceName() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length == 1 ? args[0] : null);
                    default -> null;
                };
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args == null ? new Object[0] : args);
            }
            return switch (name) {
                case "vectorAdd" -> this.context.vectorAdd((float[])args[0], (float[])args[1]);
                case "matrixMultiply" -> this.context.matrixMultiply((float[][])args[0], (float[][])args[1]);
                case "monteCarloPi" -> this.context.monteCarloPi((Integer)args[0]);
                case "terrainGeneration" -> this.context.terrainGeneration((float[])args[0]);
                case "dispatchSpirv" -> this.context.dispatchSpirv(
                    (String) args[0],
                    (byte[]) args[1],
                    (Integer) args[2],
                    (Integer) args[3],
                    (float[][]) args[4],
                    (Integer) args[5],
                    (int[]) args[6],
                    (Integer) args[7],
                    (Integer) args[8],
                    (Integer) args[9]);
                case "deviceName" -> this.context.deviceName();
                default -> throw new UnsupportedOperationException("Unsupported isolated Vulkan context method: " + name);
            };
        }
    }
}
