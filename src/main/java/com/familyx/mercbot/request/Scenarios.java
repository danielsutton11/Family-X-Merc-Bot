package com.familyx.mercbot.request;

import com.familyx.mercbot.request.Choices.Attack;
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
        Scenario sc = pick(s, now);
        // "Hidden (rule)": Mug skips early-discharge targets, Hospitalize includes them.
        boolean earlyByRule = s.attack() == Attack.HOSPITALIZE;
        Conditions c;
        Instant start = now;
        Instant end = null;
        String startNote = "now";
        String endNote = "no end";
        switch (sc) {
            case A, D -> {
                c = new Conditions(Status.AWAY, Conditions.DEFAULT_AWAY_MINUTES, false, earlyByRule);
                if (sc == Scenario.A && s.myWar() != null && s.forTarget().kind == Kind.OWN_FACTION
                        && s.myWar().start().isAfter(now)) {
                    start = s.myWar().start();
                    startNote = "your war's start";
                }
                end = start.plus(Duration.ofHours(24));
                endNote = "24h after start";
            }
            case B -> {
                c = new Conditions(Status.ONLINE, Conditions.DEFAULT_AWAY_MINUTES, true, earlyByRule);
                end = s.myWar().start();
                endNote = "your war's start";
            }
            case C -> c = new Conditions(Status.ANY, Conditions.DEFAULT_AWAY_MINUTES, true, false);
            case E -> c = new Conditions(Status.ANY, Conditions.DEFAULT_AWAY_MINUTES, true, false);
            case F -> c = new Conditions(Status.ANY, Conditions.DEFAULT_AWAY_MINUTES, true, true);
            case G -> {
                if (s.posture() == Posture.OFFENSIVE) {
                    c = new Conditions(Status.ONLINE, Conditions.DEFAULT_AWAY_MINUTES, true, earlyByRule);
                    if (s.targetWar().start().isAfter(now)) {
                        end = s.targetWar().start();
                        endNote = "their war's start";
                    }
                } else {
                    c = new Conditions(Status.AWAY, Conditions.DEFAULT_AWAY_MINUTES, false, earlyByRule);
                    if (s.targetWar().start().isAfter(now)) {
                        start = s.targetWar().start();
                        startNote = "their war's start";
                    }
                    end = start.plus(Duration.ofHours(24));
                    endNote = "24h after start";
                }
            }
            default -> c = null; // active war against the enemy: the requester picks every option
        }
        if (c != null && s.attack() == Attack.MUG) {
            c = c.withEarlyDischarge(false);
        }
        return new Defaults(sc, c, start, end, 1, startNote, endNote);
    }
}
