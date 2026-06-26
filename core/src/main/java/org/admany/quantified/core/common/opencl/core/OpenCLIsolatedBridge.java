package org.admany.quantified.core.common.opencl.core;

import org.admany.quantified.core.common.opencl.gpu.GPUDetector;
import org.lwjgl.PointerBuffer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class OpenCLIsolatedBridge {

    private static final Object LOCK = new Object();
    private static volatile OpenCLContext context;
    private static volatile GPUDetector.GPUCapabilities capabilities;

    private OpenCLIsolatedBridge() {
    }

    public static boolean isAvailable() {
        try {
            ensureContext();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object executeApiTask(Object parentApiTask) throws Exception {
        Objects.requireNonNull(parentApiTask, "parentApiTask");
        OpenCLContext ctx = ensureContext();
        Object workload = extractWorkload(parentApiTask);
        ClassLoader parentLoader = parentApiTask.getClass().getClassLoader();
        Class<?> contextInterface = Class.forName("org.admany.quantified.api.opencl.QuantifiedOpenCL$Context", false, parentLoader);
        Object proxy = Proxy.newProxyInstance(
            parentLoader,
            new Class<?>[]{contextInterface},
            new ParentContextInvocationHandler(ctx)
        );
        Method execute = findExecuteMethod(workload.getClass(), contextInterface);
        return execute.invoke(workload, proxy);
    }

    private static OpenCLContext ensureContext() {
        OpenCLContext existing = context;
        if (existing != null && existing.isHealthy()) {
            return existing;
        }
        synchronized (LOCK) {
            existing = context;
            if (existing != null && existing.isHealthy()) {
                return existing;
            }
            GPUDetector.GPUCapabilities detected = GPUDetector.detectCapabilities();
            if (!detected.supported()) {
                String reason = detected.failureReason() != null ? detected.failureReason() : "Isolated OpenCL device detection failed";
                throw new IllegalStateException(reason);
            }
            OpenCLContext created = OpenCLContext.create(detected);
            capabilities = detected;
            context = created;
            return created;
        }
    }

    private static Object extractWorkload(Object parentApiTask) throws Exception {
        Field specField = parentApiTask.getClass().getDeclaredField("spec");
        specField.setAccessible(true);
        Object spec = specField.get(parentApiTask);
        Method workloadMethod = spec.getClass().getMethod("workload");
        Object workload = workloadMethod.invoke(spec);
        if (workload == null) {
            throw new IllegalStateException("Quantified OpenCL task has no workload attached");
        }
        return workload;
    }

    private static Method findExecuteMethod(Class<?> workloadClass, Class<?> contextType) {
        for (Method method : workloadClass.getMethods()) {
            if (!"execute".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter.isAssignableFrom(contextType) || contextType.isAssignableFrom(parameter)) {
                return method;
            }
        }
        throw new IllegalStateException("Unable to resolve workload execute(Context) on " + workloadClass.getName());
    }

    private static final class ParentContextInvocationHandler implements InvocationHandler {
        private final OpenCLContext delegate;

        private ParentContextInvocationHandler(OpenCLContext delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "toString" -> "OpenCLIsolatedContextProxy[" + deviceName() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length == 1 ? args[0] : null);
                    default -> method.invoke(this, args);
                };
            }
            return switch (name) {
                case "createKernel" -> delegate.createKernel((String) args[0]);
                case "releaseKernel" -> {
                    delegate.releaseKernel((Long) args[0]);
                    yield null;
                }
                case "createBuffer" -> delegate.createBuffer((Long) args[0], (Long) args[1]);
                case "releaseBuffer" -> {
                    delegate.releaseBuffer((Long) args[0]);
                    yield null;
                }
                case "enqueueWriteBuffer" -> {
                    delegate.enqueueWriteBuffer((Long) args[0], (Boolean) args[1], (Long) args[2], (Long) args[3], (ByteBuffer) args[4]);
                    yield null;
                }
                case "enqueueReadBuffer" -> {
                    delegate.enqueueReadBuffer((Long) args[0], (Boolean) args[1], (Long) args[2], (Long) args[3], (ByteBuffer) args[4]);
                    yield null;
                }
                case "setKernelArgBuffer" -> {
                    delegate.setKernelArgBuffer((Long) args[0], (Integer) args[1], (Long) args[2]);
                    yield null;
                }
                case "setKernelArg" -> {
                    delegate.setKernelArg((Long) args[0], (Integer) args[1], (Long) args[2]);
                    yield null;
                }
                case "enqueueNDRangeKernel" -> {
                    delegate.enqueueNDRangeKernel((Long) args[0], (Integer) args[1], convertPointerBuffer(args[2], (Integer) args[1]));
                    yield null;
                }
                case "finish" -> {
                    delegate.finish();
                    yield null;
                }
                case "vectorAdd" -> delegate.vectorAdd((float[]) args[0], (float[]) args[1]);
                case "matrixMultiply" -> delegate.matrixMultiply((float[][]) args[0], (float[][]) args[1]);
                case "monteCarloPi" -> delegate.monteCarloPi((Integer) args[0]);
                default -> throw new UnsupportedOperationException("Unsupported isolated OpenCL context method: " + name);
            };
        }

        private String deviceName() {
            GPUDetector.GPUCapabilities current = capabilities;
            if (current != null && current.device() != null) {
                return current.device().name();
            }
            return "unknown";
        }

        private PointerBuffer convertPointerBuffer(Object parentPointerBuffer, int expectedLength) throws Exception {
            if (parentPointerBuffer == null) {
                return null;
            }
            Method getMethod = parentPointerBuffer.getClass().getMethod("get", int.class);
            PointerBuffer child = PointerBuffer.allocateDirect(Math.max(1, expectedLength));
            for (int i = 0; i < expectedLength; i++) {
                child.put(i, ((Number) getMethod.invoke(parentPointerBuffer, i)).longValue());
            }
            child.position(0);
            return child;
        }
    }
}
