package com.familyx.mercbot.settings;

import org.springframework.stereotype.Component;

/**
 * Typed access to leader settings, with the defaults from scope doc section 9.
 * Later phases add their own getters here as they need them.
 */
@Component
public class BotSettings {

    public static final String MIN_LEADER_KEYS = "keys.minLeaderPerFaction";
    public static final String EXCLUSIVE_TIERS = "tiers.exclusive";
    public static final String AUTO_DISABLE_EMPTY_TIERS = "tiers.autoDisableEmpty";

    private final SettingsRepository repo;

    public BotSettings(SettingsRepository repo) {
        this.repo = repo;
    }

    public int minLeaderKeysPerFaction() {
        return repo.get(MIN_LEADER_KEYS, Integer.class).orElse(2);
    }

    /** Each merc holds only their highest eligible tier (scope doc 5.2). */
    public boolean exclusiveTiers() {
        return repo.get(EXCLUSIVE_TIERS, Boolean.class).orElse(true);
    }

    /** A tier nobody holds is hidden from the request panel (scope doc 5.2). */
    public boolean autoDisableEmptyTiers() {
        return repo.get(AUTO_DISABLE_EMPTY_TIERS, Boolean.class).orElse(true);
    }

    public void set(String key, Object value, long changedBy) {
        repo.put(key, value, changedBy);
    }
}
