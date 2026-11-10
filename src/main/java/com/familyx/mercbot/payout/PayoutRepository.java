package com.familyx.mercbot.payout;

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

/**
 * Merc shares once they're owed: payout strings, credits and settlements (scope doc 6.6).
 *
 * <p>Which shares a faction pays out: Capacity X (the faction whose bank holds the mercs pool)
 * pays its own mercs, and any merc not in another family faction, straight from the pool. Every
 * other family faction pays its mercs once Capacity X's settlement with it has been received.
 */
@Repository
public class PayoutRepository {

    public record Line(long mercId, String name, long amount, Instant creditedAt) { }

    public record Payout(long id, long factionId, String status, long total, long createdBy, Instant createdAt) { }

    public record Settlement(long id, long factionId, Long contractId, Instant periodStart, Instant periodEnd, long total,
                             String status, Long sentBy, Instant sentAt, Instant receivedAt, Long receivedBy,
                             Long pingMessageId) { }

    /** Mercs paid from the pool: not in another active family faction. */
    private static final String POOL_SCOPE = """
            (s.faction_id IS NULL OR s.faction_id NOT IN (
                SELECT torn_faction_id FROM family_faction WHERE active AND torn_faction_id <> :pool))""";

    private final JdbcClient jdbc;

    public PayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String scope(long faction, long pool) {
        return faction == pool ? POOL_SCOPE : "s.faction_id = :faction";
    }

    // ------------------------------------------------------------------ owed and payout strings

    /** Shares ready to pay out and not yet in a payout string, one line per merc, largest first. */
    public List<Line> ready(long faction, long pool) {
        return jdbc.sql("""
                        SELECT s.merc_id, max(m.name) AS name, sum(s.amount) AS amount, NULL::timestamptz AS credited_at
                        FROM hit_share s LEFT JOIN settlement st ON st.id = s.settlement_id
                        LEFT JOIN merc m ON m.torn_id = s.merc_id
                        WHERE s.state = 'OWED' AND s.payout_id IS NULL AND\s""" + scope(faction, pool)
                        + (faction == pool ? "" : " AND st.status = 'RECEIVED'")
                        + " GROUP BY s.merc_id ORDER BY sum(s.amount) DESC, s.merc_id")
                .param("faction", faction).param("pool", pool).query(this::line).list();
    }

    public Optional<Payout> openPayout(long faction) {
        return jdbc.sql("SELECT * FROM payout WHERE faction_id = :f AND status = 'OPEN'").param("f", faction)
                .query(this::mapPayout).optional();
    }

    public Optional<Payout> payout(long id) {
        return jdbc.sql("SELECT * FROM payout WHERE id = :id").param("id", id).query(this::mapPayout).optional();
    }

    public List<Payout> openPayouts() {
        return jdbc.sql("SELECT * FROM payout WHERE status = 'OPEN'").query(this::mapPayout).list();
    }

    public List<Line> lines(long payoutId) {
        return jdbc.sql("""
                        SELECT l.merc_id, m.name, l.amount, l.credited_at FROM payout_line l
                        LEFT JOIN merc m ON m.torn_id = l.merc_id
                        WHERE l.payout_id = :p ORDER BY l.amount DESC, l.merc_id""")
                .param("p", payoutId).query(this::line).list();
    }

    /**
     * Freezes everything ready into a new payout string. Shares are tagged first and the lines
     * summed from them, so a share owed in the meantime can't end up half in. Empty → no payout.
     */
    public Optional<Payout> createPayout(long faction, long pool, long by) {
        long id = jdbc.sql("INSERT INTO payout (faction_id, total, created_by) VALUES (:f, 0, :by) RETURNING id")
                .param("f", faction).param("by", by).query(Long.class).single();
        jdbc.sql("""
                        UPDATE hit_share s SET payout_id = :id, updated_at = now()
                        FROM hit_share s2 LEFT JOIN settlement st ON st.id = s2.settlement_id
                        WHERE s2.id = s.id AND s.state = 'OWED' AND s.payout_id IS NULL AND\s""" + scope(faction, pool)
                        + (faction == pool ? "" : " AND st.status = 'RECEIVED'"))
                .param("id", id).param("faction", faction).param("pool", pool).update();
        jdbc.sql("""
                        INSERT INTO payout_line (payout_id, merc_id, amount)
                        SELECT :id, merc_id, sum(amount) FROM hit_share WHERE payout_id = :id GROUP BY merc_id""")
                .param("id", id).update();
        long total = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payout_line WHERE payout_id = :id")
                .param("id", id).query(Long.class).single();
        if (total == 0) {
            jdbc.sql("DELETE FROM payout WHERE id = :id").param("id", id).update();
            return Optional.empty();
        }
        jdbc.sql("UPDATE payout SET total = :t WHERE id = :id").param("t", total).param("id", id).update();
        return payout(id);
    }

