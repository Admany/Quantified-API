package org.admany.quantified.api.builders;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface QuantifiedHybridBuilder {
    QuantifiedHybridBuilder priority(QuantifiedTaskBuilder.Priority priority);
    QuantifiedHybridBuilder timeout(Duration timeout);
    QuantifiedHybridBuilder ttl(Duration ttl);
    QuantifiedHybridBuilder maxSize(long maxSize);
    QuantifiedHybridBuilder compression(boolean enabled);
    QuantifiedHybridBuilder conditional(java.util.function.Supplier<Boolean> condition);
    QuantifiedHybridBuilder allowMainThreadRerouting(boolean allow);

    <T> CompletableFuture<T> submit(Supplier<T> work);
}