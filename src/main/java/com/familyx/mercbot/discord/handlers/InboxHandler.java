package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.inbox.InboxService;
import com.familyx.mercbot.inbox.Messaging;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.RequestRepositories;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The inbox card's Reply and No reply needed, and Reply on the leader Contracts view
 * (scope doc 4.5). Merc Managers only.
 */
@Component
public class InboxHandler implements InteractionHandler {

    private final InboxService inbox;
    private final ContractRepository contracts;
    private final RequestRepositories requesters;
    private final ObjectProvider<LeaderContractsHandler> leaderContracts;

    public InboxHandler(InboxService inbox, ContractRepository contracts, RequestRepositories requesters,
                        ObjectProvider<LeaderContractsHandler> leaderContracts) {
        this.inbox = inbox;
        this.contracts = contracts;
        this.requesters = requesters;
        this.leaderContracts = leaderContracts;
    }

    @Override
    public String action() {
        return "inbox";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        Optional<Contract> c = find(event, id.longArg(0));
        if (c.isEmpty()) {
            return;
        }
        switch (id.sub()) {
            case "reply" -> {
                String who = requesters.requester(c.get().requesterId()).map(r -> r.name()).filter(n -> n != null)
                        .orElse("the requester");
                // "c" = sent from the Contracts view, so the reply refreshes that screen in place.
                boolean fromScreen = event.getMessage().isEphemeral();
                event.replyModal(Modal.create(CustomId.of("inbox", "replysave", c.get().id(), fromScreen ? "c" : "card",
                                        Long.toString(System.nanoTime() % 1_000_000_000L, 36)),
                                trim("Reply to " + who + " · " + c.get().ref(), 45))
                        .addComponents(Label.of("Your reply (sent as a DM with your name)",
                                TextInput.create("text", TextInputStyle.PARAGRAPH).setRequired(true)
                                        .setMaxLength(Messaging.MAX_LENGTH).build()))
                        .build()).queue();
            }
            case "done" -> {
                event.deferEdit().complete();
                inbox.dismiss(c.get(), event.getUser().getIdLong());
            }
            default -> note(event, "Unknown option.");
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        Optional<Contract> c = find(event, id.longArg(0));
        if (c.isEmpty()) {
            return;
        }
        String text = event.getValue("text") == null ? "" : event.getValue("text").getAsString().strip();
        if (text.isEmpty()) {
            note(event, "The reply was empty, so nothing was sent.");
            return;
        }
        String name = event.getMember() != null ? event.getMember().getEffectiveName() : event.getUser().getName();
        if ("c".equals(id.arg(1))) {
            var hook = Screen.deferInPlace(event);
            inbox.reply(c.get(), event.getUser().getIdLong(), name, text);
            leaderContracts.getObject().screen(contracts.find(c.get().id()).orElse(c.get()), "↩️ Reply sent.").show(hook);
            return;
        }
        var hook = event.deferReply(true).complete();
        inbox.reply(c.get(), event.getUser().getIdLong(), name, text);
        hook.editOriginal("↩️ Reply sent to the requester.").queue(h -> AutoDismiss.after(hook, AutoDismiss.SHORT));
    }

    private Optional<Contract> find(IReplyCallback event, long id) {
        Optional<Contract> c = contracts.find(id);
        if (c.isEmpty()) {
            note(event, "That contract no longer exists.");
        }
        return c;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void note(IReplyCallback event, String text) {
        event.reply(text).setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
    }
}
