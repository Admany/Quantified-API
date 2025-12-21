package org.admany.quantified.core.common.telemetry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class Metrics {

    private static final ConcurrentHashMap<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();

    private Metrics() {}

    public static void increment(String name) {
        COUNTERS.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public static void add(String name, long value) {
        COUNTERS.computeIfAbsent(name, k -> new LongAdder()).add(value);
    }

    public static long get(String name) {
        LongAdder adder = COUNTERS.get(name);
        return adder != null ? adder.sum() : 0;
    }

    public static void reset(String name) {
        LongAdder adder = COUNTERS.get(name);
        if (adder != null) {
            adder.reset();
        }
    }

    public static void clear() {
        COUNTERS.clear();
    }

    public static ConcurrentHashMap<String, Long> snapshot() {
        ConcurrentHashMap<String, Long> snap = new ConcurrentHashMap<>();
        COUNTERS.forEach((k, v) -> snap.put(k, v.sum()));
        return snap;
    }
}