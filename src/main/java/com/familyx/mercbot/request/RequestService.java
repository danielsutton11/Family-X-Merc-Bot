package com.familyx.mercbot.request;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.merc.RoleSync;
import com.familyx.mercbot.request.Choices.Kind;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.ContractRepository.NewContract;
import com.familyx.mercbot.request.ContractRepository.NewTarget;
import com.familyx.mercbot.request.RequestRepositories.Requester;
import com.familyx.mercbot.request.TornLookups.Player;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The request flow's logic (scope doc 4.1–4.3, 7.1, 7.6, 7.9): checks, targets, scenario
 * defaults, pricing and accepting. Discord screens live in {@code RequestHandler}.
 */
@Service
public class RequestService {

    private static final Duration DM_RECHECK = Duration.ofDays(7);
    private static final Duration IDENTITY_CACHE = Duration.ofMinutes(10);
    public static final int MAX_PLAYERS = 25;

    /** Why the requester can't go on, or their identity. */
    public sealed interface Gate {
        record Ok(Draft draft) implements Gate { }
        record Blocked(String title, String reason) implements Gate { }
    }

    /** The outcome of building a quote. */
    public sealed interface QuoteResult {
        record Denied(String reason) implements QuoteResult { }
        record ConfirmNoWar(String factionName) implements QuoteResult { }
        record Ready(Quote quote) implements QuoteResult { }
    }

    /** Everything the quote screen shows. */
    public record Quote(Tier tier, long unitPrice, BigDecimal discountPct, String discountLabel, long perHit,
                       Long silverPerHit, int targetCount, Long total, Instant expiresAt) { }

    /** The outcome of Accept. */
    public sealed interface AcceptResult {
        record Expired() implements AcceptResult { }
        record Denied(String reason) implements AcceptResult { }
        record Placed(Contract contract, boolean live) implements AcceptResult { }
    }

    private record CachedIdentity(Draft identity, Instant at) { }

    private final TornLookups torn;
    private final RequestRepositories repos;
    private final ContractRepository contracts;
    private final TierRepository tiers;
    private final FamilyFactionRepository factions;
    private final RoleSync roles;
    private final BotSettings settings;
    private final BotProperties props;
    private final Dms dms;
    private final AuditService audit;
    private final ApprovalCards approvals;
    private final com.familyx.mercbot.contract.ControlPanelService controlPanels;
    private final com.familyx.mercbot.contract.ContractClock clock;
    private final Map<Long, CachedIdentity> identities = new ConcurrentHashMap<>();

