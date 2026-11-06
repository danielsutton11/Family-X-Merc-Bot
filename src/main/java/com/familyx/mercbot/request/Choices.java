package com.familyx.mercbot.request;

/** The choices made in step 1 and on the conditions screen (scope doc 4.2, 7.2). */
public final class Choices {

    private Choices() {
    }

    /**
     * "Request for" and "Target" in one menu: Discord allows only five rows of menus and buttons
     * per message, so the scope's two menus are merged.
     */
    public enum ForTarget {
        PERSONAL_PLAYERS("Personal → players", false, Kind.PLAYERS),
        PERSONAL_FACTION("Personal → a faction", false, Kind.FACTION),
        MY_FACTION_PLAYERS("My faction → players", true, Kind.PLAYERS),
        MY_FACTION_OTHER("My faction → another faction", true, Kind.FACTION),
        MY_FACTION_MINE("My faction → my faction", true, Kind.OWN_FACTION);

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