    /** Uncredited lines go back to Owed; the string closes (Done if some lines were credited). */
    public void cancel(long payoutId) {
        jdbc.sql("UPDATE hit_share SET payout_id = NULL, updated_at = now() WHERE payout_id = :p AND state = 'OWED'")
                .param("p", payoutId).update();
        jdbc.sql("DELETE FROM payout_line WHERE payout_id = :p AND credited_at IS NULL").param("p", payoutId).update();
        jdbc.sql("""
                        UPDATE payout SET closed_at = now(),
                               total = (SELECT coalesce(sum(amount), 0) FROM payout_line WHERE payout_id = :p),
                               status = CASE WHEN EXISTS (SELECT 1 FROM payout_line WHERE payout_id = :p)
                                             THEN 'DONE' ELSE 'CANCELLED' END
                        WHERE id = :p AND status = 'OPEN'""")
                .param("p", payoutId).update();
    }

    /** Marks one merc's line credited. False if it already was. Closes the string once every line is. */
    public boolean credit(long payoutId, long mercId, String newsId, Long by) {
        int n = jdbc.sql("""
                        UPDATE payout_line SET credited_at = now(), credited_news_id = :news, credited_by = :by
                        WHERE payout_id = :p AND merc_id = :m AND credited_at IS NULL""")
                .param("news", newsId).param("by", by).param("p", payoutId).param("m", mercId).update();
        if (n == 0) {
            return false;
        }
        jdbc.sql("""
                        UPDATE hit_share SET state = 'CREDITED', credited_at = now(), credited_news_id = :news,
                               updated_at = now()
                        WHERE payout_id = :p AND merc_id = :m AND state = 'OWED'""")
                .param("news", newsId).param("p", payoutId).param("m", mercId).update();
        jdbc.sql("""
                        UPDATE payout SET status = 'DONE', closed_at = now()
                        WHERE id = :p AND status = 'OPEN'
                          AND NOT EXISTS (SELECT 1 FROM payout_line WHERE payout_id = :p AND credited_at IS NULL)""")
                .param("p", payoutId).update();
        return true;
    }

