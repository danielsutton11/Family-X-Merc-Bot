package com.familyx.mercbot.payment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Invoices, the hits on them, and deposits read from faction news (scope doc 4.6, 10.5, 10.6). */
@Repository
public class InvoiceRepository {

    public record Invoice(long id, long contractId, String kind, long total, long discountTotal, long amountPaid,
                          String status, Long payeeTornId, String payeeName, Instant dueAt, Instant remindedAt,
                          Long overdueCardId, Instant createdAt, Instant paidAt) {
        public long due() {
            return Math.max(0, total - amountPaid);
        }
    }

    /** One line of an invoice. */
    public record Line(Instant started, String target, long targetId, String tier, String downgrade, Integer hospSeconds,
                       String merc, long billed, String attackCode) { }

    /** A deposit seen in faction news. */
    public record Deposit(String newsId, Instant at, long actorId, String actorName, long accountId, long amount) { }

    private final JdbcClient jdbc;

    public InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Accepted hits on a contract that aren't on an invoice yet. */
    public List<Long> uninvoicedHits(long contractId) {
        return jdbc.sql("""
                        SELECT h.id FROM hit h JOIN contract_target ct ON ct.id = h.contract_target_id
                        WHERE ct.contract_id = ? AND h.accepted AND h.invoice_id IS NULL""")
                .param(contractId).query(Long.class).list();
    }

    public long sumBilled(List<Long> hitIds) {
        return jdbc.sql("SELECT coalesce(sum(billed_amount), 0) FROM hit WHERE id = ANY(?)")
                .param(hitIds.toArray(new Long[0])).query(Long.class).single();
    }

    /** What the discount took off: list price minus billed, for hits billed at the contract's own tier. */
    public long discountOn(List<Long> hitIds, long contractId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(c.unit_price - h.billed_amount), 0)
                        FROM hit h JOIN contract_target ct ON ct.id = h.contract_target_id
                        JOIN contract c ON c.id = ct.contract_id
                        WHERE h.id = ANY(?) AND c.id = ? AND h.billed_tier_id = c.tier_id""")
                .params(hitIds.toArray(new Long[0]), contractId).query(Long.class).single();
    }

    public long create(long contractId, String kind, long total, long discount, Long payeeId, String payeeName,
                       Instant dueAt, Instant periodStart, Instant periodEnd, List<Long> hitIds) {
        long id = jdbc.sql("""
                        INSERT INTO invoice (contract_id, kind, period_start, period_end, total, discount_total,
                                             payee_torn_id, payee_name, due_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(contractId, kind, ts(periodStart), ts(periodEnd), total, discount, payeeId, payeeName, ts(dueAt))
                .query(Long.class).single();
        jdbc.sql("UPDATE hit SET invoice_id = ? WHERE id = ANY(?)").params(id, hitIds.toArray(new Long[0])).update();
        return id;
    }

    public Optional<Invoice> find(long id) {
        return jdbc.sql("SELECT * FROM invoice WHERE id = ?").param(id).query(this::map).optional();
    }

    public List<Invoice> forContract(long contractId) {
        return jdbc.sql("SELECT * FROM invoice WHERE contract_id = ? ORDER BY created_at").param(contractId)
                .query(this::map).list();
    }

    /** Open invoices whose amount still due is exactly this, oldest first. */
    public List<Invoice> openWithDue(long amount) {
        return jdbc.sql("""
                        SELECT * FROM invoice WHERE status IN ('OPEN', 'PARTIAL') AND total - amount_paid = ?
                        ORDER BY created_at""")
                .param(amount).query(this::map).list();
    }

    /** Every open or part-paid invoice, oldest first (for matching a deposit by hand). */
    public List<Invoice> open(int limit) {
        return jdbc.sql("SELECT * FROM invoice WHERE status IN ('OPEN', 'PARTIAL') ORDER BY created_at LIMIT ?")
                .param(limit).query(this::map).list();
    }

    /** Leader panel → Invoices & payouts lists (phase 4c). */
    public enum LeaderList {
        OPEN("Open invoices", "status IN ('OPEN', 'PARTIAL') ORDER BY due_at"),
        OVERDUE("Overdue", "status IN ('OPEN', 'PARTIAL') AND due_at < now() ORDER BY due_at"),
        PAID("Paid (30 days)", "status = 'PAID' AND paid_at > now() - interval '30 days' ORDER BY paid_at DESC"),
        ALL("All", "TRUE ORDER BY created_at DESC");

        public final String label;
        final String where;

        LeaderList(String label, String where) {
            this.label = label;
            this.where = where;
        }
    }

    public List<Invoice> forLeaders(LeaderList list, int limit) {
        return jdbc.sql("SELECT * FROM invoice WHERE " + list.where + " LIMIT ?").param(limit).query(this::map).list();
    }

    /** What requesters still owe on open and part-paid invoices, and how many there are. */
    public record Unpaid(long due, int count) { }

    public Unpaid unpaid() {
        return jdbc.sql("""
                        SELECT coalesce(sum(total - amount_paid), 0), count(*) FROM invoice
                        WHERE status IN ('OPEN', 'PARTIAL')""")
                .query((rs, n) -> new Unpaid(rs.getLong(1), rs.getInt(2))).single();
    }

    public List<Invoice> overdueUnreminded() {
        return jdbc.sql("""
                        SELECT * FROM invoice WHERE status IN ('OPEN', 'PARTIAL') AND due_at < now()
                          AND reminded_at IS NULL""")
                .query(this::map).list();
    }

    public void reminded(long id, Long cardId) {
        jdbc.sql("UPDATE invoice SET reminded_at = now(), overdue_card_id = coalesce(?, overdue_card_id) WHERE id = ?")
                .params(cardId, id).update();
    }

