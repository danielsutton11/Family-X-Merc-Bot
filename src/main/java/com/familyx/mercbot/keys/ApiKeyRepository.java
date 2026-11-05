package com.familyx.mercbot.keys;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ApiKeyRepository {

    public enum Kind { LEADER, MERC }

    public record StoredKey(long id, long ownerTornId, long ownerDiscordId, Long factionId, Kind kind,
                            byte[] keyEnc, boolean healthy, String unhealthyReason, boolean bspActive,
                            Instant bspExpiresAt) {
    }

    /** Per-faction key summary for the leader panel and minimum-key checks. */
    public record FactionKeyCount(long factionId, int total, int healthy, int bsp) {
    }

    private static final String COLUMNS = """
            id, owner_torn_id, owner_discord_id, faction_id, kind, key_enc, healthy, unhealthy_reason,
            bsp_active, bsp_expires_at""";

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByHash(String hash) {
        return jdbc.sql("SELECT count(*) FROM api_key WHERE key_hash = ?").param(hash)
                .query(Integer.class).single() > 0;
    }

    public long insert(long ownerTornId, long ownerDiscordId, Long factionId, Kind kind, byte[] keyEnc, String hash) {
        return jdbc.sql("""
                        INSERT INTO api_key (owner_torn_id, owner_discord_id, faction_id, kind, key_enc, key_hash, last_checked_at)
                        VALUES (?, ?, ?, ?, ?, ?, now()) RETURNING id""")
                .params(ownerTornId, ownerDiscordId, factionId, kind.name(), keyEnc, hash)
                .query(Long.class).single();
    }

    public List<StoredKey> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM api_key").query(this::map).list();
    }

    public List<StoredKey> findByOwner(long discordId, Kind kind) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM api_key WHERE owner_discord_id = ? AND kind = ?")
                .params(discordId, kind.name()).query(this::map).list();
    }

    public Optional<StoredKey> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM api_key WHERE id = ?").param(id).query(this::map).optional();
    }

    public int deleteByOwner(long discordId, Kind kind) {
        return jdbc.sql("DELETE FROM api_key WHERE owner_discord_id = ? AND kind = ?")
                .params(discordId, kind.name()).update();
    }

    public void markUnhealthy(long id, String reason) {
        jdbc.sql("UPDATE api_key SET healthy = FALSE, unhealthy_reason = ?, last_checked_at = now() WHERE id = ?")
                .params(reason, id).update();
    }

    public void markHealthy(long id) {
        jdbc.sql("UPDATE api_key SET healthy = TRUE, unhealthy_reason = NULL, last_checked_at = now() WHERE id = ?")
                .param(id).update();
    }

    public void setBsp(long id, boolean active, Instant expiresAt) {
        jdbc.sql("UPDATE api_key SET bsp_active = ?, bsp_expires_at = ?, bsp_checked_at = now() WHERE id = ?")
                .params(active, expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), id)
                .update();
    }

    public Map<Long, FactionKeyCount> leaderKeyCountsByFaction() {
        return jdbc.sql("""
                        SELECT faction_id,
                               count(*)                                   AS total,
                               count(*) FILTER (WHERE healthy)            AS healthy,
                               count(*) FILTER (WHERE healthy AND bsp_active) AS bsp
                        FROM api_key WHERE kind = 'LEADER' AND faction_id IS NOT NULL
                        GROUP BY faction_id""")
                .query((rs, n) -> new FactionKeyCount(rs.getLong("faction_id"), rs.getInt("total"),
                        rs.getInt("healthy"), rs.getInt("bsp")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(FactionKeyCount::factionId, c -> c));
    }

    private StoredKey map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        long factionValue = rs.getLong("faction_id");
        Long faction = rs.wasNull() ? null : factionValue;
        OffsetDateTime bspExp = rs.getObject("bsp_expires_at", OffsetDateTime.class);
        return new StoredKey(
                rs.getLong("id"),
                rs.getLong("owner_torn_id"),
                rs.getLong("owner_discord_id"),
                faction,
                Kind.valueOf(rs.getString("kind")),
                rs.getBytes("key_enc"),
                rs.getBoolean("healthy"),
                rs.getString("unhealthy_reason"),
                rs.getBoolean("bsp_active"),
                bspExp == null ? null : bspExp.toInstant());
    }
}
