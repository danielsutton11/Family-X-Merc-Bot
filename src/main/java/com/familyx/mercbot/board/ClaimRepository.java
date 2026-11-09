package com.familyx.mercbot.board;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Claims on board rows (scope doc 5.4, 5.6). An open claim is one with no outcome yet. */
@Repository
public class ClaimRepository {

    /** A claim still holding its row. The lock runs from {@code lockStartsAt} to {@code expiresAt}. */
    public record OpenClaim(long id, long targetId, long mercId, String mercName, Instant claimedAt,
                            Instant lockStartsAt, Instant expiresAt) {

        /** Has the target become hittable (so the lock is counting down)? */
        public boolean started(Instant now) {
            return lockStartsAt != null && !lockStartsAt.isAfter(now);
        }
    }

    /** A merc's claim blocks: cooldown and leader suspension end times (null when not blocked). */
    public record Blocks(Instant cooldownUntil, Instant suspendedUntil, String suspensionReason) { }

    private final JdbcClient jdbc;

    public ClaimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OpenClaim> open() {
        return jdbc.sql("""
                        SELECT c.*, m.name AS merc_name FROM claim c JOIN merc m ON m.torn_id = c.merc_id
                        WHERE c.outcome IS NULL""")
                .query(this::map).list();
    }

    public int openCount(long mercId) {
        return jdbc.sql("SELECT count(*) FROM claim WHERE merc_id = ? AND outcome IS NULL").param(mercId)
                .query(Integer.class).single();
    }

    public Optional<OpenClaim> openOn(long targetId) {
        return jdbc.sql("""
                        SELECT c.*, m.name AS merc_name FROM claim c JOIN merc m ON m.torn_id = c.merc_id
                        WHERE c.outcome IS NULL AND c.contract_target_id = ?""")
                .param(targetId).query(this::map).optional();
    }

    /** Takes the row; empty if someone else already holds it. */
    public Optional<Long> take(long targetId, long mercId, Instant lockStartsAt, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO claim (contract_target_id, merc_id, lock_starts_at, expires_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (contract_target_id) WHERE outcome IS NULL DO NOTHING
                        RETURNING id""")
                .params(targetId, mercId, ts(lockStartsAt), ts(expiresAt))
                .query(Long.class).optional();
    }

    public void setLock(long id, Instant lockStartsAt, Instant expiresAt) {
        jdbc.sql("UPDATE claim SET lock_starts_at = ?, expires_at = ? WHERE id = ? AND outcome IS NULL")
                .params(ts(lockStartsAt), ts(expiresAt), id).update();
    }

    /** Ends a claim: EXPIRED (lock ran out), RELEASED (target left the board) or HIT. */
    public void close(long id, String outcome) {
        jdbc.sql("UPDATE claim SET outcome = ? WHERE id = ? AND outcome IS NULL").params(outcome, id).update();
    }

    /** Releases every open claim, for when claims are switched off. */
    public int closeAll() {
        return jdbc.sql("UPDATE claim SET outcome = 'RELEASED' WHERE outcome IS NULL").update();
    }

    public Blocks blocks(long mercId) {
        return jdbc.sql("SELECT claim_cooldown_until, claims_suspended_until, suspension_reason FROM merc WHERE torn_id = ?")
                .param(mercId)
                .query((rs, n) -> new Blocks(instant(rs, "claim_cooldown_until"), instant(rs, "claims_suspended_until"),
                        rs.getString("suspension_reason")))
                .optional().orElse(new Blocks(null, null, null));
    }

    private OpenClaim map(ResultSet rs, int n) throws SQLException {
        return new OpenClaim(rs.getLong("id"), rs.getLong("contract_target_id"), rs.getLong("merc_id"),
                rs.getString("merc_name"), instant(rs, "claimed_at"), instant(rs, "lock_starts_at"),
                instant(rs, "expires_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
