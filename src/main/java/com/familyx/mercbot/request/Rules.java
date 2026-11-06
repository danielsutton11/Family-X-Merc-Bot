package com.familyx.mercbot.request;

import com.familyx.mercbot.request.Choices.ForTarget;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Who can request what (scope doc 7.1). Returns the reason for a denial, or empty if allowed. */
public final class Rules {

    private Rules() {
    }

    /**
     * @param targetFactions the faction of every target (a faction target, or each player's faction;
     *                       players without a faction are left out), with names for the message
     */
    public static Optional<String> check(ForTarget forTarget, boolean mercMe, Long requesterFaction,
                                         Set<Long> family, Set<Long> protectedFactions,
                                         Map<Long, String> targetFactions) {
        if (mercMe) {
            return Optional.empty();
        }
        if (forTarget.myFaction && requesterFaction == null) {
            return Optional.of("You're not in a faction, so you can't make a My faction request.");
        }
        boolean requesterIsFamily = requesterFaction != null && family.contains(requesterFaction);
        for (Map.Entry<Long, String> t : targetFactions.entrySet()) {
            String name = t.getValue() == null ? "Faction " + t.getKey() : t.getValue();
            if (protectedFactions.contains(t.getKey())) {
                return Optional.of(name + " is a protected faction.");
            }
            if (family.contains(t.getKey())) {
                if (!requesterIsFamily) {
                    return Optional.of(name + " is part of Family X, and Family X can't be targeted from outside.");
                }
                if (!forTarget.myFaction) {
                    return Optional.of(name + " is a Family X faction. Family members can only be targeted with a "
                            + "My faction request.");
                }
            }
        }
        return Optional.empty();
    }

    /** Convenience for tests. */
    static Optional<String> check(ForTarget forTarget, Long requesterFaction, Collection<Long> family,
                                  Collection<Long> protectedFactions, Map<Long, String> targets) {
        return check(forTarget, false, requesterFaction, Set.copyOf(family), Set.copyOf(protectedFactions), targets);
    }
}
