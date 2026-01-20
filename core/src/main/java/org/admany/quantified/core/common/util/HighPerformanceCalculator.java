package org.admany.quantified.core.common.util;

import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskComputation;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

public final class HighPerformanceCalculator {

    private HighPerformanceCalculator() {
    }

    public static CompletableFuture<Double> weightedAverage(long taskKey,
                                                             double[] values,
                                                             double[] weights) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(weights, "weights");
        if (values.length != weights.length) {
            throw new IllegalArgumentException("values and weights must be same length");
        }
        return submit(taskKey, PriorityTaskType.BUILDING, reduce(values, weights, Double::sum));
    }

    public static CompletableFuture<Double> standardDeviation(long taskKey,
                                                               double[] values) {
        Objects.requireNonNull(values, "values");
        TaskComputation<Double> computation = TaskComputation.sync(() -> {
            double mean = sum(values) / values.length;
            double varianceSum = 0.0;
            for (double value : values) {
                double diff = value - mean;
                varianceSum += diff * diff;
            }
            double variance = varianceSum / values.length;
            return Math.sqrt(variance);
        });
        return submit(taskKey, PriorityTaskType.NOISE, computation);
    }

    public static CompletableFuture<Double> reduce(long taskKey,
                                                    double[] values,
                                                    DoubleBinaryOperator reducer,
                                                    double identity) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(reducer, "reducer");
        TaskComputation<Double> computation = TaskComputation.sync(() -> {
            double result = identity;
            for (double value : values) {
                result = reducer.applyAsDouble(result, value);
            }
            return result;
        });
        return submit(taskKey, PriorityTaskType.BACKGROUND, computation);
    }

    private static CompletableFuture<Double> submit(long key,
                                                    PriorityTaskType type,
                                                    TaskComputation<Double> computation) {
        return AsyncManager.submit(key, type, type.defaultScore(), computation, "quantified-calculator");
    }

    private static TaskComputation<Double> reduce(double[] values,
                                                  double[] weights,
                                                  DoubleBinaryOperator aggregator) {
        return TaskComputation.sync(() -> {
            double numerator = 0.0;
            double denominator = 0.0;
            for (int i = 0; i < values.length; i++) {
                double value = values[i];
                double weight = weights[i];
                numerator = aggregator.applyAsDouble(numerator, value * weight);
                denominator += weight;
            }
            return denominator == 0.0 ? 0.0 : numerator / denominator;
        });
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    public static CompletableFuture<Double> monteCarloIntegration(long taskKey,
                                                                   int samples,
                                                                   DoubleUnaryOperator function,
                                                                   double min,
                                                                   double max) {
        Objects.requireNonNull(function, "function");
        if (samples <= 0) {
            throw new IllegalArgumentException("samples must be positive");
        }
        return submit(taskKey, PriorityTaskType.NOISE, TaskComputation.async(() -> CompletableFuture.supplyAsync(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double sum = 0.0;
            double range = max - min;
            for (int i = 0; i < samples; i++) {
                double x = min + random.nextDouble() * range;
                sum += function.applyAsDouble(x);
            }
            return (sum / samples) * range;
        }, Runnable::run)));
    }
}
