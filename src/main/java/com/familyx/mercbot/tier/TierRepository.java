package com.familyx.mercbot.tier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TierRepository {

    /** A tier with its current price. */
    public record Tier(int id, String name, int rank, int colour, boolean requiresMerits, boolean requiresPerk,
                       boolean requiresStricken, Long roleId, Long channelId, Long boardMessageId,
                       boolean enabled, boolean autoHidden, long pricePerHit) {

        /** Shown on the request panel: switched on, and not hidden for having no mercs. */
        public boolean offered() {
            return enabled && !autoHidden;
        }

        public String channelName() {
            return name.toLowerCase().replaceAll("[^a-z0-9-]+", "-");
        }

        public String requirements() {
            if (requiresStricken) return "10/10 merits + perk + Stricken";
            if (requiresMerits) return "10/10 merits + perk";
            return "Any merc";
        }
    }

    private final JdbcClient jdbc;

    public TierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Tier> findActive() {
        return jdbc.sql("""
                        SELECT t.*, p.price_per_hit
                        FROM tier t
                        JOIN LATERAL (SELECT price_per_hit FROM tier_price_version v
                                      WHERE v.tier_id = t.id ORDER BY effective_from DESC, id DESC LIMIT 1) p ON TRUE
                        WHERE t.active ORDER BY t.rank""")
                .query((rs, n) -> new Tier(
                        rs.getInt("id"), rs.getString("name"), rs.getInt("rank"), rs.getInt("colour"),
                        rs.getBoolean("requires_merits"), rs.getBoolean("requires_perk"),
                        rs.getBoolean("requires_stricken"),
                        nullableLong(rs, "role_id"), nullableLong(rs, "channel_id"),
                        nullableLong(rs, "board_message_id"), rs.getBoolean("enabled"),
                        rs.getBoolean("auto_hidden"), rs.getLong("price_per_hit")))
                .list();
    }

    public java.util.Optional<Tier> find(int tierId) {
        return findActive().stream().filter(t -> t.id() == tierId).findFirst();
    }

    /** True if any tier, active or retired, already uses this name (names are unique). */
    public boolean nameTaken(String name, int exceptTierId) {
        return jdbc.sql("SELECT count(*) FROM tier WHERE lower(name) = lower(?) AND id <> ?")
                .params(name, exceptTierId).query(Integer.class).single() > 0;
    }

    /** Creates a tier with its first price version and returns its id. */
    public int create(String name, int rank, int colour, boolean merits, boolean perk, boolean stricken,
                      long price, long createdBy) {
        int id = jdbc.sql("""
                        INSERT INTO tier (name, rank, colour, requires_merits, requires_perk, requires_stricken)
                        VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(name, rank, colour, merits, perk, stricken).query(Integer.class).single();
        addPrice(id, price, createdBy);
        return id;
    }

    public void updateDetails(int tierId, String name, int rank, int colour) {
        jdbc.sql("UPDATE tier SET name = ?, rank = ?, colour = ? WHERE id = ?")
                .params(name, rank, colour, tierId).update();
    }

    public void setRequirements(int tierId, boolean merits, boolean perk, boolean stricken) {
        jdbc.sql("UPDATE tier SET requires_merits = ?, requires_perk = ?, requires_stricken = ? WHERE id = ?")
                .params(merits, perk, stricken, tierId).update();
    }

    public void setEnabled(int tierId, boolean enabled) {
        jdbc.sql("UPDATE tier SET enabled = ? WHERE id = ?").params(enabled, tierId).update();
    }

    public void setAutoHidden(int tierId, boolean hidden) {
        jdbc.sql("UPDATE tier SET auto_hidden = ? WHERE id = ?").params(hidden, tierId).update();
    }

    /** Retires a tier: it leaves every list, and its Discord ids are cleared. History keeps it. */
    public void retire(int tierId) {
        jdbc.sql("UPDATE tier SET active = FALSE, role_id = NULL, channel_id = NULL, board_message_id = NULL "
                + "WHERE id = ?").param(tierId).update();
    }

    /** Every price change is a new version (scope doc 6.1); contracts keep the version they were quoted. */
    public void addPrice(int tierId, long price, long createdBy) {
        jdbc.sql("INSERT INTO tier_price_version (tier_id, price_per_hit, created_by) VALUES (?, ?, ?)")
                .params(tierId, price, createdBy).update();
    }

    /** The id of the tier's current price version (stored on contracts so later price changes don't affect them). */
    public int currentPriceVersionId(int tierId) {
        return jdbc.sql("""
                        SELECT id FROM tier_price_version WHERE tier_id = ?
                        ORDER BY effective_from DESC, id DESC LIMIT 1""")
                .param(tierId).query(Integer.class).single();
    }

    /** A past price, newest first. */
    public record PriceVersion(long price, java.time.Instant from, Long createdBy) { }

    public List<PriceVersion> priceHistory(int tierId, int limit) {
        return jdbc.sql("""
                        SELECT price_per_hit, effective_from, created_by FROM tier_price_version
                        WHERE tier_id = ? ORDER BY effective_from DESC, id DESC LIMIT ?""")
                .params(tierId, limit)
                .query((rs, n) -> new PriceVersion(rs.getLong("price_per_hit"),
                        rs.getObject("effective_from", java.time.OffsetDateTime.class).toInstant(),
                        nullableLong(rs, "created_by")))
                .list();
    }

    public void setDiscordIds(int tierId, Long roleId, Long channelId, Long boardMessageId) {
        jdbc.sql("UPDATE tier SET role_id = ?, channel_id = ?, board_message_id = ? WHERE id = ?")
                .params(roleId, channelId, boardMessageId, tierId).update();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
