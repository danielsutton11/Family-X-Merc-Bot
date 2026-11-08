package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.board.TargetRepository;
import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.contract.ControlPanelService;
import com.familyx.mercbot.contract.ResultsFeed;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.Choices.Status;
import com.familyx.mercbot.request.Conditions;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.RequestRepositories;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The requester's DM control panel (scope doc 4.4): Pause/Resume and the Manage menu (Edit
 * conditions, Hit specific member, End now, Cancel). Only the contract's requester can use it.
 */
@Component
public class ControlPanelHandler implements InteractionHandler {

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
    private static final int LIST_SIZE = 25;
    private static final int MAX_LISTS = 4;

    private final ContractRepository contracts;
    private final RequestRepositories requesters;
    private final TargetRepository targets;
    private final ControlPanelService panels;
    private final ResultsFeed feed;
    private final BoardService boards;
    private final Layout layout;
    private final ObjectProvider<net.dv8tion.jda.api.JDA> jda;

    public ControlPanelHandler(ContractRepository contracts, RequestRepositories requesters, TargetRepository targets,
                               ControlPanelService panels, ResultsFeed feed, BoardService boards, Layout layout,
                               ObjectProvider<net.dv8tion.jda.api.JDA> jda) {
        this.contracts = contracts;
        this.requesters = requesters;
        this.targets = targets;
        this.panels = panels;
        this.feed = feed;
        this.boards = boards;
        this.layout = layout;
        this.jda = jda;
    }

    @Override
    public String action() {
        return "cp";
    }

