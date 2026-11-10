package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.contract.ControlPanelService;
import com.familyx.mercbot.contract.ResultsFeed;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.payment.InvoiceService;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.ContractRepository.LeaderFilter;
import com.familyx.mercbot.request.RequestRepositories;
import com.familyx.mercbot.tier.TierRepository;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Leader panel → Contracts (scope doc 6.1): list contracts by status, open one, and approve,
 * reprioritise, pause, resume, end or cancel it. Merc Managers only.
 */
@Component
public class LeaderContractsHandler implements InteractionHandler {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
    private static final String[] PRIORITY = {"Normal", "High", "Top"};

    private final ContractRepository contracts;
    private final TierRepository tiers;
    private final RequestRepositories requesters;
    private final ControlPanelService panels;
    private final ResultsFeed feed;
    private final BoardService boards;
    private final ObjectProvider<InvoiceService> invoices;
    private final ApprovalHandler approvals;
    private final Dms dms;
    private final AuditService audit;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;

    private final com.familyx.mercbot.inbox.InboxService inbox;

    public LeaderContractsHandler(ContractRepository contracts, TierRepository tiers, RequestRepositories requesters,
                                  ControlPanelService panels, ResultsFeed feed, BoardService boards,
                                  ObjectProvider<InvoiceService> invoices, ApprovalHandler approvals, Dms dms,
                                  AuditService audit, Layout layout, ObjectProvider<JDA> jda,
                                  com.familyx.mercbot.inbox.InboxService inbox) {
        this.inbox = inbox;
        this.contracts = contracts;
        this.tiers = tiers;
        this.requesters = requesters;
        this.panels = panels;
        this.feed = feed;
        this.boards = boards;
        this.invoices = invoices;
        this.approvals = approvals;
        this.dms = dms;
        this.audit = audit;
        this.layout = layout;
        this.jda = jda;
    }

