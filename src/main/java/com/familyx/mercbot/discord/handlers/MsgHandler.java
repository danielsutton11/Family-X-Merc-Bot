package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
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
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Message leaders, for requesters (scope doc 4.5): from the control panel, invoices, reminders and replies. */
@Component
public class MsgHandler implements InteractionHandler {

    private final InboxService inbox;
    private final ContractRepository contracts;
    private final RequestRepositories requesters;

    public MsgHandler(InboxService inbox, ContractRepository contracts, RequestRepositories requesters) {
        this.inbox = inbox;
        this.contracts = contracts;
        this.requesters = requesters;
    }

    @Override
    public String action() {
        return "msg";
    }

    @Override
    public Access.Level access() {
        return Access.Level.ANYONE;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        Optional<Contract> c = own(event, id.longArg(0));
        if (c.isEmpty()) {
            return;
        }
        String no = inbox.refusal(c.get(), event.getUser().getIdLong());
        if (no != null) {
            note(event, no);
            return;
        }
        event.replyModal(Modal.create(CustomId.of("msg", "send", c.get().id(),
                                Long.toString(System.nanoTime() % 1_000_000_000L, 36)), "Message leaders · " + c.get().ref())
                .addComponents(Label.of("Your message", TextInput.create("text", TextInputStyle.PARAGRAPH)
                        .setRequired(true).setMaxLength(Messaging.MAX_LENGTH)
                        .setPlaceholder("Questions about this contract, payment or anything else").build()))
                .build()).queue();
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        Optional<Contract> c = own(event, id.longArg(0));
        if (c.isEmpty()) {
            return;
        }
        String no = inbox.refusal(c.get(), event.getUser().getIdLong());
        if (no != null) {
            note(event, no);
            return;
        }
        String text = event.getValue("text") == null ? "" : event.getValue("text").getAsString().strip();
        if (text.isEmpty()) {
            note(event, "The message was empty, so nothing was sent.");
            return;
        }
        var hook = event.deferReply(true).complete();
        String name = requesters.requester(c.get().requesterId()).map(r -> r.name()).filter(n -> n != null)
                .orElse(event.getUser().getName());
        inbox.fromRequester(c.get(), event.getUser().getIdLong(), name, text);
        hook.editOriginal("✉️ Sent to the leaders. Their reply arrives here in DMs.")
                .queue(h -> AutoDismiss.after(hook, AutoDismiss.READING));
    }

    private Optional<Contract> own(IReplyCallback event, long contractId) {
        long user = event.getUser().getIdLong();
        Optional<Contract> c = contracts.find(contractId).filter(x ->
                requesters.requester(x.requesterId()).map(r -> r.discordId() == user).orElse(false));
        if (c.isEmpty()) {
            note(event, "That isn't your contract.");
        }
        return c;
    }

    private static void note(IReplyCallback event, String text) {
        event.reply(text).setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
    }
}
