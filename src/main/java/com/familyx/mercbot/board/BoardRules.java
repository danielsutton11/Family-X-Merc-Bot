package com.familyx.mercbot.board;

import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.request.Choices.Status;

import java.time.Duration;
import java.time.Instant;

/**
 * Who shows on a board (scope doc 5.3, 7.2). Pure logic so it can be unit tested.
 */
public final class BoardRules {

    private BoardRules() {
    }

    /** Where a target stands right now. Only {@link #SHOW} goes on the board. */
    public enum Verdict {
        SHOW,
        /** In hospital or jail, or inside the 60 s ranked-war window: not hittable yet. */
        NOT_YET,
        /** Hidden by the contract's conditions. */
        HIDDEN_REVIVABLE, HIDDEN_ONLINE, HIDDEN_OFFLINE, HIDDEN_EARLY_DISCHARGE, HIDDEN_TRAVELLING,
        /** Not part of the board at all. */
        PAUSED, DONE, UNKNOWN, GONE;

        public boolean hiddenByConditions() {
            return name().startsWith("HIDDEN_");
        }
    }

    /**
     * The lower board a target also shows on: a revivable target on a Stricken tier's contract
     * that allows revivable targets is billed at the highest tier below it that doesn't need
     * Stricken, so that tier's mercs see it too. Not Merc me (billed at Gold) or overrides
     * (Stricken required). Empty for everything else.
     */
    public static java.util.Optional<com.familyx.mercbot.tier.TierRepository.Tier> alsoOn(BoardTarget t,
            java.util.List<com.familyx.mercbot.tier.TierRepository.Tier> active, boolean mercMe) {
        var own = active.stream().filter(x -> x.id() == t.tierId()).findFirst().orElse(null);
        if (own == null || !own.requiresStricken() || mercMe || t.hasOverride() || !Boolean.TRUE.equals(t.revivable())
                || !t.conditions().allowRevivable()) {
            return java.util.Optional.empty();
        }
        return active.stream().filter(x -> x.rank() < own.rank() && !x.requiresStricken())
                .max(java.util.Comparator.comparingInt(com.familyx.mercbot.tier.TierRepository.Tier::rank));
    }

    public static Verdict verdict(BoardTarget t, Instant now) {
        if ("PAUSED".equals(t.contractStatus())) {
            return Verdict.PAUSED;
        }
        if (!t.hitsLeft()) {
            return Verdict.DONE;
        }
        if (t.lastState() == null) {
            return Verdict.UNKNOWN;
        }
        switch (t.lastState()) {
            case "Hospital", "Jail" -> {
                return Verdict.NOT_YET;
            }
            case "Traveling", "Abroad" -> {
                return Verdict.HIDDEN_TRAVELLING;
            }
            case "Federal", "Fallen" -> {
                return Verdict.GONE;
            }
            default -> { }
        }
        if (t.availableAt() != null && t.availableAt().isAfter(now)) {
            return Verdict.NOT_YET;
        }
        return conditions(t, now);
    }

    /** The contract's conditions alone, ignoring hospital, jail and the war lock. */
    static Verdict conditions(BoardTarget t, Instant now) {
        if (t.hasOverride()) {
            return Verdict.SHOW; // the requester allowed one hit despite the conditions
        }
        if (!t.conditions().allowRevivable() && Boolean.TRUE.equals(t.revivable())) {
            return Verdict.HIDDEN_REVIVABLE;
        }
        if (!t.conditions().allowEarlyDischarge() && Boolean.TRUE.equals(t.earlyDischarge())) {
            return Verdict.HIDDEN_EARLY_DISCHARGE;
        }
        Status s = t.conditions().status();
        if (s == Status.ONLINE && !"Online".equalsIgnoreCase(t.lastActionStatus())) {
            return Verdict.HIDDEN_OFFLINE;
        }
        if (s == Status.AWAY) {
            if ("Online".equalsIgnoreCase(t.lastActionStatus())) {
                return Verdict.HIDDEN_ONLINE;
            }
            if (t.lastActionAt() != null
                    && Duration.between(t.lastActionAt(), now).toMinutes() < t.conditions().awayMinutes()) {
                return Verdict.HIDDEN_ONLINE;
            }
        }
        return Verdict.SHOW;
    }

    /** War lock after leaving hospital during a ranked war (scope doc 7.2). */
    private static volatile Duration warLock = Duration.ofSeconds(60);

    /** Settings → Boards → Ranked-war outsider window; kept here so board drawing never waits on the database. */
    public static void warLock(Duration d) {
        warLock = d;
    }

    public static Duration warLock() {
        return warLock;
    }

    /**
     * When the target can next be hit: out of hospital or jail (plus the war lock when their
     * faction is in a ranked war), or now if they already can be.
     */
    public static Instant hittableAt(BoardTarget t, Instant now) {
        if ("Hospital".equals(t.lastState()) || "Jail".equals(t.lastState())) {
            Instant out = t.statusUntil() == null ? now : t.statusUntil();
            return "Hospital".equals(t.lastState()) && t.inRankedWar() ? out.plus(warLock) : out;
        }
        if (t.availableAt() != null && t.availableAt().isAfter(now)) {
            return t.availableAt();
        }
        return now;
    }

    /**
     * Claims' "show early" (scope doc 5.4): not hittable yet, but within {@code window} of being
     * hittable and otherwise meeting the contract's conditions.
     */
    public static boolean showEarly(BoardTarget t, Instant now, Duration window) {
        return verdict(t, now) == Verdict.NOT_YET && conditions(t, now) == Verdict.SHOW
                && !hittableAt(t, now).isAfter(now.plus(window));
    }

    /** "Online now", or "Away 42m" / "Away 3h 5m" since the last action. */
    public static String activity(BoardTarget t, Instant now) {
        if ("Online".equalsIgnoreCase(t.lastActionStatus())) {
            return "Online now";
        }
        if (t.lastActionAt() == null) {
            return "Away";
        }
        return "Away " + duration(Duration.between(t.lastActionAt(), now));
    }

    static String duration(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return (hours / 24) + "d";
    }
}