    /** Adds a payment; returns the invoice as it now stands. */
    public Invoice pay(long id, long amount, String newsId, long by) {
        jdbc.sql("""
                        UPDATE invoice SET amount_paid = amount_paid + ?,
                               status = CASE WHEN amount_paid + ? >= total THEN 'PAID' ELSE 'PARTIAL' END,
                               paid_at = CASE WHEN amount_paid + ? >= total THEN now() ELSE paid_at END,
                               paid_marked_by = ?,
                               matched_news_ids = CASE WHEN ?::text IS NULL THEN matched_news_ids
                                                       ELSE array_append(matched_news_ids, ?::text) END
                        WHERE id = ?""")
                .params(amount, amount, amount, by, newsId, newsId, id).update();
        return find(id).orElseThrow();
    }

    /** Shares on this invoice's hits: Pending → Owed. Returns merc id → amount now owed. */
    public java.util.Map<Long, Long> sharesOwed(long invoiceId) {
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        jdbc.sql("""
                        UPDATE hit_share s SET state = 'OWED', owed_at = now(), updated_at = now()
                        FROM hit h WHERE h.id = s.hit_id AND h.invoice_id = ? AND s.state = 'PENDING'
                        RETURNING s.merc_id, s.amount""")
                .param(invoiceId)
                .query((ResultSet rs, int n) -> out.merge(rs.getLong(1), rs.getLong(2), Long::sum)).list();
        return out;
    }

    public int hitsOn(long invoiceId, long mercId) {
        return jdbc.sql("""
                        SELECT count(DISTINCT s.hit_id) FROM hit_share s JOIN hit h ON h.id = s.hit_id
                        WHERE h.invoice_id = ? AND s.merc_id = ?""")
                .params(invoiceId, mercId).query(Integer.class).single();
    }

    public List<Line> lines(long invoiceId) {
        return jdbc.sql("""
                        SELECT h.started, coalesce(ct.target_name, ct.target_torn_id::text) AS target, ct.target_torn_id,
                               t.name AS tier, h.downgrade_reason, h.hosp_seconds,
                               coalesce(m.name, h.attacker_id::text) AS merc, h.billed_amount, h.attack_code
                        FROM hit h JOIN contract_target ct ON ct.id = h.contract_target_id
                        LEFT JOIN tier t ON t.id = h.billed_tier_id
                        LEFT JOIN merc m ON m.torn_id = h.attacker_id
                        WHERE h.invoice_id = ? ORDER BY h.started""")
                .param(invoiceId)
                .query((rs, n) -> new Line(instant(rs, "started"), rs.getString("target"), rs.getLong("target_torn_id"),
                        rs.getString("tier"), rs.getString("downgrade_reason"), integer(rs, "hosp_seconds"),
                        rs.getString("merc"), rs.getLong("billed_amount"), rs.getString("attack_code")))
                .list();
    }

    /** Running contracts with no end that have hits not yet invoiced (for interim invoices). */
    public List<Long> openEndedWithUninvoiced() {
        return jdbc.sql("""
                        SELECT DISTINCT c.id FROM contract c JOIN contract_target ct ON ct.contract_id = c.id
                        JOIN hit h ON h.contract_target_id = ct.id
                        WHERE c.status IN ('ACTIVE', 'PAUSED') AND c.ends_at IS NULL
                          AND h.accepted AND h.invoice_id IS NULL""")
                .query(Long.class).list();
    }

    // ------------------------------------------------------------------ deposits

    /** Records a news entry; false if it was already seen. */
    public boolean remember(long factionId, Deposit d, String direction, Long balanceBefore, Long balanceAfter) {
        return jdbc.sql("""
                        INSERT INTO faction_news_seen (news_id, faction_id, at, actor_id, direction, account_id, amount,
                                                       balance_before, balance_after)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (news_id) DO NOTHING""")
                .params(d.newsId(), factionId, ts(d.at()), d.actorId(), direction, d.accountId(), d.amount(),
                        balanceBefore, balanceAfter)
                .update() == 1;
    }

    public void linkDeposit(String newsId, Long invoiceId, Long cardId) {
        jdbc.sql("UPDATE faction_news_seen SET invoice_id = ?, card_message_id = ? WHERE news_id = ?")
                .params(invoiceId, cardId, newsId).update();
    }

    public void depositOutcome(String newsId, String outcome) {
        jdbc.sql("UPDATE faction_news_seen SET outcome = ? WHERE news_id = ?").params(outcome, newsId).update();
    }

    public record SeenDeposit(String newsId, Long invoiceId, long amount, String outcome, Long cardId) { }

    public Optional<SeenDeposit> deposit(String newsId) {
        return jdbc.sql("SELECT news_id, invoice_id, amount, outcome, card_message_id FROM faction_news_seen WHERE news_id = ?")
                .param(newsId)
                .query((rs, n) -> new SeenDeposit(rs.getString(1), longOrNull(rs, 2), rs.getLong(3), rs.getString(4),
                        longOrNull(rs, 5)))
                .optional();
    }

    // ------------------------------------------------------------------ helpers

    private Invoice map(ResultSet rs, int n) throws SQLException {
        return new Invoice(rs.getLong("id"), rs.getLong("contract_id"), rs.getString("kind"), rs.getLong("total"),
                rs.getLong("discount_total"), rs.getLong("amount_paid"), rs.getString("status"),
                longOrNull(rs, "payee_torn_id"), rs.getString("payee_name"), instant(rs, "due_at"),
                instant(rs, "reminded_at"), longOrNull(rs, "overdue_card_id"), instant(rs, "created_at"),
                instant(rs, "paid_at"));
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Long longOrNull(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
