package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.contract.ResultsFeed;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.payment.InvoiceRepository;
import com.familyx.mercbot.payment.InvoiceRepository.Invoice;
import com.familyx.mercbot.payment.InvoiceRepository.LeaderList;
import com.familyx.mercbot.payment.InvoiceRepository.Line;
import com.familyx.mercbot.payment.InvoiceService;
import com.familyx.mercbot.payout.PayoutRepository;
import com.familyx.mercbot.payout.PayoutRepository.Settlement;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.RequestRepositories;
import com.familyx.mercbot.settings.BotSettings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Leader panel → Invoices &amp; payouts (scope doc 6.1, 6.6): what's pending, owed and credited,
 * invoices by status, and Mark paid… or Remind again on any open invoice. Merc Managers and
 * Capacity X leaders can open it; only Capacity X leaders can change anything.
 */
@Component
public class InvoicesHandler implements InteractionHandler {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)
            .withZone(ZoneOffset.UTC);
    private static final int SHOWN = 10;

    private final InvoiceRepository invoices;
    private final InvoiceService service;
    private final PayoutRepository payouts;
    private final ContractRepository contracts;
    private final RequestRepositories requesters;
    private final FamilyFactionRepository factions;
    private final ResultsFeed feed;
    private final BotSettings settings;
    private final Access access;
    private final AuditService audit;

    public InvoicesHandler(InvoiceRepository invoices, InvoiceService service, PayoutRepository payouts,
                           ContractRepository contracts, RequestRepositories requesters,
                           FamilyFactionRepository factions, ResultsFeed feed, BotSettings settings, Access access,
                           AuditService audit) {
        this.invoices = invoices;
        this.service = service;
        this.payouts = payouts;
        this.contracts = contracts;
        this.requesters = requesters;
        this.factions = factions;
        this.feed = feed;
        this.settings = settings;
        this.access = access;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "inv";
    }

    @Override
    public Access.Level access() {
        return Access.Level.FAMILY_LEADER;
    }

    private boolean canOpen(IReplyCallback event) {
        if (access.isMercManager(event.getMember()) || capX(event.getMember())) {
            return true;
        }
        event.reply("Invoices & payouts is for Merc Managers and Capacity X leaders. Your faction's payouts are in "
                + "the payouts channel.").setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        return false;
    }

    private boolean capX(Member m) {
        return access.isLeaderOf(m, settings.paymentFactionId());
    }

    private boolean capXOnly(IReplyCallback event) {
        if (capX(event.getMember())) {
            return true;
        }
        event.reply("Only Capacity X leaders can handle payments.").setEphemeral(true)
                .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        return false;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        if (!canOpen(event)) {
            return;
        }
        switch (id.sub()) {
            case "view" -> list(LeaderList.OPEN, null).show(event);
            case "list" -> list(LeaderList.valueOf(id.arg(0)), null).show(event);
            case "open" -> withInvoice(event, id, inv -> invoice(inv, event.getMember(), null).show(event));
            case "mark" -> {
                if (!capXOnly(event)) {
                    return;
                }
                Optional<Invoice> inv = invoices.find(id.longArg(0));
                if (inv.isEmpty() || "PAID".equals(inv.get().status())) {
                    list(LeaderList.OPEN, "That invoice is already paid.").show(event);
                    return;
                }
                String ref = ref(inv.get());
                event.replyModal(Modal.create(CustomId.of("inv", "marksave", inv.get().id(), fresh()),
                                "Mark " + ref + " paid")
                        .addComponents(
                                Label.of("Amount received (" + Money.full(inv.get().due()) + " due)",
                                        TextInput.create("amount", TextInputStyle.SHORT).setRequired(true)
                                                .setMaxLength(20).setValue(Long.toString(inv.get().due())).build()),
                                Label.of("Note (optional)", TextInput.create("note", TextInputStyle.SHORT)
                                        .setRequired(false).setMaxLength(200)
                                        .setPlaceholder("e.g. paid by cash to Dan").build()))
                        .build()).queue();
            }
            case "remind" -> {
                if (!capXOnly(event)) {
                    return;
                }
                withInvoice(event, id, inv -> {
                    if ("PAID".equals(inv.status())) {
                        invoice(inv, event.getMember(), "It's already paid.").show(event);
                        return;
                    }
                    InteractionHook hook = Screen.deferInPlace(event);
                    service.remind(inv, false);
                    audit.log(event.getUser().getIdLong(), "INVOICE_REMINDED", "invoice:" + inv.id(), null, null,
                            "Reminded the requester about **" + ref(inv) + "**");
                    invoice(invoices.find(inv.id()).orElse(inv), event.getMember(), "🔔 Reminder sent.").show(hook);
                });
            }
            default -> list(LeaderList.OPEN, null).show(event);
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        if (!canOpen(event)) {
            return;
        }
        String v = event.getValues().getFirst();
        switch (id.sub()) {
            case "show" -> list(LeaderList.valueOf(v), null).show(event);
            case "pick" -> invoices.find(Long.parseLong(v)).ifPresentOrElse(
                    inv -> invoice(inv, event.getMember(), null).show(event),
                    () -> list(LeaderList.OPEN, "That invoice no longer exists.").show(event));
            default -> list(LeaderList.OPEN, null).show(event);
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        if (!capXOnly(event)) {
            return;
        }
        long invoiceId = id.longArg(0);
        String raw = event.getValue("amount") == null ? "" : event.getValue("amount").getAsString();
        String note = event.getValue("note") == null ? "" : event.getValue("note").getAsString().trim();
        long amount = Money.parse(raw);
        if (amount <= 0) {
            event.reply("❌ Enter an amount, like 1485000 or 1.485m.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            return;
        }
        InteractionHook hook = Screen.deferInPlace(event);
        Optional<Invoice> before = invoices.find(invoiceId);
        if (before.isEmpty() || "PAID".equals(before.get().status())) {
            list(LeaderList.OPEN, "That invoice is already paid.").show(hook);
            return;
        }
        Invoice inv = service.pay(invoiceId, amount, null, event.getUser().getIdLong());
        if (!note.isEmpty()) {
            audit.log(event.getUser().getIdLong(), "PAYMENT_NOTE", "invoice:" + invoiceId, null, Map.of("note", note),
                    "Note on **" + ref(inv) + "** payment: " + note);
        }
        invoice(inv, event.getMember(), "✅ Recorded " + Money.full(amount) + ". " + ("PAID".equals(inv.status())
                ? ref(inv) + " is paid; the mercs' shares are now owed and the requester has been told."
                : Money.full(inv.due()) + " still due.")).show(hook);
    }

    // ------------------------------------------------------------------ screens

    private Screen list(LeaderList which, String notice) {
        InvoiceRepository.Unpaid unpaid = invoices.unpaid();
        List<Invoice> list = invoices.forLeaders(which, 25);
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER).setAuthor("INVOICES & PAYOUTS")
                .setDescription(notice == null ? null : notice)
                .addField("⏳  Pending", Money.full(unpaid.due()) + "\n-# " + unpaid.count()
                        + (unpaid.count() == 1 ? " invoice" : " invoices") + " unpaid" + Panels.GAP, true)
                .addField("💰  Owed", Money.full(payouts.owedAll()) + "\n-# paid in, not credited" + Panels.GAP, true)
                .addField("✅  Credited", Money.full(payouts.credited7dAll()) + "\n-# last 7 days" + Panels.GAP, true);

        // A field holds at most 1024 characters: show as many rows as fit.
        StringBuilder body = new StringBuilder();
        int shown = 0;
        for (Invoice inv : list) {
            String r = row(inv) + "\n";
            if (shown == SHOWN || body.length() + r.length() > 900) {
                break;
            }
            body.append(r);
            shown++;
        }
        if (list.isEmpty()) {
            body.append("-# Nothing in ").append(which.label.toLowerCase()).append(".\n");
        } else if (list.size() > shown) {
            body.append("-# +").append(list.size() - shown).append(" more in the list below\n");
        }
        eb.addField("🧾  " + which.label, body + Panels.GAP, false);
        eb.addField("🏛️  By faction", byFaction(), false);

        StringSelectMenu.Builder show = StringSelectMenu.create(CustomId.of("inv", "show"));
        for (LeaderList l : LeaderList.values()) {
            show.addOption("Show: " + l.label, l.name());
        }
        show.setDefaultValues(which.name());
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(show.build()));
        if (!list.isEmpty()) {
            StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("inv", "pick"))
                    .setPlaceholder("Open an invoice…");
            for (Invoice inv : list) {
                pick.addOption(trim(ref(inv) + " · " + target(inv), 100), Long.toString(inv.id()),
                        trim(Money.full("PAID".equals(inv.status()) ? inv.total() : inv.due()) + " · " + state(inv), 100),
                        Emoji.fromUnicode(icon(inv)));
            }
            rows.add(ActionRow.of(pick.build()));
        }
        rows.add(ActionRow.of(Button.secondary(CustomId.of("inv", "list", which.name()), "Refresh")
                .withEmoji(Emoji.fromUnicode("🔄"))));
        return new Screen(eb.build(), rows);
    }

    private Screen invoice(Invoice inv, Member viewer, String notice) {
        Optional<Contract> c = contracts.find(inv.contractId());
        List<Line> lines = invoices.lines(inv.id());
        Map<String, Long> byTier = new LinkedHashMap<>();
        lines.forEach(l -> byTier.merge(l.tier() == null ? "?" : l.tier(), 1L, Long::sum));
        String tiers = byTier.entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        String who = c.flatMap(x -> requesters.requester(x.requesterId()))
                .map(r -> (r.name() == null ? "Player" : r.name()) + " [" + r.tornId() + "]").orElse("unknown");
        boolean paid = "PAID".equals(inv.status());
        EmbedBuilder eb = new EmbedBuilder().setColor(paid ? Panels.GOOD : Panels.LEADER)
                .setAuthor(("INTERIM".equals(inv.kind()) ? "INTERIM INVOICE · " : "INVOICE · ") + ref(inv) + " · "
                        + target(inv).toUpperCase())
                .setTitle(paid ? "✅  Paid in full" : "🧾  " + Money.full(inv.due()) + " still to pay")
                .setDescription((notice == null ? "" : notice + "\n\n") + "Requester: **" + who + "**")
                .addField("Total", Money.full(inv.total()) + "\n-# " + lines.size()
                        + (lines.size() == 1 ? " hit" : " hits") + (tiers.isEmpty() ? "" : " · " + tiers) + Panels.GAP, true)
                .addField("Paid", Money.full(inv.amountPaid()) + "\n-# " + (paid && inv.paidAt() != null
                        ? WHEN.format(inv.paidAt()) + " UTC" : inv.amountPaid() > 0 ? "part paid" : "nothing yet")
                        + Panels.GAP, true)
                .addField("Due", (inv.dueAt() == null ? "–" : WHEN.format(inv.dueAt()) + " UTC") + "\n-# "
                        + (inv.payeeTornId() == null ? "no payee set" : "payee: " + inv.payeeName() + " ["
                        + inv.payeeTornId() + "]") + Panels.GAP, true);
        List<Button> buttons = new ArrayList<>();
        boolean canAct = capX(viewer) && !paid;
        Button mark = Button.success(CustomId.of("inv", "mark", inv.id()), "Mark paid…").withEmoji(Emoji.fromUnicode("✏️"));
        Button remind = Button.secondary(CustomId.of("inv", "remind", inv.id()), "Remind again")
                .withEmoji(Emoji.fromUnicode("🔔"));
        buttons.add(canAct ? mark : mark.asDisabled());
        buttons.add(canAct ? remind : remind.asDisabled());
        if (access.isMercManager(viewer)) {
            buttons.add(Button.secondary(CustomId.of("lcon", "open", inv.contractId()), "Open contract")
                    .withEmoji(Emoji.fromUnicode("📋")));
        }
        buttons.add(Button.primary(CustomId.of("inv", "list", paid ? "PAID" : "OPEN"), "Back")
                .withEmoji(Emoji.fromUnicode("⬅️")));
        return Screen.of(eb.build(), ActionRow.of(buttons));
    }

    /** Owed per family faction, and where its latest settlement with Capacity X stands. */
    private String byFaction() {
        long pool = settings.paymentFactionId();
        List<FamilyFaction> all = factions.findActive();
        if (all.isEmpty()) {
            return "No family factions set up." + Panels.GAP;
        }
        int width = Math.max(7, all.stream().mapToInt(f -> f.name().length()).max().orElse(7));
        StringBuilder sb = new StringBuilder("```\n").append(" ".repeat(width)).append("   ")
                .append(padLeft("Owed", 12)).append("   Settlement\n");
        for (FamilyFaction f : all) {
            String settlement = "–";
            if (f.tornFactionId() != pool) {
                List<Settlement> open = payouts.inProgress(f.tornFactionId());
                settlement = open.isEmpty() ? payouts.latest(f.tornFactionId()).map(s -> cap(s.status()))
                        .orElse("–") : open.stream().map(s -> cap(s.status())).distinct().collect(Collectors.joining(", "));
            }
            sb.append(pad(f.name(), width)).append("   ").append(padLeft(Money.full(payouts.owed(f.tornFactionId(), pool)), 12))
                    .append("   ").append(settlement).append('\n');
        }
        return sb.append("```").append(Panels.GAP).toString();
    }

    // ------------------------------------------------------------------ helpers

    private String row(Invoice inv) {
        String amount = "PAID".equals(inv.status()) ? Money.full(inv.total()) : Money.full(inv.due());
        return icon(inv) + " **" + ref(inv) + " · " + target(inv) + "** · " + amount + "\n-# " + state(inv);
    }

    private static String state(Invoice inv) {
        if ("PAID".equals(inv.status())) {
            return "paid" + (inv.paidAt() == null ? "" : " " + WHEN.format(inv.paidAt()) + " UTC");
        }
        String part = "PARTIAL".equals(inv.status()) ? "part paid, " + Money.full(inv.amountPaid()) + " of "
                + Money.full(inv.total()) + " · " : "";
        if (inv.dueAt() != null && inv.dueAt().isBefore(Instant.now())) {
            Duration late = Duration.between(inv.dueAt(), Instant.now());
            return part + "overdue " + (late.toHours() < 48 ? late.toHours() + "h" : late.toDays() + " days");
        }
        return part + (inv.dueAt() == null ? "open" : "due " + WHEN.format(inv.dueAt()) + " UTC");
    }

    private static String icon(Invoice inv) {
        if ("PAID".equals(inv.status())) {
            return "✅";
        }
        if (inv.dueAt() != null && inv.dueAt().isBefore(Instant.now())) {
            return "🔴";
        }
        return "PARTIAL".equals(inv.status()) ? "🟡" : "🧾";
    }

    private String ref(Invoice inv) {
        return contracts.find(inv.contractId()).map(Contract::ref).orElse("Invoice " + inv.id());
    }

    private String target(Invoice inv) {
        return contracts.find(inv.contractId()).map(c -> feed.targetName(c, contracts.targetCount(c.id()))).orElse("?");
    }

    private interface InvoiceWork {
        void run(Invoice inv);
    }

    private void withInvoice(GenericComponentInteractionCreateEvent event, CustomId id, InvoiceWork work) {
        Optional<Invoice> inv = invoices.find(id.longArg(0));
        if (inv.isEmpty()) {
            list(LeaderList.OPEN, "That invoice no longer exists.").show(event);
            return;
        }
        work.run(inv.get());
    }

    private static String cap(String status) {
        return status.charAt(0) + status.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String padLeft(String s, int w) {
        return s.length() >= w ? s : " ".repeat(w - s.length()) + s;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String fresh() {
        return Long.toString(System.nanoTime() % 1_000_000_000L, 36);
    }
}
