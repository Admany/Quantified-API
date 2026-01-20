package org.admany.quantified.core.common.threading.core;

public enum ThreadRole {
    FOREGROUND_WORKER,
    BACKGROUND_WORKER,
    HOUSEKEEPER,
    COALESCER,
    FINALIZER,
    TELEMETRY,
    UNKNOWN;
}
