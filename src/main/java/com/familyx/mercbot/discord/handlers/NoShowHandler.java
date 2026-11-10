package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.suspension.SuspensionService;
import com.familyx.mercbot.suspension.SuspensionService.Length;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Suspend claiming, Lift now, Ignore and Keep (scope doc 5.6, 6.4), on the no-show alert card,
 * the weekly reminder card and a merc's page under Mercs. Merc Managers only.
 */
@Component
public class NoShowHandler implements InteractionHandler {

    private final SuspensionService service;
    private final ObjectProvider<MercsHandler> mercsScreen;

    public NoShowHandler(SuspensionService service, ObjectProvider<MercsHandler> mercsScreen) {
        this.service = service;
        this.mercsScreen = mercsScreen;
    }

    @Override
    public String action() {
        return "noshow";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        if (!"suspend".equals(id.sub())) {
            return;
        }
        long merc = id.longArg(0);
        Length length = Length.valueOf(event.getValues().getFirst());
        boolean fromScreen = event.getMessage().isEphemeral();
        event.replyModal(Modal.create(CustomId.of("noshow", "suspendsave", merc, length.name(), fromScreen ? "s" : "card",
                                Long.toString(System.nanoTime() % 1_000_000_000L, 36)),
                        "Suspend claiming · " + length.label.toLowerCase())
                .addComponents(Label.of("Reason (optional, sent to the merc)", TextInput.create("reason",
                        TextInputStyle.SHORT).setRequired(false).setMaxLength(200)
                        .setPlaceholder("e.g. Repeated expired claims").build()))
                .build()).queue();
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        long merc = id.longArg(0);
        Length length = Length.valueOf(id.arg(1));
        String reason = event.getValue("reason") == null ? null : event.getValue("reason").getAsString();
        long user = event.getUser().getIdLong();
        if ("s".equals(id.arg(2))) {
            var hook = Screen.deferInPlace(event);
            service.suspend(merc, length, reason, user);
            mercsScreen.getObject().screen(merc, "⛔ Claiming suspended (" + length.label.toLowerCase()
                    + "). They've been told.").show(hook);
            return;
        }
        event.deferEdit().complete(); // the card itself is edited by the service
        service.suspend(merc, length, reason, user);
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long merc = id.longArg(0);
        long user = event.getUser().getIdLong();
        boolean fromScreen = event.getMessage().isEphemeral();
        switch (id.sub()) {
            case "lift" -> {
                if (fromScreen) {
                    var hook = Screen.deferInPlace(event);
                    boolean done = service.lift(merc, user);
                    mercsScreen.getObject().screen(merc, done ? "✅ Claiming is back on. They've been told."
                            : "They weren't suspended.").show(hook);
                    return;
                }
                event.deferEdit().complete();
                boolean done = service.lift(merc, user);
                boolean reminder = event.getMessage().getEmbeds().stream()
                        .anyMatch(e -> e.getAuthor() != null && "SUSPENSION REMINDER".equals(e.getAuthor().getName()));
                if (reminder || !done) {
                    event.getHook().editOriginal(new MessageEditBuilder().setReplace(true)
                            .setContent(done ? "✅ Suspension lifted by <@" + user + ">." : "That suspension had already ended.")
                            .build()).queue(null, ignored -> { });
                }
            }
            case "keep" -> event.deferEdit().queue(h -> h.deleteOriginal().queue(null, ignored -> { }));
            case "ignore" -> {
                event.deferEdit().complete();
                service.ignore(merc, user);
            }
            default -> note(event, "Unknown option.");
        }
    }

    private static void note(IReplyCallback event, String text) {
        event.reply(text).setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
    }
}
