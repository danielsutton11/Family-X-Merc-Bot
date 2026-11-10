package com.familyx.mercbot.contract;

import com.familyx.mercbot.board.BoardRules;
import com.familyx.mercbot.board.BoardRules.Verdict;
import com.familyx.mercbot.board.TargetRepository;
import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.PanelHashes;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.Conditions;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.ContractRepository.HitStats;
import com.familyx.mercbot.request.Pricing;
import com.familyx.mercbot.request.RequestRepositories;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The requester's DM control panel (scope doc 4.4): one DM per contract that keeps itself up to
 * date with progress, the bill so far and how many targets are on the board, with Pause/Resume
 * and the Manage menu.
 */
@Service
public class ControlPanelService {

    private static final Logger log = LoggerFactory.getLogger(ControlPanelService.class);
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
    private static final Color GREY = new Color(0x6D6F78);

    private final ObjectProvider<JDA> jda;
    private final ContractRepository contracts;
    private final TargetRepository targets;
    private final TierRepository tiers;
    private final RequestRepositories requesters;
    private final PanelHashes hashes;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    private final com.familyx.mercbot.hit.HitRepository hitLog;
    private final com.familyx.mercbot.payment.InvoiceRepository invoiceLog;

    public ControlPanelService(ObjectProvider<JDA> jda, ContractRepository contracts, TargetRepository targets,
                               TierRepository tiers, RequestRepositories requesters, PanelHashes hashes,
                               com.familyx.mercbot.hit.HitRepository hitLog,
                               com.familyx.mercbot.payment.InvoiceRepository invoiceLog) {
        this.hitLog = hitLog;
        this.invoiceLog = invoiceLog;
        this.jda = jda;
        this.contracts = contracts;
        this.targets = targets;
        this.tiers = tiers;
        this.requesters = requesters;
        this.hashes = hashes;
    }

    /** Sends the panel for a newly placed contract. */
    public void create(long contractId, long requesterDiscordId) {
        Optional<Contract> c = contracts.find(contractId);
        JDA api = jda.getIfAvailable();
        if (c.isEmpty() || api == null) {
            return;
        }
        MessageEditData data = render(c.get());
        api.openPrivateChannelById(requesterDiscordId)
                .flatMap(ch -> ch.sendMessage(MessageCreateBuilder.fromEditData(data).build()))
                .queue(m -> {
                    contracts.setDmPanel(contractId, m.getChannel().getIdLong(), m.getIdLong());
                    hashes.remember(m.getIdLong(), data);
                }, e -> log.info("Couldn't DM the control panel for contract {}: {}", contractId, e.getMessage()));
    }

    /** Redraws a contract's panel within a couple of seconds (several calls in a row become one edit). */
    public void refresh(long contractId) {
        if (pending.add(contractId)) {
            scheduler.schedule(() -> {
                pending.remove(contractId);
                refreshNow(contractId);
            }, 1500, TimeUnit.MILLISECONDS);
        }
    }

    /** Redraws now even if nothing changed (resets the Manage menu after it's been used). */
    public void forceRefresh(long contractId) {
        Optional<Contract> c = contracts.find(contractId);
        c.map(Contract::dmPanelMessageId).ifPresent(id -> hashes.remember(id,
                new MessageEditBuilder().setContent("·").build()));
        refreshNow(contractId);
    }

    public void refreshNow(long contractId) {
        try {
            Optional<Contract> found = contracts.find(contractId);
            JDA api = jda.getIfAvailable();
            if (found.isEmpty() || api == null || found.get().dmPanelMessageId() == null) {
                return;
            }
            Contract c = found.get();
            MessageEditData data = render(c);
            if (!hashes.changed(c.dmPanelMessageId(), data)) {
                return;
            }
            long requester = requesters.requester(c.requesterId()).map(RequestRepositories.Requester::discordId).orElse(0L);
            if (requester == 0) {
                return;
            }
            api.openPrivateChannelById(requester)
                    .flatMap(ch -> ch.editMessageById(c.dmPanelMessageId(), data))
                    .queue(m -> hashes.remember(c.dmPanelMessageId(), data),
                            e -> log.info("Couldn't update the control panel for {}: {}", c.ref(), e.getMessage()));
        } catch (Exception e) {
            log.warn("Control panel refresh for {} failed: {}", contractId, e.getMessage());
        }
    }

