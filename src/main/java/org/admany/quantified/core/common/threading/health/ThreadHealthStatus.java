package org.admany.quantified.core.common.threading.health;

import java.time.Duration;
import java.time.Instant;

import org.admany.quantified.core.common.threading.core.ThreadRole;

public record ThreadHealthStatus(long threadId,
                                 String threadName,
                                 ThreadRole role,
                                 Instant lastHeartbeat,
                                 Duration idleDuration,
                                 int crashCount,
                                 ThreadHealthState state) {
}
