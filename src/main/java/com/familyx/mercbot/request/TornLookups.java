package com.familyx.mercbot.request;

import com.familyx.mercbot.torn.TornApiException;
import com.familyx.mercbot.torn.TornKeyPool;
import com.familyx.mercbot.torn.TornKeyPool.Scope;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public Torn lookups for the request flow, made with any healthy pool key: who a Discord user
 * is, players, faction members and ranked wars (the "ranked-war cache", scope doc 4.3).
 */
@Service
public class TornLookups {

    private static final long WAR_CACHE_MS = 10 * 60_000;

    /** A Torn player as the request flow needs them. */
    public record Player(long id, String name, int level, Long factionId, String factionName, boolean revivable) { }

    /** A faction with its current members, sorted by name. */
    public record Faction(long id, String name, List<Player> members) { }

    private record CachedWar(Optional<Scenarios.War> war, long at) { }

    private final TornKeyPool pool;
    private final Map<Long, CachedWar> wars = new ConcurrentHashMap<>();

    public TornLookups(TornKeyPool pool) {
        this.pool = pool;
    }

    /** The Torn account linked to a Discord account, or empty if there is none. */
    public Optional<Long> tornIdForDiscord(long discordId) throws Exception {
        try {
            JsonNode d = pool.call(Scope.any(), "/user/" + discordId + "/discord", Map.of()).path("discord");
            for (String field : List.of("player_id", "user_id", "id")) {
                long id = d.path(field).asLong(0);
                if (id > 0) {
                    return Optional.of(id);
                }
            }
            return Optional.empty();
        } catch (TornApiException e) {
            if (e.code() == 6 || e.code() == 7) { // incorrect id / not linked
                return Optional.empty();
            }
            throw e;
        }
    }

    public Player player(long tornId) throws Exception {
        JsonNode p = pool.call(Scope.any(), "/user/" + tornId + "/profile", Map.of()).path("profile");
        JsonNode f = p.path("faction_id");
        Long factionId = f.isNull() || f.isMissingNode() || f.asLong() == 0 ? null : f.asLong();
        return new Player(p.path("id").asLong(tornId), p.path("name").asText("Player " + tornId),
                p.path("level").asInt(0), factionId, null, p.path("revivable").asBoolean(false));
    }

    /** A faction found by name: its ID and name. */
    public record FactionMatch(long id, String name) { }

    /**
     * Factions whose name matches {@code name} (Torn's /faction/search), up to 20, leaving out
     * destroyed factions. Response: {"search": [{"id", "name", "is_destroyed", ...}], "_metadata": ...}.
     */
    public List<FactionMatch> searchFactions(String name) throws Exception {
        JsonNode list = pool.call(Scope.any(), "/faction/search", Map.of("name", name, "limit", "20")).path("search");
        List<FactionMatch> found = new java.util.ArrayList<>();
        for (JsonNode f : list) {
            if (f.path("is_destroyed").asBoolean(false) || !f.has("id") || !f.has("name")) {
                continue;
            }
            found.add(new FactionMatch(f.path("id").asLong(), f.path("name").asText()));
        }
        return found;
    }

    public Faction faction(long factionId) throws Exception {
        String name = pool.call(Scope.any(), "/faction/" + factionId + "/basic", Map.of())
                .path("basic").path("name").asText("Faction " + factionId);
        JsonNode members = pool.call(Scope.any(), "/faction/" + factionId + "/members", Map.of()).path("members");
        List<Player> list = new ArrayList<>();
        for (JsonNode m : members) {
            list.add(new Player(m.path("id").asLong(), m.path("name").asText("?"), m.path("level").asInt(0),
                    factionId, name, m.path("is_revivable").asBoolean(false)));
        }
        list.sort(Comparator.comparing(p -> p.name().toLowerCase()));
        return new Faction(factionId, name, list);
    }

    /** The faction's ranked war if it's scheduled or active (cached for 10 minutes). */
    public Optional<Scenarios.War> war(long factionId) {
        CachedWar cached = wars.get(factionId);
        if (cached != null && System.currentTimeMillis() - cached.at() < WAR_CACHE_MS) {
            return cached.war();
        }
        Optional<Scenarios.War> war;
        try {
            JsonNode ranked = pool.call(Scope.any(), "/faction/" + factionId + "/wars", Map.of())
                    .path("wars").path("ranked");
            war = parseWar(ranked, factionId, Instant.now());
        } catch (Exception e) {
            return cached == null ? Optional.empty() : cached.war();
        }
        wars.put(factionId, new CachedWar(war, System.currentTimeMillis()));
        return war;
    }

    static Optional<Scenarios.War> parseWar(JsonNode ranked, long factionId, Instant now) {
        if (ranked == null || ranked.isMissingNode() || ranked.isNull() || !ranked.has("start")) {
            return Optional.empty();
        }
        long end = ranked.path("end").asLong(0);
        if (end > 0 && Instant.ofEpochSecond(end).isBefore(now)) {
            return Optional.empty(); // finished
        }
        long opponent = 0;
        String opponentName = null;
        for (JsonNode f : ranked.path("factions")) {
            if (f.path("id").asLong() != factionId) {
                opponent = f.path("id").asLong();
                opponentName = f.path("name").asText(null);
            }
        }
        return Optional.of(new Scenarios.War(opponent, opponentName, Instant.ofEpochSecond(ranked.path("start").asLong()),
                end > 0 ? Instant.ofEpochSecond(end) : null));
    }
}
