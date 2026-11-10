package com.familyx.mercbot.merc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Signed-up mercs and their preferences (scope doc 5.1, data model 10.6). */
@Repository
public class MercRepository {

    public enum PingPref { ALL, NEW_ONLY, OFF }

    public enum DmPref { EVERY_HIT, HOURLY, OFF }

    public record Merc(long tornId, long discordId, String name, Long factionId, boolean meritsOk,
                       boolean strickenOptIn, boolean available, Instant availableUntil, PingPref pingPref,
                       DmPref dmPref, String status, Instant joinedAt) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        /** Stricken only counts while merits are verified (scope doc 3, roles table). */
        public boolean strickenActive() {
            return strickenOptIn && meritsOk;
        }
    }

    private static final String COLUMNS = """
            torn_id, discord_id, name, faction_id, merits_ok, stricken_opt_in, available, available_until,
            ping_pref, dm_pref, status, joined_at""";

    private final JdbcClient jdbc;

    public MercRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Merc> findByDiscord(long discordId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM merc WHERE discord_id = ?").param(discordId)
                .query(this::map).optional();
    }

    public Optional<Merc> findByTorn(long tornId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM merc WHERE torn_id = ?").param(tornId)
                .query(this::map).optional();
    }

    public List<Merc> findActive() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM merc WHERE status = 'ACTIVE'").query(this::map).list();
    }

    /**
     * Signs a merc up, or re-activates them if they left before. A different Discord account
     * previously tied to the same Torn account (or the reverse) is replaced.
     */
    public void upsert(long tornId, long discordId, String name, Long factionId, boolean meritsOk) {
        jdbc.sql("DELETE FROM merc WHERE discord_id = ? AND torn_id <> ?").params(discordId, tornId).update();
        jdbc.sql("""
                        INSERT INTO merc (torn_id, discord_id, name, faction_id, merits_ok, merits_checked_at)
                        VALUES (?, ?, ?, ?, ?, now())
                        ON CONFLICT (torn_id) DO UPDATE
                        SET discord_id = EXCLUDED.discord_id, name = EXCLUDED.name,
                            faction_id = EXCLUDED.faction_id, merits_ok = EXCLUDED.merits_ok,
                            merits_checked_at = now(), status = 'ACTIVE',
                            stricken_opt_in = merc.stricken_opt_in AND EXCLUDED.merits_ok""")
                .params(tornId, discordId, name, factionId, meritsOk).update();
    }

    public void updateCheck(long tornId, String name, Long factionId, boolean meritsOk) {
        jdbc.sql("""
                        UPDATE merc SET name = coalesce(?, name), faction_id = ?, merits_ok = ?,
                               merits_checked_at = now(),
                               stricken_opt_in = stricken_opt_in AND ?
                        WHERE torn_id = ?""")
                .params(name, factionId, meritsOk, meritsOk, tornId).update();
    }

    public void setStricken(long tornId, boolean on) {
        jdbc.sql("UPDATE merc SET stricken_opt_in = ? WHERE torn_id = ?").params(on, tornId).update();
    }

    /** {@code until} null with {@code available} false means off until they turn it back on. */
    /** True (and stamps the time) when an "availability is off" DM is due: none within the last N hours. */
    public boolean availabilityReminderDue(long tornId, int hours) {
        return jdbc.sql("""
                        UPDATE merc SET avail_reminded_at = now()
                        WHERE torn_id = ? AND (avail_reminded_at IS NULL OR avail_reminded_at < now() - make_interval(hours => ?))""")
                .params(tornId, hours).update() == 1;
    }

    public void setAvailability(long tornId, boolean available, Instant until) {
        jdbc.sql("UPDATE merc SET available = ?, available_until = ? WHERE torn_id = ?")
                .params(available, until == null ? null : OffsetDateTime.ofInstant(until, ZoneOffset.UTC), tornId)
                .update();
    }

    /** Mercs whose timed "off" has run out. */
    public List<Merc> availabilityExpired() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM merc WHERE status = 'ACTIVE' AND NOT available "
                        + "AND available_until IS NOT NULL AND available_until <= now()")
                .query(this::map).list();
    }

    public void setPingPref(long tornId, PingPref pref) {
        jdbc.sql("UPDATE merc SET ping_pref = ? WHERE torn_id = ?").params(pref.name(), tornId).update();
    }

    public void setDmPref(long tornId, DmPref pref) {
        jdbc.sql("UPDATE merc SET dm_pref = ? WHERE torn_id = ?").params(pref.name(), tornId).update();
    }

    public void markLeft(long tornId) {
        jdbc.sql("UPDATE merc SET status = 'LEFT' WHERE torn_id = ?").param(tornId).update();
    }

    // ------------------------------------------------------------------ tier opt-ins

    /**
     * The tiers a merc has opted out of. Opt-in is the default, so a merc with no rows wants
     * every tier, including ones added later.
     */
    public Set<Integer> optedOut(long tornId) {
        return new HashSet<>(jdbc.sql("SELECT tier_id FROM merc_tier_optin WHERE merc_id = ? AND NOT opted_in")
                .param(tornId).query(Integer.class).list());
    }

    /** Opt-outs for every merc, keyed by Torn id. */
    public Map<Long, Set<Integer>> allOptedOut() {
        Map<Long, Set<Integer>> out = new HashMap<>();
        jdbc.sql("SELECT merc_id, tier_id FROM merc_tier_optin WHERE NOT opted_in")
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    out.computeIfAbsent(rs.getLong("merc_id"), k -> new HashSet<>()).add(rs.getInt("tier_id"));
                });
        return out;
    }

    /** Stores the merc's choice: every tier in {@code wanted} on, every other active tier off. */
    public void setOptIns(long tornId, Set<Integer> wanted, List<Integer> allTierIds) {
        for (int tierId : allTierIds) {
            jdbc.sql("""
                            INSERT INTO merc_tier_optin (merc_id, tier_id, opted_in) VALUES (?, ?, ?)
                            ON CONFLICT (merc_id, tier_id) DO UPDATE SET opted_in = EXCLUDED.opted_in""")
                    .params(tornId, tierId, wanted.contains(tierId)).update();
        }
    }

    private Merc map(ResultSet rs, int row) throws SQLException {
        long factionValue = rs.getLong("faction_id");
        Long faction = rs.wasNull() ? null : factionValue;
        OffsetDateTime until = rs.getObject("available_until", OffsetDateTime.class);
        OffsetDateTime joined = rs.getObject("joined_at", OffsetDateTime.class);
        return new Merc(rs.getLong("torn_id"), rs.getLong("discord_id"), rs.getString("name"), faction,
                rs.getBoolean("merits_ok"), rs.getBoolean("stricken_opt_in"), rs.getBoolean("available"),
                until == null ? null : until.toInstant(), PingPref.valueOf(rs.getString("ping_pref")),
                DmPref.valueOf(rs.getString("dm_pref")), rs.getString("status"),
                joined == null ? null : joined.toInstant());
    }
}