    /** The same view for leaders: the panel's embed, with the priority and who the requester is. */
    public net.dv8tion.jda.api.entities.MessageEmbed leaderView(Contract c, String priorityLabel) {
        var embed = render(c).getEmbeds().get(0);
        EmbedBuilder eb = new EmbedBuilder(embed);
        eb.setAuthor(embed.getAuthor() == null ? c.ref() : embed.getAuthor().getName() + " · PRIORITY "
                + priorityLabel.toUpperCase());
        var r = requesters.requester(c.requesterId());
        eb.addField("👤  Requester", r.map(x -> (x.name() == null ? "Player" : x.name()) + " [" + x.tornId() + "]")
                .orElse("Unknown") + "\n-# " + (c.myFaction() ? "Faction request" : "Personal request") + " · placed "
                + SHOW.format(c.createdAt()) + " UTC", false);
        return eb.build();
    }

    // ------------------------------------------------------------------ drawing

    MessageEditData render(Contract c) {
        Instant now = Instant.now();
        Tier tier = tiers.find(c.tierId()).orElse(null);
        String tierName = tier == null ? "Tier" : tier.name();
        boolean scheduled = "ACTIVE".equals(c.status()) && c.startsAt() != null && c.startsAt().isAfter(now);

        String state;
        String icon;
        Color colour;
        switch (c.status()) {
            case "ACTIVE" -> {
                state = scheduled ? "STARTS " + SHOW.format(c.startsAt()).toUpperCase() + " UTC" : "LIVE";
                icon = scheduled ? "⏳" : "🟢";
                colour = scheduled ? Panels.PRIVATE : Panels.GOOD;
            }
            case "PAUSED" -> {
                state = "PAUSED";
                icon = "⏸️";
                colour = Panels.WARN;
            }
            case "PENDING_APPROVAL" -> {
                state = "WAITING FOR A LEADER";
                icon = "⏳";
                colour = Panels.PRIVATE;
            }
            case "COMPLETED" -> {
                state = "COMPLETED";
                icon = "✅";
                colour = GREY;
            }
            case "ENDED" -> {
                state = "ENDED EARLY";
                icon = "⏹️";
                colour = GREY;
            }
            case "REJECTED" -> {
                state = "NOT APPROVED";
                icon = "✖️";
                colour = GREY;
            }
            default -> {
                state = "CANCELLED";
                icon = "✖️";
                colour = GREY;
            }
        }

        String target = c.mercMe() ? "Merc me"
                : c.factionTarget() ? (c.targetFactionName() == null ? "Faction" : c.targetFactionName())
                : targetCountLabel(c);
        StringBuilder desc = new StringBuilder(c.attack().label).append(" · ");
        if (c.mode() == Mode.HITS) {
            desc.append(c.hitsPerTarget()).append(c.hitsPerTarget() != null && c.hitsPerTarget() == 1 ? " hit" : " hits")
                    .append(c.mercMe() ? "" : " per target");
        } else {
            desc.append(c.startsAt() == null || !c.startsAt().isAfter(now) ? "from " + SHOW.format(c.createdAt())
                    : "from " + SHOW.format(c.startsAt())).append(" UTC, ")
                    .append(c.endsAt() == null ? "no end" : "until " + SHOW.format(c.endsAt()) + " UTC");
        }

        EmbedBuilder eb = new EmbedBuilder().setColor(colour).setAuthor("CONTRACT " + c.ref() + " · " + state)
                .setTitle(icon + "  " + target + " · " + tierName).setDescription(desc + Panels.GAP);

        // targets
        List<BoardTarget> list = targets.forContract(c.id());
        if (c.running(now) || "PAUSED".equals(c.status())) {
            int onBoard = 0;
            int hidden = 0;
            int notYet = 0;
            for (BoardTarget t : list) {
                Verdict v = BoardRules.verdict(t, now);
                if (v == Verdict.SHOW) {
                    onBoard++;
                } else if (v.hiddenByConditions()) {
                    hidden++;
                } else if (v == Verdict.NOT_YET) {
                    notYet++;
                }
            }
            eb.addField("🎯  Targets", "PAUSED".equals(c.status()) ? "Off the boards\n-# while paused" + Panels.GAP
                    : onBoard + " on the board\n-# " + hidden + " hidden · " + notYet + " in hospital" + Panels.GAP, true);
        } else {
            eb.addField("🎯  Targets", list.size() + (list.size() == 1 ? " target" : " targets") + Panels.GAP, true);
        }

        // hits and bill
        HitStats stats = contracts.hitStats(c.id());
        var breakdown = hitLog.breakdown(c.id());
        String byTier = breakdown.byTier().isEmpty() ? "billed as they land" : breakdown.byTier().entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(" · "));
        if (c.mode() == Mode.HITS) {
            int[] progress = contracts.hitProgress(c.id());
            eb.addField("✅  Hits", progress[0] + " of " + progress[1] + "\n-# " + byTier + Panels.GAP, true);
        } else {
            eb.addField("✅  Hits", stats.accepted() + " so far\n-# " + byTier + Panels.GAP, true);
        }
        long perHit = Pricing.discounted(c.unitPrice(), c.discountPct());
        eb.addField("💰  Bill so far", Money.full(stats.billed()) + "\n-# " + Money.full(perHit) + " per hit"
                + Panels.GAP, true);
        var invoiceList = invoiceLog.forContract(c.id());
        if (!invoiceList.isEmpty()) {
            long total = invoiceList.stream().mapToLong(i -> i.total()).sum();
            long paid = invoiceList.stream().mapToLong(i -> i.amountPaid()).sum();
            var latest = invoiceList.get(invoiceList.size() - 1);
            String text = paid >= total ? "✅ Paid\n-# " + Money.full(total)
                    : Money.full(total - paid) + " to pay\n-# due " + SHOW.format(latest.dueAt()) + " UTC"
                    + (latest.payeeName() == null ? "" : " · pay " + latest.payeeName());
            eb.addField("🧾  Invoice", text + Panels.GAP, true);
        } else if (("COMPLETED".equals(c.status()) || "ENDED".equals(c.status())) && stats.accepted() == 0) {
            eb.addField("🧾  Invoice", "Nothing to pay\n-# no hits were made" + Panels.GAP, true);
        }
        if (breakdown.lastAt() != null) {
            eb.addField("", "-# Last hit: " + breakdown.lastTarget() + " · " + breakdown.lastTier() + " · "
                    + SHOW.format(breakdown.lastAt()) + " UTC", false);
        }

