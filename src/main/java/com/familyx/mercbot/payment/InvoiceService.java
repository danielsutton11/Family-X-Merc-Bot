package com.familyx.mercbot.payment;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.contract.ControlPanelService;
import com.familyx.mercbot.contract.ResultsFeed;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.DmPref;
import com.familyx.mercbot.payment.InvoiceRepository.Deposit;
import com.familyx.mercbot.payment.InvoiceRepository.Invoice;
import com.familyx.mercbot.payment.InvoiceRepository.Line;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.RequestRepositories;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.settings.BotSettings.Payee;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Invoices and payments (scope doc 4.6, 6.3, 6.6): the invoice DM when a contract ends (and an
 * interim one each settlement period for contracts with no end), cards for leaders when a
 * matching deposit shows in faction news or an invoice goes overdue, and shares moving from
 * Pending to Owed once an invoice is paid in full.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
    private static final String TRADE = "https://www.torn.com/trade.php#step=start&userID=";
    private static final String ATTACK_LOG = "https://www.torn.com/page.php?sid=attackLog&ID=";
    private static final int LINES_SHOWN = 3;

    private final InvoiceRepository invoices;
    private final ContractRepository contracts;
    private final RequestRepositories requesters;
    private final MercRepository mercs;
    private final BotSettings settings;
    private final Dms dms;
    private final ResultsFeed feed;
    private final ControlPanelService panels;
    private final AuditService audit;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;

    private final ObjectProvider<com.familyx.mercbot.payout.PayoutService> payouts;
    private final ObjectProvider<com.familyx.mercbot.reputation.ReputationService> reputation;

    public InvoiceService(InvoiceRepository invoices, ContractRepository contracts, RequestRepositories requesters,
                          MercRepository mercs, BotSettings settings, Dms dms, ResultsFeed feed,
                          ControlPanelService panels, AuditService audit, Layout layout, ObjectProvider<JDA> jda,
                          ObjectProvider<com.familyx.mercbot.payout.PayoutService> payouts,
                          ObjectProvider<com.familyx.mercbot.reputation.ReputationService> reputation) {
        this.reputation = reputation;
        this.payouts = payouts;
        this.invoices = invoices;
        this.contracts = contracts;
        this.requesters = requesters;
        this.mercs = mercs;
        this.settings = settings;
        this.dms = dms;
        this.feed = feed;
        this.panels = panels;
        this.audit = audit;
        this.layout = layout;
        this.jda = jda;
    }

    // ------------------------------------------------------------------ issuing

    /** The contract has finished: invoice every hit not yet invoiced. Nothing to pay → no invoice. */
    public void issueFinal(Contract c) {
        issue(c, "FINAL", null, Instant.now());
        try {
            reputation.getObject().completedIfDone(c.id()); // nothing left to pay: +completed (scope doc 6.5)
        } catch (Exception e) {
            log.warn("Reputation check for {} failed: {}", c.ref(), e.getMessage());
        }
    }

    /** Interim invoices for contracts with no end, at the close of each settlement period (scope doc 4.6). */
    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    public void periodClose() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean close = "MONTHLY".equals(settings.settlementPeriod()) ? today.getDayOfMonth() == 1
                : today.getDayOfWeek() == DayOfWeek.MONDAY;
        if (!close) {
            return;
        }
        Instant now = Instant.now();
        for (long id : invoices.openEndedWithUninvoiced()) {
            contracts.find(id).ifPresent(c -> issue(c, "INTERIM", now.minus(Duration.ofDays(
                    "MONTHLY".equals(settings.settlementPeriod()) ? 31 : 7)), now));
        }
    }

    private synchronized void issue(Contract c, String kind, Instant periodStart, Instant periodEnd) {
        List<Long> hitIds = invoices.uninvoicedHits(c.id());
        if (hitIds.isEmpty()) {
            panels.refresh(c.id());
            return;
        }
        long total = invoices.sumBilled(hitIds);
        long discount = invoices.discountOn(hitIds, c.id());
        Optional<Payee> payee = settings.nextPayee();
        Instant due = Instant.now().plus(Duration.ofHours(settings.paymentDueHours()));
        long id = invoices.create(c.id(), kind, total, discount, payee.map(Payee::tornId).orElse(null),
                payee.map(Payee::name).orElse(null), due, periodStart, periodEnd, hitIds);
        Invoice inv = invoices.find(id).orElseThrow();
        List<Line> lines = invoices.lines(id);
        requesters.requester(c.requesterId()).ifPresent(r -> {
            MessageEmbed embed = invoiceEmbed(c, inv, lines);
            FileUpload file = FileUpload.fromData(csv(lines).getBytes(StandardCharsets.UTF_8),
                    c.ref() + ("INTERIM".equals(inv.kind()) ? "-interim-" + inv.id() : "") + "-invoice.csv");
            Button message = Button.secondary(CustomId.of("msg", "write", c.id()), "Message leaders")
                    .withEmoji(Emoji.fromUnicode("✉️"));
            if (payee.isPresent()) {
                dms.send(r.discordId(), embed, file, ActionRow.of(Button.link(TRADE + payee.get().tornId(),
                        "Start trade with " + payee.get().name() + " ↗").withEmoji(Emoji.fromUnicode("💸")), message));
            } else {
                dms.send(r.discordId(), embed, file, ActionRow.of(message));
            }
        });
        if (payee.isEmpty()) {
            log.warn("Invoice {} for {} has no payee: set payees in Settings → Payments", id, c.ref());
        }
        audit.log(null, "INVOICE_ISSUED", "invoice:" + id, null, Map.of("total", total, "kind", kind),
                ("INTERIM".equals(kind) ? "Interim invoice" : "Invoice") + " for **" + c.ref() + "**: " + Money.full(total));
        panels.refresh(c.id());
    }

    private MessageEmbed invoiceEmbed(Contract c, Invoice inv, List<Line> lines) {
        Map<String, Long> byTier = new java.util.LinkedHashMap<>();
        lines.forEach(l -> byTier.merge(l.tier() == null ? "?" : l.tier(), 1L, Long::sum));
        String tiers = byTier.entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
                .collect(java.util.stream.Collectors.joining(" · "));
        String how = inv.payeeTornId() == null
                ? "A Capacity X leader will tell you who to pay."
                : "Start a trade with **" + inv.payeeName() + " [" + inv.payeeTornId() + "]** using the button below "
                + "and put **exactly " + Money.full(inv.total()) + "** in it.\n**Put `" + c.ref()
                + "` in the trade description** so we know which contract it's for.";
        StringBuilder preview = new StringBuilder("```\n");
        lines.stream().limit(LINES_SHOWN).forEach(l -> preview.append(line(l)).append('\n'));
        if (lines.size() > LINES_SHOWN) {
            preview.append("… ").append(lines.size() - LINES_SHOWN).append(" more in the spreadsheet below\n");
        }
        preview.append("```");
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.PRIVATE)
                .setAuthor(("INTERIM".equals(inv.kind()) ? "INTERIM INVOICE · " : "INVOICE · ") + c.ref() + " · "
                        + feed.targetName(c, contracts.targetCount(c.id())).toUpperCase())
                .setTitle("🧾  " + Money.full(inv.total()) + " to pay")
                .addField("✅  Hits", lines.size() + "\n-# " + tiers + Panels.GAP, true);
        if (inv.discountTotal() > 0) {
            eb.addField("🏷️  Discount", c.discountPct().stripTrailingZeros().toPlainString() + "% off "
                    + "\n-# −" + Money.full(inv.discountTotal()) + Panels.GAP, true);
        }
        eb.addField("📅  Due", WHEN.format(inv.dueAt()) + " UTC\n-# within " + settings.paymentDueHours() + " h"
                        + Panels.GAP, true)
                .addField("💸  How to pay", how + "\n-# Exact amounts are matched automatically.", false)
                .addField("", preview.toString(), false);
        return eb.build();
    }

    private static String line(Line l) {
        return WHEN.format(l.started()) + " · " + l.target() + " · " + (l.tier() == null ? "?" : l.tier())
                + (l.downgrade() == null ? "" : " (" + l.downgrade() + ")")
                + (l.hospSeconds() == null ? "" : " · " + hosp(l.hospSeconds())) + " · " + l.merc();
    }

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    /** Every hit on the invoice, one row each, for opening in a spreadsheet. */
    static String csv(List<Line> lines) {
        StringBuilder sb = new StringBuilder(
                "Time (UTC),Target,Target ID,Billed tier,Why billed lower,Hospital time,Merc,Amount,Attack log\n");
        for (Line l : lines) {
            sb.append(cell(CSV_TIME.format(l.started()))).append(',')
                    .append(cell(l.target())).append(',')
                    .append(l.targetId()).append(',')
                    .append(cell(l.tier() == null ? "" : l.tier())).append(',')
                    .append(cell(l.downgrade() == null ? "" : l.downgrade())).append(',')
                    .append(cell(l.hospSeconds() == null ? "" : hosp(l.hospSeconds()))).append(',')
                    .append(cell(l.merc())).append(',')
                    .append(l.billed()).append(',')
                    .append(cell(l.attackCode() == null ? "" : ATTACK_LOG + l.attackCode())).append('\n');
        }
        return sb.toString();
    }

    private static String cell(String v) {
        if (v == null) {
            return "";
        }
        boolean quote = v.contains(",") || v.contains("\"") || v.contains("\n");
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    // ------------------------------------------------------------------ deposits and payments

    /** A deposit into the mercs pool appeared in faction news: card for leaders if it matches an invoice. */
    public void depositSpotted(Deposit d) {
        List<Invoice> matches = invoices.openWithDue(d.amount());
        String when = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(d.at()) + " UTC";
        if (matches.isEmpty()) {
            unmatched(d.newsId(), d.amount(), "By " + d.actorName() + " at " + when + ".");
            return;
        }
        if (matches.size() > 1) {
            // Several invoices are for exactly this amount: the trade description says which.
            Card card = pickCard(d.newsId(), d.amount(), matches, "PAYMENT SPOTTED",
                    "By " + d.actorName() + " at " + when + ". " + matches.size()
                            + " open invoices are for exactly this amount. Pick the one in the trade description.");
            invoices.linkDeposit(d.newsId(), null, postCard(card.embed(), card.rows()));
            return;
        }
        Invoice inv = matches.get(0);
        Contract c = contracts.find(inv.contractId()).orElse(null);
        if (c == null) {
            return;
        }
        Long card = postCard(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("PAYMENT SPOTTED")
                        .setTitle("💰  " + c.ref() + " · " + Money.full(d.amount()))
                        .setDescription(d.actorName() + " added **" + Money.full(d.amount()) + "** to **"
                                + settings.poolAccountName() + "** at " + when + ". That matches " + c.ref()
                                + "'s invoice exactly.")
                        .build(),
                ActionRow.of(Button.success(CustomId.of("pay", "confirm", d.newsId()), "Confirm paid")
                                .withEmoji(Emoji.fromUnicode("✅")),
                        Button.secondary(CustomId.of("pay", "other", d.newsId()), "Not this invoice")
                                .withEmoji(Emoji.fromUnicode("✖️"))));
        invoices.linkDeposit(d.newsId(), inv.id(), card);
    }

    /** A card's look: the embed and its rows. */
    public record Card(MessageEmbed embed, ActionRow[] rows) { }

    /**
     * A deposit that doesn't exactly match any invoice (a part payment, or a mix): a card where a
     * Capacity X leader picks the contract it's for (the requester puts the reference in the trade).
     */
    private void unmatched(String newsId, long amount, String by) {
        Card card = otherInvoices(newsId, amount, by);
        if (card != null) {
            invoices.linkDeposit(newsId, null, postCard(card.embed(), card.rows()));
        }
    }

    /** The "pick the contract" card over every open invoice; null when nothing is owed. */
    public Card otherInvoices(String newsId, long amount, String by) {
        List<Invoice> open = invoices.open(25);
        if (open.isEmpty()) {
            return null; // nothing is owed, so it isn't an invoice payment
        }
        return pickCard(newsId, amount, open, "DEPOSIT TO MATCH", (by == null ? "" : by + " ")
                + "It doesn't match an open invoice exactly. Pick the contract it's for: the requester was asked "
                + "to put the reference in the trade description.");
    }

    private Card pickCard(String newsId, long amount, List<Invoice> list, String author, String text) {
        net.dv8tion.jda.api.components.selections.StringSelectMenu.Builder pick =
                net.dv8tion.jda.api.components.selections.StringSelectMenu.create(CustomId.of("pay", "pick", newsId))
                        .setPlaceholder("Pick the contract it's for…");
        for (Invoice inv : list) {
            contracts.find(inv.contractId()).ifPresent(c -> pick.addOption(
                    trim(c.ref() + " · " + feed.targetName(c, contracts.targetCount(c.id())), 100),
                    Long.toString(inv.id()),
                    trim(Money.full(inv.due()) + " due" + ("PARTIAL".equals(inv.status()) ? " (part paid)" : "")
                            + " · since " + WHEN.format(inv.createdAt()) + " UTC", 100),
                    Emoji.fromUnicode("🧾")));
        }
        return new Card(new EmbedBuilder().setColor(Panels.LEADER).setAuthor(author)
                .setTitle("💰  " + Money.full(amount) + " added to " + settings.poolAccountName())
                .setDescription(text).build(),
                new ActionRow[] {ActionRow.of(pick.build()),
                        ActionRow.of(Button.secondary(CustomId.of("pay", "not", newsId), "Not an invoice payment")
                                .withEmoji(Emoji.fromUnicode("✖️")))});
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Records a payment (from a spotted deposit or typed in by a leader). */
    public synchronized Invoice pay(long invoiceId, long amount, String newsId, long leader) {
        Invoice before = invoices.find(invoiceId).orElseThrow();
        Invoice inv = invoices.pay(invoiceId, amount, newsId, leader);
        Contract c = contracts.find(inv.contractId()).orElseThrow();
        audit.log(leader, "PAYMENT_RECORDED", "invoice:" + invoiceId, Map.of("paid", before.amountPaid()),
                Map.of("paid", inv.amountPaid(), "status", inv.status()),
                "Recorded " + Money.full(amount) + " for **" + c.ref() + "** (" + inv.status().toLowerCase() + ")");
        String author = "INVOICE · " + c.ref() + " · " + feed.targetName(c, contracts.targetCount(c.id())).toUpperCase();
        requesters.requester(c.requesterId()).ifPresent(r -> dms.send(r.discordId(), "PAID".equals(inv.status())
                ? new EmbedBuilder().setColor(Panels.GOOD).setAuthor(author).setTitle("✅  Paid in full · thank you")
                .setDescription(Money.full(inv.amountPaid()) + " received. Nothing more to pay on this invoice.").build()
                : new EmbedBuilder().setColor(Panels.WARN).setAuthor(author).setTitle("🧾  Part payment received")
                .setDescription(Money.full(inv.amountPaid()) + " of " + Money.full(inv.total()) + " received. **"
                        + Money.full(inv.due()) + "** still to pay" + (inv.payeeName() == null ? "."
                        : " to " + inv.payeeName() + " [" + inv.payeeTornId() + "].")).build()));
        if ("PAID".equals(inv.status())) {
            if (!"PAID".equals(before.status())) {
                reputation.getObject().paid(c, inv);
            }
            sharesNowOwed(c, inv);
            if (inv.overdueCardId() != null) {
                deleteCard(inv.overdueCardId());
            }
        }
        panels.refresh(c.id());
        return inv;
    }

    private void sharesNowOwed(Contract c, Invoice inv) {
        Map<Long, Long> owed = invoices.sharesOwed(inv.id());
        String target = feed.targetName(c, contracts.targetCount(c.id())).toUpperCase();
        owed.forEach((mercId, amount) -> mercs.findByTorn(mercId).filter(m -> m.dmPref() != DmPref.OFF)
                .ifPresent(m -> {
                    int n = invoices.hitsOn(inv.id(), mercId);
                    dms.send(m.discordId(), new EmbedBuilder().setColor(Panels.GOOD)
                            .setAuthor(c.ref() + " · " + target + " · PAID")
                            .setTitle("💰  " + Money.full(amount) + " now owed to you")
                            .setDescription("The requester has paid. Your shares from " + n + (n == 1 ? " hit" : " hits")
                                    + " move from Pending to **Owed** until your faction credits them.")
                            .build());
                }));
        try {
            payouts.getObject().sharesOwed(); // settles per contract, and updates the payouts embeds
        } catch (Exception e) {
            log.warn("Couldn't update payouts after {} was paid: {}", c.ref(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ overdue

    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 120_000)
    public void overdue() {
        for (Invoice inv : invoices.overdueUnreminded()) {
            try {
                reputation.getObject().overdue(inv);
                remind(inv, true);
            } catch (Exception e) {
                log.warn("Overdue reminder for invoice {} failed: {}", inv.id(), e.getMessage());
            }
        }
    }

    /** DMs the requester a reminder; the first time, also posts the overdue card for leaders. */
    public void remind(Invoice inv, boolean withCard) {
        Contract c = contracts.find(inv.contractId()).orElseThrow();
        var requester = requesters.requester(c.requesterId());
        requester.ifPresent(r -> dms.send(r.discordId(), new EmbedBuilder().setColor(Panels.WARN)
                .setAuthor("REMINDER · " + c.ref() + " · " + feed.targetName(c, contracts.targetCount(c.id())).toUpperCase())
                .setTitle("⏰  " + Money.full(inv.due()) + " is overdue")
                .setDescription("This invoice was due " + WHEN.format(inv.dueAt()) + " UTC. "
                        + (inv.payeeTornId() == null ? "A Capacity X leader will tell you who to pay"
                        : "[Start a trade with " + inv.payeeName() + " [" + inv.payeeTornId() + "]](" + TRADE
                        + inv.payeeTornId() + ") in Torn and put it in") + ".")
                .build(), ActionRow.of(Button.secondary(CustomId.of("msg", "write", c.id()), "Message leaders")
                .withEmoji(Emoji.fromUnicode("✉️")))));
        Long card = null;
        if (withCard) {
            long daysLate = Math.max(1, Duration.between(inv.dueAt(), Instant.now()).toDays());
            card = postCard(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("INVOICE OVERDUE")
                            .setTitle("⏰  " + c.ref() + " · " + Money.full(inv.due()) + " · " + daysLate
                                    + (daysLate == 1 ? " day late" : " days late"))
                            .setDescription("Requester: **" + requester.map(r -> r.name() + " [" + r.tornId() + "]")
                                    .orElse("unknown") + "** · reminded " + WHEN.format(Instant.now()) + " UTC")
                            .build(),
                    ActionRow.of(Button.secondary(CustomId.of("pay", "remind", inv.id()), "Remind again")
                                    .withEmoji(Emoji.fromUnicode("🔔")),
                            Button.secondary(CustomId.of("pay", "mark", inv.id()), "Mark paid…")
                                    .withEmoji(Emoji.fromUnicode("✏️")),
                            Button.secondary(CustomId.of("reqs", "open", c.requesterId()), "Open requester")
                                    .withEmoji(Emoji.fromUnicode("👤"))));
        }
        invoices.reminded(inv.id(), card);
    }

    // ------------------------------------------------------------------ helpers

    private Long postCard(MessageEmbed embed, ActionRow... rows) {
        JDA api = jda.getIfAvailable();
        TextChannel ch = api == null ? null : layout.get(Layout.CH_LEADERS).map(api::getTextChannelById).orElse(null);
        if (ch == null) {
            return null;
        }
        try {
            return ch.sendMessageEmbeds(embed).setComponents(rows).complete().getIdLong();
        } catch (Exception e) {
            log.warn("Couldn't post a card in the leaders channel: {}", e.getMessage());
            return null;
        }
    }

    public void deleteCard(long messageId) {
        JDA api = jda.getIfAvailable();
        if (api == null) {
            return;
        }
        layout.get(Layout.CH_LEADERS).map(api::getTextChannelById)
                .ifPresent(ch -> ch.deleteMessageById(messageId).queue(null, ignored -> { }));
    }

    private static String hosp(int seconds) {
        long m = seconds / 60;
        return m < 60 ? m + "m" : (m / 60) + "h " + (m % 60) + "m";
    }
}
