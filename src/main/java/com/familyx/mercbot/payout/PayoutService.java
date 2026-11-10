package com.familyx.mercbot.payout;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.MessageUpdater;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.DmPref;
import com.familyx.mercbot.payment.NewsReconciler;
import com.familyx.mercbot.payout.PayoutRepository.Line;
import com.familyx.mercbot.payout.PayoutRepository.Payout;
import com.familyx.mercbot.payout.PayoutRepository.Settlement;
import com.familyx.mercbot.settings.BotSettings;
import com.fasterxml.jackson.databind.JsonNode;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 4c (scope doc 6.6): payout strings per faction, credits matched from faction news,
 * and Capacity X's settlement with the other family factions (Owed → Sent → Received).
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    /** Credits in faction news are only matched from shortly before the string was generated. */
    private static final long NEWS_SLACK_SECONDS = 120;

    private final PayoutRepository repo;
    private final PayoutEmbeds embeds;
    private final FamilyFactionRepository factions;
    private final MercRepository mercs;
    private final BotSettings settings;
    private final Dms dms;
    private final AuditService audit;
    private final MessageUpdater updater;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;

    public PayoutService(PayoutRepository repo, PayoutEmbeds embeds, FamilyFactionRepository factions,
                         MercRepository mercs, BotSettings settings, Dms dms, AuditService audit,
                         MessageUpdater updater, Layout layout, ObjectProvider<JDA> jda) {
        this.repo = repo;
        this.embeds = embeds;
        this.factions = factions;
        this.mercs = mercs;
        this.settings = settings;
        this.dms = dms;
        this.audit = audit;
        this.updater = updater;
        this.layout = layout;
        this.jda = jda;
    }

    public long pool() {
        return settings.paymentFactionId();
    }

    // ------------------------------------------------------------------ payout strings

    /** The faction's open payout string, or a new one from everything ready. Empty when nothing is owed. */
    @Transactional
    public Optional<Payout> generate(long faction, long by) {
        Optional<Payout> open = repo.openPayout(faction);
        if (open.isPresent()) {
            return open;
        }
        Optional<Payout> made = repo.createPayout(faction, pool(), by);
        made.ifPresent(p -> audit.log(by, "PAYOUT_GENERATED", "payout:" + p.id(), null, Map.of("total", p.total()),
                "Generated payout #" + p.id() + " for **" + embeds.name(faction) + "**: " + Money.full(p.total())));
        refresh(faction);
        return made;
    }

    public String payoutString(long payoutId) {
        return Periods.payoutString(repo.lines(payoutId).stream().filter(l -> l.creditedAt() == null)
                .map(l -> new long[] {l.mercId(), l.amount()}).toList());
    }

    @Transactional
    public void cancel(Payout p, long by) {
        repo.cancel(p.id());
        audit.log(by, "PAYOUT_CANCELLED", "payout:" + p.id(), null, null,
                "Cancelled payout #" + p.id() + " for **" + embeds.name(p.factionId()) + "**; uncredited shares are owed again");
        refresh(p.factionId());
    }

    /** A leader marks lines credited by hand (the bot couldn't see them in faction news). */
    public int creditByHand(Payout p, List<Long> mercIds, long by) {
        int n = 0;
        for (long mercId : mercIds) {
            Optional<Line> line = repo.lines(p.id()).stream().filter(l -> l.mercId() == mercId).findFirst();
            if (line.isPresent() && repo.credit(p.id(), mercId, null, by)) {
                n++;
                credited(p, line.get(), by);
            }
        }
        refresh(p.factionId());
        return n;
    }

    /**
     * Matches "X increased &lt;merc&gt;'s money balance by $N" in a faction's news against its open
     * payout string: same merc, exact amount, not before the string was made, each entry used once.
     */
    public void creditFromNews(long faction, JsonNode news) {
        Optional<Payout> open = repo.openPayout(faction);
        if (open.isEmpty()) {
            return;
        }
        Payout p = open.get();
        Map<Long, Line> waiting = new HashMap<>();
        repo.lines(p.id()).stream().filter(l -> l.creditedAt() == null).forEach(l -> waiting.put(l.mercId(), l));
        boolean any = false;
        for (JsonNode n : news.path("news")) {
            String id = n.path("id").asText(null);
            long at = n.path("timestamp").asLong();
            if (id == null || waiting.isEmpty() || at < p.createdAt().getEpochSecond() - NEWS_SLACK_SECONDS) {
                continue;
            }
            var parsed = NewsReconciler.parse(n.path("text").asText(""));
            if (parsed.isEmpty() || !"increased".equals(parsed.get().direction())) {
                continue;
            }
            Line line = waiting.get(parsed.get().accountId());
            if (line == null || line.amount() != parsed.get().amount() || repo.newsUsed(id)) {
                continue;
            }
            if (repo.credit(p.id(), line.mercId(), id, null)) {
                waiting.remove(line.mercId());
                credited(p, line, null);
                any = true;
            }
        }
        if (any) {
            refresh(faction);
        }
    }

    private void credited(Payout p, Line line, Long by) {
        String faction = embeds.name(p.factionId());
        PayoutRepository.Refs refs = repo.refs(p.id(), line.mercId());
        audit.log(by, "SHARE_CREDITED", "payout:" + p.id(), null, Map.of("merc", line.mercId(), "amount", line.amount()),
                "Credited " + Money.full(line.amount()) + " to **" + PayoutEmbeds.who(line) + "** ("
                        + faction + (by == null ? ", seen in faction news)" : ", marked by hand)"));
        mercs.findByTorn(line.mercId()).filter(m -> m.dmPref() != DmPref.OFF).ifPresent(m ->
                dms.send(m.discordId(), new EmbedBuilder().setColor(Panels.GOOD)
                        .setAuthor("CREDITED · " + faction.toUpperCase())
                        .setTitle("💰  " + Money.full(line.amount()) + " added to your faction balance")
                        .setDescription(refs.hits() + (refs.hits() == 1 ? " hit" : " hits")
                                + (refs.refs().isEmpty() ? "" : " · " + String.join(", ", refs.refs())))
                        .build()));
    }

    // ------------------------------------------------------------------ settlement

    /** An invoice was paid in full and its shares are now owed. */
    public void sharesOwed() {
        if (Periods.PER_CONTRACT.equals(settings.settlementPeriod())) {
            settleContracts();
        }
        refreshAll();
    }

    /**
     * Hourly: closes the settlement period that has ended (weekly or monthly), or settles any
     * contract still unsettled (per contract). Safe to repeat, so a missed run is caught up later.
     */
    @Scheduled(cron = "0 10 * * * *", zone = "UTC")
    public void settle() {
        try {
            String mode = settings.settlementPeriod();
            Optional<Periods.Period> period = Periods.lastClosed(mode, Instant.now());
            if (period.isEmpty()) {
                settleContracts();
            } else {
                for (FamilyFaction f : factions.findActive()) {
                    if (f.tornFactionId() == pool()) {
                        continue;
                    }
                    repo.settlePeriod(f.tornFactionId(), period.get().start(), period.get().end())
                            .ifPresent(id -> log.info("Settlement {} for {} ({})", id, f.name(),
                                    Periods.label(period.get().start(), period.get().end())));
                }
            }
            refreshAll();
        } catch (Exception e) {
            log.warn("Settlement run failed: {}", e.getMessage());
        }
    }

    private void settleContracts() {
        Instant now = Instant.now();
        for (PayoutRepository.Unsettled u : repo.unsettledByContract(pool())) {
            repo.settleContract(u.factionId(), u.contractId(), now, now);
        }
    }

    /** Capacity X has sent the money: ping the faction's leaders in the payouts channel. */
    public boolean markSent(Settlement s, long by) {
        if (!repo.markSent(s.id(), by)) {
            return false;
        }
        String faction = embeds.name(s.factionId());
        audit.log(by, "SETTLEMENT_SENT", "settlement:" + s.id(), Map.of("status", "OWED"), Map.of("status", "SENT"),
                "Marked " + Money.full(s.total()) + " sent to **" + faction + "** (" + embeds.period(s) + ")");
        Long role = factions.find(s.factionId()).map(FamilyFaction::leaderRoleId).orElse(null);
        JDA api = jda.getIfAvailable();
        TextChannel ch = api == null ? null : layout.get(Layout.CH_PAYOUTS).map(api::getTextChannelById).orElse(null);
        if (ch != null) {
            try {
                long id = ch.sendMessage((role == null ? "" : "<@&" + role + "> ") + "Capacity X has sent **"
                                + Money.full(s.total()) + "** for " + embeds.period(s) + ". Press **Mark received** in "
                                + faction + "'s payouts once it arrives.")
                        .setAllowedMentions(EnumSet.of(Message.MentionType.ROLE)).complete().getIdLong();
                repo.setPing(s.id(), id);
            } catch (Exception e) {
                log.warn("Couldn't post the settlement ping for {}: {}", faction, e.getMessage());
            }
        }
        refresh(pool());
        refresh(s.factionId());
        return true;
    }

    public boolean markReceived(Settlement s, long by) {
        if (!repo.markReceived(s.id(), by)) {
            return false;
        }
        audit.log(by, "SETTLEMENT_RECEIVED", "settlement:" + s.id(), Map.of("status", "SENT"), Map.of("status", "RECEIVED"),
                "**" + embeds.name(s.factionId()) + "** received " + Money.full(s.total()) + " (" + embeds.period(s) + ")");
        if (s.pingMessageId() != null) {
            JDA api = jda.getIfAvailable();
            if (api != null) {
                layout.get(Layout.CH_PAYOUTS).map(api::getTextChannelById).ifPresent(ch ->
                        ch.deleteMessageById(s.pingMessageId()).queue(null, ignored -> { }));
            }
        }
        refresh(pool());
        refresh(s.factionId());
        return true;
    }

    // ------------------------------------------------------------------ embeds

    /** Edits one faction's embed in the payouts channel (debounced; skipped when nothing changed). */
    public void refresh(long faction) {
        updater.request("payouts:" + faction, () -> {
            Optional<Long> channel = layout.get(Layout.CH_PAYOUTS);
            Optional<Long> message = layout.get(Layout.payoutsMessage(faction));
            Optional<FamilyFaction> f = factions.find(faction);
            if (channel.isEmpty() || message.isEmpty() || f.isEmpty() || !f.get().active()) {
                return Optional.empty();
            }
            PayoutEmbeds.Look look = embeds.embed(f.get());
            return Optional.of(new MessageUpdater.Edit(channel.get(), message.get(),
                    new MessageEditBuilder().setEmbeds(look.embed()).setComponents(look.rows()).build()));
        });
    }

    /** Every faction's embed; also every 10 minutes, so pending and credited totals stay current. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void refreshAll() {
        factions.findActive().forEach(f -> refresh(f.tornFactionId()));
    }

    public List<Payout> openPayouts() {
        return repo.openPayouts();
    }
}
