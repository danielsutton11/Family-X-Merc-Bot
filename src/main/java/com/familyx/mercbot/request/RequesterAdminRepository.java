package com.familyx.mercbot.request;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Leader panel → Requesters (scope doc 6.5): lists, history, reputation, blacklist and discounts. */
@Repository
public class RequesterAdminRepository {

    public record Row(long tornId, Long discordId, String name, Long factionId, int score, boolean blacklisted,
                      String blacklistReason, Instant lastAt, String lastRef, int unpaid) { }

    public record ContractLine(String ref, long contractId, String target, String tier, int hits, long billed,
                               String payment) { }

    public record Payments(int onTime, int late, int unpaid, long unpaidDue) { }

    public record Event(Instant at, int change, String reason, String ref, Long by) { }

    public record Discount(long factionId, BigDecimal pct) { }

    public record Counts(int requesters, int blacklisted, int personal, int faction) { }

    private static final String ROW = """
            SELECT r.torn_id, r.discord_id, r.name, r.faction_id, r.score, r.blacklisted, r.blacklist_reason,
                   lc.created_at AS last_at, lc.ref AS last_ref,
                   (SELECT count(*) FROM invoice i JOIN contract c2 ON c2.id = i.contract_id
                    WHERE c2.requester_id = r.torn_id AND i.status IN ('OPEN', 'PARTIAL')) AS unpaid
            FROM requester r
            LEFT JOIN LATERAL (SELECT c.created_at, c.ref FROM contract c WHERE c.requester_id = r.torn_id
                               ORDER BY c.created_at DESC LIMIT 1) lc ON TRUE
            """;

    private final JdbcClient jdbc;

