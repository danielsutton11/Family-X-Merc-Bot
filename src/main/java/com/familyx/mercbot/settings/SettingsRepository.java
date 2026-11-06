package com.familyx.mercbot.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Key/value store for leader settings and internal state (channel layout, poll cursors).
 * Every write bumps the version so changes can be audited (scope doc section 9).
 */
@Repository
public class SettingsRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SettingsRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        return jdbc.sql("SELECT value::text FROM settings WHERE key = ?").param(key)
                .query(String.class).optional()
                .map(json -> {
                    try {
                        return mapper.readValue(json, type);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Bad JSON in setting " + key, e);
                    }
                });
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        return jdbc.sql("SELECT value::text FROM settings WHERE key = ?").param(key)
                .query(String.class).optional()
                .map(json -> {
                    try {
                        return mapper.readValue(json, type);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Bad JSON in setting " + key, e);
                    }
                });
    }

    public void put(String key, Object value, Long changedBy) {
        String json;
        try {
            json = mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Can't serialise setting " + key, e);
        }
        jdbc.sql("""
                        INSERT INTO settings (key, value, changed_by) VALUES (?, ?::jsonb, ?)
                        ON CONFLICT (key) DO UPDATE
                        SET value = EXCLUDED.value, version = settings.version + 1,
                            changed_by = EXCLUDED.changed_by, changed_at = now()""")
                .params(key, json, changedBy).update();
    }
}
