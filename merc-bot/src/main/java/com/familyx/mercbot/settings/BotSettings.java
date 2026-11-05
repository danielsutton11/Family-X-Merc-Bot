package com.familyx.mercbot.settings;

import org.springframework.stereotype.Component;

/**
 * Typed access to leader settings, with the defaults from scope doc section 9.
 * Later phases add their own getters here as they need them.
 */
@Component
public class BotSettings {

    public static final String MIN_LEADER_KEYS = "keys.minLeaderPerFaction";

    private final SettingsRepository repo;

    public BotSettings(SettingsRepository repo) {
        this.repo = repo;
    }

    public int minLeaderKeysPerFaction() {
        return repo.get(MIN_LEADER_KEYS, Integer.class).orElse(2);
    }
}
