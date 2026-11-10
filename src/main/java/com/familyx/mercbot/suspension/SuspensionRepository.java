package com.familyx.mercbot.suspension;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Claim no-shows for the leader alert, and leader suspensions of claiming (scope doc 5.6, 6.4). */
@Repository
public class SuspensionRepository {

    /** "Until turned back on" is stored as this far-off end time, flagged indefinite. */
    public static final Instant INDEFINITE = Instant.parse("9999-01-01T00:00:00Z");

    public record NoShow(Instant at, String ref, String target, String hitBy) { }

    public record State(long mercId, String name, long discordId, Instant suspendedUntil, boolean indefinite,
                        String reason, Long suspendedBy, Instant suspendedAt, Instant remindedAt, Long cardId,
                        Instant ignoredAt, Instant cooldownUntil) {
        public boolean suspended(Instant now) {
            return suspendedUntil != null && suspendedUntil.isAfter(now);
        }
    }

    private final JdbcClient jdbc;

    public SuspensionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<State> state(long mercId) {
        return jdbc.sql("SELECT * FROM merc WHERE torn_id = ?").param(mercId).query(this::mapState).optional();
    }

    /** No-shows since the given time, newest first. */
    public List<NoShow> since(long mercId, Instant from, int limit) {
        return jdbc.sql("""
                        SELECT n.at, c.ref, coalesce(ct.target_name, 'Player ' || ct.target_torn_id) AS target,
                               (SELECT coalesce(m.name, 'Player ' || h.attacker_id) FROM hit h
                                LEFT JOIN merc m ON m.torn_id = h.attacker_id
                                WHERE h.claim_id = cl.id ORDER BY h.id LIMIT 1) AS hit_by
                        FROM claim_noshow n JOIN claim cl ON cl.id = n.claim_id
                        JOIN contract_target ct ON ct.id = cl.contract_target_id
                        JOIN contract c ON c.id = ct.contract_id
                        WHERE n.merc_id = ? AND n.at >= ? ORDER BY n.at DESC LIMIT ?""")
                .params(mercId, ts(from), limit)
                .query((rs, n) -> new NoShow(instant(rs, "at"), rs.getString("ref"), rs.getString("target"),
                        rs.getString("hit_by")))
                .list();
    }

    public int countSince(long mercId, Instant from) {
        return jdbc.sql("SELECT count(*) FROM claim_noshow WHERE merc_id = ? AND at >= ?").params(mercId, ts(from))
                .query(Integer.class).single();
    }

    /** Claims made in the last 7 days, and how many ended in the claimer's hit. */
    public int[] weekClaims(long mercId) {
        return jdbc.sql("""
                        SELECT count(*), count(*) FILTER (WHERE outcome = 'HIT') FROM claim
                        WHERE merc_id = ? AND claimed_at > now() - interval '7 days'""")
                .param(mercId).query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}).single();
    }

    public void setCard(long mercId, Long cardId) {
        jdbc.sql("UPDATE merc SET noshow_card_id = ? WHERE torn_id = ?").params(cardId, mercId).update();
    }

    public void ignore(long mercId) {
        jdbc.sql("UPDATE merc SET noshow_card_id = NULL, noshow_ignored_at = now() WHERE torn_id = ?")
                .param(mercId).update();
    }

    public void suspend(long mercId, Instant until, boolean indefinite, String reason, long by) {
        jdbc.sql("""
                        UPDATE merc SET claims_suspended_until = ?, suspension_indefinite = ?, suspension_reason = ?,
                               suspended_by = ?, suspended_at = now(), suspension_reminded_at = now()
                        WHERE torn_id = ?""")
                .params(ts(until), indefinite, reason, by, mercId).update();
    }

    /** Clears a suspension; false when there wasn't one. */
    public boolean lift(long mercId) {
        return jdbc.sql("""
                        UPDATE merc SET claims_suspended_until = NULL, suspension_indefinite = FALSE,
                               suspension_reason = NULL, suspension_reminded_at = NULL
                        WHERE torn_id = ? AND claims_suspended_until IS NOT NULL""")
                .param(mercId).update() == 1;
    }

    /** Timed suspensions that have run out (still set, so they can be announced once and cleared). */
    public List<State> expired() {
        return jdbc.sql("""
                        SELECT * FROM merc WHERE claims_suspended_until IS NOT NULL AND claims_suspended_until <= now()
                          AND NOT suspension_indefinite""")
                .query(this::mapState).list();
    }

    /** Indefinite suspensions not reminded about for 7 days. */
    public List<State> dueReminder() {
        return jdbc.sql("""
                        SELECT * FROM merc WHERE suspension_indefinite AND claims_suspended_until IS NOT NULL
                          AND coalesce(suspension_reminded_at, suspended_at, now()) <= now() - interval '7 days'""")
                .query(this::mapState).list();
    }

    public void reminded(long mercId) {
        jdbc.sql("UPDATE merc SET suspension_reminded_at = now() WHERE torn_id = ?").param(mercId).update();
    }

    private State mapState(ResultSet rs, int n) throws SQLException {
        return new State(rs.getLong("torn_id"), rs.getString("name"), rs.getLong("discord_id"),
                instant(rs, "claims_suspended_until"), rs.getBoolean("suspension_indefinite"),
                rs.getString("suspension_reason"), rs.getObject("suspended_by", Long.class), instant(rs, "suspended_at"),
                instant(rs, "suspension_reminded_at"), rs.getObject("noshow_card_id", Long.class),
                instant(rs, "noshow_ignored_at"), instant(rs, "claim_cooldown_until"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
