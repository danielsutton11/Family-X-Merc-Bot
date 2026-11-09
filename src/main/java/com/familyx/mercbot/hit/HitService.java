package com.familyx.mercbot.hit;

import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.board.TargetRepository;
import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.contract.ControlPanelService;
import com.familyx.mercbot.contract.ResultsFeed;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.hit.HitRepository.ClaimAt;
import com.familyx.mercbot.hit.HitRepository.Terms;
import com.familyx.mercbot.hit.HitRules.Share;
import com.familyx.mercbot.merc.FactionPerkService;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.DmPref;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.Pricing;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import com.familyx.mercbot.torn.TornKeyPool;
import com.familyx.mercbot.torn.TornKeyPool.Scope;
import net.dv8tion.jda.api.EmbedBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns attack-log fights into hits (scope doc 5.5, 7.2–7.5): picks the contract the attack
 * counts towards, checks it, bills it at the right tier, splits it, settles any claim, and tells
 * the mercs, the requester's control panel and #merc-results.
 */
@Service
public class HitService {

    private static final Logger log = LoggerFactory.getLogger(HitService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
    static final String ATTACK_LOG = "https://www.torn.com/page.php?sid=attackLog&ID=";

    private final HitRepository hits;
    private final TargetRepository targets;
    private final ContractRepository contracts;
    private final MercRepository mercs;
    private final TierRepository tiers;
    private final FactionPerkService perks;
    private final BoardService boards;
    private final ControlPanelService panels;
    private final ResultsFeed feed;
    private final Dms dms;
    private final BotSettings settings;
    private final TornKeyPool pool;

    public HitService(HitRepository hits, TargetRepository targets, ContractRepository contracts, MercRepository mercs,
                      TierRepository tiers, FactionPerkService perks, BoardService boards, ControlPanelService panels,
                      ResultsFeed feed, Dms dms, BotSettings settings, TornKeyPool pool) {
        this.hits = hits;
        this.targets = targets;
        this.contracts = contracts;
        this.mercs = mercs;
        this.tiers = tiers;
        this.perks = perks;
        this.boards = boards;
        this.panels = panels;
        this.feed = feed;
        this.dms = dms;
        this.settings = settings;
        this.pool = pool;
    }

    /**
     * Processes the combined attack logs. Fights that ended after {@code settledBefore} are left
     * for the next pass. Returns how many hits were recorded.
     */
    public int process(List<TornAttack> batch, Instant settledBefore) {
        int recorded = 0;
        for (TornAttack.Fight fight : TornAttack.fights(batch)) {
            TornAttack fin = fight.finish();
            if (fin.ended() != null && fin.ended().isAfter(settledBefore)) {
                continue;
            }
            try {
                if (handle(fight)) {
                    recorded++;
                }
            } catch (Exception e) {
                log.warn("Couldn't process attack {}: {}", fight.finish().id(), e.getMessage());
            }
        }
        if (recorded > 0) {
            Thread.ofVirtual().start(boards::refresh);
        }
        return recorded;
    }

    private boolean handle(TornAttack.Fight fight) {
        TornAttack f = fight.finish();
        if (f.attackerId() == null || hits.seen(f.id())) {
            return false;
        }
        Merc starter = mercs.findByTorn(f.attackerId()).filter(Merc::active).orElse(null);
        if (starter == null) {
            return false; // not a merc: outside the service (phase 5)
        }
        List<BoardTarget> rows = targets.forPlayer(f.defenderId());
        if (rows.isEmpty()) {
            return false;
        }
        Map<Long, Contract> byId = new HashMap<>();
        for (BoardTarget r : rows) {
            contracts.find(r.contractId()).ifPresent(c -> byId.put(c.id(), c));
        }
        List<BoardTarget> ordered = rows.stream().filter(r -> byId.containsKey(r.contractId()))
                .filter(r -> !byId.get(r.contractId()).createdAt().isAfter(f.started()))
                .sorted(Comparator.comparingInt(BoardTarget::priority).reversed()
                        .thenComparing(BoardTarget::contractCreated, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (ordered.isEmpty()) {
            return false;
        }
        // A claim decides the contract; then the first that counts by priority, then age.
        BoardTarget row = null;
        String reason = null;
        Optional<ClaimAt> claim = Optional.empty();
        for (BoardTarget r : ordered) {
            Optional<ClaimAt> c = hits.claimCovering(r.id(), f.started());
            if (c.isPresent() && check(r, byId.get(r.contractId()), f) == null) {
                row = r;
                claim = c;
                break;
            }
        }
        if (row == null) {
            for (BoardTarget r : ordered) {
                if (check(r, byId.get(r.contractId()), f) == null) {
                    row = r;
                    claim = hits.claimCovering(r.id(), f.started());
                    break;
                }
            }
        }
        if (row == null) {
            row = ordered.get(0);
            reason = check(row, byId.get(row.contractId()), f);
        }
        Contract c = byId.get(row.contractId());
        if (reason != null) {
            reject(f, row, c, starter, reason, fight.assists().size());
            return true;
        }
        accept(fight, row, c, starter, claim);
        return true;
    }

    /** Why this attack doesn't count towards this row, or null if it does (scope doc 7.2, 7.3). */
    String check(BoardTarget r, Contract c, TornAttack f) {
        boolean wantHosp = c.attack() == Attack.HOSPITALIZE;
        if (wantHosp ? !"Hospitalized".equalsIgnoreCase(f.result()) : !"Mugged".equalsIgnoreCase(f.result())) {
            return "wrong result for the attack type";
        }
        if ((c.startsAt() != null && c.startsAt().isAfter(f.started()))
                || (c.endsAt() != null && !c.endsAt().isAfter(f.started()))) {
            return "outside the contract window";
        }
        if ("PAUSED".equals(c.status())
                || (c.endedAt() != null && !c.endedAt().isAfter(f.started()))) {
            return "contract paused or ended";
        }
        if (r.hitsRequired() != null && r.hitsDone() >= r.hitsRequired()) {
            return "no hits left on that target";
        }
        Optional<com.familyx.mercbot.board.BoardRules.Verdict> at = boards.verdictAt(r.id(), f.started());
        if (at.isPresent()) {
            return HitRules.rejectReason(at);
        }
        // No record from the board (just restarted): check what doesn't change with an attack.
        if (!r.hasOverride() && !r.conditions().allowRevivable() && Boolean.TRUE.equals(r.revivable())) {
            return "target revivable";
        }
        if (!r.conditions().allowEarlyDischarge() && Boolean.TRUE.equals(r.earlyDischarge())) {
            return "target had early discharge";
        }
        return null;
    }

    // ------------------------------------------------------------------ accepted

    private void accept(TornAttack.Fight fight, BoardTarget row, Contract c, Merc starter, Optional<ClaimAt> claim) {
        TornAttack f = fight.finish();
        List<Tier> active = tiers.findActive();
        Tier contractTier = tiers.find(c.tierId()).orElseThrow();
        boolean merits = starter.meritsOk();
        boolean perk = perks.has(starter.factionId());
        boolean cap = !c.mercMe() && !row.hasOverride() && Boolean.TRUE.equals(row.revivable());
        Tier billed = HitRules.billedTier(active, contractTier, f.stricken(), merits, perk, cap);
        String downgrade = HitRules.downgradeReason(contractTier, billed, f.stricken(), merits, perk, cap);
        long amount = billed.id() == contractTier.id() ? Pricing.discounted(c.unitPrice(), c.discountPct())
                : hits.priceAt(billed.id(), c.createdAt());
        Terms terms = hits.terms(c.id());
        long cut = HitRules.factionCut(amount, terms.factionCutPct());

        // Who was in the fight
        Map<Long, Long> attackOf = new HashMap<>(); // merc → their attack id
        List<Long> mercJoiners = new ArrayList<>();
        Set<Long> everyone = new HashSet<>();
        everyone.add(starter.tornId());
        int others = 0;
        for (TornAttack a : fight.assists()) {
            if (a.attackerId() == null || a.attackerId() == starter.tornId() || !everyone.add(a.attackerId())) {
                if (a.attackerId() == null) {
                    others++;
                }
                continue;
            }
            if (mercs.findByTorn(a.attackerId()).filter(Merc::active).isPresent()) {
                mercJoiners.add(a.attackerId());
                attackOf.put(a.attackerId(), a.id());
            } else {
                others++;
            }
        }
        attackOf.put(starter.tornId(), f.id());
        Long claimer = claim.map(ClaimAt::mercId).orElse(null);
        boolean claimerTookPart = claimer != null && everyone.contains(claimer);
        List<Share> shares = HitRules.split(amount - cut, starter.tornId(), mercJoiners, others, terms.starterPct(),
                claimer, claimerTookPart, terms.claimerNoShowPct());

        Optional<Long> hitId = hits.insert(row.id(), f, null, billed.id(), amount, claim.map(ClaimAt::id).orElse(null),
                true, null, downgrade, fight.assists().size());
        if (hitId.isEmpty()) {
            return; // another faction's poll got it first
        }
        for (Share s : shares) {
            Long faction = mercs.findByTorn(s.mercId()).map(Merc::factionId).orElse(null);
            hits.addShare(hitId.get(), s.mercId(), faction, s.role().name(), attackOf.get(s.mercId()), s.pct(), s.amount());
        }
        hits.countHit(row.id());
        if (row.hasOverride()) {
            hits.useOverride(row.id(), hitId.get());
        }
        claim.ifPresent(cl -> {
            if (claimerTookPart) {
                hits.judge(cl.id(), "HIT", true);
                hits.resetNoShows(cl.mercId());
            } else {
                hits.judge(cl.id(), "NOSHOW", false);
                noShow(cl.mercId(), cl.id());
            }
        });
        Integer hosp = f.result().equalsIgnoreCase("Hospitalized") ? hospitalSeconds(f) : null;
        if (hosp != null) {
            hits.setHospSeconds(hitId.get(), hosp);
        }

        // Tell everyone
        String target = name(f.defenderName(), f.defenderId());
        for (Share s : shares) {
            Merc m = s.mercId() == starter.tornId() ? starter : mercs.findByTorn(s.mercId()).orElse(null);
            if (m == null) {
                continue;
            }
            if (m.dmPref() == DmPref.EVERY_HIT) {
                dms.send(m.discordId(), acceptedEmbed(c, f, target, billed, contractTier, downgrade, amount, s, hosp));
            }
            if (m.dmPref() != DmPref.HOURLY) {
                hits.markNotified(hitId.get(), m.tornId());
            }
        }
        int n = contracts.targetCount(c.id());
        String verb = c.attack() == Attack.MUG ? "mugged" : "hospitalized";
        int joiners = mercJoiners.size() + others;
        var progress = hits.breakdown(c.id());
        feed.post("✅ **" + c.ref() + " · " + feed.targetName(c, n) + "** · " + target + " [" + f.defenderId() + "] "
                + verb + " by **" + name(starter.name(), starter.tornId()) + "**"
                + (joiners == 0 ? "" : " + " + joiners + (joiners == 1 ? " joiner" : " joiners"))
                + "\n-# " + billed.name() + (downgrade == null ? "" : " (" + downgrade + ")")
                + (hosp == null ? "" : " · " + duration(hosp) + " in hospital")
                + (n > 1 ? " · " + progress.targetsHit() + " of " + n + " targets hit" : ""));
        panels.refresh(c.id());
    }

    private EmbedBuilder base(Contract c, String author) {
        return new EmbedBuilder().setAuthor(author + " · " + c.ref() + " · "
                + feed.targetName(c, contracts.targetCount(c.id())).toUpperCase());
    }

    private net.dv8tion.jda.api.entities.MessageEmbed acceptedEmbed(Contract c, TornAttack f, String target, Tier billed,
                                                                    Tier contractTier, String downgrade, long amount,
                                                                    Share s, Integer hosp) {
        String verb = c.attack() == Attack.MUG ? "mugged" : "hospitalized";
        String why = switch (s.role()) {
            case STARTER -> s.pct().compareTo(java.math.BigDecimal.valueOf(100)) >= 0 ? "100% · solo"
                    : pct(s) + " · started the fight";
            case ASSIST -> pct(s) + " · joined";
            case CLAIMER -> pct(s) + " · your claim";
            case HITTER -> pct(s) + " · hit a claimed target";
        };
        EmbedBuilder eb = base(c, "HIT ACCEPTED")
                .setColor(downgrade == null ? Panels.GOOD : Panels.WARN)
                .setTitle("✅  " + target + " [" + f.defenderId() + "] " + verb
                        + (downgrade == null ? "" : " · billed at " + billed.name()));
        List<String> lines = new ArrayList<>();
        if (downgrade != null) {
            lines.add("⚡ " + capitalise(downgrade) + ", so this hit is billed at " + billed.name() + ", not "
                    + contractTier.name() + ".");
        }
        if (s.role() == HitRules.Role.CLAIMER && f.attackerId() != null && s.mercId() != f.attackerId()) {
            lines.add("You didn't join this fight, so your claim counts as a no-show.");
        }
        if (!lines.isEmpty()) {
            eb.setDescription(String.join("\n", lines));
        }
        boolean discounted = billed.id() == contractTier.id() && c.discountPct() != null && c.discountPct().signum() > 0;
        eb.addField(Panels.tierIcon(billed) + "  Billed", billed.name() + "\n-# " + Money.full(amount)
                        + (discounted ? " (" + c.discountPct().stripTrailingZeros().toPlainString() + "% off)" : ""), true)
                .addField("💰  Your share", Money.full(s.amount()) + "\n-# " + why, true)
                .addField(c.attack() == Attack.MUG ? "💸  Mugged" : "🏥  Hospital",
                        (hosp == null ? "–" : duration(hosp)) + "\n-# " + TIME.format(f.started()) + " UTC", true)
                .addField("", "-# Pending until the requester pays · [Attack log ↗](" + ATTACK_LOG + f.code() + ")",
                        false);
        return eb.build();
    }

    // ------------------------------------------------------------------ rejected

    private void reject(TornAttack f, BoardTarget row, Contract c, Merc starter, String reason, int joiners) {
        Optional<Long> hitId = hits.insert(row.id(), f, null, null, null, null, false, reason, null, joiners);
        if (hitId.isEmpty()) {
            return;
        }
        String target = name(f.defenderName(), f.defenderId());
        dms.send(starter.discordId(), base(c, "HIT REJECTED").setColor(Panels.BAD)
                .setTitle("✖️  " + target + " [" + f.defenderId() + "] wasn't billed")
                .setDescription(explain(reason, c, f) + "\n-# [Attack log ↗](" + ATTACK_LOG + f.code() + ")").build());
        int n = contracts.targetCount(c.id());
        feed.post("✖️ **" + c.ref() + " · " + feed.targetName(c, n) + "** · " + target + " [" + f.defenderId() + "] by **"
                + name(starter.name(), starter.tornId()) + "** · rejected\n-# " + reason);
    }

    private static String explain(String reason, Contract c, TornAttack f) {
        return switch (reason) {
            case "wrong result for the attack type" -> "This contract needs a "
                    + (c.attack() == Attack.MUG ? "mug" : "hospitalization") + ", but the result was **" + f.result() + "**.";
            case "status condition not met" -> "The target didn't meet the contract's status condition (**"
                    + c.conditions().statusLabel() + "**) when you attacked.";
            case "target revivable" -> "The target was **revivable**, and this contract skips revivable targets.";
            case "target had early discharge" -> "The target had **early discharge**, and this contract skips them.";
            case "outside the contract window" -> "The attack started **outside the contract's time window**.";
            case "contract paused or ended" -> "The contract was **paused or had ended** when you attacked.";
            case "no hits left on that target" -> "That target had **no hits left** on this contract.";
            default -> capitalise(reason) + ".";
        };
    }

    // ------------------------------------------------------------------ claims

    /**
     * Expired claims the attack log has been read past without a hit from the claimer: no-shows
     * (scope doc 5.6). Released claims with no matched hit were hit by someone outside the service.
     */
    public void judgeClaims(Instant readUpTo) {
        for (ClaimAt c : hits.expiredUnjudged(readUpTo)) {
            hits.judge(c.id(), "NOSHOW", false);
            noShow(c.mercId(), c.id());
        }
        hits.settleReleased(readUpTo);
    }

    private void noShow(long mercId, long claimId) {
        hits.addNoShow(mercId, claimId);
        int run = hits.bumpNoShows(mercId);
        int limit = settings.claimCooldownNoShows();
        if (run < limit) {
            return;
        }
        Instant until = Instant.now().plus(Duration.ofMinutes(settings.claimCooldownMinutes()));
        hits.startCooldown(mercId, until);
        mercs.findByTorn(mercId).ifPresent(m -> dms.send(m.discordId(), new EmbedBuilder().setColor(Panels.WARN)
                .setAuthor("CLAIMS")
                .setTitle("⏳  Claim cooldown until " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
                        .format(until) + " UTC")
                .setDescription("Your last " + limit + " claims ended without you in the fight"
                        + hits.targetNameForClaim(claimId).map(t -> " (the latest: " + t + ")").orElse("")
                        + ". You can't claim for " + settings.claimCooldownMinutes() + " minutes. Attack still works.")
                .build()));
    }

    // ------------------------------------------------------------------ helpers

    /** Hospital time from the target's status straight after the hit (scope doc 10.4). */
    private Integer hospitalSeconds(TornAttack f) {
        try {
            var status = pool.call(Scope.any(), "/user/" + f.defenderId() + "/profile", Map.of())
                    .path("profile").path("status");
            if (!"Hospital".equalsIgnoreCase(status.path("state").asText(""))) {
                return null;
            }
            long until = status.path("until").asLong(0);
            Instant from = f.ended() == null ? Instant.now() : f.ended();
            long secs = until - from.getEpochSecond();
            return secs > 0 ? (int) secs : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String duration(int seconds) {
        long m = seconds / 60;
        return m < 60 ? m + "m" : (m / 60) + "h " + (m % 60) + "m";
    }

    private static String pct(Share s) {
        return s.pct().stripTrailingZeros().toPlainString() + "%";
    }

    private static String name(String name, long id) {
        return name == null ? "Player " + id : name;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
