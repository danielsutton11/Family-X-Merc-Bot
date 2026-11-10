package com.familyx.mercbot.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports, leaderboards and the audit view (scope doc 6.7). Every query takes the start of the
 * period (null = all time) and, where it applies, a family faction: hits made by that faction's mercs.
 */
@Repository
public class ReportRepository {

    private static final OffsetDateTime ALL = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? ALL : i.atOffset(ZoneOffset.UTC);
    }

    /** Hits by this faction's mercs (any merc when faction is -1). */
    private static final String BY_FACTION = " AND (? = -1 OR h.attacker_id IN (SELECT torn_id FROM merc WHERE faction_id = ?)) ";

    private static long f(Long faction) {
        return faction == null ? -1L : faction;
    }

    // ------------------------------------------------------------------ revenue

    public record Revenue(long billed, long paid, long outstanding, int outstandingInvoices, long cut) { }

    public record TierLine(String tier, int hits, long billed, long discounts) { }

    public record FactionShare(Long factionId, long amount, long credited) { }

    public Revenue revenue(Instant since, Long faction) {
        return jdbc.sql("""
                        SELECT coalesce(sum(h.billed_amount), 0),
                               coalesce(sum(h.billed_amount) FILTER (WHERE i.status = 'PAID'), 0),
                               coalesce(sum(h.billed_amount) FILTER (WHERE i.status IS DISTINCT FROM 'PAID'), 0),
                               count(DISTINCT i.id) FILTER (WHERE i.status IN ('OPEN', 'PARTIAL')),
                               coalesce(sum(h.billed_amount - coalesce((SELECT sum(s.amount) FROM hit_share s WHERE s.hit_id = h.id), 0)), 0)
                        FROM hit h LEFT JOIN invoice i ON i.id = h.invoice_id
                        WHERE h.accepted AND h.started >= ?""" + BY_FACTION)
                .params(ts(since), f(faction), f(faction))
                .query((rs, n) -> new Revenue(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getLong(5)))
                .single();
    }

    public List<TierLine> revenueByTier(Instant since, Long faction) {
        return jdbc.sql("""
                        SELECT coalesce(t.name, '?') AS tier, count(*) AS hits, coalesce(sum(h.billed_amount), 0) AS billed,
                               coalesce(sum(CASE WHEN c.discount_pct > 0 AND c.discount_pct < 100
                                   THEN round(h.billed_amount * c.discount_pct / (100 - c.discount_pct)) ELSE 0 END), 0) AS disc
                        FROM hit h LEFT JOIN tier t ON t.id = h.billed_tier_id
                        JOIN contract_target ct ON ct.id = h.contract_target_id JOIN contract c ON c.id = ct.contract_id
                        WHERE h.accepted AND h.started >= ?""" + BY_FACTION + """
                        GROUP BY coalesce(t.name, '?'), t.rank ORDER BY t.rank DESC NULLS LAST""")
                .params(ts(since), f(faction), f(faction))
                .query((rs, n) -> new TierLine(rs.getString("tier"), rs.getInt("hits"), rs.getLong("billed"),
                        rs.getLong("disc")))
                .list();
    }

    public List<FactionShare> sharesByFaction(Instant since, Long faction) {
        return jdbc.sql("""
                        SELECT s.faction_id, coalesce(sum(s.amount), 0),
                               coalesce(sum(s.amount) FILTER (WHERE s.state = 'CREDITED'), 0)
                        FROM hit_share s JOIN hit h ON h.id = s.hit_id
                        WHERE h.accepted AND h.started >= ? AND (? = -1 OR s.faction_id = ?)
                        GROUP BY s.faction_id ORDER BY 2 DESC""")
                .params(ts(since), f(faction), f(faction))
                .query((rs, n) -> new FactionShare(rs.getObject(1, Long.class), rs.getLong(2), rs.getLong(3)))
                .list();
    }

    // ------------------------------------------------------------------ mercs

    public record MercLine(long mercId, String name, int hits, long earned, Integer avgHosp, Integer strickenPct,
                           int noShows) { }

    public record Count(String label, int count) { }

    public List<MercLine> mercs(Instant since, Long faction, int limit) {
        return jdbc.sql("""
                        SELECT m.torn_id, m.name,
                               (SELECT count(*) FROM hit h WHERE h.accepted AND h.attacker_id = m.torn_id AND h.started >= ?) AS hits,
                               (SELECT coalesce(sum(s.amount), 0) FROM hit_share s JOIN hit h ON h.id = s.hit_id
                                WHERE h.accepted AND s.merc_id = m.torn_id AND h.started >= ?) AS earned,
                               (SELECT avg(h.hosp_seconds) FROM hit h WHERE h.accepted AND h.attacker_id = m.torn_id
                                AND h.started >= ?) AS hosp,
                               (SELECT CASE WHEN count(*) = 0 OR NOT m.stricken_opt_in THEN NULL
                                       ELSE round(100.0 * count(*) FILTER (WHERE h.stricken_value IS NOT NULL) / count(*)) END
                                FROM hit h WHERE h.accepted AND h.attacker_id = m.torn_id AND h.started >= ?) AS stricken,
                               (SELECT count(*) FROM claim_noshow n WHERE n.merc_id = m.torn_id AND n.at >= ?) AS noshows
                        FROM merc m WHERE (? = -1 OR m.faction_id = ?)
                        ORDER BY 3 DESC, 4 DESC LIMIT ?""")
                .params(ts(since), ts(since), ts(since), ts(since), ts(since), f(faction), f(faction), limit)
                .query((rs, n) -> new MercLine(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4),
                        intOrNull(rs, 5), intOrNull(rs, 6), rs.getInt(7)))
                .list().stream().filter(l -> l.hits() > 0 || l.earned() > 0 || l.noShows() > 0).toList();
    }

    public int[] hitCounts(Instant since, Long faction) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE h.accepted), count(*) FILTER (WHERE NOT h.accepted)
                        FROM hit h WHERE h.started >= ?""" + BY_FACTION)
                .params(ts(since), f(faction), f(faction))
                .query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}).single();
    }

    public List<Count> rejections(Instant since, Long faction) {
        return jdbc.sql("""
                        SELECT coalesce(h.reject_reason, 'unknown'), count(*) FROM hit h
                        WHERE NOT h.accepted AND h.started >= ?""" + BY_FACTION + """
                        GROUP BY 1 ORDER BY 2 DESC LIMIT 8""")
                .params(ts(since), f(faction), f(faction))
                .query((rs, n) -> new Count(rs.getString(1), rs.getInt(2))).list();
    }

    // ------------------------------------------------------------------ contracts

    public record Contracts(int started, int completed, int ended, int cancelled, Long firstHitMedianS,
                            Long doneMedianS, int pausedByLeaders, int pausedDefensive) { }

    private static final String LIVE = " c.status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'ENDED', 'CANCELLED') ";

    public Contracts contracts(Instant since) {
        return jdbc.sql("""
                        SELECT count(*),
                               count(*) FILTER (WHERE c.status = 'COMPLETED'),
                               count(*) FILTER (WHERE c.status = 'ENDED'),
                               count(*) FILTER (WHERE c.status = 'CANCELLED'),
                               (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY x) FROM (
                                    SELECT extract(epoch FROM min(h.started) - coalesce(c2.starts_at, c2.created_at)) AS x
                                    FROM contract c2 JOIN contract_target ct ON ct.contract_id = c2.id
                                    JOIN hit h ON h.contract_target_id = ct.id AND h.accepted
                                    WHERE c2.created_at >= ? GROUP BY c2.id, c2.starts_at, c2.created_at) q WHERE x >= 0),
                               (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM c3.ended_at - c3.created_at))
                                FROM contract c3 WHERE c3.created_at >= ? AND c3.mode = 'HITS' AND c3.status = 'COMPLETED'
                                  AND c3.ended_at IS NOT NULL),
                               (SELECT count(*) FROM audit_event a WHERE a.action = 'CONTRACT_PAUSED' AND a.at >= ?),
                               (SELECT count(*) FROM audit_event a WHERE a.action = 'CONTRACT_PAUSED' AND a.at >= ?
                                  AND a.after_json->>'reason' = 'defensive')
                        FROM contract c WHERE c.created_at >= ? AND""" + LIVE)
                .params(ts(since), ts(since), ts(since), ts(since), ts(since))
                .query((rs, n) -> new Contracts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), longOrNull(rs, 5),
                        longOrNull(rs, 6), rs.getInt(7), rs.getInt(8)))
                .single();
    }

    /** Counts by tier, attack type, target kind and approval, for the "By kind" field. */
    public List<Count> contractKinds(Instant since) {
        return jdbc.sql("""
                        SELECT 'tier:' || t.name, count(*) FROM contract c JOIN tier t ON t.id = c.tier_id
                        WHERE c.created_at >= ? AND""" + LIVE + """
                        GROUP BY t.name, t.rank
                        UNION ALL SELECT 'attack:' || c.attack_type, count(*) FROM contract c WHERE c.created_at >= ? AND""" + LIVE + """
                        GROUP BY c.attack_type
                        UNION ALL SELECT 'target:' || CASE WHEN c.merc_me THEN 'Merc me' WHEN c.target_kind = 'FACTION' THEN 'Faction'
                                         ELSE 'Players' END, count(*) FROM contract c WHERE c.created_at >= ? AND""" + LIVE + """
                        GROUP BY 1
                        UNION ALL SELECT 'approval:' || CASE WHEN c.approved_by IS NULL THEN 'auto-accepted'
                                         ELSE 'approved by a leader' END, count(*) FROM contract c WHERE c.created_at >= ? AND""" + LIVE + """
                        GROUP BY 1""")
                .params(ts(since), ts(since), ts(since), ts(since))
                .query((rs, n) -> new Count(rs.getString(1), rs.getInt(2))).list();
    }

    // ------------------------------------------------------------------ hospital time

    public record HospLine(String tier, int hits, Integer median, Integer min, Integer max, Integer inPct, Integer belowPct) { }

    public List<HospLine> hospital(Instant since, Long faction) {
        return jdbc.sql("""
                        WITH x AS (
                            SELECT t.name, t.rank, h.hosp_seconds AS s,
                                   (SELECT v.expected_hosp_min_s FROM tier_price_version v WHERE v.tier_id = t.id
                                    ORDER BY v.effective_from DESC, v.id DESC LIMIT 1) AS lo,
                                   (SELECT v.expected_hosp_max_s FROM tier_price_version v WHERE v.tier_id = t.id
                                    ORDER BY v.effective_from DESC, v.id DESC LIMIT 1) AS hi
                            FROM hit h JOIN tier t ON t.id = h.billed_tier_id
                            WHERE h.accepted AND h.hosp_seconds IS NOT NULL AND h.started >= ?""" + BY_FACTION + """
                        )
                        SELECT name, count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY s), max(lo), max(hi),
                               CASE WHEN max(lo) IS NULL THEN NULL ELSE round(100.0 * count(*) FILTER (WHERE s >= lo) / count(*)) END,
                               CASE WHEN max(lo) IS NULL THEN NULL ELSE round(100.0 * count(*) FILTER (WHERE s < lo) / count(*)) END
                        FROM x GROUP BY name, rank ORDER BY rank DESC""")
                .params(ts(since), f(faction), f(faction))
                .query((rs, n) -> new HospLine(rs.getString(1), rs.getInt(2), intOrNull(rs, 3), intOrNull(rs, 4),
                        intOrNull(rs, 5), intOrNull(rs, 6), intOrNull(rs, 7)))
                .list();
    }

    // ------------------------------------------------------------------ requesters

    public record RequesterLine(String name, long tornId, int contracts, long billed, Long avgPayS, long outstanding) { }

    public record RequesterTotals(int requesters, long billed, Long avgPayS, Integer onTimePct, int outstanding,
                                  long outstandingDue) { }

    public List<RequesterLine> requesters(Instant since, int limit) {
        return jdbc.sql("""
                        SELECT r.name, r.torn_id,
                               count(DISTINCT c.id) AS contracts,
                               coalesce((SELECT sum(i.total) FROM invoice i JOIN contract c2 ON c2.id = i.contract_id
                                         WHERE c2.requester_id = r.torn_id AND i.created_at >= ?), 0) AS billed,
                               (SELECT avg(extract(epoch FROM i.paid_at - i.created_at)) FROM invoice i
                                JOIN contract c2 ON c2.id = i.contract_id
                                WHERE c2.requester_id = r.torn_id AND i.status = 'PAID' AND i.created_at >= ?) AS pay,
                               coalesce((SELECT sum(i.total - i.amount_paid) FROM invoice i JOIN contract c2 ON c2.id = i.contract_id
                                         WHERE c2.requester_id = r.torn_id AND i.status IN ('OPEN', 'PARTIAL')), 0) AS owed
                        FROM requester r JOIN contract c ON c.requester_id = r.torn_id AND c.created_at >= ? AND""" + LIVE + """
                        GROUP BY r.torn_id, r.name ORDER BY billed DESC LIMIT ?""")
                .params(ts(since), ts(since), ts(since), limit)
                .query((rs, n) -> new RequesterLine(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getLong(4),
                        longOrNull(rs, 5), rs.getLong(6)))
                .list();
    }

    public RequesterTotals requesterTotals(Instant since) {
        return jdbc.sql("""
                        SELECT (SELECT count(DISTINCT c.requester_id) FROM contract c WHERE c.created_at >= ? AND""" + LIVE + """
                               ),
                               coalesce((SELECT sum(i.total) FROM invoice i WHERE i.created_at >= ?), 0),
                               (SELECT avg(extract(epoch FROM i.paid_at - i.created_at)) FROM invoice i
                                WHERE i.status = 'PAID' AND i.created_at >= ?),
                               (SELECT round(100.0 * count(*) FILTER (WHERE i.paid_at <= i.due_at) / nullif(count(*), 0))
                                FROM invoice i WHERE i.status = 'PAID' AND i.created_at >= ?),
                               (SELECT count(*) FROM invoice i WHERE i.status IN ('OPEN', 'PARTIAL')),
                               coalesce((SELECT sum(i.total - i.amount_paid) FROM invoice i WHERE i.status IN ('OPEN', 'PARTIAL')), 0)""")
                .params(ts(since), ts(since), ts(since), ts(since))
                .query((rs, n) -> new RequesterTotals(rs.getInt(1), rs.getLong(2), longOrNull(rs, 3), intOrNull(rs, 4),
                        rs.getInt(5), rs.getLong(6)))
                .single();
    }

    // ------------------------------------------------------------------ leaderboard

    public record Standing(long mercId, String name, int hits, long earned) { }

    public record BestHosp(String name, int avgSeconds, int hits) { }

    public List<Standing> standings(Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT m.torn_id, m.name,
                               (SELECT count(*) FROM hit h WHERE h.accepted AND h.attacker_id = m.torn_id
                                AND h.started >= ? AND h.started < ?) AS hits,
                               (SELECT coalesce(sum(s.amount), 0) FROM hit_share s JOIN hit h ON h.id = s.hit_id
                                WHERE h.accepted AND s.merc_id = m.torn_id AND h.started >= ? AND h.started < ?) AS earned
                        FROM merc m WHERE m.active ORDER BY 3 DESC, 4 DESC, m.name LIMIT ?""")
                .params(ts(from), ts(to), ts(from), ts(to), limit)
                .query((rs, n) -> new Standing(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)))
                .list().stream().filter(s -> s.hits() > 0).toList();
    }

    public java.util.Optional<BestHosp> bestHospital(Instant from, Instant to, int minHits) {
        return jdbc.sql("""
                        SELECT m.name, avg(h.hosp_seconds), count(*) FROM hit h JOIN merc m ON m.torn_id = h.attacker_id
                        WHERE h.accepted AND m.active AND h.hosp_seconds IS NOT NULL AND h.started >= ? AND h.started < ?
                        GROUP BY m.torn_id, m.name HAVING count(*) >= ? ORDER BY 2 DESC LIMIT 1""")
                .params(ts(from), ts(to), minHits)
                .query((rs, n) -> new BestHosp(rs.getString(1), rs.getInt(2), rs.getInt(3))).optional();
    }

    // ------------------------------------------------------------------ audit view

    public record AuditLine(Instant at, Long actor, String action, String entity, String summary) { }

    public List<AuditLine> audit(List<String> actionPrefixes, String find, List<String> entities, int limit) {
        StringBuilder sql = new StringBuilder("SELECT at, actor_discord_id, action, entity, summary FROM audit_event WHERE TRUE");
        List<Object> params = new ArrayList<>();
        if (actionPrefixes != null && !actionPrefixes.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < actionPrefixes.size(); i++) {
                sql.append(i == 0 ? "" : " OR ").append("action LIKE ?");
                params.add(actionPrefixes.get(i) + "%");
            }
            sql.append(")");
        }
        if (find != null) {
            sql.append(" AND (summary ILIKE ?");
            params.add("%" + find.replace("%", "").replace("_", "\\_") + "%");
            for (String e : entities) {
                sql.append(" OR entity = ?");
                params.add(e);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new AuditLine(instant(rs, "at"), rs.getObject("actor_discord_id", Long.class),
                        rs.getString("action"), rs.getString("entity"), rs.getString("summary")))
                .list();
    }

    public java.util.Optional<Long> contractIdByRef(String ref) {
        return jdbc.sql("SELECT id FROM contract WHERE upper(ref) = upper(?)").param(ref).query(Long.class).optional();
    }

    // ------------------------------------------------------------------ helpers

    private static Integer intOrNull(ResultSet rs, int col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).intValue();
    }

    private static Long longOrNull(ResultSet rs, int col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