        if (!c.mercMe()) {
            Conditions k = c.conditions();
            eb.addField("🎛️  Conditions", "Status: **" + k.statusLabel() + "**\nRevivable: **"
                    + (k.allowRevivable() ? "Hit" : "Don't hit") + "**"
                    + (c.attack() == Attack.HOSPITALIZE ? "\nEarly discharge: **"
                    + (k.allowEarlyDischarge() ? "Hit" : "Skip") + "**" : ""), false);
        }
        if (c.notes() != null && !c.notes().isBlank()) {
            eb.addField("📝  Notes", c.notes(), false);
        }

        List<ActionRow> rows = new ArrayList<>();
        if (c.open()) {
            StringSelectMenu.Builder manage = StringSelectMenu.create(CustomId.of("cp", "manage", c.id()))
                    .setPlaceholder("Manage…");
            boolean pendingApproval = "PENDING_APPROVAL".equals(c.status());
            if (!pendingApproval) {
                manage.addOption("Edit conditions", "edit", "Status, revivable, early discharge, hits or end time, notes",
                        Emoji.fromUnicode("🎛️"));
                if (!c.mercMe()) {
                    manage.addOption("Hit specific user", "override", "Allow one hit on a user the conditions hide",
                            Emoji.fromUnicode("🎯"));
                }
                manage.addOption("End now", "end", "Close the contract and invoice the hits made so far",
                        Emoji.fromUnicode("⏹️"));
            }
            if (stats.accepted() == 0) {
                manage.addOption("Cancel", "cancel", "Only before the first accepted hit; nothing is billed",
                        Emoji.fromUnicode("✖️"));
            }
            rows.add(ActionRow.of(manage.build()));
            List<Button> buttons = new ArrayList<>();
            if ("ACTIVE".equals(c.status())) {
                buttons.add(Button.secondary(CustomId.of("cp", "pause", c.id()), "Pause").withEmoji(Emoji.fromUnicode("⏸️")));
            } else if ("PAUSED".equals(c.status())) {
                buttons.add(Button.success(CustomId.of("cp", "resume", c.id()), "Resume").withEmoji(Emoji.fromUnicode("▶️")));
            }
            buttons.add(Button.secondary(CustomId.of("msg", "write", c.id()), "Message leaders")
                    .withEmoji(Emoji.fromUnicode("✉️")));
            rows.add(ActionRow.of(buttons));
        } else if (com.familyx.mercbot.inbox.Messaging.canMessage(c, java.time.Instant.now())) {
            // Finished contracts keep Message leaders for a while (scope doc 4.5).
            rows.add(ActionRow.of(Button.secondary(CustomId.of("msg", "write", c.id()), "Message leaders")
                    .withEmoji(Emoji.fromUnicode("✉️"))));
        }
        return new MessageEditBuilder().setReplace(true).setEmbeds(eb.build()).setComponents(rows).build();
    }

    private String targetCountLabel(Contract c) {
        int n = contracts.targetCount(c.id());
        return n == 1 ? contracts.firstTargetName(c.id()).orElse("1 player") : n + " players";
    }
}
