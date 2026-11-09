package com.familyx.mercbot.request;

import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.Choices.ForTarget;
import com.familyx.mercbot.request.Choices.Kind;
import com.familyx.mercbot.request.Choices.Posture;
import com.familyx.mercbot.request.Choices.Status;

import java.time.Duration;
import java.time.Instant;

/**
 * Picks the situation (scope doc 4.3, scenarios A–G) and its default conditions. Pure logic so
 * it can be unit tested; the tier, attack type and mode always come from the requester's own
 * step-1 choices.
 */
public final class Scenarios {

    private Scenarios() {
    }

    /** A ranked war: scheduled (start in the future) or active. */
    public record War(long opponentId, String opponentName, Instant start, Instant end) {
        public boolean active(Instant now) {
            return !start.isAfter(now) && (end == null || end.isAfter(now));
        }
    }

    public enum Scenario {
        A("Your faction is at war"),
        B("Target is your faction's war enemy (war scheduled)"),
        C("Personal request against players"),
        D("Your faction has no war"),
        E("Another faction, no war"),
        F("Merc me"),
        G("Target faction is at war"),
        WAR_ACTIVE("Target is your faction's enemy and the war is active");

        public final String description;

        Scenario(String description) {
            this.description = description;
        }
    }

    /** Defaults for a quote. {@code conditions} is null when the requester must pick everything. */
    public record Defaults(Scenario scenario, Conditions conditions, Instant start, Instant end, int hits,
                           String startNote, String endNote) { }

    /** What the scenario depends on. */
    public record Situation(ForTarget forTarget, boolean mercMe, Long targetFactionId, War myWar, War targetWar,
                            Attack attack, Posture posture) { }

    /** The editable sets of defaults (Settings → Requests). D uses A's; G has one per posture. */
    public enum Key {
        A("A · Your faction is at war",
                "My Faction (Defensive), or Faction request → Player(s), while your faction has a ranked war scheduled or active. D (no war) uses these too, starting now."),
        B("B · Your war enemy, war scheduled", "Another Faction (Offensive) against your war enemy before the war starts."),
        C("C · Personal request against players", "Personal request → Player(s)."),
        E("E · Another faction, no war", "A faction with no ranked war on either side."),
        F("F · Merc me", "The requester is the target. Gold and one hit are fixed."),
        G_DEF("G · Faction at war with someone else (Defensive)", "A faction in a ranked war that isn't yours, Defensive posture."),
        G_OFF("G · Faction at war with someone else (Offensive)", "A faction in a ranked war that isn't yours, Offensive posture.");

        public final String title;
        public final String about;

        Key(String title, String about) {
            this.title = title;
            this.about = about;
        }

        /** Whether the start/end length can be changed (the others end at a war start). */
        public boolean lengthEditable() {
            return this == A || this == E || this == G_DEF;
        }
    }

    /** How early-discharge targets are treated: by attack type (Hospitalize hits, Mug skips), or always. */
    public enum Early { RULE, HIT, SKIP }

    /**
     * One scenario's defaults. {@code tierId} null means the scope doc's choice ({@code gold}:
     * the Gold tier, otherwise Silver). {@code lengthHours} null means no end.
     */
    public record Entry(Integer tierId, boolean gold, Attack attack, Mode mode, Status status, int awayMinutes,
                        boolean revivable, Early early, Integer lengthHours, int hits) {

        public Entry withTier(Integer id) {
            return new Entry(id, gold, attack, mode, status, awayMinutes, revivable, early, lengthHours, hits);
        }

        public Entry withAttackMode(Attack a, Mode m) {
            return new Entry(tierId, gold, a, m, status, awayMinutes, revivable, early, lengthHours, hits);
        }

        public Entry withStatus(Status s, int minutes) {
            return new Entry(tierId, gold, attack, mode, s, minutes, revivable, early, lengthHours, hits);
        }

        public Entry withRevivableEarly(boolean r, Early e) {
            return new Entry(tierId, gold, attack, mode, status, awayMinutes, r, e, lengthHours, hits);
        }

        public Entry withLength(Integer hours) {
            return new Entry(tierId, gold, attack, mode, status, awayMinutes, revivable, early, hours, hits);
        }

        public Entry withHits(int n) {
            return new Entry(tierId, gold, attack, mode, status, awayMinutes, revivable, early, lengthHours, n);
        }
    }

