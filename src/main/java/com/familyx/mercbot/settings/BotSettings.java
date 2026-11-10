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

    // ---- payments (scope doc 4.6, 6.6; Settings → Payments)

    /** A Capacity X leader requesters pay in Torn. */
    public record Payee(long tornId, String name, long discordId) { }

    public static final String PAYEES = "payments.payees";
    public static final String PAYEE_NEXT = "payments.nextPayee";
    public static final String POOL_ACCOUNT = "payments.poolAccountId";
    public static final String POOL_NAME = "payments.poolAccountName";
    public static final String DUE_HOURS = "payments.dueHours";
    public static final String SETTLEMENT = "payments.settlementPeriod";
    public static final String PAY_FACTION = "payments.factionId";

    public java.util.List<Payee> payees() {
        try {
            return repo.get(PAYEES, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Payee>>() { })
                    .orElse(java.util.List.of());
        } catch (RuntimeException e) {
            return java.util.List.of();
        }
    }

    /** The next payee in turn (rotates so one leader isn't always holding the money); empty when none are set. */
    public synchronized java.util.Optional<Payee> nextPayee() {
        java.util.List<Payee> list = payees();
        if (list.isEmpty()) {
            return java.util.Optional.empty();
        }
        int i = Math.floorMod(repo.get(PAYEE_NEXT, Integer.class).orElse(0), list.size());
        repo.put(PAYEE_NEXT, (i + 1) % list.size(), null);
        return java.util.Optional.of(list.get(i));
    }

    /** The faction balance account requester payments are added to (scope doc 6.6). */
    public long poolAccountId() {
        return repo.get(POOL_ACCOUNT, Long.class).orElse(2311136L);
    }

    public String poolAccountName() {
        return repo.get(POOL_NAME, String.class).orElse("mercs");
    }

    public int paymentDueHours() {
        return repo.get(DUE_HOURS, Integer.class).orElse(48);
    }

    /** PER_CONTRACT, WEEKLY or MONTHLY. */
    public String settlementPeriod() {
        return repo.get(SETTLEMENT, String.class).orElse("WEEKLY");
    }

    /** Capacity X: whose bank and news hold the payments, and whose leaders confirm them. */
    public long paymentFactionId() {
        return repo.get(PAY_FACTION, Long.class).orElse(9357L);
    }

    // ---- request defaults (scope doc 4.3; Settings → Requests)

    public static String requestDefaultsKey(com.familyx.mercbot.request.Scenarios.Key k) {
        return "requests.defaults." + k.name();
    }

    /** A scenario's defaults as leaders set them, or the scope doc's when never changed. */
    public com.familyx.mercbot.request.Scenarios.Entry requestDefaults(com.familyx.mercbot.request.Scenarios.Key k) {
        try {
            return repo.get(requestDefaultsKey(k), com.familyx.mercbot.request.Scenarios.Entry.class)
                    .orElseGet(() -> com.familyx.mercbot.request.Scenarios.scope(k));
        } catch (RuntimeException e) {
            return com.familyx.mercbot.request.Scenarios.scope(k); // unreadable: fall back rather than break requests
        }
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
        return repo.get(CLAIM_LOCK_S, Integer.class).orElse(60);
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

    // ---- alerts (scope doc 7.8, 5.1; Settings → Alerts)

    public static final String ALERT_OFFENSIVE_COUNT = "alerts.offensiveCount";
    public static final String ALERT_OFFENSIVE_MIN = "alerts.offensiveMinutes";
    public static final String ALERT_PING_COUNT = "alerts.urgentPings";
    public static final String ALERT_PING_GAP_MIN = "alerts.urgentPingGapMinutes";
    public static final String ALERT_DEFENSIVE_COUNT = "alerts.defensiveCount";
    public static final String ALERT_DEFENSIVE_MIN = "alerts.defensiveMinutes";
    public static final String ALERT_AVAIL_HOURS = "alerts.availabilityOffHours";

    public int offensiveCount() {
        return repo.get(ALERT_OFFENSIVE_COUNT, Integer.class).orElse(3);
    }

    public int offensiveMinutes() {
        return repo.get(ALERT_OFFENSIVE_MIN, Integer.class).orElse(30);
    }

    public int urgentPings() {
        return repo.get(ALERT_PING_COUNT, Integer.class).orElse(3);
    }

    public int urgentPingGapMinutes() {
        return repo.get(ALERT_PING_GAP_MIN, Integer.class).orElse(10);
    }

    public int defensiveCount() {
        return repo.get(ALERT_DEFENSIVE_COUNT, Integer.class).orElse(3);
    }

    public int defensiveMinutes() {
        return repo.get(ALERT_DEFENSIVE_MIN, Integer.class).orElse(30);
    }

    /** Minimum gap between "your availability is off" DMs. */
    public int availabilityOffHours() {
        return repo.get(ALERT_AVAIL_HOURS, Integer.class).orElse(6);
    }

    // ---- reputation (scope doc 6.5; Settings → Reputation)

    public static final String REP_START = "reputation.start";
    public static final String REP_ON_TIME = "reputation.paidOnTime";
    public static final String REP_COMPLETED = "reputation.completed";
    public static final String REP_LATE = "reputation.late";
    public static final String REP_PARTIAL = "reputation.partialAtDue";
    public static final String REP_UNPAID_WEEK = "reputation.unpaidWeek";

    public int repStart() {
        return repo.get(REP_START, Integer.class).orElse(50);
    }

    public int repOnTime() {
        return repo.get(REP_ON_TIME, Integer.class).orElse(5);
    }

    public int repCompleted() {
        return repo.get(REP_COMPLETED, Integer.class).orElse(2);
    }

    /** Positive number; taken off. */
    public int repLate() {
        return repo.get(REP_LATE, Integer.class).orElse(10);
    }

    public int repPartial() {
        return repo.get(REP_PARTIAL, Integer.class).orElse(15);
    }

    public int repUnpaidWeek() {
        return repo.get(REP_UNPAID_WEEK, Integer.class).orElse(25);
    }

    public void set(String key, Object value, long changedBy) {
        repo.put(key, value, changedBy);
    }
}
