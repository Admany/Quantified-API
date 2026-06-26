package org.admany.quantified.core.client.legacy;

import org.admany.quantified.core.compat.LegacyApiRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs legacy API usage once per client session.
 */
public final class LegacyApiClientNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyApiClientNotifier.class);
    private static boolean initialized;

    private LegacyApiClientNotifier() {
    }

    public static void initialize(Logger logger) {
        if (initialized) {
            return;
        }
        initialized = true;
        Logger target = logger != null ? logger : LOGGER;
        if (!LegacyApiRegistry.hasLegacyMods()) {
            target.debug("Quantified API legacy warning notifier initialized; no v1.x integrations detected.");
            return;
        }
        target.warn(
            "Quantified API v{} detected {} mod(s) using the legacy v1.x integration path (latest v1 release was {}). "
                + "V1 compatibility will use fallback behavior; update these mods to the V2 API: {}",
            LegacyApiRegistry.CURRENT_API_MAJOR,
            LegacyApiRegistry.legacyMods().size(),
            LegacyApiRegistry.LAST_V1_RELEASE,
            LegacyApiRegistry.legacyMods().stream()
                .map(record -> record.modId() + "[" + record.binding() + ": " + record.reason() + "]")
                .toList()
        );
    }
}
