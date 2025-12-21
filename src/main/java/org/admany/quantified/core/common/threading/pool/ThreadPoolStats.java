package org.admany.quantified.core.common.threading.pool;

import java.time.Instant;

public record ThreadPoolStats(Instant capturedAt,
                              long submitted,
                              long executed,
                              long foregroundExecuted,
                              long backgroundExecuted,
                              long suppressedDuplicates,
                              long dropped,
                              int coalesced,
                              int foregroundQueue,
                              int backgroundQueue,
                              long workerCrashes,
                              int desiredForegroundWorkers,
                              int desiredBackgroundWorkers) {
}
