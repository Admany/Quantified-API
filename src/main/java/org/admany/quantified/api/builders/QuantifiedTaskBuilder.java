package org.admany.quantified.api.builders;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface QuantifiedTaskBuilder {
    QuantifiedTaskBuilder priority(Priority priority);
    QuantifiedTaskBuilder timeout(Duration timeout);
    QuantifiedTaskBuilder retry(int maxRetries);

    QuantifiedTaskBuilder resourceEstimate(long vramBytes, int computeUnits);
    QuantifiedTaskBuilder onProgress(Consumer<Double> progressCallback);
    QuantifiedTaskBuilder onFailure(Consumer<Throwable> failureCallback);
    QuantifiedTaskBuilder allowMainThreadRerouting(boolean allow);

    <T> CompletableFuture<T> submit(Supplier<T> work);

    enum Priority {
        AUTO, FOREGROUND, BACKGROUND, CRITICAL
    }
}