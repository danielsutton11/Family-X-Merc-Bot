package com.familyx.mercbot.inbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Requester ↔ leader messages, kept against the contract (scope doc 4.5). */
@Repository
public class InboxRepository {

    public record Message(long id, long contractId, boolean toLeaders, long authorDiscordId, String authorName,
                          String text, Long cardMessageId, Instant answeredAt, Instant createdAt) { }

    private final JdbcClient jdbc;

    public InboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long add(long contractId, boolean toLeaders, long author, String authorName, String text) {
        return jdbc.sql("""
                        INSERT INTO inbox_message (contract_id, direction, author_discord_id, author_name, text)
                        VALUES (?, ?, ?, ?, ?) RETURNING id""")
                .params(contractId, toLeaders ? "TO_LEADERS" : "TO_REQUESTER", author, authorName, text)
                .query(Long.class).single();
    }

    /** Requester messages on this contract nobody has answered yet, oldest first. */
    public List<Message> waiting(long contractId) {
        return jdbc.sql("""
                        SELECT * FROM inbox_message WHERE contract_id = ? AND direction = 'TO_LEADERS'
                          AND answered_at IS NULL ORDER BY created_at, id""")
                .param(contractId).query(this::map).list();
    }

    /** The newest messages on a contract, newest last. */
    public List<Message> recent(long contractId, int limit) {
        List<Message> list = new java.util.ArrayList<>(jdbc.sql(
                        "SELECT * FROM inbox_message WHERE contract_id = ? ORDER BY created_at DESC, id DESC LIMIT ?")
                .params(contractId, limit).query(this::map).list());
        java.util.Collections.reverse(list);
        return list;
    }

    public int count(long contractId) {
        return jdbc.sql("SELECT count(*) FROM inbox_message WHERE contract_id = ?").param(contractId)
                .query(Integer.class).single();
    }

    public int countFromRequester(long contractId) {
        return jdbc.sql("SELECT count(*) FROM inbox_message WHERE contract_id = ? AND direction = 'TO_LEADERS'")
                .param(contractId).query(Integer.class).single();
    }

    /** When this person last wrote to leaders (any contract), for the one-a-minute limit. */
    public Optional<Instant> lastFrom(long authorDiscordId) {
        return jdbc.sql("""
                        SELECT max(created_at) FROM inbox_message
                        WHERE author_discord_id = ? AND direction = 'TO_LEADERS'""")
                .param(authorDiscordId).query((rs, n) -> Optional.ofNullable(instant(rs, 1))).list().stream()
                .flatMap(Optional::stream).findFirst();
    }

    /** Puts the waiting messages on one inbox card. */
    public void setCard(long contractId, long cardMessageId) {
        jdbc.sql("""
                        UPDATE inbox_message SET discord_message_id = ?
                        WHERE contract_id = ? AND direction = 'TO_LEADERS' AND answered_at IS NULL""")
                .params(cardMessageId, contractId).update();
    }

    /** Marks every waiting message answered; returns the cards they were on (to delete). */
    public List<Long> answer(long contractId, long by) {
        return jdbc.sql("""
                        UPDATE inbox_message SET answered_at = now(), answered_by = ?
                        WHERE contract_id = ? AND direction = 'TO_LEADERS' AND answered_at IS NULL
                        RETURNING discord_message_id""")
                .params(by, contractId).query((rs, n) -> rs.getObject(1, Long.class)).list().stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    private Message map(ResultSet rs, int n) throws SQLException {
        return new Message(rs.getLong("id"), rs.getLong("contract_id"), "TO_LEADERS".equals(rs.getString("direction")),
                rs.getLong("author_discord_id"), rs.getString("author_name"), rs.getString("text"),
                rs.getObject("discord_message_id", Long.class), instant(rs, "answered_at"), instant(rs, "created_at"));
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
