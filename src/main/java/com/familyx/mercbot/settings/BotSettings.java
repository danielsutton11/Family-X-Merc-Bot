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

    // ---- requests (scope doc section 9, Requests group)

    public boolean autoAcceptFamily() {
        return repo.get("requests.autoAcceptFamily", Boolean.class).orElse(true);
    }

    public int autoAcceptScore() {
        return repo.get("requests.autoAcceptScore", Integer.class).orElse(70);
    }

    /** Capped at 15 minutes, Discord's limit for private interactive messages. */
    public int quoteMinutes() {
        return Math.min(15, repo.get("requests.quoteMinutes", Integer.class).orElse(10));
    }

    // ---- money (scope doc 7.5)

    public java.math.BigDecimal factionCutPct() {
        return repo.get("money.factionCutPct", java.math.BigDecimal.class).orElse(java.math.BigDecimal.ONE);
    }

    public java.math.BigDecimal starterPct() {
        return repo.get("money.starterPct", java.math.BigDecimal.class).orElse(java.math.BigDecimal.valueOf(75));
    }

    public java.math.BigDecimal claimerNoShowPct() {
        return repo.get("money.claimerNoShowPct", java.math.BigDecimal.class).orElse(java.math.BigDecimal.valueOf(100));
    }

    // ---- claims (scope doc 5.4, 5.6, 7.7)

    public static final String CLAIMS_ON = "claims.enabled";
    public static final String CLAIM_SHOW_EARLY_MIN = "claims.showEarlyMinutes";
    public static final String CLAIM_LOCK_S = "claims.lockSeconds";
    public static final String CLAIM_MAX = "claims.maxPerMerc";
    public static final String CLAIM_COOLDOWN_COUNT = "claims.cooldownNoShows";
    public static final String CLAIM_COOLDOWN_MIN = "claims.cooldownMinutes";
    public static final String CLAIM_ALERT_COUNT = "claims.alertNoShows";
    public static final String CLAIM_ALERT_HOURS = "claims.alertHours";

    public boolean claimsOn() {
        return repo.get(CLAIMS_ON, Boolean.class).orElse(true);
    }

    public int claimShowEarlyMinutes() {
        return repo.get(CLAIM_SHOW_EARLY_MIN, Integer.class).orElse(2);
    }

    public int claimLockSeconds() {
        return repo.get(CLAIM_LOCK_S, Integer.class).orElse(30);
    }

    public int maxClaims() {
        return repo.get(CLAIM_MAX, Integer.class).orElse(2);
    }

    public int claimCooldownNoShows() {
        return repo.get(CLAIM_COOLDOWN_COUNT, Integer.class).orElse(2);
    }

    public int claimCooldownMinutes() {
        return repo.get(CLAIM_COOLDOWN_MIN, Integer.class).orElse(10);
    }

    public int claimAlertNoShows() {
        return repo.get(CLAIM_ALERT_COUNT, Integer.class).orElse(3);
    }

    public int claimAlertHours() {
        return repo.get(CLAIM_ALERT_HOURS, Integer.class).orElse(24);
    }

    public void set(String key, Object value, long changedBy) {
        repo.put(key, value, changedBy);
    }
}
