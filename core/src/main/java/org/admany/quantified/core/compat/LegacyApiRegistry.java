package org.admany.quantified.core.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LegacyApiRegistry {

    public static final String LAST_V1_RELEASE = "1.4.4";
    public static final String CURRENT_API_MAJOR = "2";

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyApiRegistry.class);
    private static final Map<String, LegacyModRecord> MODS = new ConcurrentHashMap<>();

    private LegacyApiRegistry() {
    }

    public record LegacyModRecord(
        String modId,
        String displayName,
        LegacyApiBinding binding,
        String reason
    ) {}

    public static void markV2(String modId, String displayName) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        MODS.put(modId, new LegacyModRecord(modId, safeName(displayName, modId), LegacyApiBinding.V2, "registerV2"));
    }

    public static void markLegacyRegistration(String modId, String displayName, String reason) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        MODS.compute(modId, (id, existing) -> {
            if (existing != null && existing.binding == LegacyApiBinding.V2) {
                return existing;
            }
            return new LegacyModRecord(id, safeName(displayName, id), LegacyApiBinding.V1_LEGACY, reason);
        });
    }

    public static void markDetected(String modId, String displayName, String reason) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        MODS.computeIfAbsent(modId, id -> new LegacyModRecord(
            id,
            safeName(displayName, id),
            LegacyApiBinding.DETECTED_UNREGISTERED,
            reason
        ));
    }

    public static boolean isLegacyMod(String modId) {
        LegacyModRecord record = MODS.get(modId);
        return record != null && record.binding != LegacyApiBinding.V2;
    }

    public static boolean isLegacyHandle(String modId) {
        return isLegacyMod(modId);
    }

    public static List<LegacyModRecord> legacyMods() {
        List<LegacyModRecord> out = new ArrayList<>();
        for (LegacyModRecord record : MODS.values()) {
            if (record.binding != LegacyApiBinding.V2) {
                out.add(record);
            }
        }
        out.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return Collections.unmodifiableList(out);
    }

    public static boolean hasLegacyMods() {
        return !legacyMods().isEmpty();
    }

    public static Map<String, LegacyModRecord> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(MODS));
    }

    public static void logSummary() {
        List<LegacyModRecord> legacy = legacyMods();
        if (legacy.isEmpty()) {
            LOGGER.info("Quantified API v{}: no legacy v1.x mod integrations detected.", CURRENT_API_MAJOR);
            return;
        }
        LOGGER.warn(
            "Quantified API v{}: {} mod(s) are on the legacy v1.x integration path: {}",
            CURRENT_API_MAJOR,
            legacy.size(),
            legacy.stream().map(LegacyModRecord::modId).toList()
        );
    }

    private static String safeName(String displayName, String modId) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return modId;
    }
}
