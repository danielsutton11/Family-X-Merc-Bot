package com.familyx.mercbot.hit;

import com.familyx.mercbot.board.BoardRules.Verdict;
import com.familyx.mercbot.tier.TierRepository.Tier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Billing tier and splits (scope doc 7.3–7.5). Pure, so it can be unit tested. */
public final class HitRules {

    private HitRules() {
    }

    /**
     * The highest tier the hit qualifies for, never above the contract's. {@code capBelowStricken}
     * is a revivable target on a Gold contract: billed at a tier that doesn't need Stricken at most.
     */
    public static Tier billedTier(List<Tier> tiers, Tier contractTier, boolean stricken, boolean merits, boolean perk,
                                  boolean capBelowStricken) {
        List<Tier> eligible = tiers.stream().filter(t -> t.rank() <= contractTier.rank())
                .sorted(Comparator.comparingInt(Tier::rank).reversed()).toList();
        for (Tier t : eligible) {
            if ((t.requiresStricken() && (!stricken || capBelowStricken)) || (t.requiresMerits() && !merits)
                    || (t.requiresPerk() && !perk)) {
                continue;
            }
            return t;
        }
        return eligible.isEmpty() ? contractTier : eligible.get(eligible.size() - 1);
    }

    /** Why the tier dropped, in a few words, or null when billed at the contract's tier. */
    public static String downgradeReason(Tier contractTier, Tier billed, boolean stricken, boolean merits, boolean perk,
                                         boolean capBelowStricken) {
        if (billed.id() == contractTier.id()) {
            return null;
        }
        if (contractTier.requiresStricken() && capBelowStricken) {
            return "revivable target";
        }
        if (contractTier.requiresStricken() && !stricken) {
            return "no Stricken on the finishing hit";
        }
        if (!merits) {
            return "hospital merits not maxed";
        }
        if (!perk) {
            return "faction doesn't have Hospitalization X";
        }
        return "requirements not met";
    }

    /** The rejection reason for how the target stood at the attack (scope doc 7.3), or null if it counts. */
    public static String rejectReason(Optional<Verdict> at) {
        if (at.isEmpty()) {
            return null;
        }
        return switch (at.get()) {
            case HIDDEN_ONLINE, HIDDEN_OFFLINE -> "status condition not met";
            case HIDDEN_REVIVABLE -> "target revivable";
            case HIDDEN_EARLY_DISCHARGE -> "target had early discharge";
            case PAUSED -> "contract paused or ended";
            case DONE -> "no hits left on that target";
            default -> null;
        };
    }

    public static long factionCut(long billed, BigDecimal pct) {
        return BigDecimal.valueOf(billed).multiply(pct == null ? BigDecimal.ZERO : pct)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }

    public enum Role { STARTER, ASSIST, CLAIMER, HITTER }

    public record Share(long mercId, Role role, BigDecimal pct, long amount) { }

    /**
     * Splits what's left after the faction cut (scope doc 7.5).
     *
     * @param mercJoiners    joiners who are signed-up mercs (not the starter)
     * @param otherJoiners   joiners who aren't; their share goes to the starter
     * @param claimerId      the claimer when the target was claimed, else null
     * @param claimerTookPart the claimer started, joined or finished the fight
     */
    public static List<Share> split(long amount, long starterId, List<Long> mercJoiners, int otherJoiners,
                                    BigDecimal starterPct, Long claimerId, boolean claimerTookPart,
                                    BigDecimal claimerNoShowPct) {
        List<Share> out = new ArrayList<>();
        if (claimerId != null) {
            if (claimerTookPart) {
                out.add(new Share(claimerId, Role.CLAIMER, hundred(), amount));
                return out;
            }
            BigDecimal pct = claimerNoShowPct == null ? hundred() : claimerNoShowPct;
            long toClaimer = part(amount, pct);
            out.add(new Share(claimerId, Role.CLAIMER, pct, toClaimer));
            if (amount - toClaimer > 0) {
                out.add(new Share(starterId, Role.HITTER, hundred().subtract(pct), amount - toClaimer));
            }
            return out;
        }
        int joiners = mercJoiners.size() + otherJoiners;
        if (joiners == 0) {
            out.add(new Share(starterId, Role.STARTER, hundred(), amount));
            return out;
        }
        BigDecimal joinPct = hundred().subtract(starterPct).divide(BigDecimal.valueOf(joiners), 3, RoundingMode.DOWN);
        long each = part(amount, joinPct);
        long toJoiners = 0;
        List<Share> joinerShares = new ArrayList<>();
        for (long j : mercJoiners) {
            joinerShares.add(new Share(j, Role.ASSIST, joinPct, each));
            toJoiners += each;
        }
        long toStarter = amount - toJoiners; // includes forfeited shares and rounding
        BigDecimal starterShown = starterPct.add(joinPct.multiply(BigDecimal.valueOf(otherJoiners)));
        out.add(new Share(starterId, Role.STARTER, starterShown, toStarter));
        out.addAll(joinerShares);
        return out;
    }

    private static long part(long amount, BigDecimal pct) {
        return BigDecimal.valueOf(amount).multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                .longValueExact();
    }

    private static BigDecimal hundred() {
        return BigDecimal.valueOf(100);
    }
}
