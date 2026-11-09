package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.payment.InvoiceRepository;
import com.familyx.mercbot.payment.InvoiceRepository.Invoice;
import com.familyx.mercbot.payment.InvoiceService;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.settings.BotSettings;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Payment cards in the leaders channel (scope doc 6.3): Confirm paid, Mark paid…, Not this
 * invoice and Remind again. Only Capacity X's leaders can use them (they handle settlement).
 */
@Component
public class PaymentHandler implements InteractionHandler {

    private final InvoiceService service;
    private final InvoiceRepository invoices;
    private final ContractRepository contracts;
    private final BotSettings settings;
    private final Access access;
    private final AuditService audit;

    public PaymentHandler(InvoiceService service, InvoiceRepository invoices, ContractRepository contracts,
                          BotSettings settings, Access access, AuditService audit) {
        this.service = service;
        this.invoices = invoices;
        this.contracts = contracts;
        this.settings = settings;
        this.access = access;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "pay";
    }

    @Override
    public Access.Level access() {
        return Access.Level.FAMILY_LEADER;
    }

    private boolean capXLeader(IReplyCallback event) {
        if (access.isLeaderOf(event.getMember(), settings.paymentFactionId())) {
            return true;
        }
        event.reply("Only Capacity X leaders can handle payments.").setEphemeral(true)
                .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        return false;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        if (!capXLeader(event)) {
            return;
        }
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "confirm" -> {
                String newsId = id.arg(0);
                var dep = invoices.deposit(newsId).orElse(null);
                if (dep == null || dep.invoiceId() == null || dep.outcome() != null) {
                    done(event, "This payment has already been handled.");
                    return;
                }
                event.deferReply(true).queue();
                invoices.depositOutcome(newsId, "CONFIRMED");
                Invoice inv = service.pay(dep.invoiceId(), dep.amount(), newsId, user);
                event.getMessage().delete().queue(null, ignored -> { });
                event.getHook().editOriginal("✅ Recorded " + Money.full(dep.amount()) + " against "
                                + ref(inv) + ". It's " + ("PAID".equals(inv.status()) ? "paid in full." : "part paid: "
                                + Money.full(inv.due()) + " still due."))
                        .queue(h -> AutoDismiss.after(event.getHook(), AutoDismiss.SHORT));
            }
            case "other" -> {
                // The exact match was for a different contract: offer every open invoice instead.
                String newsId = id.arg(0);
                var dep = invoices.deposit(newsId).orElse(null);
                if (dep == null || dep.outcome() != null) {
                    done(event, "This payment has already been handled.");
                    return;
                }
                InvoiceService.Card card = service.otherInvoices(newsId, dep.amount(), null);
                if (card == null) {
                    done(event, "There are no other open invoices.");
                    return;
                }
                invoices.linkDeposit(newsId, null, event.getMessage().getIdLong());
                event.editMessageEmbeds(card.embed()).setComponents(card.rows()).queue();
            }
            case "not" -> {
                String newsId = id.arg(0);
                invoices.depositOutcome(newsId, "SET_ASIDE");
                audit.log(user, "PAYMENT_SET_ASIDE", "news:" + newsId, null, null,
                        "Set aside a deposit that matched an invoice amount");
                event.getMessage().delete().queue(null, ignored -> { });
                done(event, "Set aside: not counted against any invoice.");
            }
            case "mark" -> {
                long invoiceId = id.longArg(0);
                Optional<Invoice> inv = invoices.find(invoiceId);
                if (inv.isEmpty() || "PAID".equals(inv.get().status())) {
                    done(event, "That invoice is already paid.");
                    return;
                }
                event.replyModal(Modal.create(CustomId.of("pay", "marksave", invoiceId,
                                event.getMessage().getIdLong()), ref(inv.get()) + " · mark paid")
                        .addComponents(Label.of("Amount received (" + Money.full(inv.get().due()) + " due)",
                                TextInput.create("amount", TextInputStyle.SHORT).setRequired(true).setMaxLength(20)
                                        .setValue(Long.toString(inv.get().due())).build()))
                        .build()).queue();
            }
            case "remind" -> {
                Optional<Invoice> inv = invoices.find(id.longArg(0));
                if (inv.isEmpty() || "PAID".equals(inv.get().status())) {
                    event.getMessage().delete().queue(null, ignored -> { });
                    done(event, "That invoice is already paid.");
                    return;
                }
                event.deferReply(true).queue();
                service.remind(inv.get(), false);
                audit.log(user, "INVOICE_REMINDED", "invoice:" + inv.get().id(), null, null,
                        "Reminded the requester about **" + ref(inv.get()) + "**");
                event.getHook().editOriginal("🔔 Reminder sent.")
                        .queue(h -> AutoDismiss.after(event.getHook(), AutoDismiss.SHORT));
            }
            default -> done(event, "Unknown option.");
        }
    }

    /** A Capacity X leader picked which contract an unmatched deposit is for. */
    @Override
    public void stringSelect(net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent event,
                             CustomId id) {
        if (!capXLeader(event)) {
            return;
        }
        String newsId = id.arg(0);
        var dep = invoices.deposit(newsId).orElse(null);
        if (dep == null || dep.outcome() != null) {
            event.reply("This deposit has already been handled.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            return;
        }
        long invoiceId = Long.parseLong(event.getValues().getFirst());
        Optional<Invoice> before = invoices.find(invoiceId);
        if (before.isEmpty() || "PAID".equals(before.get().status())) {
            event.reply("That invoice is already paid. Pick another.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            return;
        }
        event.deferReply(true).queue();
        invoices.depositOutcome(newsId, "CONFIRMED");
        invoices.linkDeposit(newsId, invoiceId, dep.cardId());
        Invoice inv = service.pay(invoiceId, dep.amount(), newsId, event.getUser().getIdLong());
        event.getMessage().delete().queue(null, ignored -> { });
        event.getHook().editOriginal("✅ Recorded " + Money.full(dep.amount()) + " against " + ref(inv) + ". "
                        + ("PAID".equals(inv.status()) ? "It's paid in full." : Money.full(inv.due()) + " still due."))
                .queue(h -> AutoDismiss.after(event.getHook(), AutoDismiss.SHORT));
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        if (!capXLeader(event)) {
            return;
        }
        long invoiceId = id.longArg(0);
        long cardId = id.longArg(1);
        String raw = event.getValue("amount") == null ? "" : event.getValue("amount").getAsString();
        Long amount = Money.parse(raw);
        if (amount == null || amount <= 0) {
            event.reply("❌ Enter an amount, like 100000000 or 100m.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            return;
        }
        event.deferReply(true).queue();
        Invoice inv = service.pay(invoiceId, amount, null, event.getUser().getIdLong());
        if ("PAID".equals(inv.status()) && event.getMessage() != null) {
            event.getMessage().delete().queue(null, ignored -> { });
        } else if ("PAID".equals(inv.status()) && cardId > 0) {
            service.deleteCard(cardId);
        }
        event.getHook().editOriginal("✅ Recorded " + Money.full(amount) + " against " + ref(inv) + ". "
                        + ("PAID".equals(inv.status()) ? "It's paid in full." : Money.full(inv.due()) + " still due."))
                .queue(h -> AutoDismiss.after(event.getHook(), AutoDismiss.SHORT));
    }

    private String ref(Invoice inv) {
        return contracts.find(inv.contractId()).map(c -> c.ref()).orElse("the invoice");
    }

    private static void done(ButtonInteractionEvent event, String text) {
        event.reply(text).setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
    }
}
