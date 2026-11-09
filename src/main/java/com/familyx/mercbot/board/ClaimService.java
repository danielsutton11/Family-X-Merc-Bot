package com.familyx.mercbot.board;

import com.familyx.mercbot.board.BoardRules.Verdict;
import com.familyx.mercbot.board.ClaimRepository.Blocks;
import com.familyx.mercbot.board.ClaimRepository.OpenClaim;
import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.merc.MercService;
import com.familyx.mercbot.merc.RoleSync;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import com.familyx.mercbot.torn.TornClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Claims (scope doc 5.4, 5.6): a merc reserves a board row; the lock starts when the target is
 * hittable and lasts the claim lock. No-shows need the attack log, so they're counted from phase 4.
 */
@Service
public class ClaimService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(ZoneOffset.UTC);

    /** What the merc is told, privately, after pressing Claim. */
    public enum Kind { CLAIMED, REFUSED, BLOCKED }

    public record Result(Kind kind, String title, String text, List<String> warnings) {
        static Result refused(String title, String text) {
            return new Result(Kind.REFUSED, title, text, List.of());
        }
    }

    private final ClaimRepository claims;
    private final TargetRepository targets;
    private final MercRepository mercs;
    private final MercService mercService;
    private final RoleSync roles;
    private final TierRepository tiers;
    private final BotSettings settings;
    private final TornClient torn;

    public ClaimService(ClaimRepository claims, TargetRepository targets, MercRepository mercs, MercService mercService,
                        RoleSync roles, TierRepository tiers, BotSettings settings, TornClient torn) {
        this.claims = claims;
        this.targets = targets;
        this.mercs = mercs;
        this.mercService = mercService;
        this.roles = roles;
        this.tiers = tiers;
        this.settings = settings;
        this.torn = torn;
    }

    /** A merc pressed Claim on a board row. */
    public Result claim(long discordId, long targetId) {
        Instant now = Instant.now();
        if (!settings.claimsOn()) {
            return Result.refused("Claims are switched off", "Attack straight from the board.");
        }
        Merc m = mercs.findByDiscord(discordId).filter(Merc::active).orElse(null);
        if (m == null) {
            return Result.refused("You're not signed up as a merc", "Sign up in the merc sign-up channel first.");
        }
        BoardTarget t = targets.running().stream().filter(x -> x.id() == targetId).findFirst().orElse(null);
        Tier tier = t == null ? null : tiers.find(t.tierId()).orElse(null);
        Duration early = Duration.ofMinutes(settings.claimShowEarlyMinutes());
        if (t == null || tier == null
                || BoardRules.verdict(t, now) != Verdict.SHOW && !BoardRules.showEarly(t, now, early)) {
            return Result.refused("That target isn't on the board any more", "The board will catch up in a few seconds.");
        }
        if (BoardRules.verdict(t, now) == Verdict.SHOW) {
            return Result.refused("They're already hittable", "Claims are for targets that are coming up. Attack from the board.");
        }
        Set<Integer> held = roles.tiersFor(m, tiers.findActive(), mercs.allOptedOut().getOrDefault(m.tornId(), Set.of()));
        if (!held.contains(tier.id())) {
            return Result.refused("That's not your tier", "You can only claim targets on boards for tiers you hold.");
        }
        Blocks b = claims.blocks(m.tornId());
        if (b.suspendedUntil() != null && b.suspendedUntil().isAfter(now)) {
            return new Result(Kind.BLOCKED, "⛔  Claiming suspended until " + WHEN.format(b.suspendedUntil()) + " UTC",
                    (b.suspensionReason() == null ? "Set by a Merc Manager." : b.suspensionReason())
                            + " Attack still works.", List.of());
        }
        if (b.cooldownUntil() != null && b.cooldownUntil().isAfter(now)) {
            return new Result(Kind.BLOCKED, "⏳  Claim cooldown until " + WHEN.format(b.cooldownUntil()) + " UTC",
                    settings.claimCooldownNoShows() + " claims in a row ended without you in the fight. Attack still works.",
                    List.of());
        }
        Optional<OpenClaim> existing = claims.openOn(t.id());
        if (existing.isPresent()) {
            OpenClaim c = existing.get();
            return c.mercId() == m.tornId()
                    ? Result.refused("You already hold this claim", lockText(c, now))
                    : Result.refused("Already claimed", name(c) + " claimed " + targetName(t)
                    + " first. You can still attack once they're hittable.");
        }
        int max = settings.maxClaims();
        int holding = claims.openCount(m.tornId());
        if (holding >= max) {
            return Result.refused("You're holding " + holding + (holding == 1 ? " claim" : " claims"),
                    "That's the limit. One frees up when its lock ends or the target is hit.");
        }
        Instant start = BoardRules.hittableAt(t, now);
        if (start.isBefore(now)) {
            start = now;
        }
        Instant ends = start.plusSeconds(settings.claimLockSeconds());
        Optional<Long> id = claims.take(t.id(), m.tornId(), start, ends);
        if (id.isEmpty()) {
            return Result.refused("Already claimed", "Someone claimed " + targetName(t) + " a moment before you.");
        }
        String text = (start.isAfter(now)
                ? "Your " + settings.claimLockSeconds() + "s lock starts when they're hittable (<t:"
                + start.getEpochSecond() + ":R>)."
                : "Your lock ends <t:" + ends.getEpochSecond() + ":R>.")
                + " Attack from the board.\n-# You hold " + (holding + 1) + " of " + max + " claims.";
        return new Result(Kind.CLAIMED, "🔒  You've claimed " + targetName(t), text, strickenWarnings(m, t, tier));
    }

    /**
     * Gold rows need Stricken (scope doc 7.4): warn, never block. Revivable targets on a Gold
     * contract that allows them are the reverse; requester overrides always need Stricken.
     */
    private List<String> strickenWarnings(Merc m, BoardTarget t, Tier tier) {
        if (!tier.requiresStricken()) {
            return List.of();
        }
        boolean dontUse = !t.hasOverride() && Boolean.TRUE.equals(t.revivable()) && t.conditions().allowRevivable();
        Optional<Boolean> equipped = strickenEquipped(m);
        if (equipped.isEmpty()) {
            return List.of("-# Couldn't check your equipped weapons.");
        }
        List<String> out = new ArrayList<>();
        if (dontUse && equipped.get()) {
            out.add("🔴 **Don't use Stricken on this target**");
        } else if (!dontUse && !equipped.get()) {
            out.add("🔴 **You don't have a Stricken weapon equipped**");
        }
        return out;
    }

    /** Whether a weapon in slots 1–3 has the Stricken bonus; empty if the key can't read it. */
    Optional<Boolean> strickenEquipped(Merc m) {
        Optional<String> key = mercService.keyFor(m);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode eq = torn.get("/user/equipment", Map.of(), key.get()).path("equipment");
            return Optional.of(hasStricken(eq));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Pure, for tests: the /user/equipment "equipment" list. */
    public static boolean hasStricken(JsonNode equipment) {
        for (JsonNode item : equipment) {
            int slot = item.path("slot").asInt(0);
            if (slot < 1 || slot > 3) {
                continue;
            }
            for (JsonNode bonus : item.path("bonuses")) {
                if ("Stricken".equalsIgnoreCase(bonus.path("title").asText(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ upkeep (called by the board refresh)

    /**
     * Moves claims along with their targets: the lock starts when the target becomes hittable,
     * a claim ends when its lock runs out (EXPIRED) or its target is hit or leaves the board
     * (RELEASED; phase 4 tells hits apart). Returns the claims still open, by target row.
     */
    public Map<Long, OpenClaim> sweep(List<BoardTarget> running, Instant now) {
        if (!settings.claimsOn()) {
            claims.closeAll();
            return Map.of();
        }
        Map<Long, BoardTarget> byId = new java.util.HashMap<>();
        running.forEach(t -> byId.put(t.id(), t));
        Duration lock = Duration.ofSeconds(settings.claimLockSeconds());
        Duration early = Duration.ofMinutes(settings.claimShowEarlyMinutes());
        Map<Long, OpenClaim> open = new java.util.HashMap<>();
        for (OpenClaim c : claims.open()) {
            BoardTarget t = byId.get(c.targetId());
            Verdict v = t == null ? Verdict.GONE : BoardRules.verdict(t, now);
            boolean early1 = t != null && BoardRules.showEarly(t, now, early);
            if (v != Verdict.SHOW && v != Verdict.NOT_YET) {
                claims.close(c.id(), "RELEASED");
                continue;
            }
            if (!c.started(now)) {
                // Waiting for the target: keep the lock lined up with when they come out.
                Instant start = v == Verdict.SHOW ? now : BoardRules.hittableAt(t, now);
                if (v == Verdict.NOT_YET && !early1 && start.isAfter(now.plus(early))) {
                    claims.close(c.id(), "RELEASED"); // back in for longer, or no longer meets the conditions
                    continue;
                }
                if (start.isBefore(now)) {
                    start = now;
                }
                if (c.lockStartsAt() == null || Duration.between(c.lockStartsAt(), start).abs().getSeconds() > 1) {
                    c = new OpenClaim(c.id(), c.targetId(), c.mercId(), c.mercName(), c.claimedAt(), start, start.plus(lock));
                    claims.setLock(c.id(), c.lockStartsAt(), c.expiresAt());
                }
            } else {
                // Lock running. Sent back to hospital or jail since it started: the target was hit.
                boolean rehit = v == Verdict.NOT_YET && t.statusUntil() != null
                        && t.statusUntil().isAfter(c.lockStartsAt().plusSeconds(5));
                if (rehit) {
                    claims.close(c.id(), "RELEASED");
                    continue;
                }
                if (c.expiresAt() != null && !c.expiresAt().isAfter(now)) {
                    claims.close(c.id(), "EXPIRED");
                    continue;
                }
            }
            open.put(c.targetId(), c);
        }
        return open;
    }

    // ------------------------------------------------------------------ wording

    static String name(OpenClaim c) {
        return c.mercName() == null ? "Merc " + c.mercId() : c.mercName();
    }

    private static String targetName(BoardTarget t) {
        return t.name() == null ? "Player " + t.tornId() : t.name();
    }

    private static String lockText(OpenClaim c, Instant now) {
        return c.started(now) ? "Your lock ends <t:" + c.expiresAt().getEpochSecond() + ":R>."
                : "Your lock starts when they're hittable (<t:" + c.lockStartsAt().getEpochSecond() + ":R>).";
    }
}
