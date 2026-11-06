package com.familyx.mercbot.request;

import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.Choices.Posture;
import com.familyx.mercbot.request.Choices.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Contracts and their targets (scope doc 10.6). */
@Repository
public class ContractRepository {

    /** One target row to store. */
    public record NewTarget(long tornId, String name, Integer level, Long factionId) { }

    /** What gets stored when a quote is accepted. */
    public record NewContract(long requesterId, boolean myFaction, boolean mercMe, String scenario, Posture posture,
                              int tierId, int priceVersionId, long unitPrice, BigDecimal discountPct,
                              BigDecimal factionCutPct, BigDecimal starterPct, BigDecimal claimerNoShowPct,
                              Attack attack, Mode mode, Instant startsAt, Instant endsAt, Integer hitsPerTarget,
                              boolean factionTarget, Long targetFactionId, String targetFactionName,
                              boolean allMembers, Conditions conditions, String notes, String status,
                              List<NewTarget> targets) { }

    /** A stored contract, as much as the request flow needs. */
    public record Contract(long id, String ref, long requesterId, boolean myFaction, boolean mercMe, String scenario,
                           Posture posture, int tierId, long unitPrice, BigDecimal discountPct, Attack attack,
                           Mode mode, Instant startsAt, Instant endsAt, Integer hitsPerTarget, boolean factionTarget,
                           Long targetFactionId, String targetFactionName, boolean allMembers, Conditions conditions,
                           String notes, String status, Long approvalMessageId, Instant createdAt) { }

    private final JdbcClient jdbc;

    public ContractRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the contract and its targets; returns the new contract. */
    @Transactional
    public Contract create(NewContract c) {
        long seq = jdbc.sql("SELECT nextval('contract_ref_seq')").query(Long.class).single();
        String ref = String.format("C-%04d", seq);
        long id = jdbc.sql("""
                        INSERT INTO contract (ref, requester_id, requested_for, merc_me, scenario, posture, tier_id,
                            price_version_id, unit_price, discount_pct, faction_cut_pct, starter_pct,
                            claimer_noshow_pct, attack_type, mode, starts_at, ends_at, hits_per_target, target_kind,
                            target_faction_id, target_faction_name, member_selection, status_condition, away_minutes,
                            allow_revivable, allow_early_discharge, status, notes)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id""")
                .params(ref, c.requesterId(), c.myFaction() ? "MY_FACTION" : "PERSONAL", c.mercMe(), c.scenario(),
                        c.posture() == null ? null : c.posture().name(), c.tierId(), c.priceVersionId(),
                        c.unitPrice(), c.discountPct(), c.factionCutPct(), c.starterPct(), c.claimerNoShowPct(),
                        c.attack().name(), c.mode() == Mode.HITS ? "HITS" : "DURATION", ts(c.startsAt()),
                        ts(c.endsAt()), c.hitsPerTarget(), c.factionTarget() ? "FACTION" : "PLAYERS",
                        c.targetFactionId(), c.targetFactionName(),
                        c.factionTarget() ? (c.allMembers() ? "ALL" : "PICKED") : null,
                        c.conditions().status().name(),
                        c.conditions().status() == Status.AWAY ? c.conditions().awayMinutes() : null,
                        c.conditions().allowRevivable(), c.conditions().allowEarlyDischarge(), c.status(), c.notes())
                .query(Long.class).single();
        for (NewTarget t : c.targets()) {
            jdbc.sql("""
                            INSERT INTO contract_target (contract_id, target_torn_id, target_name, level, faction_id,
                                hits_required) VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT (contract_id, target_torn_id) DO NOTHING""")
                    .params(id, t.tornId(), t.name(), t.level(), t.factionId(),
                            c.mode() == Mode.HITS ? c.hitsPerTarget() : null)
                    .update();
        }
        return find(id).orElseThrow();
    }

    public Optional<Contract> find(long id) {
        return jdbc.sql("SELECT * FROM contract WHERE id = ?").param(id).query(this::map).optional();
    }

    /** The requester's most recent contract that was actually placed (for Repeat last). */
    public Optional<Contract> lastFor(long requesterId) {
        return jdbc.sql("""
                        SELECT * FROM contract WHERE requester_id = ? AND status NOT IN ('QUOTED', 'EXPIRED')
                        ORDER BY created_at DESC, id DESC LIMIT 1""")
                .param(requesterId).query(this::map).optional();
    }

    /** The requester's most recent placed contracts, newest first (for the Repeat picker). */
    public List<Contract> recentFor(long requesterId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM contract WHERE requester_id = ? AND status NOT IN ('QUOTED', 'EXPIRED')
                        ORDER BY created_at DESC, id DESC LIMIT ?""")
                .params(requesterId, limit).query(this::map).list();
    }

    public Optional<String> firstTargetName(long contractId) {
        return jdbc.sql("SELECT target_name FROM contract_target WHERE contract_id = ? ORDER BY id LIMIT 1")
                .param(contractId).query(String.class).optional();
    }

    public List<Long> targetIds(long contractId) {
        return jdbc.sql("SELECT target_torn_id FROM contract_target WHERE contract_id = ? ORDER BY id")
                .param(contractId).query(Long.class).list();
    }

    public int targetCount(long contractId) {
        return jdbc.sql("SELECT count(*) FROM contract_target WHERE contract_id = ?").param(contractId)
                .query(Integer.class).single();
    }

    public void setApprovalMessage(long contractId, long messageId) {
        jdbc.sql("UPDATE contract SET approval_message_id = ?, updated_at = now() WHERE id = ?")
                .params(messageId, contractId).update();
    }

    /** Moves a pending contract on; returns false if it had already been handled. */
    public boolean decide(long contractId, boolean approve, long leaderDiscordId, String reason) {
        return jdbc.sql("""
                        UPDATE contract SET status = ?, approved_by = ?, rejected_reason = ?, updated_at = now()
                        WHERE id = ? AND status = 'PENDING_APPROVAL'""")
                .params(approve ? "ACTIVE" : "REJECTED", leaderDiscordId, approve ? null : reason, contractId)
                .update() == 1;
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private Contract map(ResultSet rs, int row) throws SQLException {
        int hits = rs.getInt("hits_per_target");
        Integer hitsPerTarget = rs.wasNull() ? null : hits;
        int away = rs.getInt("away_minutes");
        int awayMinutes = rs.wasNull() ? Conditions.DEFAULT_AWAY_MINUTES : away;
        String posture = rs.getString("posture");
        return new Contract(rs.getLong("id"), rs.getString("ref"), rs.getLong("requester_id"),
                "MY_FACTION".equals(rs.getString("requested_for")), rs.getBoolean("merc_me"),
                rs.getString("scenario"), posture == null ? null : Posture.valueOf(posture), rs.getInt("tier_id"),
                rs.getLong("unit_price"), rs.getBigDecimal("discount_pct"),
                Attack.valueOf(rs.getString("attack_type")),
                "HITS".equals(rs.getString("mode")) ? Mode.HITS : Mode.DURATION,
                instant(rs, "starts_at"), instant(rs, "ends_at"), hitsPerTarget,
                "FACTION".equals(rs.getString("target_kind")), nullableLong(rs, "target_faction_id"),
                rs.getString("target_faction_name"), "ALL".equals(rs.getString("member_selection")),
                new Conditions(Status.valueOf(rs.getString("status_condition")), awayMinutes,
                        rs.getBoolean("allow_revivable"), rs.getBoolean("allow_early_discharge")),
                rs.getString("notes"), rs.getString("status"), nullableLong(rs, "approval_message_id"),
                instant(rs, "created_at"));
    }
}
