package org.admany.quantified.core.compat;

/**
 * How Quantified API should treat mods compiled for v1.x when running on v2.
 */
public enum LegacyApiPolicy {
    /**
     * Keep v1-compatible execution paths active and show an in-game notice that v2 perks are unavailable.
     */
    FALLBACK,
    /**
     * Treat the combination as unsupported and recommend downgrading Quantified API to the last v1 release.
     */
    WARN_DOWNGRADE
}