    public RequesterAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Counts counts() {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM requester), (SELECT count(*) FROM requester WHERE blacklisted),
                               (SELECT count(DISTINCT torn_id) FROM discount WHERE scope = 'PERSON' AND pct > 0),
                               (SELECT count(DISTINCT faction_id) FROM discount WHERE scope = 'FACTION' AND pct > 0)""")
                .query((rs, n) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))).single();
    }

    /** Most recent first: by their latest contract, then when they were first seen. */
    public List<Row> recent(int limit) {
        return jdbc.sql(ROW + " ORDER BY coalesce(lc.created_at, r.created_at) DESC LIMIT ?").param(limit)
                .query(this::row).list();
    }

    public List<Row> blacklistedRows(int limit) {
        return jdbc.sql(ROW + " WHERE r.blacklisted ORDER BY r.blacklisted_at DESC NULLS LAST LIMIT ?").param(limit)
                .query(this::row).list();
    }

    public Optional<Row> find(long tornId) {
        return jdbc.sql(ROW + " WHERE r.torn_id = ?").param(tornId).query(this::row).optional();
    }

    public List<Row> byName(String name, int limit) {
        return jdbc.sql(ROW + " WHERE r.name ILIKE ? ORDER BY r.name LIMIT ?")
                .params("%" + name.replace("%", "").replace("_", "\\_") + "%", limit).query(this::row).list();
    }

    public List<ContractLine> contracts(long tornId, int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.ref, t.name AS tier,
                               (SELECT count(*) FROM hit h JOIN contract_target ct ON ct.id = h.contract_target_id
                                WHERE ct.contract_id = c.id AND h.accepted) AS hits,
                               (SELECT coalesce(sum(i.total), 0) FROM invoice i WHERE i.contract_id = c.id) AS billed,
                               (SELECT CASE WHEN count(*) = 0 THEN NULL
                                            WHEN bool_or(i.status <> 'PAID') AND bool_or(i.amount_paid > 0) THEN 'part paid'
                                            WHEN bool_or(i.status <> 'PAID') THEN 'unpaid'
                                            WHEN bool_or(i.paid_at > i.due_at) THEN 'paid late'
                                            ELSE 'paid on time' END
                                FROM invoice i WHERE i.contract_id = c.id) AS payment
                        FROM contract c LEFT JOIN tier t ON t.id = c.tier_id
                        WHERE c.requester_id = ? AND c.status NOT IN ('QUOTED', 'EXPIRED')
                        ORDER BY c.created_at DESC LIMIT ?""")
                .params(tornId, limit)
                .query((rs, n) -> new ContractLine(rs.getString("ref"), rs.getLong("id"), null, rs.getString("tier"),
                        rs.getInt("hits"), rs.getLong("billed"), rs.getString("payment")))
                .list();
    }

    /** Contract count, total billed and total paid across everything they've requested. */
    public long[] totals(long tornId) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM contract c WHERE c.requester_id = ? AND c.status NOT IN ('QUOTED', 'EXPIRED')),
                               coalesce(sum(i.total), 0), coalesce(sum(i.amount_paid), 0)
                        FROM invoice i JOIN contract c ON c.id = i.contract_id WHERE c.requester_id = ?""")
                .params(tornId, tornId)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
    }

    public Payments payments(long tornId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE i.status = 'PAID' AND i.paid_at <= i.due_at),
                               count(*) FILTER (WHERE i.status = 'PAID' AND i.paid_at > i.due_at),
                               count(*) FILTER (WHERE i.status IN ('OPEN', 'PARTIAL')),
                               coalesce(sum(i.total - i.amount_paid) FILTER (WHERE i.status IN ('OPEN', 'PARTIAL')), 0)
                        FROM invoice i JOIN contract c ON c.id = i.contract_id WHERE c.requester_id = ?""")
                .param(tornId)
                .query((rs, n) -> new Payments(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4))).single();
    }

    public Optional<Instant> firstRequest(long tornId) {
        return jdbc.sql("SELECT min(created_at) FROM contract WHERE requester_id = ?").param(tornId)
                .query((rs, n) -> Optional.ofNullable(instant(rs, 1))).single();
    }

    // ------------------------------------------------------------------ reputation

    public List<Event> events(long tornId, int limit) {
        return jdbc.sql("""
                        SELECT e.at, e.change, e.reason, c.ref, e.by_discord_id FROM reputation_event e
                        LEFT JOIN contract c ON c.id = e.contract_id
                        WHERE e.requester_id = ? ORDER BY e.at DESC, e.id DESC LIMIT ?""")
                .params(tornId, limit)
                .query((rs, n) -> new Event(instant(rs, "at"), rs.getInt("change"), rs.getString("reason"),
                        rs.getString("ref"), rs.getObject("by_discord_id", Long.class)))
                .list();
    }

    // ------------------------------------------------------------------ blacklist

    public void setBlacklisted(long tornId, boolean on, String reason, Long by) {
        jdbc.sql("""
                        UPDATE requester SET blacklisted = ?, blacklist_reason = ?, blacklisted_by = ?,
                               blacklisted_at = CASE WHEN ? THEN now() ELSE NULL END
                        WHERE torn_id = ?""")
                .params(on, on ? reason : null, on ? by : null, on, tornId).update();
    }

    /** A requester row for someone who has never requested, so they can be blacklisted in advance. */
    public void ensure(long tornId, long discordId, String name, Long factionId, int startingScore) {
        jdbc.sql("""
                        INSERT INTO requester (torn_id, discord_id, name, faction_id, score) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (torn_id) DO NOTHING""")
                .params(tornId, discordId, name, factionId, startingScore).update();
    }

    // ------------------------------------------------------------------ discounts

    public void setPersonalDiscount(long tornId, BigDecimal pct, long by) {
        jdbc.sql("DELETE FROM discount WHERE scope = 'PERSON' AND torn_id = ?").param(tornId).update();
        if (pct.signum() > 0) {
            jdbc.sql("INSERT INTO discount (scope, torn_id, pct, created_by) VALUES ('PERSON', ?, ?, ?)")
                    .params(tornId, pct, by).update();
        }
    }

    public void setFactionDiscount(long factionId, BigDecimal pct, long by) {
        jdbc.sql("DELETE FROM discount WHERE scope = 'FACTION' AND faction_id = ?").param(factionId).update();
        if (pct.signum() > 0) {
            jdbc.sql("INSERT INTO discount (scope, faction_id, pct, created_by) VALUES ('FACTION', ?, ?, ?)")
                    .params(factionId, pct, by).update();
        }
    }

    public List<Discount> factionDiscounts() {
        return jdbc.sql("""
                        SELECT DISTINCT ON (faction_id) faction_id, pct FROM discount
                        WHERE scope = 'FACTION' AND pct > 0 ORDER BY faction_id, created_at DESC""")
                .query((rs, n) -> new Discount(rs.getLong(1), rs.getBigDecimal(2))).list();
    }

    // ------------------------------------------------------------------ mapping

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getLong("torn_id"), rs.getObject("discord_id", Long.class), rs.getString("name"),
                rs.getObject("faction_id", Long.class), rs.getInt("score"), rs.getBoolean("blacklisted"),
                rs.getString("blacklist_reason"), instant(rs, "last_at"), rs.getString("last_ref"), rs.getInt("unpaid"));
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
