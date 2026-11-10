package com.familyx.mercbot.request;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Requesters, discounts and protected factions (scope doc 6.5, 7.1, 7.6). */
@Repository
public class RequestRepositories {

    public record Requester(long tornId, long discordId, String name, Long factionId, int score,
                            boolean blacklisted, String blacklistReason, Instant dmOkAt) { }

    private final JdbcClient jdbc;

    public RequestRepositories(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Requester> requester(long tornId) {
        return jdbc.sql("""
                        SELECT torn_id, discord_id, name, faction_id, score, blacklisted, blacklist_reason, dm_ok_at
                        FROM requester WHERE torn_id = ?""")
                .param(tornId)
                .query((rs, n) -> {
                    long f = rs.getLong("faction_id");
                    Long faction = rs.wasNull() ? null : f;
                    OffsetDateTime dm = rs.getObject("dm_ok_at", OffsetDateTime.class);
                    return new Requester(rs.getLong("torn_id"), rs.getLong("discord_id"), rs.getString("name"),
                            faction, rs.getInt("score"), rs.getBoolean("blacklisted"),
                            rs.getString("blacklist_reason"), dm == null ? null : dm.toInstant());
                })
                .optional();
    }

    /** Records (or refreshes) a requester's Torn identity. New requesters start at a score of 50. */
    public void upsertRequester(long tornId, long discordId, String name, Long factionId, int startingScore) {
        jdbc.sql("""
                        INSERT INTO requester (torn_id, discord_id, name, faction_id, score) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (torn_id) DO UPDATE
                        SET discord_id = EXCLUDED.discord_id, name = EXCLUDED.name, faction_id = EXCLUDED.faction_id""")
                .params(tornId, discordId, name, factionId, startingScore).update();
    }

    public void markDmOk(long tornId) {
        jdbc.sql("UPDATE requester SET dm_ok_at = now() WHERE torn_id = ?").param(tornId).update();
    }

    /** The latest personal discount for a player, if any. */
    public Optional<BigDecimal> personalDiscount(long tornId) {
        return jdbc.sql("""
                        SELECT pct FROM discount WHERE scope = 'PERSON' AND torn_id = ?
                        ORDER BY created_at DESC, id DESC LIMIT 1""")
                .param(tornId).query(BigDecimal.class).optional();
    }

    /** The latest faction discount, if any (family factions get theirs here too). */
    public Optional<BigDecimal> factionDiscount(long factionId) {
        return jdbc.sql("""
                        SELECT pct FROM discount WHERE scope = 'FACTION' AND faction_id = ?
                        ORDER BY created_at DESC, id DESC LIMIT 1""")
                .param(factionId).query(BigDecimal.class).optional();
    }

    public Set<Long> protectedFactions() {
        return new HashSet<>(jdbc.sql("SELECT torn_faction_id FROM protected_faction").query(Long.class).list());
    }
}