    public RequestService(TornLookups torn, RequestRepositories repos, ContractRepository contracts,
                          TierRepository tiers, FamilyFactionRepository factions, RoleSync roles,
                          BotSettings settings, BotProperties props, Dms dms, AuditService audit,
                          ApprovalCards approvals, com.familyx.mercbot.contract.ControlPanelService controlPanels,
                          com.familyx.mercbot.contract.ContractClock clock) {
        this.controlPanels = controlPanels;
        this.clock = clock;
        this.torn = torn;
        this.repos = repos;
        this.contracts = contracts;
        this.tiers = tiers;
        this.factions = factions;
        this.roles = roles;
        this.settings = settings;
        this.props = props;
        this.dms = dms;
        this.audit = audit;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ checks on every click (4.1)

    public Gate gate(long discordId, Member member) {
        if (props.verifiedRoleId() != 0 && (member == null
                || member.getRoles().stream().noneMatch(r -> r.getIdLong() == props.verifiedRoleId()))) {
            return new Gate.Blocked("You can't request yet", "You need the Verified role first.");
        }
        Draft identity;
        try {
            identity = identity(discordId);
        } catch (Exception e) {
            return new Gate.Blocked("Torn didn't answer", "Couldn't check your Torn account just now. Try again in a minute.");
        }
        if (identity == null) {
            return new Gate.Blocked("You can't request yet", "Your Discord account isn't linked to a Torn account. "
                    + "Link it in Torn's settings (Discord), then try again.");
        }
        Requester r = repos.requester(identity.requesterTornId).orElse(null);
        if (r != null && r.blacklisted()) {
            return new Gate.Blocked("You can't request", "Requests from your account aren't accepted.");
        }
        if (r == null || r.dmOkAt() == null || r.dmOkAt().isBefore(Instant.now().minus(DM_RECHECK))) {
            boolean ok = dms.sendNow(discordId, new EmbedBuilder().setColor(Panels.GOOD)
                    .setTitle("👋  DMs are working")
                    .setDescription("Your contract control panel, invoices and leader replies will arrive here.").build());
            if (!ok) {
                return new Gate.Blocked("You can't request yet", "Your DMs from this server are closed. Your control "
                        + "panel, invoice and leader replies arrive by DM.\n-# Server menu → Privacy Settings → allow "
                        + "Direct Messages, then click Request again.");
            }
            repos.markDmOk(identity.requesterTornId);
        }
        Draft d = new Draft();
        d.requesterDiscordId = discordId;
        d.requesterTornId = identity.requesterTornId;
        d.requesterName = identity.requesterName;
        d.requesterFactionId = identity.requesterFactionId;
        d.requesterFactionName = identity.requesterFactionName;
        d.requesterRevivable = identity.requesterRevivable;
        return new Gate.Ok(d);
    }

    private Draft identity(long discordId) throws Exception {
        CachedIdentity cached = identities.get(discordId);
        if (cached != null && cached.at().isAfter(Instant.now().minus(IDENTITY_CACHE))) {
            return cached.identity();
        }
        Optional<Long> tornId = torn.tornIdForDiscord(discordId);
        if (tornId.isEmpty()) {
            return null;
        }
        Player p = torn.player(tornId.get());
        Draft d = new Draft();
        d.requesterTornId = p.id();
        d.requesterName = p.name();
        d.requesterFactionId = p.factionId();
        d.requesterRevivable = p.revivable();
        if (p.factionId() != null) {
            d.requesterFactionName = factions.find(p.factionId()).map(FamilyFaction::name)
                    .orElseGet(() -> safeFactionName(p.factionId()));
        }
        repos.upsertRequester(p.id(), discordId, p.name(), p.factionId());
        identities.put(discordId, new CachedIdentity(d, Instant.now()));
        return d;
    }

    private String safeFactionName(long factionId) {
        try {
            return torn.faction(factionId).name();
        } catch (Exception e) {
            return "Faction " + factionId;
        }
    }

    // ------------------------------------------------------------------ tiers

    /** Tiers a requester can pick: switched on and not hidden for having no mercs. */
    public List<Tier> offeredTiers() {
        return tiers.findActive().stream().filter(Tier::offered).toList();
    }

    /** An active tier by id (empty if it has been retired). */
    public Optional<Tier> tier(int tierId) {
        return tiers.find(tierId);
    }

    /**
     * Pre-selects Tier, Attack and Mode on step 1 from the scenario for the chosen target type,
     * except any the requester has already picked themselves in this request.
     */
    public void applyStep1Defaults(Draft d) {
        if (d.forTarget == null || d.mercMe) {
            return;
        }
        Scenarios.War myWar = d.requesterFactionId == null ? null : torn.war(d.requesterFactionId).orElse(null);
        Scenarios.Step1 s = Scenarios.step1(d.forTarget, myWar, java.time.Instant.now());
        if (s == null) {
            return;
        }
        if (!d.chosenInStep1.contains("tier")) {
            List<Tier> offered = offeredTiers();
            Optional<Tier> want = s.gold()
                    ? goldTier().filter(g -> offered.stream().anyMatch(t -> t.id() == g.id()))
                    : offered.stream().filter(t -> t.name().equalsIgnoreCase("Silver")).findFirst();
            want.ifPresent(t -> d.tierId = t.id());
        }
        if (!d.chosenInStep1.contains("attack")) {
            d.attack = s.attack();
        }
        if (!d.chosenInStep1.contains("mode")) {
            d.mode = s.mode();
        }
    }

    /** Merc me is always the Gold tier (the highest tier that needs Stricken). */
    public Optional<Tier> goldTier() {
        return tiers.findActive().stream().filter(Tier::requiresStricken).max(Comparator.comparingInt(Tier::rank))
                .or(() -> tiers.findActive().stream().max(Comparator.comparingInt(Tier::rank)));
    }

    /** "Merc me is unavailable: no Gold mercs right now." when Gold has nobody, or is switched off. */
    public boolean mercMeAvailable() {
        Optional<Tier> gold = goldTier();
        return gold.isPresent() && gold.get().enabled() && roles.holderCounts().getOrDefault(gold.get().id(), 0) > 0;
    }

    // ------------------------------------------------------------------ step 2: targets

    private static final java.util.regex.Pattern PROFILE_ID =
            java.util.regex.Pattern.compile("(?i)[?&]XID=(\\d{1,10})");

    /**
     * Player IDs from what was typed: plain IDs or Torn profile links
     * (https://www.torn.com/profiles.php?XID=1561637), separated by spaces, commas or new lines.
     * Returns null if any entry is neither.
     */
    public static List<Long> parsePlayerIds(String raw) {
        List<Long> ids = new ArrayList<>();
        for (String s : raw.trim().split("[\\s,;]+")) {
            if (s.isBlank()) {
                continue;
            }
            java.util.regex.Matcher m = PROFILE_ID.matcher(s);
            if (m.find()) {
                ids.add(Long.parseLong(m.group(1)));
            } else if (s.matches("\\[?\\d{1,10}\\]?")) {
                ids.add(Long.parseLong(s.replaceAll("[\\[\\]]", "")));
            } else {
                return null;
            }
        }
        return ids.stream().distinct().toList();
    }

    /** Parses up to 25 player IDs or profile links and looks them up. Returns an error, or null. */
    public String loadPlayers(Draft d, String raw) {
        List<Long> ids = parsePlayerIds(raw);
        if (ids == null) {
            return "Some of those weren't player IDs or profile links. Use an ID like 1561637 or a link like "
                    + "https://www.torn.com/profiles.php?XID=1561637.";
        }
        if (ids.isEmpty()) {
            return "Enter at least one player ID or profile link.";
        }
        if (ids.size() > MAX_PLAYERS) {
            return "Up to " + MAX_PLAYERS + " players per request (you entered " + ids.size() + ").";
        }
        List<Player> found = new java.util.ArrayList<>();
        for (long id : ids) {
            try {
                found.add(torn.player(id));
            } catch (Exception e) {
                return "Torn couldn't find player " + id + ".";
            }
        }
        d.players = found;
        return null;
    }

    /** Looks up a faction and its members. Returns an error, or null. */
    public String loadFaction(Draft d, long factionId) {
        try {
            TornLookups.Faction f = torn.faction(factionId);
            if (f.members().isEmpty()) {
                return "Torn couldn't find faction " + factionId + ", or it has no members.";
            }
            d.factionId = f.id();
            d.factionName = f.name();
            d.factionMembers = f.members();
            d.allMembers = true;
            d.picked.clear();
            return null;
        } catch (Exception e) {
            return "Torn couldn't find faction " + factionId + ".";
        }
    }

    // ------------------------------------------------------------------ quote

    public QuoteResult quote(Draft d) {
        Instant now = Instant.now();
        Tier tier = tiers.find(d.tierId).orElse(null);
        if (tier == null || !tier.offered() && !d.mercMe) {
            return new QuoteResult.Denied("That tier isn't available right now. Pick another.");
        }

        // Rules (7.1)
        Map<Long, String> targetFactions = new LinkedHashMap<>();
        if (!d.mercMe) {
            if (d.forTarget.kind == Kind.PLAYERS) {
                for (Player p : d.players) {
                    if (p.factionId() != null) {
                        targetFactions.putIfAbsent(p.factionId(), factionLabel(p.factionId()));
                    }
                }
            } else if (d.factionId != null) {
                targetFactions.put(d.factionId, d.factionName);
            }
        }
        Set<Long> family = factions.findActive().stream().map(FamilyFaction::tornFactionId).collect(Collectors.toSet());
        Optional<String> denied = Rules.check(d.forTarget, d.mercMe, d.requesterFactionId, family,
                repos.protectedFactions(), targetFactions);
        if (denied.isPresent()) {
            return new QuoteResult.Denied(denied.get());
        }
        if (d.targets().isEmpty()) {
            return new QuoteResult.Denied("Pick at least one member to target.");
        }

        // Scenario and defaults (4.3), applied once; later edits on the conditions screen stay.
        Scenarios.War myWar = d.requesterFactionId == null ? null : torn.war(d.requesterFactionId).orElse(null);
        Scenarios.War targetWar = d.factionId == null || d.mercMe ? null : torn.war(d.factionId).orElse(null);
        Scenarios.Situation situation = new Scenarios.Situation(d.mercMe ? null : d.forTarget, d.mercMe, d.factionId,
                myWar, targetWar, d.attack, d.posture);
        if (!d.mercMe && Scenarios.pick(situation, now) == Scenarios.Scenario.D && !d.confirmedNoWar) {
            return new QuoteResult.ConfirmNoWar(d.requesterFactionName == null ? "Your faction" : d.requesterFactionName);
        }
        if (d.quotedAt == null || d.scenario == null) {
            Scenarios.Defaults defaults = Scenarios.defaults(situation, now);
            d.scenario = defaults.scenario();
            if (d.conditions == null) {
                d.conditions = defaults.conditions();
            }
            if (d.timesGiven) {
                d.start = d.startInput != null ? d.startInput : now;
                d.end = d.endInput;
            } else {
                d.start = defaults.start();
                d.end = defaults.end();
            }
            d.hits = d.hitsInput != null ? d.hitsInput : defaults.hits();
        }
        if (d.scenario == Scenarios.Scenario.G && d.posture == null) {
            d.posture = Choices.Posture.DEFENSIVE;
        }
        if (d.mode == Mode.HITS) {
            d.start = null;
            d.end = null;
        }
        d.quotedAt = now;

        // Price (7.4, 7.6)
        boolean gold = tier.requiresStricken();
        BigDecimal pct;
        String label;
        if (d.mercMe || !d.forTarget.myFaction) {
            pct = repos.personalDiscount(d.requesterTornId).orElse(BigDecimal.ZERO);
            label = "Personal discount";
        } else {
            pct = d.requesterFactionId == null ? BigDecimal.ZERO
                    : repos.factionDiscount(d.requesterFactionId).orElse(BigDecimal.ZERO);
            label = (d.requesterFactionName == null ? "Faction" : d.requesterFactionName) + " discount";
        }
        pct = Pricing.applicableDiscount(gold, pct);
        long perHit = Pricing.discounted(tier.pricePerHit(), pct);
        Long silverPerHit = null;
        if (gold && d.conditions != null && d.conditions.allowRevivable()) {
            silverPerHit = tiers.findActive().stream().filter(t -> t.requiresMerits() && !t.requiresStricken())
                    .max(Comparator.comparingInt(Tier::rank)).map(Tier::pricePerHit).orElse(null);
        }
        int count = d.targets().size();
        Long total = d.mode == Mode.HITS ? Pricing.total(perHit, count, d.hits) : null;
        Instant expires = now.plus(Duration.ofMinutes(settings.quoteMinutes()));
        return new QuoteResult.Ready(new Quote(tier, tier.pricePerHit(), pct, label, perHit, silverPerHit, count,
                total, expires));
    }

    /**
     * The default times for this request as far as it's known, for pre-filling the times box.
     * Call once the targets are known (for faction targets, after the faction ID).
     */
    public Scenarios.Defaults previewDefaults(Draft d) {
        Scenarios.War myWar = d.requesterFactionId == null ? null : torn.war(d.requesterFactionId).orElse(null);
        Scenarios.War targetWar = d.factionId == null || d.mercMe ? null : torn.war(d.factionId).orElse(null);
        return Scenarios.defaults(new Scenarios.Situation(d.mercMe ? null : d.forTarget, d.mercMe, d.factionId, myWar,
                targetWar, d.attack, d.posture), Instant.now());
    }

    private String factionLabel(long factionId) {
        return factions.find(factionId).map(FamilyFaction::name).orElse(null);
    }

    // ------------------------------------------------------------------ accept (7.9)

    public AcceptResult accept(Draft d, Quote q, long discordId) {
        if (d.quotedAt == null || q.expiresAt().isBefore(Instant.now())) {
            return new AcceptResult.Expired();
        }
        if (d.conditions == null) {
            return new AcceptResult.Denied("Pick every condition first.");
        }
        Requester r = repos.requester(d.requesterTornId).orElse(null);
        if (r != null && r.blacklisted()) {
            return new AcceptResult.Denied("Requests from your account aren't accepted.");
        }
        boolean family = d.requesterFactionId != null && factions.isFamily(d.requesterFactionId);
        int score = r == null ? 50 : r.score();
        boolean live = (family && settings.autoAcceptFamily()) || score >= settings.autoAcceptScore();

        List<NewTarget> targets = d.targets().stream()
                .map(p -> new NewTarget(p.id(), p.name(), p.level(), p.factionId())).toList();
        boolean factionTarget = !d.mercMe && d.forTarget.kind != Kind.PLAYERS;
        Contract c = contracts.create(new NewContract(d.requesterTornId, !d.mercMe && d.forTarget.myFaction, d.mercMe,
                d.scenario == null ? null : d.scenario.name(), d.posture, q.tier().id(),
                tiers.currentPriceVersionId(q.tier().id()), q.unitPrice(), q.discountPct(), settings.factionCutPct(),
                settings.starterPct(), settings.claimerNoShowPct(), d.attack, d.mode, d.start, d.end,
                d.mode == Mode.HITS ? d.hits : null, factionTarget, factionTarget ? d.factionId : null,
                factionTarget ? d.factionName : null, d.allMembers, d.conditions, d.notes,
                live ? "ACTIVE" : "PENDING_APPROVAL", targets));
        audit.log(discordId, live ? "CONTRACT_PLACED" : "CONTRACT_REQUESTED", "contract:" + c.id(), null,
                Map.of("tier", q.tier().name(), "targets", targets.size(), "perHit", q.perHit()),
                (live ? "Placed " : "Requested ") + "**" + c.ref() + "**: " + q.tier().name() + ", "
                        + targets.size() + " target" + (targets.size() == 1 ? "" : "s")
                        + (live ? " (auto-accepted)" : " (waiting for approval)"));
        if (!live) {
            approvals.post(c, d, q, score);
        }
        controlPanels.create(c.id(), discordId);
        if (live) {
            Thread.ofVirtual().start(clock::run); // announce the start straight away
        }
        return new AcceptResult.Placed(c, live);
    }

    // ------------------------------------------------------------------ repeat last (4.1)

    /** Rebuilds a draft from one of the requester's own contracts, or returns null if it can't be repeated. */
    public Draft repeat(Draft who, long contractId) {
        Optional<Contract> found = contracts.find(contractId).filter(c -> c.requesterId() == who.requesterTornId);
        if (found.isEmpty()) {
            return null;
        }
        Contract c = found.get();
        Draft d = who;
        d.resetDetails(); // start clean, keeping who the requester is
        d.repeatOfRef = c.ref();
        d.mercMe = c.mercMe();
        d.tierId = c.tierId();
        d.attack = c.attack();
        d.mode = c.mode();
        d.chosenInStep1.addAll(List.of("tier", "attack", "mode")); // a repeat keeps its own choices
        d.forTarget = c.mercMe() ? Choices.ForTarget.PERSONAL_PLAYERS
                : c.factionTarget()
                ? (c.myFaction() ? (who.requesterFactionId != null && who.requesterFactionId.equals(c.targetFactionId())
                ? Choices.ForTarget.MY_FACTION_MINE : Choices.ForTarget.MY_FACTION_OTHER) : Choices.ForTarget.PERSONAL_FACTION)
                : (c.myFaction() ? Choices.ForTarget.MY_FACTION_PLAYERS : Choices.ForTarget.PERSONAL_PLAYERS);
        d.factionRequest = d.forTarget.myFaction;
        d.conditions = c.conditions();
        d.posture = c.posture();
        d.notes = c.notes();
        d.hitsInput = c.hitsPerTarget();
        d.confirmedNoWar = true;
        List<String> changes = new java.util.ArrayList<>();

        Optional<Tier> tier = tiers.find(c.tierId());
        if (tier.isEmpty()) {
            return null;
        }
        if (tier.get().pricePerHit() != c.unitPrice()) {
            changes.add(tier.get().name() + " is now " + com.familyx.mercbot.discord.Money.shortForm(tier.get().pricePerHit())
                    + " per hit (was " + com.familyx.mercbot.discord.Money.shortForm(c.unitPrice()) + ")");
        }
        if (c.factionTarget()) {
            String err = loadFaction(d, c.targetFactionId());
            if (err != null) {
                return null;
            }
            int before = contracts.targetCount(c.id());
            if (c.allMembers()) {
                if (d.factionMembers.size() != before) {
                    changes.add(d.factionName + " has " + d.factionMembers.size() + " members (was " + before + ")");
                }
            } else {
                d.allMembers = false;
                Set<Long> previous = Set.copyOf(contracts.targetIds(c.id()));
                d.factionMembers.stream().map(Player::id).filter(previous::contains).forEach(d.picked::add);
                int gone = previous.size() - d.picked.size();
                if (gone > 0) {
                    changes.add(gone + " picked member" + (gone == 1 ? " has" : "s have") + " left " + d.factionName);
                }
            }
        } else if (c.mercMe()) {
            d.players = List.of(new Player(who.requesterTornId, who.requesterName, 0, who.requesterFactionId,
                    who.requesterFactionName, who.requesterRevivable));
        } else {
            String raw = contracts.targetIds(c.id()).stream().map(String::valueOf).collect(Collectors.joining(","));
            String err = loadPlayers(d, raw);
            if (err != null) {
                return null;
            }
        }
        d.changes = changes;
        return d;
    }

    /** Merc me: the requester is the only target, always Gold, one hit (scope doc 4.1, scenario F). */
    public void prepareMercMe(Draft d) {
        d.mercMe = true;
        d.factionRequest = false;
        d.forTarget = Choices.ForTarget.PERSONAL_PLAYERS;
        d.tierId = goldTier().map(Tier::id).orElse(null);
        d.attack = Choices.Attack.HOSPITALIZE;
        d.mode = Mode.HITS;
        d.players = List.of(new Player(d.requesterTornId, d.requesterName, 0, d.requesterFactionId,
                d.requesterFactionName, d.requesterRevivable));
    }

}