    public boolean newsUsed(String newsId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payout_line WHERE credited_news_id = :n)").param("n", newsId)
                .query(Boolean.class).single();
    }

    /** The contract references behind one merc's line, and how many hits they're from. */
    public record Refs(int hits, List<String> refs) { }

    public Refs refs(long payoutId, long mercId) {
        List<String> refs = jdbc.sql("""
                        SELECT DISTINCT c.ref FROM hit_share s JOIN hit h ON h.id = s.hit_id
                        JOIN contract_target ct ON ct.id = h.contract_target_id JOIN contract c ON c.id = ct.contract_id
                        WHERE s.payout_id = :p AND s.merc_id = :m ORDER BY c.ref""")
                .param("p", payoutId).param("m", mercId).query(String.class).list();
        int hits = jdbc.sql("SELECT count(DISTINCT hit_id) FROM hit_share WHERE payout_id = :p AND merc_id = :m")
                .param("p", payoutId).param("m", mercId).query(Integer.class).single();
        return new Refs(hits, refs);
    }

    // ------------------------------------------------------------------ totals

    /** Shares not yet payable because the requester hasn't paid. */
    public long pending(long faction, long pool) {
        return jdbc.sql("SELECT coalesce(sum(s.amount), 0) FROM hit_share s WHERE s.state = 'PENDING' AND "
                        + scope(faction, pool))
                .param("faction", faction).param("pool", pool).query(Long.class).single();
    }

    public long credited7d(long faction, long pool) {
        return jdbc.sql("SELECT coalesce(sum(s.amount), 0) FROM hit_share s WHERE s.state = 'CREDITED' "
                        + "AND s.credited_at > now() - interval '7 days' AND " + scope(faction, pool))
                .param("faction", faction).param("pool", pool).query(Long.class).single();
    }

    /** All owed shares, paid in and not credited, across every faction. */
    public long owedAll() {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM hit_share WHERE state = 'OWED'").query(Long.class).single();
    }

    public long owed(long faction, long pool) {
        return jdbc.sql("SELECT coalesce(sum(s.amount), 0) FROM hit_share s WHERE s.state = 'OWED' AND "
                        + scope(faction, pool))
                .param("faction", faction).param("pool", pool).query(Long.class).single();
    }

    public long credited7dAll() {
        return jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) FROM hit_share
                        WHERE state = 'CREDITED' AND credited_at > now() - interval '7 days'""")
                .query(Long.class).single();
    }

    /** The faction cut kept in the pool on invoices paid in the last 7 days: billed minus every share. */
    public long cut7d() {
        return jdbc.sql("""
                        SELECT coalesce(sum(h.billed_amount - coalesce(
                                   (SELECT sum(s.amount) FROM hit_share s WHERE s.hit_id = h.id), 0)), 0)
                        FROM hit h JOIN invoice i ON i.id = h.invoice_id
                        WHERE i.status = 'PAID' AND i.paid_at > now() - interval '7 days'""")
                .query(Long.class).single();
    }

    // ------------------------------------------------------------------ settlements

    /** Owed shares of another faction not yet in a settlement (building up for the next close). */
    public long unsettled(long faction) {
        return jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) FROM hit_share
                        WHERE state = 'OWED' AND settlement_id IS NULL AND faction_id = :f""")
                .param("f", faction).query(Long.class).single();
    }

    /** Puts a faction's unsettled owed shares (owed before the end) into one settlement. Empty → none. */
    public Optional<Long> settlePeriod(long faction, Instant start, Instant end) {
        boolean any = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM hit_share WHERE state = 'OWED' AND settlement_id IS NULL
                                       AND faction_id = :f AND owed_at < :end)""")
                .param("f", faction).param("end", ts(end)).query(Boolean.class).single();
        if (!any) {
            return Optional.empty();
        }
        long id = insertSettlement(faction, null, start, end);
        jdbc.sql("""
                        UPDATE hit_share SET settlement_id = :id
                        WHERE state = 'OWED' AND settlement_id IS NULL AND faction_id = :f AND owed_at < :end""")
                .param("id", id).param("f", faction).param("end", ts(end)).update();
        return finishSettlement(id);
    }

    /** Per-contract settlement: a faction's unsettled owed shares from one contract. */
    public Optional<Long> settleContract(long faction, long contractId, Instant start, Instant end) {
        long id = insertSettlement(faction, contractId, start, end);
        jdbc.sql("""
                        UPDATE hit_share s SET settlement_id = :id
                        FROM hit h JOIN contract_target ct ON ct.id = h.contract_target_id
                        WHERE h.id = s.hit_id AND ct.contract_id = :c AND s.faction_id = :f
                          AND s.state = 'OWED' AND s.settlement_id IS NULL""")
                .param("id", id).param("c", contractId).param("f", faction).update();
        return finishSettlement(id);
    }

    /** Faction and contract pairs with owed shares not yet settled (for per-contract settlement). */
    public record Unsettled(long factionId, long contractId) { }

    public List<Unsettled> unsettledByContract(long pool) {
        return jdbc.sql("""
                        SELECT DISTINCT s.faction_id, ct.contract_id FROM hit_share s
                        JOIN hit h ON h.id = s.hit_id JOIN contract_target ct ON ct.id = h.contract_target_id
                        WHERE s.state = 'OWED' AND s.settlement_id IS NULL AND s.faction_id IN (
                            SELECT torn_faction_id FROM family_faction WHERE active AND torn_faction_id <> :pool)""")
                .param("pool", pool)
                .query((rs, n) -> new Unsettled(rs.getLong(1), rs.getLong(2))).list();
    }

    private long insertSettlement(long faction, Long contractId, Instant start, Instant end) {
        return jdbc.sql("""
                        INSERT INTO settlement (faction_id, contract_id, period_start, period_end, total)
                        VALUES (:f, :c, :s, :e, 0) RETURNING id""")
                .param("f", faction).param("c", contractId).param("s", ts(start)).param("e", ts(end))
                .query(Long.class).single();
    }

    private Optional<Long> finishSettlement(long id) {
        long total = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM hit_share WHERE settlement_id = :id")
                .param("id", id).query(Long.class).single();
        if (total == 0) {
            jdbc.sql("DELETE FROM settlement WHERE id = :id").param("id", id).update();
            return Optional.empty();
        }
        jdbc.sql("UPDATE settlement SET total = :t WHERE id = :id").param("t", total).param("id", id).update();
        return Optional.of(id);
    }

    public Optional<Settlement> settlement(long id) {
        return jdbc.sql("SELECT * FROM settlement WHERE id = :id").param("id", id).query(this::mapSettlement).optional();
    }

    /** Settlements still in progress (Owed or Sent), oldest first; all factions when faction is null. */
    public List<Settlement> inProgress(Long faction) {
        return jdbc.sql("SELECT * FROM settlement WHERE status IN ('OWED', 'SENT')"
                        + (faction == null ? "" : " AND faction_id = :f") + " ORDER BY period_end, id")
                .param("f", faction == null ? 0L : faction).query(this::mapSettlement).list();
    }

    /** The newest settlement for a faction, whatever its state. */
    public Optional<Settlement> latest(long faction) {
        return jdbc.sql("SELECT * FROM settlement WHERE faction_id = :f ORDER BY id DESC LIMIT 1")
                .param("f", faction).query(this::mapSettlement).optional();
    }

    public boolean markSent(long id, long by) {
        return jdbc.sql("UPDATE settlement SET status = 'SENT', sent_by = :by, sent_at = now() "
                        + "WHERE id = :id AND status = 'OWED'")
                .param("by", by).param("id", id).update() == 1;
    }

    public void setPing(long id, Long messageId) {
        jdbc.sql("UPDATE settlement SET ping_message_id = :m WHERE id = :id").param("m", messageId).param("id", id).update();
    }

    public boolean markReceived(long id, long by) {
        return jdbc.sql("UPDATE settlement SET status = 'RECEIVED', received_by = :by, received_at = now() "
                        + "WHERE id = :id AND status = 'SENT'")
                .param("by", by).param("id", id).update() == 1;
    }

    /** One line per merc in a settlement, with the contracts behind it. */
    public record SettlementLine(long mercId, String name, long amount, int hits, String refs) { }

    public List<SettlementLine> settlementLines(long id) {
        return jdbc.sql("""
                        SELECT s.merc_id, max(m.name), sum(s.amount), count(DISTINCT s.hit_id),
                               string_agg(DISTINCT c.ref, ', ')
                        FROM hit_share s LEFT JOIN merc m ON m.torn_id = s.merc_id
                        JOIN hit h ON h.id = s.hit_id JOIN contract_target ct ON ct.id = h.contract_target_id
                        JOIN contract c ON c.id = ct.contract_id
                        WHERE s.settlement_id = :id GROUP BY s.merc_id ORDER BY sum(s.amount) DESC""")
                .param("id", id)
                .query((rs, n) -> new SettlementLine(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4),
                        rs.getString(5)))
                .list();
    }

    public Optional<String> contractRef(long contractId) {
        return jdbc.sql("SELECT ref FROM contract WHERE id = :id").param("id", contractId).query(String.class).optional();
    }

    // ------------------------------------------------------------------ mapping

    private Line line(ResultSet rs, int n) throws SQLException {
        return new Line(rs.getLong("merc_id"), rs.getString("name"), rs.getLong("amount"), instant(rs, "credited_at"));
    }

    private Payout mapPayout(ResultSet rs, int n) throws SQLException {
        return new Payout(rs.getLong("id"), rs.getLong("faction_id"), rs.getString("status"), rs.getLong("total"),
                rs.getLong("created_by"), instant(rs, "created_at"));
    }

    private Settlement mapSettlement(ResultSet rs, int n) throws SQLException {
        return new Settlement(rs.getLong("id"), rs.getLong("faction_id"), (Long) rs.getObject("contract_id", Long.class),
                instant(rs, "period_start"), instant(rs, "period_end"), rs.getLong("total"), rs.getString("status"),
                rs.getObject("sent_by", Long.class), instant(rs, "sent_at"), instant(rs, "received_at"),
                rs.getObject("received_by", Long.class), rs.getObject("ping_message_id", Long.class));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
