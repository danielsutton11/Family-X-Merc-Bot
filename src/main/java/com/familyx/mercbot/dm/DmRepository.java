package com.familyx.mercbot.dm;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** DMs the bot will delete later (DM cleanup): by time, or when what they say stops being true. */
@Repository
public class DmRepository {

    public record Tracked(long id, long userId, long messageId, String kind, String ref, Instant deleteAt, Instant createdAt) { }

    private final JdbcClient jdbc;

    public DmRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void track(long userId, long messageId, String kind, String ref, Instant deleteAt) {
        jdbc.sql("INSERT INTO dm_message (user_id, message_id, kind, ref, delete_at) VALUES (?, ?, ?, ?, ?)")
                .params(userId, messageId, kind, ref, ts(deleteAt)).update();
    }

    /** Marks earlier DMs of this kind and subject for deletion now (a newer one replaces them). */
    public void expire(long userId, String kind, String ref) {
        jdbc.sql("UPDATE dm_message SET delete_at = now() WHERE user_id = ? AND kind = ? AND ref = ?")
                .params(userId, kind, ref).update();
    }

    /** The live message of this kind and subject for a user, if one hasn't been deleted yet. */
    public Optional<Tracked> live(long userId, String kind, String ref) {
        return jdbc.sql("""
                        SELECT * FROM dm_message WHERE user_id = ? AND kind = ? AND ref = ?
                          AND (delete_at IS NULL OR delete_at > now()) ORDER BY id DESC LIMIT 1""")
                .params(userId, kind, ref).query(this::map).optional();
    }

    public void setDeleteAt(long id, Instant at) {
        jdbc.sql("UPDATE dm_message SET delete_at = ? WHERE id = ?").params(ts(at), id).update();
    }

    public void forget(long id) {
        jdbc.sql("DELETE FROM dm_message WHERE id = ?").param(id).update();
    }

    public List<Tracked> due(int limit) {
        return jdbc.sql("SELECT * FROM dm_message WHERE delete_at <= now() ORDER BY delete_at LIMIT ?").param(limit)
                .query(this::map).list();
    }

    /**
     * DMs waiting on an event whose event has now happened: their deletion becomes due. Each
     * kind's condition reads the current state, so nothing has to remember to call in.
     */
    public int markEventsDone() {
        int n = 0;
        n += mark("PART_PAID", "EXISTS (SELECT 1 FROM invoice i WHERE 'invoice:' || i.id = d.ref AND i.status = 'PAID')");
        n += mark("OVERDUE", "EXISTS (SELECT 1 FROM invoice i WHERE 'invoice:' || i.id = d.ref AND i.status = 'PAID')");
        n += mark("SHARES_OWED", """
                NOT EXISTS (SELECT 1 FROM hit_share s JOIN hit h ON h.id = s.hit_id
                            WHERE 'invoice:' || h.invoice_id || ':merc:' || s.merc_id = d.ref AND s.state <> 'CREDITED')""");
        n += mark("AVAIL_OFF", "EXISTS (SELECT 1 FROM merc m WHERE 'merc:' || m.torn_id = d.ref AND m.available)");
        n += mark("COOLDOWN", """
                EXISTS (SELECT 1 FROM merc m WHERE 'merc:' || m.torn_id = d.ref
                        AND (m.claim_cooldown_until IS NULL OR m.claim_cooldown_until <= now()))""");
        n += mark("SUSPENDED", """
                EXISTS (SELECT 1 FROM merc m WHERE 'merc:' || m.torn_id = d.ref
                        AND (m.claims_suspended_until IS NULL OR m.claims_suspended_until <= now()))""");
        n += mark("KEY_FAILED", """
                EXISTS (SELECT 1 FROM api_key k WHERE k.owner_discord_id = d.user_id AND k.healthy
                        AND k.created_at > d.created_at)""");
        n += mark("DEFENSIVE_PAUSE", """
                EXISTS (SELECT 1 FROM contract c WHERE 'contract:' || c.id = d.ref AND c.status <> 'PAUSED')""");
        return n;
    }

    private int mark(String kind, String condition) {
        return jdbc.sql("UPDATE dm_message d SET delete_at = now() WHERE d.kind = ? AND (d.delete_at IS NULL OR d.delete_at > now()) AND "
                        + condition)
                .param(kind).update();
    }

    /** Control panels of finished contracts with everything paid, 7 days on from the later of the two. */
    public record Panel(long contractId, long requesterDiscordId, long messageId) { }

    public List<Panel> panelsDue() {
        return jdbc.sql("""
                        SELECT c.id, r.discord_id, c.dm_panel_message_id FROM contract c
                        JOIN requester r ON r.torn_id = c.requester_id
                        WHERE c.dm_panel_message_id IS NOT NULL AND c.dm_panel_deleted_at IS NULL
                          AND c.status IN ('COMPLETED', 'ENDED', 'CANCELLED', 'REJECTED')
                          AND NOT EXISTS (SELECT 1 FROM invoice i WHERE i.contract_id = c.id AND i.status <> 'PAID')
                          AND greatest(coalesce(c.ended_at, c.created_at),
                                       coalesce((SELECT max(i.paid_at) FROM invoice i WHERE i.contract_id = c.id), c.created_at))
                              < now() - interval '7 days'
                        LIMIT 50""")
                .query((rs, n) -> new Panel(rs.getLong(1), rs.getLong(2), rs.getLong(3))).list();
    }

    public void panelDeleted(long contractId) {
        jdbc.sql("UPDATE contract SET dm_panel_deleted_at = now(), dm_panel_message_id = NULL WHERE id = ?")
                .param(contractId).update();
    }

    private Tracked map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Tracked(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("message_id"), rs.getString("kind"),
                rs.getString("ref"), instant(rs.getTimestamp("delete_at")), instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
