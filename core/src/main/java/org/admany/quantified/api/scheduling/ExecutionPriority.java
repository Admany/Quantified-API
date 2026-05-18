package org.admany.quantified.api;

import org.admany.quantified.core.common.async.task.PriorityTaskType;

public enum ExecutionPriority {
    AUTO,
    FOREGROUND,
    BACKGROUND,
    CRITICAL;

    PriorityTaskType toTaskType() {
        return switch (this) {
            case AUTO -> PriorityTaskType.OTHER;
            case FOREGROUND, CRITICAL -> PriorityTaskType.FOREGROUND;
            case BACKGROUND -> PriorityTaskType.BACKGROUND;
        };
    }

    boolean isAuto() {
        return this == AUTO;
    }
}
