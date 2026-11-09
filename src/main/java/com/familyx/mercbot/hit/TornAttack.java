package com.familyx.mercbot.hit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One row of a faction's attack log (Torn API v2 /faction/attacks). Stealthed attackers come
 * back without an id, so {@code attackerId} can be null.
 */
public record TornAttack(long id, String code, Instant started, Instant ended, Long attackerId, String attackerName,
                     Long attackerFactionId, long defenderId, String defenderName, String result, boolean stricken,
                     Integer strickenValue) {

    public boolean finishing() {
        return "Hospitalized".equalsIgnoreCase(result) || "Mugged".equalsIgnoreCase(result);
    }

    public boolean assist() {
        return "Assist".equalsIgnoreCase(result);
    }

    /** Parses the "attacks" list; rows it can't read are skipped. */
    public static List<TornAttack> parse(JsonNode attacks) {
        List<TornAttack> out = new ArrayList<>();
        for (JsonNode a : attacks) {
            JsonNode def = a.path("defender");
            if (!a.has("id") || !def.has("id")) {
                continue;
            }
            JsonNode att = a.path("attacker");
            Long attackerId = att.has("id") && !att.path("id").isNull() ? att.path("id").asLong() : null;
            JsonNode attFaction = att.path("faction");
            Long attackerFaction = attFaction.has("id") && !attFaction.path("id").isNull()
                    ? attFaction.path("id").asLong() : null;
            boolean stricken = false;
            Integer strickenValue = null;
            for (JsonNode e : a.path("finishing_hit_effects")) {
                String name = e.isTextual() ? e.asText() : e.path("name").asText("");
                if ("stricken".equalsIgnoreCase(name.trim())) {
                    stricken = true;
                    if (e.has("value") && !e.path("value").isNull()) {
                        strickenValue = e.path("value").asInt();
                    }
                }
            }
            long ended = a.path("ended").asLong(0);
            out.add(new TornAttack(a.path("id").asLong(), a.path("code").asText(null),
                    Instant.ofEpochSecond(a.path("started").asLong()), ended == 0 ? null : Instant.ofEpochSecond(ended),
                    attackerId, att.path("name").asText(null), attackerFaction, def.path("id").asLong(),
                    def.path("name").asText(null), a.path("result").asText(""), stricken, strickenValue));
        }
        return out;
    }

    /** The finishing attack plus the assists in the same fight. */
    public record Fight(TornAttack finish, List<TornAttack> assists) { }

    /**
     * Groups a batch into fights: each Hospitalized/Mugged row with the Assist rows on the same
     * defender that share its log code, or (when codes differ) overlap its time.
     */
    public static List<Fight> fights(List<TornAttack> batch) {
        List<Fight> out = new ArrayList<>();
        for (TornAttack f : batch) {
            if (!f.finishing()) {
                continue;
            }
            List<TornAttack> assists = new ArrayList<>();
            Instant end = f.ended() == null ? f.started().plusSeconds(300) : f.ended();
            for (TornAttack a : batch) {
                if (!a.assist() || a.defenderId() != f.defenderId()) {
                    continue;
                }
                boolean sameCode = f.code() != null && f.code().equals(a.code());
                boolean overlaps = !a.started().isAfter(end.plusSeconds(5)) && !a.started().isBefore(f.started().minusSeconds(300));
                if (sameCode || overlaps) {
                    assists.add(a);
                }
            }
            out.add(new Fight(f, assists));
        }
        return out;
    }
}
