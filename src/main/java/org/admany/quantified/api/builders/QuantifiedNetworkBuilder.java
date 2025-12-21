package org.admany.quantified.api.builders;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface QuantifiedNetworkBuilder {
    QuantifiedNetworkBuilder chunkSize(int bytes);
    QuantifiedNetworkBuilder timeout(Duration timeout);
    QuantifiedNetworkBuilder retryPolicy(RetryPolicy policy);
    QuantifiedNetworkBuilder callback(Consumer<Object> responseCallback);
    QuantifiedNetworkBuilder withQoS(QoS qos);

    CompletableFuture<Void> send(Object data);
    CompletableFuture<Void> sendToAll(Object data);
    <T> CompletableFuture<T> sendAndReceive(Object data);

    enum RetryPolicy {
        NONE, LINEAR_BACKOFF, EXPONENTIAL_BACKOFF
    }

    enum QoS {
        REALTIME, HIGH, NORMAL, BULK
    }
}