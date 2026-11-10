package com.familyx.mercbot.reputation;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.payment.InvoiceRepository;
import com.familyx.mercbot.payment.InvoiceRepository.Invoice;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.RequestRepositories;
import com.familyx.mercbot.settings.BotSettings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Requester reputation (scope doc 6.5). Each change is tied to its invoice or contract so it
 * happens once: paid in full by the due time, paid late, still part paid at the due time,
 * still unpaid a week after it, a contract's last invoice paid, and leader adjustments.
 * The score stays between 0 and 100.
 */
@Service
public class ReputationService {

    private static final Logger log = LoggerFactory.getLogger(ReputationService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK).withZone(ZoneOffset.UTC);

    public static final String PAID_ON_TIME = "paid on time";
    public static final String PAID_LATE = "paid late";
    public static final String PART_AT_DUE = "part paid at the due time";
    public static final String UNPAID_WEEK = "unpaid a week after the due time";
    public static final String COMPLETED = "completed";

    private final JdbcClient jdbc;
    private final ContractRepository contracts;
    private final RequestRepositories requesters;
    private final InvoiceRepository invoices;
    private final BotSettings settings;
    private final AuditService audit;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;

    public ReputationService(JdbcClient jdbc, ContractRepository contracts, RequestRepositories requesters,
                             InvoiceRepository invoices, BotSettings settings, AuditService audit, Layout layout,
                             ObjectProvider<JDA> jda) {
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.requesters = requesters;
        this.invoices = invoices;
        this.settings = settings;
        this.audit = audit;
        this.layout = layout;
        this.jda = jda;
    }

    /** Changes a score within 0–100 and records why; returns the new score. */
    public int change(long requesterId, int delta, String reason, Long contractId, Long invoiceId, Long by) {
        Integer before = jdbc.sql("SELECT score FROM requester WHERE torn_id = ?").param(requesterId)
                .query(Integer.class).optional().orElse(null);
        if (before == null) {
            return 0;
        }
        int after = Math.max(0, Math.min(100, before + delta));
        jdbc.sql("UPDATE requester SET score = ? WHERE torn_id = ?").params(after, requesterId).update();
        jdbc.sql("""
                        INSERT INTO reputation_event (requester_id, change, reason, contract_id, invoice_id, by_discord_id)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(requesterId, after - before, reason, contractId, invoiceId, by).update();
        return after;
    }

    private boolean done(Long invoiceId, Long contractId, String reason) {
        if (invoiceId != null) {
            return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reputation_event WHERE reason = ? AND invoice_id = ?)")
                    .params(reason, invoiceId).query(Boolean.class).single();
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reputation_event WHERE reason = ? AND contract_id = ?)")
                .params(reason, contractId == null ? -1L : contractId).query(Boolean.class).single();
    }

    // ------------------------------------------------------------------ automatic changes

    /** An invoice was just paid in full. */
    public void paid(Contract c, Invoice inv) {
        try {
            boolean onTime = inv.dueAt() == null || inv.paidAt() == null || !inv.paidAt().isAfter(inv.dueAt());
            String reason = onTime ? PAID_ON_TIME : PAID_LATE;
            if (!done(inv.id(), null, PAID_ON_TIME) && !done(inv.id(), null, PAID_LATE)) {
                change(c.requesterId(), onTime ? settings.repOnTime() : -settings.repLate(), reason, c.id(), inv.id(), null);
            }
            completedIfDone(c.id());
        } catch (Exception e) {
            log.warn("Reputation for invoice {} failed: {}", inv.id(), e.getMessage());
        }
    }

    /** +completed once a finished contract's invoices are all paid. Also called when a contract finishes. */
    public void completedIfDone(long contractId) {
        Contract c = contracts.find(contractId).orElse(null);
        if (c == null || c.open() || done(null, c.id(), COMPLETED)) {
            return;
        }
        List<Invoice> list = invoices.forContract(c.id());
        if (list.isEmpty() || list.stream().anyMatch(i -> !"PAID".equals(i.status()))
                || !invoices.uninvoicedHits(c.id()).isEmpty()) {
            return;
        }
        change(c.requesterId(), settings.repCompleted(), COMPLETED, c.id(), null, null);
    }

    /** An invoice has just gone overdue: −partial if some, but not all, of it was paid. */
    public void overdue(Invoice inv) {
        try {
            if (!"PARTIAL".equals(inv.status()) || done(inv.id(), null, PART_AT_DUE)) {
                return;
            }
            contracts.find(inv.contractId()).ifPresent(c ->
                    change(c.requesterId(), -settings.repPartial(), PART_AT_DUE, c.id(), inv.id(), null));
        } catch (Exception e) {
            log.warn("Reputation for overdue invoice {} failed: {}", inv.id(), e.getMessage());
        }
    }

    /** Hourly: invoices still unpaid a week after the due time lose −25 and suggest a blacklist (never automatic). */
    @Scheduled(cron = "0 25 * * * *", zone = "UTC")
    public void unpaidWeek() {
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM invoice WHERE status IN ('OPEN', 'PARTIAL') AND unpaid_week_at IS NULL
                          AND due_at < now() - interval '7 days'""")
                .query(Long.class).list();
        for (long id : ids) {
            try {
                jdbc.sql("UPDATE invoice SET unpaid_week_at = now() WHERE id = ?").param(id).update();
                Invoice inv = invoices.find(id).orElseThrow();
                Contract c = contracts.find(inv.contractId()).orElseThrow();
                int score = change(c.requesterId(), -settings.repUnpaidWeek(), UNPAID_WEEK, c.id(), inv.id(), null);
                String who = requesters.requester(c.requesterId())
                        .map(r -> (r.name() == null ? "Player" : r.name()) + " [" + r.tornId() + "]").orElse("Requester");
                audit.log(null, "REPUTATION_UNPAID_WEEK", "invoice:" + id, null, Map.of("score", score),
                        "**" + c.ref() + "** still unpaid a week after it was due; reputation now " + score);
                card(c, inv, who, score);
            } catch (Exception e) {
                log.warn("Unpaid-week check for invoice {} failed: {}", id, e.getMessage());
            }
        }
    }

    private void card(Contract c, Invoice inv, String who, int score) {
        JDA api = jda.getIfAvailable();
        TextChannel ch = api == null ? null : layout.get(Layout.CH_LEADERS).map(api::getTextChannelById).orElse(null);
        if (ch == null) {
            return;
        }
        ch.sendMessageEmbeds(new EmbedBuilder().setColor(Panels.BAD).setAuthor("INVOICE UNPAID 7 DAYS")
                        .setTitle("⛔  " + c.ref() + " · " + who + " · " + Money.full(inv.due()) + " still unpaid")
                        .setDescription("Due " + (inv.dueAt() == null ? "?" : DAY.format(inv.dueAt())) + ". Reputation −"
                                + settings.repUnpaidWeek() + ", now " + score + ". Blacklist them?")
                        .build())
                .setComponents(ActionRow.of(
                        Button.danger(CustomId.of("reqs", "bl", c.requesterId(), "card"), "Blacklist…")
                                .withEmoji(Emoji.fromUnicode("⛔")),
                        Button.secondary(CustomId.of("reqs", "open", c.requesterId()), "Open requester")
                                .withEmoji(Emoji.fromUnicode("👤")),
                        Button.secondary(CustomId.of("reqs", "notnow"), "Not now")))
                .queue(null, e -> log.warn("Couldn't post the unpaid-week card: {}", e.getMessage()));
    }
}
