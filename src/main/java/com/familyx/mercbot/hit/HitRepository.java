package com.familyx.mercbot.hit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Hits, shares and the attack-log cursors (scope doc 10.6). */
@Repository
public class HitRepository {

    /** The money settings a contract fixed when it was accepted. */
    public record Terms(BigDecimal factionCutPct, BigDecimal starterPct, BigDecimal claimerNoShowPct) { }

    /** A claim whose lock covered an attack. */
    public record ClaimAt(long id, long mercId, String outcome) { }

    /** A share not yet told to its merc. */
    public record Unnotified(long shareId, long mercId, String role, long amount, long hitId, String ref,
                             String targetName, long targetId, String tierName, String downgrade, Instant at) { }

    private final JdbcClient jdbc;

    public HitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean seen(long attackId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM hit WHERE starter_attack_id = ?)").param(attackId)
                .query(Boolean.class).single();
    }

    public Terms terms(long contractId) {
        return jdbc.sql("SELECT faction_cut_pct, starter_pct, claimer_noshow_pct FROM contract WHERE id = ?")
                .param(contractId)
                .query((rs, n) -> new Terms(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3))).single();
    }

    /** A tier's price as it stood when the contract was made (for hits billed below the contract's tier). */
    public long priceAt(int tierId, Instant at) {
        return jdbc.sql("""
                        SELECT price_per_hit FROM tier_price_version WHERE tier_id = ? AND effective_from <= ?
                        ORDER BY effective_from DESC LIMIT 1""")
                .params(tierId, ts(at)).query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("""
                                SELECT price_per_hit FROM tier_price_version WHERE tier_id = ?
                                ORDER BY effective_from DESC LIMIT 1""")
                        .param(tierId).query(Long.class).optional().orElse(0L));
    }

    /** Inserts the hit; empty if this attack was already recorded (two family factions saw it). */
    public Optional<Long> insert(long targetRowId, TornAttack a, Integer hospSeconds, Integer tierId, Long amount,
                                 Long claimId, boolean accepted, String reason, String downgrade, int joiners) {
        return jdbc.sql("""
                        INSERT INTO hit (contract_target_id, starter_attack_id, attack_code, result, started, ended,
                                         stricken_value, hosp_seconds, billed_tier_id, billed_amount, claim_id,
                                         accepted, reject_reason, downgrade_reason, attacker_id, joiners)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (starter_attack_id) DO NOTHING RETURNING id""")
                .params(targetRowId, a.id(), a.code(), a.result(), ts(a.started()), ts(a.ended()),
                        a.stricken() ? (a.strickenValue() == null ? 1 : a.strickenValue()) : null, hospSeconds,
                        tierId, amount, claimId, accepted, reason, downgrade, a.attackerId(), joiners)
                .query(Long.class).optional();
    }

    public void setHospSeconds(long hitId, int seconds) {
        jdbc.sql("UPDATE hit SET hosp_seconds = ? WHERE id = ?").params(seconds, hitId).update();
    }

    public void addShare(long hitId, long mercId, Long factionId, String role, Long attackId, BigDecimal pct, long amount) {
        jdbc.sql("""
                        INSERT INTO hit_share (hit_id, merc_id, faction_id, role, attack_id, pct, amount)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(hitId, mercId, factionId, role, attackId, pct, amount).update();
    }

    public void countHit(long targetRowId) {
        jdbc.sql("UPDATE contract_target SET hits_done = hits_done + 1 WHERE id = ?").param(targetRowId).update();
    }

    public void useOverride(long targetRowId, long hitId) {
        jdbc.sql("""
                        UPDATE target_override SET used_hit_id = ? WHERE id = (
                            SELECT id FROM target_override WHERE contract_target_id = ? AND used_hit_id IS NULL
                            ORDER BY created_at LIMIT 1)""")
                .params(hitId, targetRowId).update();
    }

    // ------------------------------------------------------------------ claims

    /** The claim on this row whose lock covered the attack start (open, or already closed by the board). */
    public Optional<ClaimAt> claimCovering(long targetRowId, Instant started) {
        return jdbc.sql("""
                        SELECT id, merc_id, outcome FROM claim
                        WHERE contract_target_id = ? AND NOT judged
                          AND claimed_at <= ? AND (expires_at IS NULL OR expires_at >= ?)
                          AND (outcome IS NULL OR outcome IN ('EXPIRED', 'RELEASED'))
                        ORDER BY claimed_at DESC LIMIT 1""")
                .params(targetRowId, ts(started), ts(started))
                .query((rs, n) -> new ClaimAt(rs.getLong(1), rs.getLong(2), rs.getString(3))).optional();
    }

    /** Settles a claim: HIT (claimer took part) or NOSHOW. */
    public void judge(long claimId, String outcome, boolean claimerJoined) {
        jdbc.sql("UPDATE claim SET outcome = ?, claimer_joined = ?, judged = TRUE WHERE id = ?")
                .params(outcome, claimerJoined, claimId).update();
    }

    /** Expired claims whose lock ended before {@code before}: the attack log has been read past them. */
    public List<ClaimAt> expiredUnjudged(Instant before) {
        return jdbc.sql("""
                        SELECT id, merc_id, outcome FROM claim
                        WHERE outcome = 'EXPIRED' AND NOT judged AND expires_at < ?""")
                .param(ts(before)).query((rs, n) -> new ClaimAt(rs.getLong(1), rs.getLong(2), rs.getString(3))).list();
    }

    /** Released claims with nothing matched: the target was hit by someone outside the service. */
    public void settleReleased(Instant before) {
        jdbc.sql("UPDATE claim SET judged = TRUE WHERE outcome = 'RELEASED' AND NOT judged AND expires_at < ?")
                .param(ts(before)).update();
    }

    public void addNoShow(long mercId, long claimId) {
        jdbc.sql("INSERT INTO claim_noshow (merc_id, claim_id) VALUES (?, ?)").params(mercId, claimId).update();
    }

    /** Adds a no-show to the merc's run; returns the new run length. */
    public int bumpNoShows(long mercId) {
        return jdbc.sql("UPDATE merc SET consecutive_noshows = consecutive_noshows + 1 WHERE torn_id = ? RETURNING consecutive_noshows")
                .param(mercId).query(Integer.class).single();
    }

    public void resetNoShows(long mercId) {
        jdbc.sql("UPDATE merc SET consecutive_noshows = 0 WHERE torn_id = ?").param(mercId).update();
    }

    public void startCooldown(long mercId, Instant until) {
        jdbc.sql("UPDATE merc SET claim_cooldown_until = ?, consecutive_noshows = 0 WHERE torn_id = ?")
                .params(ts(until), mercId).update();
    }

    public Optional<String> targetNameForClaim(long claimId) {
        return jdbc.sql("""
                        SELECT coalesce(ct.target_name, ct.target_torn_id::text) FROM claim c
                        JOIN contract_target ct ON ct.id = c.contract_target_id WHERE c.id = ?""")
                .param(claimId).query(String.class).optional();
    }

    // ------------------------------------------------------------------ notifying mercs

    public List<Unnotified> unnotified(long mercId) {
        return jdbc.sql("""
                        SELECT s.id, s.merc_id, s.role, s.amount, h.id AS hit_id, c.ref,
                               coalesce(ct.target_name, ct.target_torn_id::text) AS target_name, ct.target_torn_id,
                               t.name AS tier_name, h.downgrade_reason, h.started
                        FROM hit_share s JOIN hit h ON h.id = s.hit_id
                        JOIN contract_target ct ON ct.id = h.contract_target_id
                        JOIN contract c ON c.id = ct.contract_id
                        LEFT JOIN tier t ON t.id = h.billed_tier_id
                        WHERE s.merc_id = ? AND s.notified_at IS NULL ORDER BY h.started""")
                .param(mercId).query((rs, n) -> unnotified(rs, n)).list();
    }

    public List<Long> mercsWithUnnotified() {
        return jdbc.sql("SELECT DISTINCT merc_id FROM hit_share WHERE notified_at IS NULL").query(Long.class).list();
    }

    public void markNotified(List<Long> shareIds) {
        if (shareIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE hit_share SET notified_at = now() WHERE id = ANY(?)")
                .param(shareIds.toArray(new Long[0])).update();
    }

    public void markNotified(long hitId, long mercId) {
        jdbc.sql("UPDATE hit_share SET notified_at = now() WHERE hit_id = ? AND merc_id = ?")
                .params(hitId, mercId).update();
    }

    // ------------------------------------------------------------------ control panel

    /** Accepted hits per billed tier name, and the latest accepted hit. */
    public record Breakdown(java.util.Map<String, Integer> byTier, String lastTarget, String lastTier, Instant lastAt,
                            int targetsHit) { }

    public Breakdown breakdown(long contractId) {
        java.util.Map<String, Integer> by = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                        SELECT t.name, count(*) FROM hit h JOIN contract_target ct ON ct.id = h.contract_target_id
                        JOIN tier t ON t.id = h.billed_tier_id
                        WHERE ct.contract_id = ? AND h.accepted GROUP BY t.name, t.rank ORDER BY t.rank DESC""")
                .param(contractId).query((java.sql.ResultSet rs, int n) -> by.put(rs.getString(1), rs.getInt(2))).list();
        int targetsHit = jdbc.sql("""
                        SELECT count(DISTINCT h.contract_target_id) FROM hit h
                        JOIN contract_target ct ON ct.id = h.contract_target_id WHERE ct.contract_id = ? AND h.accepted""")
                .param(contractId).query(Integer.class).single();
        return jdbc.sql("""
                        SELECT coalesce(ct.target_name, ct.target_torn_id::text) AS name, t.name AS tier, h.started
                        FROM hit h JOIN contract_target ct ON ct.id = h.contract_target_id
                        JOIN tier t ON t.id = h.billed_tier_id
                        WHERE ct.contract_id = ? AND h.accepted ORDER BY h.started DESC LIMIT 1""")
                .param(contractId)
                .query((rs, n) -> new Breakdown(by, rs.getString("name"), rs.getString("tier"), instant(rs, "started"),
                        targetsHit))
                .optional().orElse(new Breakdown(by, null, null, null, targetsHit));
    }

    // ------------------------------------------------------------------ helpers

    private Unnotified unnotified(ResultSet rs, int n) throws SQLException {
        return new Unnotified(rs.getLong("id"), rs.getLong("merc_id"), rs.getString("role"), rs.getLong("amount"),
                rs.getLong("hit_id"), rs.getString("ref"), rs.getString("target_name"), rs.getLong("target_torn_id"),
                rs.getString("tier_name"), rs.getString("downgrade_reason"), instant(rs, "started"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
