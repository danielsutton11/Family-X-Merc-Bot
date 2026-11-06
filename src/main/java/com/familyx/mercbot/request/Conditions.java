package com.familyx.mercbot.request;

import com.familyx.mercbot.request.Choices.Status;

/**
 * Who shows on the board and which hits count (scope doc 7.2). {@code awayMinutes} is only used
 * with {@link Status#AWAY}.
 */
public record Conditions(Status status, int awayMinutes, boolean allowRevivable, boolean allowEarlyDischarge) {

    public static final int DEFAULT_AWAY_MINUTES = 30;

    public Conditions withStatus(Status s, int minutes) {
        return new Conditions(s, minutes, allowRevivable, allowEarlyDischarge);
    }

    public Conditions withRevivable(boolean allow) {
        return new Conditions(status, awayMinutes, allow, allowEarlyDischarge);
    }

    public Conditions withEarlyDischarge(boolean allow) {
        return new Conditions(status, awayMinutes, allowRevivable, allow);
    }

    public String statusLabel() {
        return switch (status) {
            case ANY -> "Any";
            case ONLINE -> "Online";
            case AWAY -> "Idle/Offline " + awayMinutes + "+ min";
        };
    }
}
