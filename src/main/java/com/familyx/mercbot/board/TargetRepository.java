package com.familyx.mercbot.board;

import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.Status;
import com.familyx.mercbot.request.Conditions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Contract targets with their live Torn state, for the watcher and the boards (scope doc 5.3, 10.4). */
@Repository
public class TargetRepository {

    /** A target of a running (or paused) contract, with everything the board needs. */
    public record BoardTarget(long id, long contractId, String ref, String contractStatus, int tierId, int priority,
                              Instant contractCreated, boolean factionTarget, String targetFactionName,
                              int targetCount, Attack attack, Conditions conditions, Instant startsAt, Instant endsAt,
                              long tornId, String name, Integer level, Long factionId, String lastState,
                              Instant statusUntil, String statusDetails, Boolean revivable, Boolean earlyDischarge,
                              Instant lastActionAt, String lastActionStatus, Instant availableAt, boolean onBoard,
                              Integer hitsRequired, int hitsDone, boolean hasOverride, boolean inRankedWar,
                              Instant lastCheckedAt) {

        /** Hit count contracts stop showing a target once its hits are done. */
        public boolean hitsLeft() {
            return hitsRequired == null || hitsDone < hitsRequired;
        }
    }

    /** One target's state as read from Torn. */
    public record TornState(String state, Instant until, String details, Boolean revivable, Boolean earlyDischarge,
                            Instant lastActionAt, String lastActionStatus, Integer level, String name) { }

    private final JdbcClient jdbc;

    public TargetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Targets of contracts that are live or paused right now (inside their time window). */
    public List<BoardTarget> running() {
        return jdbc.sql("""
                        SELECT ct.*, c.ref, c.status AS contract_status, c.tier_id, c.priority, c.created_at AS c_created,
                               c.target_kind, c.target_faction_name, c.attack_type, c.status_condition, c.away_minutes,
                               c.allow_revivable, c.allow_early_discharge, c.starts_at, c.ends_at,
                               (SELECT count(*) FROM contract_target x WHERE x.contract_id = c.id
                                    AND x.roster_status = 'ACTIVE') AS target_count,
                               EXISTS (SELECT 1 FROM target_override o WHERE o.contract_target_id = ct.id
                                    AND o.used_hit_id IS NULL) AS has_override
                        FROM contract_target ct JOIN contract c ON c.id = ct.contract_id
                        WHERE c.status IN ('ACTIVE', 'PAUSED') AND ct.roster_status = 'ACTIVE'
                          AND (c.starts_at IS NULL OR c.starts_at <= now())
                          AND (c.ends_at IS NULL OR c.ends_at > now())""")
                .query(this::map).list();
    }

    /** Targets of one contract (any status), for the control panel. */
    public List<BoardTarget> forContract(long contractId) {
        return jdbc.sql("""
                        SELECT ct.*, c.ref, c.status AS contract_status, c.tier_id, c.priority, c.created_at AS c_created,
                               c.target_kind, c.target_faction_name, c.attack_type, c.status_condition, c.away_minutes,
                               c.allow_revivable, c.allow_early_discharge, c.starts_at, c.ends_at,
                               (SELECT count(*) FROM contract_target x WHERE x.contract_id = c.id
                                    AND x.roster_status = 'ACTIVE') AS target_count,
                               EXISTS (SELECT 1 FROM target_override o WHERE o.contract_target_id = ct.id
                                    AND o.used_hit_id IS NULL) AS has_override
                        FROM contract_target ct JOIN contract c ON c.id = ct.contract_id
                        WHERE c.id = ? AND ct.roster_status = 'ACTIVE' ORDER BY lower(ct.target_name)""")
                .param(contractId).query(this::map).list();
    }

    /** A running "All members" faction contract, whose roster follows the faction. */
    public record RosterContract(long contractId, long factionId, Integer hitsPerTarget) { }

    public List<RosterContract> allMemberContracts() {
        return jdbc.sql("""
                        SELECT id, target_faction_id, hits_per_target FROM contract
                        WHERE status IN ('ACTIVE', 'PAUSED') AND target_kind = 'FACTION' AND member_selection = 'ALL'
                          AND target_faction_id IS NOT NULL AND (ends_at IS NULL OR ends_at > now())""")
                .query((rs, n) -> {
                    int h = rs.getInt("hits_per_target");
                    return new RosterContract(rs.getLong("id"), rs.getLong("target_faction_id"), rs.wasNull() ? null : h);
                })
                .list();
    }

