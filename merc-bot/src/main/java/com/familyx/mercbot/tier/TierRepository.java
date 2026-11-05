package com.familyx.mercbot.tier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TierRepository {

    /** A tier with its current price. */
    public record Tier(int id, String name, int rank, int colour, boolean requiresMerits, boolean requiresPerk,
                       boolean requiresStricken, Long roleId, Long channelId, Long boardMessageId,
                       boolean enabled, long pricePerHit) {

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
                        rs.getLong("price_per_hit")))
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
