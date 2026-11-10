package com.familyx.mercbot.outside;

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

/** Attacks on contract targets by outsiders, and the alerts they raise (scope doc 7.8). */
@Repository
public class OutsideRepository {

    public record Event(Instant at, String cause, long targetTornId, String targetName, Long attackerId,
                        String attackerName, Long attackerFactionId) { }

    public record Alert(long contractId, String kind, int pingsSent, Instant lastPingAt, Long cardMessageId,
                        Instant mutedUntil) { }

    private final JdbcClient jdbc;

    public OutsideRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(long contractTargetId, long targetTornId, String cause, Long attackerId, String attackerName,
                    Long attackerFactionId) {
        jdbc.sql("""
                        INSERT INTO outside_attack_event (contract_target_id, target_torn_id, cause, attacker_id,
                                                          attacker_name, attacker_faction_id)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(contractTargetId, targetTornId, cause, attackerId, attackerName, attackerFactionId).update();
    }

    /** A contract's events of these causes since a time, newest first. */
    public List<Event> events(long contractId, List<String> causes, Instant since) {
        return jdbc.sql("""
                        SELECT e.at, e.cause, ct.target_torn_id, ct.target_name, e.attacker_id, e.attacker_name,
                               e.attacker_faction_id
                        FROM outside_attack_event e JOIN contract_target ct ON ct.id = e.contract_target_id
                        WHERE ct.contract_id = ? AND e.counted AND e.at >= ? AND e.cause IN ("""
                        + String.join(", ", causes.stream().map(c -> "'" + c.replaceAll("[^A-Z]", "") + "'").toList())
                        + ") ORDER BY e.at DESC")
                .params(contractId, ts(since))
                .query((rs, n) -> new Event(instant(rs, "at"), rs.getString("cause"), rs.getLong("target_torn_id"),
                        rs.getString("target_name"), rs.getObject("attacker_id", Long.class), rs.getString("attacker_name"),
                        rs.getObject("attacker_faction_id", Long.class)))
                .list();
    }

    /** Contracts with any outside event in the last two hours (for the minute-by-minute check). */
    public List<Long> recentContracts() {
        return jdbc.sql("""
                        SELECT DISTINCT ct.contract_id FROM outside_attack_event e
                        JOIN contract_target ct ON ct.id = e.contract_target_id
                        WHERE e.at > now() - interval '2 hours'""")
                .query(Long.class).list();
    }

    public Alert alert(long contractId, String kind) {
        jdbc.sql("INSERT INTO outside_alert (contract_id, kind) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(contractId, kind).update();
        return jdbc.sql("SELECT * FROM outside_alert WHERE contract_id = ? AND kind = ?").params(contractId, kind)
                .query(this::mapAlert).single();
    }

    public Optional<Alert> findAlert(long contractId, String kind) {
        return jdbc.sql("SELECT * FROM outside_alert WHERE contract_id = ? AND kind = ?").params(contractId, kind)
                .query(this::mapAlert).optional();
    }

    public void pinged(long contractId) {
        jdbc.sql("""
                        UPDATE outside_alert SET pings_sent = pings_sent + 1, last_ping_at = now()
                        WHERE contract_id = ? AND kind = 'OFFENSIVE'""")
                .param(contractId).update();
    }

    /** The offensive episode is over: the next one starts its pings again from one. */
    public void resetPings(long contractId) {
        jdbc.sql("UPDATE outside_alert SET pings_sent = 0 WHERE contract_id = ? AND kind = 'OFFENSIVE'")
                .param(contractId).update();
    }

    public void setCard(long contractId, Long messageId) {
        jdbc.sql("UPDATE outside_alert SET card_message_id = ? WHERE contract_id = ? AND kind = 'DEFENSIVE'")
                .params(messageId, contractId).update();
    }

    public void mute(long contractId, Instant until) {
        jdbc.sql("""
                        UPDATE outside_alert SET muted_until = ?, card_message_id = NULL
                        WHERE contract_id = ? AND kind = 'DEFENSIVE'""")
                .params(ts(until), contractId).update();
    }

    private Alert mapAlert(ResultSet rs, int n) throws SQLException {
        return new Alert(rs.getLong("contract_id"), rs.getString("kind"), rs.getInt("pings_sent"),
                instant(rs, "last_ping_at"), rs.getObject("card_message_id", Long.class), instant(rs, "muted_until"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
