package com.familyx.mercbot.inbox;

import com.familyx.mercbot.request.ContractRepository.Contract;

import java.time.Duration;
import java.time.Instant;

/** When a requester can message leaders about a contract (scope doc 4.5): plain logic, unit tested. */
public final class Messaging {

    /** How long after a contract ends its requester can still write. */
    public static final Duration AFTER_END = Duration.ofDays(30);
    /** At most one message a minute per requester. */
    public static final Duration MIN_GAP = Duration.ofMinutes(1);
    public static final int MAX_LENGTH = 1000;

    private Messaging() { }

    /** While it's live or waiting, and for 30 days after it ends. */
    public static boolean canMessage(Contract c, Instant now) {
        return canMessage(c.status(), c.endedAt() != null ? c.endedAt() : c.createdAt(), now);
    }

    public static boolean canMessage(String status, Instant endedAt, Instant now) {
        return switch (status) {
            case "PENDING_APPROVAL", "ACTIVE", "PAUSED" -> true;
            case "COMPLETED", "ENDED", "CANCELLED", "REJECTED" -> endedAt != null && endedAt.plus(AFTER_END).isAfter(now);
            default -> false;
        };
    }

    /** "1st", "2nd", "3rd", "11th" … */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}
