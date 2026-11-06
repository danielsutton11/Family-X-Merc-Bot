package com.familyx.mercbot.request;

/** The choices made in step 1 and on the conditions screen (scope doc 4.2, 7.2). */
public final class Choices {

    private Choices() {
    }

    /**
     * Request for (Personal or Faction request, chosen on the first screen) and the target.
     * The target menu in step 1 lists only the options for the chosen request type.
     */
    public enum ForTarget {
        // Faction request targets, in menu order
        MY_FACTION_MINE("My Faction (Defensive)", true, Kind.OWN_FACTION),
        MY_FACTION_OTHER("Another Faction (Offensive)", true, Kind.FACTION),
        MY_FACTION_PLAYERS("Player(s)", true, Kind.PLAYERS),
        // Personal request targets, in menu order
        PERSONAL_PLAYERS("Player(s)", false, Kind.PLAYERS),
        PERSONAL_FACTION("A faction", false, Kind.FACTION);

        public final String label;
        public final boolean myFaction;
        public final Kind kind;

        ForTarget(String label, boolean myFaction, Kind kind) {
            this.label = label;
            this.myFaction = myFaction;
            this.kind = kind;
        }
    }

    /** What the targets are. */
    public enum Kind { PLAYERS, FACTION, OWN_FACTION }

    public enum Attack {
        HOSPITALIZE("Hospitalize"), MUG("Mug");

        public final String label;

        Attack(String label) {
            this.label = label;
        }
    }

    public enum Mode {
        HITS("Hit count"), DURATION("Duration");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    public enum Status { ANY, ONLINE, AWAY }

    public enum Posture { DEFENSIVE, OFFENSIVE }
}
