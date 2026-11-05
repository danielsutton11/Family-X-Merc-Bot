package com.familyx.mercbot.faction;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class FamilyFactionRepository {

    public record FamilyFaction(long tornFactionId, String name, Long leaderRoleId, boolean hospPerkOk,
                                boolean active) { }

    private final JdbcClient jdbc;

    public FamilyFactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FamilyFaction> findActive() {
        return jdbc.sql("""
                        SELECT torn_faction_id, name, leader_role_id, hosp_perk_ok, active
                        FROM family_faction WHERE active ORDER BY name""")
                .query(this::map).list();
    }

    public Optional<FamilyFaction> find(long factionId) {
        return jdbc.sql("""
                        SELECT torn_faction_id, name, leader_role_id, hosp_perk_ok, active
                        FROM family_faction WHERE torn_faction_id = ?""")
                .param(factionId).query(this::map).optional();
    }

    public boolean isFamily(long factionId) {
        return find(factionId).map(FamilyFaction::active).orElse(false);
    }

    /** Adds a faction, or reactivates and renames it if it was removed before. */
    public void upsert(long factionId, String name) {
        jdbc.sql("""
                        INSERT INTO family_faction (torn_faction_id, name) VALUES (?, ?)
                        ON CONFLICT (torn_faction_id) DO UPDATE SET name = EXCLUDED.name, active = TRUE""")
                .params(factionId, name).update();
    }

    public void setLeaderRole(long factionId, Long roleId) {
        jdbc.sql("UPDATE family_faction SET leader_role_id = ? WHERE torn_faction_id = ?")
                .params(roleId, factionId).update();
    }

    public void deactivate(long factionId) {
        jdbc.sql("UPDATE family_faction SET active = FALSE WHERE torn_faction_id = ?").param(factionId).update();
    }

    private FamilyFaction map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        long roleValue = rs.getLong("leader_role_id");
        Long role = rs.wasNull() ? null : roleValue;
        return new FamilyFaction(rs.getLong("torn_faction_id"), rs.getString("name"),
                role, rs.getBoolean("hosp_perk_ok"), rs.getBoolean("active"));
    }
}
