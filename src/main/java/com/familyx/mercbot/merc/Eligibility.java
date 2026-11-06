package com.familyx.mercbot.merc;

import com.familyx.mercbot.tier.TierRepository.Tier;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which tier roles a merc should hold (scope doc 5.2). Pure logic, no Discord or database,
 * so it can be unit tested.
 */
public final class Eligibility {

    private Eligibility() {
    }

    /** What we know about a merc when deciding tiers. */
    public record Facts(boolean meritsOk, boolean factionPerkOk, boolean strickenOptIn, Set<Integer> optedOut) { }

    /** Does the merc meet this tier's requirements (ignoring opt-outs)? */
    public static boolean meets(Tier tier, Facts f) {
        if (tier.requiresMerits() && !f.meritsOk()) return false;
        if (tier.requiresPerk() && !f.factionPerkOk()) return false;
        if (tier.requiresStricken() && !(f.strickenOptIn() && f.meritsOk())) return false;
        return true;
    }

    /**
     * The tiers to hold: those the merc qualifies for and hasn't opted out of. With exclusive
     * tiers on, only the highest-ranked of those.
     */
    public static Set<Integer> tiersToHold(List<Tier> tiers, Facts f, boolean exclusive) {
        List<Tier> wanted = tiers.stream()
                .filter(t -> !f.optedOut().contains(t.id()))
                .filter(t -> meets(t, f))
                .toList();
        if (exclusive) {
            return wanted.stream().max(Comparator.comparingInt(Tier::rank))
                    .map(t -> Set.of(t.id())).orElse(Set.of());
        }
        return wanted.stream().map(Tier::id).collect(Collectors.toSet());
    }

    /** The highest tier this merc would qualify for if they turned Stricken on (for the profile hint). */
    public static boolean strickenWouldUnlock(List<Tier> tiers, Facts f) {
        Facts with = new Facts(f.meritsOk(), f.factionPerkOk(), true, f.optedOut());
        int now = tiersToHold(tiers, f, true).stream().mapToInt(id -> rank(tiers, id)).max().orElse(0);
        int then = tiersToHold(tiers, with, true).stream().mapToInt(id -> rank(tiers, id)).max().orElse(0);
        return then > now;
    }

    private static int rank(List<Tier> tiers, int id) {
        return tiers.stream().filter(t -> t.id() == id).mapToInt(Tier::rank).findFirst().orElse(0);
    }
}
