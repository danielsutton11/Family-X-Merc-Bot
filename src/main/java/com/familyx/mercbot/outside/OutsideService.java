package com.familyx.mercbot.outside;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.board.PingService;
import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.contract.ControlPanelService;
import com.familyx.mercbot.contract.ResultsFeed;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.inbox.InboxService;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.outside.HospitalCause.Cause;
import com.familyx.mercbot.outside.OutsideRepository.Alert;
import com.familyx.mercbot.outside.OutsideRepository.Event;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.RequestRepositories;
import com.familyx.mercbot.request.TornLookups;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.torn.TornKeyPool;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Other merc services (scope doc 7.8). When a contract target goes into hospital, the cause is
 * read from its status. Family attackers, ranked-war enemies of the target's faction, and
 * "someone" during that war are ignored. Mugs and leaves by outsiders send urgent pings to the
 * tier; hospitalisations by outsiders put a "possible defensive merc service" card in front of
 * leaders, who can pause the contract or ignore the card for 6 hours.
 */
@Service
public class OutsideService {

    private static final Logger log = LoggerFactory.getLogger(OutsideService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
    private static final Duration LOOKUP_TTL = Duration.ofHours(24);
    public static final Duration IGNORE_FOR = Duration.ofHours(6);
    private static final List<String> OFFENSIVE = List.of("MUGGED", "ATTACKED");
    private static final List<String> DEFENSIVE = List.of("HOSPITALIZED");

    private record Cached<T>(T value, Instant at) { }

    private final Map<Long, Cached<Optional<Long>>> attackerFaction = new ConcurrentHashMap<>();
    private final Map<Long, Cached<String>> factionNames = new ConcurrentHashMap<>();

    private final OutsideRepository repo;
    private final ContractRepository contracts;
    private final TierRepository tiers;
    private final RequestRepositories requesters;
    private final FamilyFactionRepository factions;
    private final MercRepository mercs;
    private final TornLookups lookups;
    private final TornKeyPool pool;
    private final PingService pings;
    private final ObjectProvider<BoardService> boards;
    private final ControlPanelService panels;
    private final ResultsFeed feed;
    private final Dms dms;
    private final AuditService audit;
    private final BotSettings settings;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;

    public OutsideService(OutsideRepository repo, ContractRepository contracts, TierRepository tiers,
                          RequestRepositories requesters, FamilyFactionRepository factions, MercRepository mercs,
                          TornLookups lookups, TornKeyPool pool, PingService pings, ObjectProvider<BoardService> boards,
                          ControlPanelService panels, ResultsFeed feed, Dms dms, AuditService audit,
                          BotSettings settings, Layout layout, ObjectProvider<JDA> jda) {
        this.repo = repo;
        this.contracts = contracts;
        this.tiers = tiers;
        this.requesters = requesters;
        this.factions = factions;
        this.mercs = mercs;
        this.lookups = lookups;
        this.pool = pool;
        this.pings = pings;
        this.boards = boards;
        this.panels = panels;
        this.feed = feed;
        this.dms = dms;
        this.audit = audit;
        this.settings = settings;
        this.layout = layout;
        this.jda = jda;
    }

    // ------------------------------------------------------------------ spotting

    /** The target watcher saw a target go into hospital, with this status text. */
    public void hospitalised(BoardTarget t, String details, Instant now) {
        try {
            Optional<Cause> cause = HospitalCause.parse(details);
            if (cause.isEmpty() || !"ACTIVE".equals(t.contractStatus())) {
                return;
            }
            Cause c = cause.get();
            Long attackerFactionId = null;
            if (c.attackerId() != null) {
                if (mercs.findByTorn(c.attackerId()).isPresent()) {
                    return; // one of ours
                }
                attackerFactionId = factionOf(c.attackerId()).orElse(null);
                if (attackerFactionId != null && family().contains(attackerFactionId)) {
                    return;
                }
            } else if (c.attackerName() != null && mercNames().contains(c.attackerName().toLowerCase())) {
                return; // a name without a link that matches a signed-up merc
            }
            // Ranked war between the attacker's faction and the target's: part of the war, not a service.
            if (t.factionId() != null) {
                var war = lookups.war(t.factionId()).filter(w -> w.active(now));
                if (war.isPresent() && (c.unknown() || attackerFactionId != null && attackerFactionId == war.get().opponentId())) {
                    return;
                }
            }
            repo.add(t.id(), t.tornId(), c.kind().name(), c.attackerId(), c.attackerName(), attackerFactionId);
            check(t.contractId(), now);
        } catch (Exception e) {
            log.warn("Outside attack check on {} failed: {}", t.tornId(), e.getMessage());
        }
    }

    /** Every minute: pings keep going while it carries on, and episodes end once it stops. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 75_000)
    public void tick() {
        try {
            Instant now = Instant.now();
            for (long id : repo.recentContracts()) {
                check(id, now);
            }
        } catch (Exception e) {
            log.warn("Outside service check failed: {}", e.getMessage());
        }
    }

    private synchronized void check(long contractId, Instant now) {
        Optional<Contract> found = contracts.find(contractId);
        if (found.isEmpty()) {
            return;
        }
        Contract c = found.get();
        offensive(c, now);
        defensive(c, now);
    }

    private void offensive(Contract c, Instant now) {
        List<Event> events = repo.events(c.id(), OFFENSIVE,
                now.minus(Duration.ofMinutes(settings.offensiveMinutes())));
        Alert a = repo.alert(c.id(), "OFFENSIVE");
        if (events.size() < settings.offensiveCount()) {
            if (a.pingsSent() > 0) {
                repo.resetPings(c.id());
            }
            return;
        }
        if (!"ACTIVE".equals(c.status()) || a.pingsSent() >= settings.urgentPings()) {
            return;
        }
        if (a.lastPingAt() != null && a.lastPingAt().plus(Duration.ofMinutes(settings.urgentPingGapMinutes())).isAfter(now)) {
            return;
        }
        tiers.find(c.tierId()).ifPresent(tier -> pings.urgent(tier, c.ref(),
                feed.targetName(c, contracts.targetCount(c.id())),
                events.size() + " mugs or attacks by outsiders in the last " + settings.offensiveMinutes()
                        + " min. Hit the targets on the board before they do.",
                "Ping " + (a.pingsSent() + 1) + " of " + settings.urgentPings()
                        + (a.pingsSent() + 1 < settings.urgentPings()
                        ? " · next in " + settings.urgentPingGapMinutes() + " min if it carries on" : "")));
        repo.pinged(c.id());
        audit.log(null, "OUTSIDE_OFFENSIVE", "contract:" + c.id(), null, Map.of("events", events.size()),
                "Urgent ping for **" + c.ref() + "**: " + events.size() + " mugs or attacks by outsiders");
    }

    private void defensive(Contract c, Instant now) {
        List<Event> events = repo.events(c.id(), DEFENSIVE,
                now.minus(Duration.ofMinutes(settings.defensiveMinutes())));
        if (events.size() < settings.defensiveCount()) {
            return;
        }
        Alert a = repo.alert(c.id(), "DEFENSIVE");
        if (a.mutedUntil() != null && a.mutedUntil().isAfter(now)) {
            return;
        }
        // Everything outsiders hospitalised in the last 2 hours, so a growing card shows the whole picture.
        List<Event> shown = repo.events(c.id(), DEFENSIVE, now.minus(Duration.ofHours(2)));
        MessageEmbed embed = card(c, shown, null);
        ActionRow row = buttons(c, true);
        TextChannel ch = leaders();
        if (ch == null) {
            return;
        }
        if (a.cardMessageId() != null) {
            try {
                ch.editMessageById(a.cardMessageId(), new MessageEditBuilder().setEmbeds(embed).setComponents(row).build())
                        .complete();
                return;
            } catch (Exception gone) {
                // deleted by hand: post again
            }
        }
        if (!"ACTIVE".equals(c.status())) {
            return; // no new card for a contract that's already paused or over
        }
        try {
            long id = ch.sendMessageEmbeds(embed).setComponents(row).complete().getIdLong();
            repo.setCard(c.id(), id);
            audit.log(null, "OUTSIDE_DEFENSIVE", "contract:" + c.id(), null, Map.of("events", events.size()),
                    "Possible defensive merc service on **" + c.ref() + "**: " + events.size()
                            + " targets hospitalised by outsiders");
        } catch (Exception e) {
            log.warn("Couldn't post the defensive service card for {}: {}", c.ref(), e.getMessage());
        }
    }

    private MessageEmbed card(Contract c, List<Event> events, String outcome) {
        int tw = Math.min(14, events.stream().mapToInt(e -> target(e).length()).max().orElse(8));
        int aw = Math.min(26, events.stream().mapToInt(e -> attacker(e).length()).max().orElse(8));
        StringBuilder table = new StringBuilder("```\n");
        events.stream().limit(12).forEach(e -> {
            String t = target(e);
            String a = attacker(e);
            table.append(TIME.format(e.at())).append("  ").append(pad(t.length() > tw ? t.substring(0, tw - 1) + "…" : t, tw))
                    .append("  by ").append(pad(a.length() > aw ? a.substring(0, aw - 1) + "…" : a, aw))
                    .append(e.attackerFactionId() == null ? "" : " · " + factionName(e.attackerFactionId()))
                    .append('\n');
        });
        if (events.size() > 12) {
            table.append("+").append(events.size() - 12).append(" more\n");
        }
        table.append("```");
        var stats = contracts.hitStats(c.id());
        String tier = tiers.find(c.tierId()).map(TierRepository.Tier::name).orElse("");
        String state = switch (c.status()) {
            case "ACTIVE" -> "Live";
            case "PAUSED" -> "Paused";
            default -> "Finished";
        };
        long distinct = events.stream().map(Event::targetTornId).distinct().count();
        return new EmbedBuilder().setColor(Panels.BAD).setAuthor("POSSIBLE DEFENSIVE MERC SERVICE")
                .setTitle("🛡️  " + c.ref() + " · " + feed.targetName(c, contracts.targetCount(c.id())) + " · " + distinct
                        + (distinct == 1 ? " target" : " targets") + " hospitalised by outsiders")
                .setDescription((outcome == null ? "" : outcome + "\n") + table + "\n-# " + state + " · " + tier + " · "
                        + stats.accepted() + (stats.accepted() == 1 ? " hit" : " hits") + " so far · requester not told")
                .build();
    }

    private static ActionRow buttons(Contract c, boolean live) {
        Button pause = Button.danger(CustomId.of("outside", "pause", c.id()), "Pause contract").withEmoji(Emoji.fromUnicode("⏸️"));
        Button ignore = Button.secondary(CustomId.of("outside", "ignore", c.id()), "Ignore for 6 h");
        Button open = Button.secondary(CustomId.of("lcon", "open", c.id()), "Open contract").withEmoji(Emoji.fromUnicode("📋"));
        return live && "ACTIVE".equals(c.status()) ? ActionRow.of(pause, ignore, open) : ActionRow.of(open);
    }

    // ------------------------------------------------------------------ leader actions on the card

    /** Pause contract: off the boards, a merc-results line, and a DM to the requester explaining why. */
    public boolean pause(Contract c, long leader, long cardMessageId) {
        if (!contracts.setStatus(c.id(), List.of("ACTIVE"), "PAUSED")) {
            return false;
        }
        Contract now = contracts.find(c.id()).orElse(c);
        feed.contract(now, "⏸️", "paused by a leader", "possible defensive merc service");
        audit.log(leader, "CONTRACT_PAUSED", "contract:" + c.id(), null, Map.of("reason", "defensive"),
                "Paused **" + c.ref() + "** (possible defensive merc service)");
        requesters.requester(c.requesterId()).ifPresent(r -> dms.send(r.discordId(),
                com.familyx.mercbot.discord.Dms.Clean.when("DEFENSIVE_PAUSE", "contract:" + c.id()), new EmbedBuilder()
                .setColor(Panels.PRIVATE).setAuthor("CONTRACT " + c.ref()).setTitle("⏸️  Paused by a leader")
                .setDescription("Another group seems to be putting your targets in hospital to protect them, so our "
                        + "hits wouldn't land. Your contract is paused; hits so far are still billed. Message leaders "
                        + "if you'd like to talk it through.")
                .build(), ActionRow.of(InboxService.messageButton(c.id()))));
        panels.refresh(c.id());
        Thread.ofVirtual().start(() -> boards.getObject().refresh());
        TextChannel ch = leaders();
        if (ch != null) {
            List<Event> shown = repo.events(c.id(), DEFENSIVE, Instant.now().minus(Duration.ofHours(2)));
            ch.editMessageById(cardMessageId, new MessageEditBuilder()
                    .setEmbeds(card(now, shown, "⏸️ Paused by <@" + leader + ">. The requester has been told."))
                    .setComponents(buttons(now, false)).build()).queue(null, ignored -> { });
        }
        return true;
    }

    /** Ignore: the card goes and this contract's defensive alerts are muted for 6 hours. */
    public void ignore(Contract c, long leader, long cardMessageId) {
        repo.alert(c.id(), "DEFENSIVE");
        repo.mute(c.id(), Instant.now().plus(IGNORE_FOR));
        audit.log(leader, "OUTSIDE_IGNORED", "contract:" + c.id(), null, null,
                "Ignored the defensive service card for **" + c.ref() + "** for 6 hours");
        TextChannel ch = leaders();
        if (ch != null) {
            ch.deleteMessageById(cardMessageId).queue(null, ignored -> { });
        }
    }

    // ------------------------------------------------------------------ lookups

    private Optional<Long> factionOf(long playerId) {
        Cached<Optional<Long>> c = attackerFaction.get(playerId);
        if (c != null && c.at().plus(LOOKUP_TTL).isAfter(Instant.now())) {
            return c.value();
        }
        try {
            Optional<Long> f = Optional.ofNullable(lookups.player(playerId).factionId());
            attackerFaction.put(playerId, new Cached<>(f, Instant.now()));
            return f;
        } catch (Exception e) {
            return c == null ? Optional.empty() : c.value();
        }
    }

    private String factionName(long factionId) {
        Cached<String> c = factionNames.get(factionId);
        if (c != null && c.at().plus(LOOKUP_TTL).isAfter(Instant.now())) {
            return c.value();
        }
        try {
            String name = pool.call(TornKeyPool.Scope.any(), "/faction/" + factionId + "/basic", Map.of())
                    .path("basic").path("name").asText("Faction " + factionId);
            factionNames.put(factionId, new Cached<>(name, Instant.now()));
            return name;
        } catch (Exception e) {
            return c == null ? "Faction " + factionId : c.value();
        }
    }

    private Set<Long> family() {
        return factions.findActive().stream().map(FamilyFaction::tornFactionId).collect(Collectors.toSet());
    }

    private Set<String> mercNames() {
        return mercs.findActive().stream().map(Merc::name).filter(java.util.Objects::nonNull)
                .map(String::toLowerCase).collect(Collectors.toSet());
    }

    private TextChannel leaders() {
        JDA api = jda.getIfAvailable();
        return api == null ? null : layout.get(Layout.CH_LEADERS).map(api::getTextChannelById).orElse(null);
    }

    private static String target(Event e) {
        return e.targetName() == null ? "Player " + e.targetTornId() : e.targetName();
    }

    private static String attacker(Event e) {
        if (e.attackerName() == null && e.attackerId() == null) {
            return "someone";
        }
        return (e.attackerName() == null ? "Player" : e.attackerName())
                + (e.attackerId() == null ? "" : " [" + e.attackerId() + "]");
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }
}