    // ------------------------------------------------------------------ buttons

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        Optional<Contract> found = own(event, id);
        if (found.isEmpty()) {
            return;
        }
        Contract c = found.get();
        switch (id.sub()) {
            case "pause" -> {
                event.deferEdit().queue();
                if (contracts.setStatus(c.id(), List.of("ACTIVE"), "PAUSED")) {
                    feed.post("⏸️ **" + c.ref() + "** paused by the requester");
                    changed(c.id());
                }
            }
            case "resume" -> {
                event.deferEdit().queue();
                if (contracts.setStatus(c.id(), List.of("PAUSED"), "ACTIVE")) {
                    feed.post("▶️ **" + c.ref() + "** resumed by the requester");
                    changed(c.id());
                }
            }
            case "minutes" -> event.replyModal(Modal.create(CustomId.of("cp", "minutesave", c.id()),
                            "Idle or offline for how long?")
                    .addComponents(Label.of("Minutes since their last action", TextInput.create("minutes",
                            TextInputStyle.SHORT).setRequired(true).setMaxLength(4)
                            .setValue(Integer.toString(c.conditions().status() == Status.AWAY
                                    ? c.conditions().awayMinutes() : Conditions.DEFAULT_AWAY_MINUTES)).build()))
                    .build()).queue();
            case "end" -> event.replyModal(Modal.create(CustomId.of("cp", "endsave", c.id()), "Change the end time")
                    .addComponents(Label.of("End (UTC) · blank for no end", TextInput.create("end",
                            TextInputStyle.SHORT).setRequired(false).setMaxLength(16)
                            .setPlaceholder("e.g. 2026-11-09 18:00")
                            .setValue(c.endsAt() == null ? null : INPUT.format(c.endsAt().atOffset(ZoneOffset.UTC)))
                            .build()))
                    .build()).queue();
            case "hits" -> event.replyModal(Modal.create(CustomId.of("cp", "hitsave", c.id()), "Change hits")
                    .addComponents(Label.of("Hits per target", TextInput.create("hits", TextInputStyle.SHORT)
                            .setRequired(true).setMaxLength(3)
                            .setValue(c.hitsPerTarget() == null ? "1" : Integer.toString(c.hitsPerTarget())).build()))
                    .build()).queue();
            case "notes" -> event.replyModal(Modal.create(CustomId.of("cp", "notesave", c.id()), "Notes for leaders")
                    .addComponents(Label.of("Notes (blank to clear)", TextInput.create("notes", TextInputStyle.PARAGRAPH)
                            .setRequired(false).setMaxLength(500).setValue(c.notes()).build()))
                    .build()).queue();
            case "endyes" -> {
                if (contracts.setStatus(c.id(), List.of("ACTIVE", "PAUSED"), "ENDED")) {
                    feed.post("⏹️ **" + c.ref() + "** ended early by the requester");
                    changed(c.id());
                    closing(event, "⏹️ **" + c.ref() + "** has ended. No more hits will count.");
                } else {
                    closing(event, "That contract isn't running any more.");
                }
            }
            case "cancelyes" -> {
                if (contracts.hitStats(c.id()).accepted() > 0) {
                    closing(event, "A hit has already been accepted, so this contract can't be cancelled. Use End now.");
                    return;
                }
                boolean wasPending = "PENDING_APPROVAL".equals(c.status());
                if (contracts.setStatus(c.id(), List.of("PENDING_APPROVAL", "ACTIVE", "PAUSED"), "CANCELLED")) {
                    if (wasPending) {
                        removeApprovalCard(c);
                    } else {
                        feed.post("✖️ **" + c.ref() + "** cancelled by the requester");
                    }
                    changed(c.id());
                    closing(event, "✖️ **" + c.ref() + "** is cancelled. Nothing is billed.");
                } else {
                    closing(event, "That contract can't be cancelled now.");
                }
            }
            case "close" -> closing(event, "Saved.");
            default -> closing(event, "Unknown option.");
        }
    }

    // ------------------------------------------------------------------ menus

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        Optional<Contract> found = own(event, id);
        if (found.isEmpty()) {
            return;
        }
        Contract c = found.get();
        String v = event.getValues().isEmpty() ? "" : event.getValues().getFirst();
        switch (id.sub()) {
            case "manage" -> {
                Screen screen = switch (v) {
                    case "edit" -> editScreen(c, null);
                    case "override" -> overrideScreen(c, null);
                    case "end" -> Screen.of(new EmbedBuilder().setColor(Panels.WARN).setAuthor("CONTRACT " + c.ref())
                                    .setTitle("⏹️  End this contract now?")
                                    .setDescription("No more hits will count. The hits made so far are invoiced.").build(),
                            ActionRow.of(Button.danger(CustomId.of("cp", "endyes", c.id()), "End now"),
                                    Button.secondary(CustomId.of("cp", "close", c.id()), "Keep it running")));
                    case "cancel" -> Screen.of(new EmbedBuilder().setColor(Panels.WARN).setAuthor("CONTRACT " + c.ref())
                                    .setTitle("✖️  Cancel this contract?")
                                    .setDescription("It comes off the boards and nothing is billed.").build(),
                            ActionRow.of(Button.danger(CustomId.of("cp", "cancelyes", c.id()), "Cancel contract"),
                                    Button.secondary(CustomId.of("cp", "close", c.id()), "Keep it")));
                    default -> null;
                };
                if (screen != null) {
                    screen.show(event);
                    // Reset the menu on the panel so the same option can be picked again later.
                    Thread.ofVirtual().start(() -> panels.forceRefresh(c.id()));
                }
            }
            case "status" -> {
                Status s = Status.valueOf(v);
                if (s == Status.AWAY) {
                    event.replyModal(Modal.create(CustomId.of("cp", "minutesave", c.id()), "Idle or offline for how long?")
                            .addComponents(Label.of("Minutes since their last action", TextInput.create("minutes",
                                    TextInputStyle.SHORT).setRequired(true).setMaxLength(4)
                                    .setValue(Integer.toString(Conditions.DEFAULT_AWAY_MINUTES)).build()))
                            .build()).queue();
                    return;
                }
                contracts.updateConditions(c.id(), c.conditions().withStatus(s, Conditions.DEFAULT_AWAY_MINUTES));
                changed(c.id());
                editScreen(reload(c), "✅ Saved.").show(event);
            }
            case "reviv" -> {
                contracts.updateConditions(c.id(), c.conditions().withRevivable("hit".equals(v)));
                changed(c.id());
                editScreen(reload(c), "✅ Saved.").show(event);
            }
            case "early" -> {
                contracts.updateConditions(c.id(), c.conditions().withEarlyDischarge("hit".equals(v)));
                changed(c.id());
                editScreen(reload(c), "✅ Saved.").show(event);
            }
            case "pick" -> {
                long targetRow = Long.parseLong(v);
                Optional<BoardTarget> t = targets.forContract(c.id()).stream().filter(x -> x.id() == targetRow).findFirst();
                if (t.isEmpty()) {
                    overrideScreen(c, "❌ That member is no longer a target.").show(event);
                    return;
                }
                boolean revivableBlock = !c.conditions().allowRevivable() && Boolean.TRUE.equals(t.get().revivable());
                targets.addOverride(targetRow, revivableBlock ? "REVIVABLE" : "STATUS");
                changed(c.id());
                event.editMessage(new MessageEditBuilder().setReplace(true).setEmbeds(new EmbedBuilder()
                                .setColor(Panels.GOOD).setTitle("🎯  One hit allowed on " + t.get().name())
                                .setDescription("They show on the board, flagged \"requester override, 1 hit\", as soon "
                                        + "as they can be hit.").build()).build())
                        .queue(h -> AutoDismiss.after(h, AutoDismiss.REPORT));
            }
            default -> closing(event, "Unknown option.");
        }
    }

    // ------------------------------------------------------------------ boxes

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        long contractId = id.longArg(0);
        Optional<Contract> found = contracts.find(contractId).filter(c -> isOwner(c, event.getUser().getIdLong()));
        InteractionHook hook = Screen.deferInPlace(event);
        if (found.isEmpty() || !found.get().open()) {
            hook.editOriginal("That contract can't be changed now.").queue(m -> AutoDismiss.after(hook, AutoDismiss.SHORT));
            return;
        }
        Contract c = found.get();
        String text = event.getValue(fieldOf(id.sub())) == null ? "" : event.getValue(fieldOf(id.sub())).getAsString().trim();
        String notice;
        switch (id.sub()) {
            case "minutesave" -> {
                Integer m = parseInt(text);
                if (m == null || m < 1 || m > 1440) {
                    notice = "❌ Enter a whole number of minutes from 1 to 1440.";
                } else {
                    contracts.updateConditions(c.id(), c.conditions().withStatus(Status.AWAY, m));
                    notice = "✅ Saved.";
                }
            }
            case "endsave" -> {
                if (text.isBlank() || "none".equalsIgnoreCase(text)) {
                    contracts.updateEnd(c.id(), null);
                    notice = "✅ No end time.";
                } else {
                    try {
                        Instant end = LocalDateTime.parse(text, INPUT).toInstant(ZoneOffset.UTC);
                        if (!end.isAfter(Instant.now())) {
                            notice = "❌ The end has to be in the future. Use End now to stop it straight away.";
                        } else {
                            contracts.updateEnd(c.id(), end);
                            notice = "✅ Ends " + SHOW.format(end) + " UTC.";
                        }
                    } catch (DateTimeParseException e) {
                        notice = "❌ Times should look like 2026-11-08 18:00 (UTC).";
                    }
                }
            }
            case "hitsave" -> {
                Integer h = parseInt(text);
                if (h == null || h < 1 || h > 100) {
                    notice = "❌ Hits should be a number from 1 to 100.";
                } else {
                    contracts.updateHits(c.id(), h);
                    notice = "✅ Saved.";
                }
            }
            case "notesave" -> {
                contracts.updateNotes(c.id(), text.isBlank() ? null : text);
                notice = "✅ Notes saved.";
            }
            default -> notice = "Unknown option.";
        }
        changed(c.id());
        editScreen(reload(c), notice).show(hook);
    }

    // ------------------------------------------------------------------ screens

    private Screen editScreen(Contract c, String notice) {
        Conditions k = c.conditions();
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.PRIVATE).setAuthor("CONTRACT " + c.ref())
                .setTitle("🎛️  Edit conditions")
                .setDescription((notice == null ? "" : notice + "\n\n")
                        + "Changes apply to later hits. Tier and attack type need a new contract." + Panels.GAP);
        List<ActionRow> rows = new ArrayList<>();
        if (!c.mercMe()) {
            rows.add(ActionRow.of(StringSelectMenu.create(CustomId.of("cp", "status", c.id()))
                    .addOption("Status: Any", Status.ANY.name())
                    .addOption("Status: Online", Status.ONLINE.name())
                    .addOption(k.status() == Status.AWAY ? "Status: Idle/Offline " + k.awayMinutes() + "+ min"
                            : "Status: Idle/Offline for X+ minutes", Status.AWAY.name(), "You choose the minutes")
                    .setDefaultValues(k.status().name()).build()));
            rows.add(ActionRow.of(StringSelectMenu.create(CustomId.of("cp", "reviv", c.id()))
                    .addOption("Revivable: Hit", "hit").addOption("Revivable: Don't hit", "skip")
                    .setDefaultValues(k.allowRevivable() ? "hit" : "skip").build()));
            if (c.attack() == Attack.HOSPITALIZE) {
                rows.add(ActionRow.of(StringSelectMenu.create(CustomId.of("cp", "early", c.id()))
                        .addOption("Early discharge: Hit", "hit").addOption("Early discharge: Skip", "skip")
                        .setDefaultValues(k.allowEarlyDischarge() ? "hit" : "skip").build()));
            }
        }
        List<Button> buttons = new ArrayList<>();
        if (!c.mercMe() && k.status() == Status.AWAY) {
            buttons.add(Button.secondary(CustomId.of("cp", "minutes", c.id()), "Change minutes").withEmoji(Emoji.fromUnicode("⏳")));
        }
        buttons.add(c.mode() == Mode.HITS
                ? Button.secondary(CustomId.of("cp", "hits", c.id()), "Change hits").withEmoji(Emoji.fromUnicode("🔢"))
                : Button.secondary(CustomId.of("cp", "end", c.id()), "Change end time").withEmoji(Emoji.fromUnicode("🕒")));
        buttons.add(Button.secondary(CustomId.of("cp", "notes", c.id()), "Notes").withEmoji(Emoji.fromUnicode("📝")));
        buttons.add(Button.primary(CustomId.of("cp", "close", c.id()), "Done").withEmoji(Emoji.fromUnicode("✅")));
        rows.add(ActionRow.of(buttons));
        return new Screen(eb.build(), rows);
    }

    private Screen overrideScreen(Contract c, String notice) {
        List<BoardTarget> list = targets.forContract(c.id());
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.PRIVATE).setAuthor("CONTRACT " + c.ref() + " · HIT SPECIFIC MEMBER")
                .setTitle("🎯  Allow one hit on…")
                .setDescription((notice == null ? "" : notice + "\n\n")
                        + "This overrides your set conditions for one hit. A status override is billed at the contract's "
                        + "normal rate. A revivable override follows the revivable rules: on Gold, no Stricken and "
                        + "billed at the Silver rate." + Panels.GAP);
        List<ActionRow> rows = new ArrayList<>();
        if (list.size() > LIST_SIZE * MAX_LISTS) {
            eb.addField("​", "-# This contract has too many targets to list them all; the first "
                    + LIST_SIZE * MAX_LISTS + " are shown.", false);
        }
        for (int i = 0; i < MAX_LISTS && i * LIST_SIZE < list.size(); i++) {
            List<BoardTarget> chunk = list.subList(i * LIST_SIZE, Math.min(list.size(), (i + 1) * LIST_SIZE));
            StringSelectMenu.Builder menu = StringSelectMenu.create(CustomId.of("cp", "pick", c.id(), i))
                    .setPlaceholder(initial(chunk.getFirst()) + "–" + initial(chunk.getLast()) + " (" + chunk.size() + ")");
            for (BoardTarget t : chunk) {
                menu.addOption(t.name() == null ? "Player " + t.tornId() : t.name(), Long.toString(t.id()),
                        "[" + t.tornId() + "]" + (t.level() == null ? "" : " · Level " + t.level()));
            }
            rows.add(ActionRow.of(menu.build()));
        }
        if (rows.isEmpty()) {
            return Screen.of(eb.setDescription("This contract has no targets right now.").build(),
                    ActionRow.of(Button.secondary(CustomId.of("cp", "close", c.id()), "Close")));
        }
        return new Screen(eb.build(), rows);
    }

    // ------------------------------------------------------------------ helpers

    /** The contract, if the person clicking placed it; otherwise a private refusal. */
    private Optional<Contract> own(GenericComponentInteractionCreateEvent event, CustomId id) {
        long contractId = id.longArg(0);
        Optional<Contract> c = contracts.find(contractId).filter(x -> isOwner(x, event.getUser().getIdLong()));
        if (c.isEmpty()) {
            event.reply("That isn't your contract.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            return Optional.empty();
        }
        if (!c.get().open() && !"close".equals(id.sub())) {
            event.reply("**" + c.get().ref() + "** has finished, so it can't be changed.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            panels.refresh(contractId);
            return Optional.empty();
        }
        return c;
    }

    private boolean isOwner(Contract c, long discordId) {
        return requesters.requester(c.requesterId()).map(r -> r.discordId() == discordId).orElse(false);
    }

    private Contract reload(Contract c) {
        return contracts.find(c.id()).orElse(c);
    }

    private void changed(long contractId) {
        panels.refresh(contractId);
        Thread.ofVirtual().start(boards::refresh);
    }

    private void removeApprovalCard(Contract c) {
        net.dv8tion.jda.api.JDA api = jda.getIfAvailable();
        if (api == null || c.approvalMessageId() == null) {
            return;
        }
        layout.get(Layout.CH_INBOX).map(id -> api.getTextChannelById(id)).ifPresent(ch ->
                ch.deleteMessageById(c.approvalMessageId().longValue()).queue(null, ignored -> { }));
    }

    private static void closing(GenericComponentInteractionCreateEvent event, String text) {
        if (event.getMessage().isEphemeral()) {
            event.editMessage(new MessageEditBuilder().setReplace(true).setContent(text).build())
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        } else {
            event.reply(text).setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        }
    }

    private static String fieldOf(String sub) {
        return switch (sub) {
            case "minutesave" -> "minutes";
            case "endsave" -> "end";
            case "hitsave" -> "hits";
            default -> "notes";
        };
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String initial(BoardTarget t) {
        String n = t.name() == null ? "" : t.name();
        return !n.isEmpty() && Character.isLetter(n.charAt(0)) ? n.substring(0, 1).toUpperCase() : "#";
    }
}
