package org.admany.quantified.core.common.threading.health;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public final class ThreadHealthSnapshot {

    private final Instant capturedAt;
    private final List<ThreadHealthStatus> statuses;
    private final int stalledCount;
    private final int terminatedCount;

    public ThreadHealthSnapshot(Instant capturedAt,
                                List<ThreadHealthStatus> statuses,
                                int stalledCount,
                                int terminatedCount) {
        this.capturedAt = capturedAt;
        this.statuses = List.copyOf(statuses);
        this.stalledCount = stalledCount;
        this.terminatedCount = terminatedCount;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public List<ThreadHealthStatus> statuses() {
        return Collections.unmodifiableList(statuses);
    }

    public int stalledCount() {
        return stalledCount;
    }

    public int terminatedCount() {
        return terminatedCount;
    }
}
