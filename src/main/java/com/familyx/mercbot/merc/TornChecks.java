package com.familyx.mercbot.merc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads merit and faction-upgrade levels out of Torn API responses. The checks search the
 * whole response for the entry by id, so small changes in Torn's nesting don't break them.
 */
public final class TornChecks {

    /** Merit 8 is Hospitalisation in Torn's merit list (scope doc 5.1). */
    public static final int HOSP_MERIT_ID = 8;
    /** Faction upgrade 40 is Hospitalization X, needed at level 10 (scope doc 5.1). */
    public static final int HOSP_X_UPGRADE_ID = 40;
    public static final int MAX_LEVEL = 10;

    private TornChecks() {
    }

    /** True if /user/merits shows merit 8 at level 10. */
    public static boolean hospMeritsMaxed(JsonNode merits) {
        return levelOf(merits, HOSP_MERIT_ID) >= MAX_LEVEL;
    }

    /** True if /faction/upgrades shows Hospitalization X (id 40) at level 10, or unlocked by name. */
    public static boolean hasHospitalizationX(JsonNode upgrades) {
        return levelOf(upgrades, HOSP_X_UPGRADE_ID) >= MAX_LEVEL || hasNamed(upgrades, "Hospitalization X");
    }

    /** The highest "level" found on any object with this id, or -1 if none. */
    static int levelOf(JsonNode node, int id) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return -1;
        }
        int best = -1;
        if (node.isObject()) {
            if (node.path("id").asInt(-1) == id && node.has("level")) {
                best = node.path("level").asInt(-1);
            }
            for (JsonNode child : node) {
                best = Math.max(best, levelOf(child, id));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                best = Math.max(best, levelOf(child, id));
            }
        }
        return best;
    }

    private static boolean hasNamed(JsonNode node, String name) {
        if (node == null) {
            return false;
        }
        if (node.isObject() && name.equalsIgnoreCase(node.path("name").asText(""))) {
            return true;
        }
        for (JsonNode child : node) {
            if (hasNamed(child, name)) {
                return true;
            }
        }
        return false;
    }
}
