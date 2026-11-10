package com.familyx.mercbot.merc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Leader panel → Mercs (scope doc 6.1): a merc's stats, claim history, and the merc blacklist. */
@Repository
public class MercStatsRepository {

    /** Hits the merc started (accepted), and everything they earned, over 7 days, 30 days and all time. */
    public record Stats(int hits7, int hits30, int hitsAll, long earned7, long earned30, long earnedAll,
                        Integer avgHosp7, Integer avgHosp30, int joins7, Instant lastHitAt, String lastTarget,
                        String lastRef) { }

    public record ClaimWeek(int claims, int hit, int noShows, int released) { }

    public record ClaimLine(Instant at, String ref, String target, String outcome, String hitBy, boolean joined) { }

    public record Blacklisted(long tornId, Long discordId, String name, String reason, Long addedBy, Instant addedAt) { }

    private final JdbcClient jdbc;

    public MercStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Stats stats(long mercId) {
        Stats base = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE h.started > now() - interval '7 days')  AS h7,
                               count(*) FILTER (WHERE h.started > now() - interval '30 days') AS h30,
                               count(*) AS hall,
                               avg(h.hosp_seconds) FILTER (WHERE h.started > now() - interval '7 days')  AS a7,
                               avg(h.hosp_seconds) FILTER (WHERE h.started > now() - interval '30 days') AS a30
                        FROM hit h WHERE h.accepted AND h.attacker_id = ?""")
                .param(mercId)
                .query((rs, n) -> new Stats(rs.getInt("h7"), rs.getInt("h30"), rs.getInt("hall"), 0, 0, 0,
                        avg(rs, "a7"), avg(rs, "a30"), 0, null, null, null))
                .single();
        long[] earned = jdbc.sql("""
                        SELECT coalesce(sum(s.amount) FILTER (WHERE h.started > now() - interval '7 days'), 0),
                               coalesce(sum(s.amount) FILTER (WHERE h.started > now() - interval '30 days'), 0),
                               coalesce(sum(s.amount), 0),
                               count(DISTINCT s.hit_id) FILTER (WHERE s.role = 'ASSIST' AND h.started > now() - interval '7 days')
                        FROM hit_share s JOIN hit h ON h.id = s.hit_id WHERE h.accepted AND s.merc_id = ?""")
                .param(mercId)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}).single();
        Optional<Object[]> last = jdbc.sql("""
                        SELECT h.started, coalesce(ct.target_name, 'Player ' || ct.target_torn_id), c.ref
                        FROM hit h JOIN contract_target ct ON ct.id = h.contract_target_id
                        JOIN contract c ON c.id = ct.contract_id
                        WHERE h.accepted AND h.attacker_id = ? ORDER BY h.started DESC LIMIT 1""")
                .param(mercId)
                .query((rs, n) -> new Object[] {instant(rs, 1), rs.getString(2), rs.getString(3)}).optional();
        return new Stats(base.hits7(), base.hits30(), base.hitsAll(), earned[0], earned[1], earned[2], base.avgHosp7(),
                base.avgHosp30(), (int) earned[3], last.map(l -> (Instant) l[0]).orElse(null),
                last.map(l -> (String) l[1]).orElse(null), last.map(l -> (String) l[2]).orElse(null));
    }

    /** Accepted hits the merc started, by billed tier name, all time. */
    public Map<String, Integer> byTier(long mercId) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                        SELECT coalesce(t.name, '?') AS tier, count(*) AS n FROM hit h LEFT JOIN tier t ON t.id = h.billed_tier_id
                        WHERE h.accepted AND h.attacker_id = ? GROUP BY coalesce(t.name, '?'), t.rank
                        ORDER BY t.rank DESC NULLS LAST""")
                .param(mercId)
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString("tier"), rs.getInt("n")));
        return out;
    }

    public ClaimWeek claimWeek(long mercId) {
        return jdbc.sql("""
                        SELECT count(*), count(*) FILTER (WHERE outcome = 'HIT'), count(*) FILTER (WHERE outcome = 'NOSHOW'),
                               count(*) FILTER (WHERE outcome = 'RELEASED')
                        FROM claim WHERE merc_id = ? AND claimed_at > now() - interval '7 days'""")
                .param(mercId)
                .query((rs, n) -> new ClaimWeek(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))).single();
    }

    public int noShowRun(long mercId) {
        return jdbc.sql("SELECT consecutive_noshows FROM merc WHERE torn_id = ?").param(mercId)
                .query(Integer.class).optional().orElse(0);
    }

    public List<ClaimLine> claims(long mercId, int limit) {
        return jdbc.sql("""
                        SELECT cl.claimed_at, c.ref, coalesce(ct.target_name, 'Player ' || ct.target_torn_id) AS target,
                               cl.outcome, coalesce(cl.claimer_joined, FALSE) AS joined,
                               (SELECT coalesce(m.name, 'Player ' || h.attacker_id) FROM hit h
                                LEFT JOIN merc m ON m.torn_id = h.attacker_id
                                WHERE h.claim_id = cl.id AND h.accepted ORDER BY h.id LIMIT 1) AS hit_by
                        FROM claim cl JOIN contract_target ct ON ct.id = cl.contract_target_id
                        JOIN contract c ON c.id = ct.contract_id
                        WHERE cl.merc_id = ? ORDER BY cl.claimed_at DESC LIMIT ?""")
                .params(mercId, limit)
                .query((rs, n) -> new ClaimLine(instant(rs, "claimed_at"), rs.getString("ref"), rs.getString("target"),
                        rs.getString("outcome"), rs.getString("hit_by"), rs.getBoolean("joined")))
                .list();
    }

    /** Suspension lines from the audit log, newest first. */
    public List<String> suspensions(long mercId, int limit) {
        return jdbc.sql("""
                        SELECT to_char(at AT TIME ZONE 'UTC', 'DD Mon') || ' · ' || coalesce(after_json->>'length', '')
                               || coalesce(', "' || (after_json->>'reason') || '"', '') AS line
                        FROM audit_event WHERE action = 'CLAIMS_SUSPENDED' AND entity = ? ORDER BY at DESC LIMIT ?""")
                .params("merc:" + mercId, limit).query(String.class).list();
    }

    // ------------------------------------------------------------------ merc blacklist

    public void blacklist(long tornId, Long discordId, String name, String reason, long by) {
        jdbc.sql("""
                        INSERT INTO merc_blacklist (torn_id, discord_id, name, reason, added_by) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (torn_id) DO UPDATE SET discord_id = EXCLUDED.discord_id, name = EXCLUDED.name,
                               reason = EXCLUDED.reason, added_by = EXCLUDED.added_by, added_at = now()""")
                .params(tornId, discordId, name, reason, by).update();
    }

    public boolean unblacklist(long tornId) {
        return jdbc.sql("DELETE FROM merc_blacklist WHERE torn_id = ?").param(tornId).update() == 1;
    }

    public boolean blacklisted(Long tornId, Long discordId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM merc_blacklist WHERE torn_id = ? OR discord_id = ?)")
                .params(tornId == null ? -1L : tornId, discordId == null ? -1L : discordId).query(Boolean.class).single();
    }

    public List<Blacklisted> blacklist() {
        return jdbc.sql("SELECT * FROM merc_blacklist ORDER BY added_at DESC")
                .query((rs, n) -> new Blacklisted(rs.getLong("torn_id"), rs.getObject("discord_id", Long.class),
                        rs.getString("name"), rs.getString("reason"), rs.getObject("added_by", Long.class),
                        instant(rs, "added_at")))
                .list();
    }

    private static Integer avg(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).intValue();
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