    /** The scope doc's 4.3 table. */
    public static Entry scope(Key k) {
        int m = Conditions.DEFAULT_AWAY_MINUTES;
        return switch (k) {
            case A, G_DEF -> new Entry(null, true, Attack.HOSPITALIZE, Mode.DURATION, Status.AWAY, m, false, Early.RULE, 24, 1);
            case B, G_OFF -> new Entry(null, false, Attack.MUG, Mode.DURATION, Status.ONLINE, m, true, Early.RULE, null, 1);
            case C -> new Entry(null, true, Attack.HOSPITALIZE, Mode.HITS, Status.ANY, m, true, Early.SKIP, null, 1);
            case E -> new Entry(null, true, Attack.HOSPITALIZE, Mode.DURATION, Status.ANY, m, true, Early.SKIP, null, 1);
            case F -> new Entry(null, true, Attack.HOSPITALIZE, Mode.HITS, Status.ANY, m, true, Early.HIT, null, 1);
        };
    }

    /** Which set of defaults a scenario uses; null for the active war against the enemy. */
    public static Key keyFor(Scenario sc, Posture posture) {
        return switch (sc) {
            case A, D -> Key.A;
            case B -> Key.B;
            case C -> Key.C;
            case E -> Key.E;
            case F -> Key.F;
            case G -> posture == Posture.OFFENSIVE ? Key.G_OFF : Key.G_DEF;
            case WAR_ACTIVE -> null;
        };
    }

    /** Step 1 defaults: a specific tier (or the scope doc's Gold/Silver), the attack type and the mode. */
    public record Step1(Integer tierId, boolean gold, Attack attack, Mode mode) { }

    public static Step1 step1(Situation s, Instant now) {
        return step1(s, now, Scenarios::scope);
    }

    /** Step 1 defaults for the situation; null when there are none (war against the enemy already active). */
    public static Step1 step1(Situation s, Instant now, java.util.function.Function<Key, Entry> table) {
        Key k = keyFor(pick(s, now), s.posture());
        if (k == null) {
            return null;
        }
        Entry e = table.apply(k);
        return new Step1(e.tierId(), e.gold(), e.attack(), e.mode());
    }

    public static Scenario pick(Situation s, Instant now) {
        if (s.mercMe()) {
            return Scenario.F;
        }
        if (s.forTarget().kind == Kind.OWN_FACTION) {
            return s.myWar() != null ? Scenario.A : Scenario.D;
        }
        if (s.forTarget().kind == Kind.PLAYERS) {
            return s.forTarget().myFaction ? Scenario.A : Scenario.C;
        }
        if (s.myWar() != null && s.targetFactionId() != null && s.myWar().opponentId() == s.targetFactionId()) {
            return s.myWar().active(now) ? Scenario.WAR_ACTIVE : Scenario.B;
        }
        return s.targetWar() != null ? Scenario.G : Scenario.E;
    }

    public static Defaults defaults(Situation s, Instant now) {
        return defaults(s, now, Scenarios::scope);
    }

    public static Defaults defaults(Situation s, Instant now, java.util.function.Function<Key, Entry> table) {
        Scenario sc = pick(s, now);
        Key k = keyFor(sc, s.posture());
        if (k == null) {
            return new Defaults(sc, null, now, null, 1, "now", "no end"); // the requester picks every option
        }
        Entry e = table.apply(k);
        boolean early = switch (e.early()) {
            case RULE -> s.attack() == Attack.HOSPITALIZE; // "Hidden (rule)": Mug skips them, Hospitalize includes them
            case HIT -> true;
            case SKIP -> false;
        };
        Conditions c = new Conditions(e.status(), e.awayMinutes(), e.revivable(), early);
        if (s.attack() == Attack.MUG) {
            c = c.withEarlyDischarge(false);
        }
        Instant start = now;
        Instant end = null;
        String startNote = "now";
        String endNote = "no end";
        switch (k) {
            case A -> {
                if (sc == Scenario.A && s.myWar() != null && s.forTarget().kind == Kind.OWN_FACTION
                        && s.myWar().start().isAfter(now)) {
                    start = s.myWar().start();
                    startNote = "your war's start";
                }
            }
            case B -> {
                end = s.myWar().start();
                endNote = "your war's start";
            }
            case G_DEF -> {
                if (s.targetWar().start().isAfter(now)) {
                    start = s.targetWar().start();
                    startNote = "their war's start";
                }
            }
            case G_OFF -> {
                if (s.targetWar().start().isAfter(now)) {
                    end = s.targetWar().start();
                    endNote = "their war's start";
                }
            }
            default -> { }
        }
        if (k.lengthEditable() && e.lengthHours() != null) {
            end = start.plus(Duration.ofHours(e.lengthHours()));
            endNote = e.lengthHours() + "h after start";
        }
        return new Defaults(sc, c, start, end, Math.max(1, e.hits()), startNote, endNote);
    }
}
