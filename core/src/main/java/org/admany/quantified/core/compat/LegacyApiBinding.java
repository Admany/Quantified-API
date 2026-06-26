package org.admany.quantified.core.compat;

/**
 * How a connected mod was bound to Quantified API at runtime.
 */
public enum LegacyApiBinding {
    /** Mod explicitly registered against Quantified API v2. */
    V2,
    /** Mod is using the v1.x integration surface (init/register without v2 opt-in, or legacy bytecode). */
    V1_LEGACY,
    /** Scanner found Quantified API usage but no registration yet. */
    DETECTED_UNREGISTERED
}