    @Override
    public String action() {
        return "lcon";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "view" -> list(LeaderFilter.LIVE, null).show(event);
            case "list" -> list(LeaderFilter.valueOf(id.arg(0)), null).show(event);
            case "open" -> withContract(event, id, c -> contract(c, null).show(event));
            case "pause" -> withContract(event, id, c -> {
                if (contracts.setStatus(c.id(), List.of("ACTIVE"), "PAUSED")) {
                    acted(c, user, "⏸️", "paused by a leader", "off the boards until resumed", "CONTRACT_PAUSED",
                            "A leader paused your contract. Its targets are off the boards until it's resumed.");
                }
                contract(reload(c), "⏸️ Paused.").show(event);
            });
            case "resume" -> withContract(event, id, c -> {
                if (contracts.setStatus(c.id(), List.of("PAUSED"), "ACTIVE")) {
                    acted(c, user, "▶️", "resumed by a leader", "back on the boards", "CONTRACT_RESUMED",
                            "A leader resumed your contract. Its targets are back on the boards.");
                }
                contract(reload(c), "▶️ Resumed.").show(event);
            });
            case "end" -> withContract(event, id, c -> confirm(c, "end").show(event));
            case "cancel" -> withContract(event, id, c -> confirm(c, "cancel").show(event));
            case "endyes" -> withContract(event, id, c -> {
                InteractionHook hook = Screen.deferInPlace(event);
                if (contracts.setStatus(c.id(), List.of("ACTIVE", "PAUSED"), "ENDED")) {
                    acted(c, user, "⏹️", "ended by a leader", feed.hitsLanded(c), "CONTRACT_ENDED",
                            "A leader ended your contract. No more hits will count; you'll get the invoice for the "
                                    + "hits made.");
                    invoices.getObject().issueFinal(reload(c));
                }
                contract(reload(c), "⏹️ Ended. Any hits are invoiced.").show(hook);
            });
            case "cancelyes" -> withContract(event, id, c -> {
                if (contracts.hitStats(c.id()).accepted() > 0) {
                    contract(c, "❌ A hit has been accepted, so it can't be cancelled. Use End now.").show(event);
                    return;
                }
                boolean wasPending = "PENDING_APPROVAL".equals(c.status());
                if (contracts.setStatus(c.id(), List.of("PENDING_APPROVAL", "ACTIVE", "PAUSED"), "CANCELLED")) {
                    if (wasPending) {
                        removeApprovalCard(c);
                    }
                    acted(c, user, "✖️", "cancelled by a leader", "nothing billed", "CONTRACT_CANCELLED",
                            "A leader cancelled your contract. Nothing is billed.");
                }
                contract(reload(c), "✖️ Cancelled. Nothing is billed.").show(event);
            });
            case "approve" -> withContract(event, id, c -> {
                InteractionHook hook = Screen.deferInPlace(event);
                approvals.decide(c.id(), true, user, null, null);
                removeApprovalCard(c);
                contract(reload(c), "✅ Approved. The requester has been told.").show(hook);
            });
            case "reject" -> event.replyModal(Modal.create(CustomId.of("lcon", "rejectsave", id.arg(0)),
                            "Reject this request")
                    .addComponents(Label.of("Reason (sent to the requester)", TextInput.create("reason",
                            TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(500).build()))
                    .build()).queue();
            default -> list(LeaderFilter.LIVE, null).show(event);
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        String v = event.getValues().getFirst();
        switch (id.sub()) {
            case "show" -> list(LeaderFilter.valueOf(v), null).show(event);
            case "pick" -> contracts.find(Long.parseLong(v)).ifPresentOrElse(c -> contract(c, null).show(event),
                    () -> list(LeaderFilter.LIVE, "That contract no longer exists.").show(event));
            case "prio" -> withContract(event, id, c -> {
                int p = Integer.parseInt(v);
                contracts.setPriority(c.id(), p);
                audit.log(event.getUser().getIdLong(), "CONTRACT_PRIORITY", "contract:" + c.id(),
                        Map.of("priority", c.priority()), Map.of("priority", p),
                        "Set **" + c.ref() + "** to priority **" + label(p) + "**");
                Thread.ofVirtual().start(boards::refresh);
                contract(reload(c), "✅ Priority set to " + label(p) + ". The boards are re-sorting.").show(event);
            });
            default -> list(LeaderFilter.LIVE, null).show(event);
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        Optional<Contract> found = contracts.find(id.longArg(0));
        if (found.isEmpty()) {
            list(LeaderFilter.LIVE, "That contract no longer exists.").show(hook);
            return;
        }
        String reason = event.getValue("reason") == null ? "" : event.getValue("reason").getAsString().trim();
        approvals.decide(found.get().id(), false, event.getUser().getIdLong(), reason, null);
        removeApprovalCard(found.get());
        contract(reload(found.get()), "✖️ Rejected. The requester has been told why.").show(hook);
    }

    // ------------------------------------------------------------------ screens

    private Screen list(LeaderFilter filter, String notice) {
        List<Contract> list = contracts.forLeaders(filter, 25);
        int live = contracts.countStatus("ACTIVE");
        int paused = contracts.countStatus("PAUSED");
        int pending = contracts.countFor(LeaderFilter.PENDING);
        int unpaid = contracts.countFor(LeaderFilter.UNPAID);
        StringBuilder body = new StringBuilder(notice == null ? "" : notice + "\n\n")
                .append("**").append(live).append("** live · **").append(paused).append("** paused · **")
                .append(pending).append("** waiting for approval · **").append(unpaid).append("** unpaid invoices\n");
        if (list.isEmpty()) {
            body.append("\n-# No contracts in ").append(filter.label.toLowerCase()).append('.');
        }
        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("lcon", "pick"))
                .setPlaceholder("Open a contract…");
        int shown = 0;
        for (Contract c : list) {
            String target = feed.targetName(c, contracts.targetCount(c.id()));
            String tier = tiers.find(c.tierId()).map(t -> t.name()).orElse("Tier");
            String line = summary(c);
            if (shown < 10) {
                body.append('\n').append(icon(c)).append(" **").append(c.ref()).append(" · ").append(target)
                        .append("** · ").append(tier).append(c.priority() > 0 ? " · priority **" + label(c.priority()) + "**" : "")
                        .append("\n-# ").append(line);
            }
            shown++;
            pick.addOption(trim(c.ref() + " · " + target + " · " + tier, 100), Long.toString(c.id()), trim(line, 100),
                    Emoji.fromUnicode(icon(c)));
        }
        if (list.size() > 10) {
            body.append("\n-# +").append(list.size() - 10).append(" more in the list below");
        }
        StringSelectMenu.Builder show = StringSelectMenu.create(CustomId.of("lcon", "show"));
        for (LeaderFilter f : LeaderFilter.values()) {
            show.addOption("Show: " + f.label, f.name());
        }
        show.setDefaultValues(filter.name());
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(show.build()));
        if (!list.isEmpty()) {
            rows.add(ActionRow.of(pick.build()));
        }
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("MERC MANAGERS")
                .setTitle("📋  Contracts").setDescription(body + Panels.GAP).build(), rows.toArray(new ActionRow[0]));
    }

    /** The contract view, for other leader screens that return to it (phase 5a: inbox replies). */
    public Screen screen(Contract c, String notice) {
        return contract(c, notice);
    }

    private Screen contract(Contract c, String notice) {
        var embed = panels.leaderView(c, label(c.priority()));
        String talk = inbox.conversation(c);
        if (notice != null || talk != null) {
            EmbedBuilder eb = new EmbedBuilder(embed);
            if (notice != null) {
                eb.setDescription(notice + "\n\n" + (embed.getDescription() == null ? "" : embed.getDescription()));
            }
            if (talk != null) {
                eb.addField("✉️  Messages", talk, false);
            }
            embed = eb.build();
        }
        List<ActionRow> rows = new ArrayList<>();
        Button back = Button.primary(CustomId.of("lcon", "list", "LIVE"), "All contracts").withEmoji(Emoji.fromUnicode("⬅️"));
        Button reply = Button.secondary(CustomId.of("inbox", "reply", c.id()), "Reply").withEmoji(Emoji.fromUnicode("↩️"));
        switch (c.status()) {
            case "PENDING_APPROVAL" -> rows.add(talk == null ? ActionRow.of(
                    Button.success(CustomId.of("lcon", "approve", c.id()), "Approve").withEmoji(Emoji.fromUnicode("✅")),
                    Button.danger(CustomId.of("lcon", "reject", c.id()), "Reject…").withEmoji(Emoji.fromUnicode("✖️")),
                    back) : ActionRow.of(
                    Button.success(CustomId.of("lcon", "approve", c.id()), "Approve").withEmoji(Emoji.fromUnicode("✅")),
                    Button.danger(CustomId.of("lcon", "reject", c.id()), "Reject…").withEmoji(Emoji.fromUnicode("✖️")),
                    reply, back));
            case "ACTIVE", "PAUSED" -> {
                StringSelectMenu.Builder prio = StringSelectMenu.create(CustomId.of("lcon", "prio", c.id()));
                for (int p = 0; p < PRIORITY.length; p++) {
                    prio.addOption("Priority: " + PRIORITY[p], Integer.toString(p),
                            p == 0 ? "Sorted by BSP with everything else" : p == 1 ? "Above Normal contracts"
                                    : "Above everything else", Emoji.fromUnicode(p == 0 ? "➖" : p == 1 ? "🔼" : "⏫"));
                }
                prio.setDefaultValues(Integer.toString(Math.max(0, Math.min(2, c.priority()))));
                rows.add(ActionRow.of(prio.build()));
                boolean hits = contracts.hitStats(c.id()).accepted() > 0;
                Button cancel = Button.danger(CustomId.of("lcon", "cancel", c.id()), "Cancel")
                        .withEmoji(Emoji.fromUnicode("✖️"));
                rows.add(ActionRow.of(
                        "ACTIVE".equals(c.status())
                                ? Button.secondary(CustomId.of("lcon", "pause", c.id()), "Pause").withEmoji(Emoji.fromUnicode("⏸️"))
                                : Button.success(CustomId.of("lcon", "resume", c.id()), "Resume").withEmoji(Emoji.fromUnicode("▶️")),
                        Button.secondary(CustomId.of("lcon", "end", c.id()), "End now").withEmoji(Emoji.fromUnicode("⏹️")),
                        hits ? cancel.asDisabled() : cancel,
                        back));
                if (talk != null) {
                    rows.add(ActionRow.of(reply));
                }
            }
            default -> rows.add(talk == null ? ActionRow.of(back) : ActionRow.of(reply, back));
        }
        return Screen.of(embed, rows.toArray(new ActionRow[0]));
    }

    private Screen confirm(Contract c, String what) {
        boolean end = "end".equals(what);
        return Screen.of(new EmbedBuilder().setColor(Panels.WARN).setAuthor("CONTRACT " + c.ref())
                        .setTitle(end ? "⏹️  End " + c.ref() + " now?" : "✖️  Cancel " + c.ref() + "?")
                        .setDescription(end ? "Its targets come off the boards and no more hits count. The hits made so "
                                + "far are invoiced to the requester." : "It's removed and nothing is billed.")
                        .build(),
                ActionRow.of(
                        Button.danger(CustomId.of("lcon", end ? "endyes" : "cancelyes", c.id()), end ? "End now" : "Cancel it")
                                .withEmoji(Emoji.fromUnicode(end ? "⏹️" : "✖️")),
                        Button.secondary(CustomId.of("lcon", "open", c.id()), end ? "Keep it running" : "Keep it")
                                .withEmoji(Emoji.fromUnicode("↩️"))));
    }

    // ------------------------------------------------------------------ helpers

    /** Logs a leader action, posts the merc-results line, tells the requester and updates their panel. */
    private void acted(Contract c, long leader, String emoji, String happened, String detail, String auditAction,
                       String toRequester) {
        feed.contract(c, emoji, happened, detail);
        audit.log(leader, auditAction, "contract:" + c.id(), null, null, capitalise(happened) + ": **" + c.ref() + "**");
        requesters.requester(c.requesterId()).ifPresent(r -> dms.send(r.discordId(), new EmbedBuilder()
                .setColor(Panels.PRIVATE).setAuthor("CONTRACT " + c.ref())
                .setTitle(emoji + "  " + capitalise(happened)).setDescription(toRequester).build()));
        panels.refresh(c.id());
        Thread.ofVirtual().start(boards::refresh);
    }

    private interface ContractWork {
        void run(Contract c);
    }

    private void withContract(net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent event,
                              CustomId id, ContractWork work) {
        Optional<Contract> c = contracts.find(id.longArg(0));
        if (c.isEmpty()) {
            list(LeaderFilter.LIVE, "That contract no longer exists.").show(event);
            return;
        }
        work.run(c.get());
    }

    private Contract reload(Contract c) {
        return contracts.find(c.id()).orElse(c);
    }

    private String summary(Contract c) {
        String who = requesters.requester(c.requesterId()).map(r -> r.name() == null ? "Player " + r.tornId() : r.name())
                .orElse("Unknown");
        var stats = contracts.hitStats(c.id());
        String state = switch (c.status()) {
            case "ACTIVE" -> "Live";
            case "PAUSED" -> "Paused";
            case "PENDING_APPROVAL" -> "Waiting for approval since " + WHEN.format(c.createdAt()) + " UTC";
            case "COMPLETED" -> "Completed";
            case "ENDED" -> "Ended early";
            case "CANCELLED" -> "Cancelled";
            case "REJECTED" -> "Rejected";
            default -> c.status();
        };
        return state + " · " + who + " · " + stats.accepted() + (stats.accepted() == 1 ? " hit" : " hits")
                + (stats.billed() > 0 ? " · " + Money.shortForm(stats.billed()) + " billed" : "")
                + (c.endsAt() != null && ("ACTIVE".equals(c.status()) || "PAUSED".equals(c.status()))
                ? " · until " + WHEN.format(c.endsAt()) + " UTC" : "");
    }

    private static String icon(Contract c) {
        return switch (c.status()) {
            case "ACTIVE" -> "🟢";
            case "PAUSED" -> "⏸️";
            case "PENDING_APPROVAL" -> "⏳";
            case "COMPLETED" -> "✅";
            case "ENDED" -> "⏹️";
            default -> "✖️";
        };
    }

    private static String label(int p) {
        return PRIORITY[Math.max(0, Math.min(PRIORITY.length - 1, p))];
    }

    private void removeApprovalCard(Contract c) {
        JDA api = jda.getIfAvailable();
        if (api == null || c.approvalMessageId() == null) {
            return;
        }
        layout.get(Layout.CH_INBOX).map(api::getTextChannelById).ifPresent(ch ->
                ch.deleteMessageById(c.approvalMessageId().longValue()).queue(null, ignored -> { }));
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
