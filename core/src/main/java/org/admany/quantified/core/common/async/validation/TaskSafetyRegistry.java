package org.admany.quantified.core.common.async.validation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.admany.quantified.core.common.async.task.TaskComputation;

public final class TaskSafetyRegistry {

    private static final ConcurrentHashMap<Class<?>, Boolean> OVERRIDES = new ConcurrentHashMap<>();

    private TaskSafetyRegistry() {
    }

    public static void markThreadSafe(Class<?> computationClass) {
        Objects.requireNonNull(computationClass, "computationClass");
        OVERRIDES.put(computationClass, Boolean.TRUE);
    }

    public static void markNotThreadSafe(Class<?> computationClass) {
        Objects.requireNonNull(computationClass, "computationClass");
        OVERRIDES.put(computationClass, Boolean.FALSE);
    }

    public static Optional<Boolean> threadSafetyOverride(Class<?> computationClass) {
        Objects.requireNonNull(computationClass, "computationClass");
        return Optional.ofNullable(OVERRIDES.get(computationClass));
    }

    static <T> Resolution resolve(TaskComputation<T> computation) {
        Objects.requireNonNull(computation, "computation");
        Optional<Boolean> override = threadSafetyOverride(computation.getClass());
        boolean resolved = override.orElseGet(computation::isThreadSafe);
        return new Resolution(resolved, override.isPresent());
    }

    public record Resolution(boolean threadSafe, boolean overrideUsed) {
    }
}