    /** Current roster of a contract: Torn id → target row id. */
    public java.util.Map<Long, Long> roster(long contractId) {
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        jdbc.sql("SELECT id, target_torn_id FROM contract_target WHERE contract_id = ? AND roster_status = 'ACTIVE'")
                .param(contractId)
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getLong("target_torn_id"),
                        rs.getLong("id")));
        return out;
    }

    public void updateState(long id, TornState s, Instant availableAt, boolean inRankedWar) {
        jdbc.sql("""
                        UPDATE contract_target SET last_state = ?, status_until = ?, status_details = ?,
                               revivable = ?, early_discharge = ?, last_action_at = ?, last_action_status = ?,
                               level = coalesce(?, level), target_name = coalesce(?, target_name),
                               available_at = ?, in_ranked_war = ?, last_checked_at = now()
                        WHERE id = ?""")
                .params(s.state(), ts(s.until()), s.details(), s.revivable(), s.earlyDischarge(), ts(s.lastActionAt()),
                        s.lastActionStatus(), s.level(), s.name(), ts(availableAt), inRankedWar, id)
                .update();
    }

    public void setOnBoard(long id, boolean onBoard) {
        jdbc.sql("UPDATE contract_target SET on_board = ? WHERE id = ?").params(onBoard, id).update();
    }

    /** A member joined a faction targeted with "All members": add them to the contract. */
    public void addMember(long contractId, long tornId, String name, Integer level, long factionId, Integer hitsRequired) {
        jdbc.sql("""
                        INSERT INTO contract_target (contract_id, target_torn_id, target_name, level, faction_id, hits_required)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (contract_id, target_torn_id) DO UPDATE SET roster_status = 'ACTIVE'""")
                .params(contractId, tornId, name, level, factionId, hitsRequired).update();
    }

    /** A target left the targeted faction: they stop being a target of that contract. */
    public void markLeft(long id) {
        jdbc.sql("UPDATE contract_target SET roster_status = 'LEFT', on_board = FALSE WHERE id = ?").param(id).update();
    }

    /** "Hit specific member": one hit allowed despite the conditions (scope doc 4.4). */
    public void addOverride(long targetId, String kind) {
        jdbc.sql("INSERT INTO target_override (contract_target_id, kind) VALUES (?, ?)").params(targetId, kind).update();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Boolean bool(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private BoardTarget map(ResultSet rs, int row) throws SQLException {
        long f = rs.getLong("faction_id");
        Long factionId = rs.wasNull() ? null : f;
        Integer away = integer(rs, "away_minutes");
        Conditions conditions = new Conditions(Status.valueOf(rs.getString("status_condition")),
                away == null ? Conditions.DEFAULT_AWAY_MINUTES : away, rs.getBoolean("allow_revivable"),
                rs.getBoolean("allow_early_discharge"));
        return new BoardTarget(rs.getLong("id"), rs.getLong("contract_id"), rs.getString("ref"),
                rs.getString("contract_status"), rs.getInt("tier_id"), rs.getInt("priority"),
                instant(rs, "c_created"), "FACTION".equals(rs.getString("target_kind")),
                rs.getString("target_faction_name"), rs.getInt("target_count"),
                Attack.valueOf(rs.getString("attack_type")), conditions, instant(rs, "starts_at"),
                instant(rs, "ends_at"), rs.getLong("target_torn_id"), rs.getString("target_name"),
                integer(rs, "level"), factionId, rs.getString("last_state"), instant(rs, "status_until"),
                rs.getString("status_details"), bool(rs, "revivable"), bool(rs, "early_discharge"),
                instant(rs, "last_action_at"), rs.getString("last_action_status"), instant(rs, "available_at"),
                rs.getBoolean("on_board"), integer(rs, "hits_required"), rs.getInt("hits_done"),
                rs.getBoolean("has_override"), rs.getBoolean("in_ranked_war"), instant(rs, "last_checked_at"));
    }
}
