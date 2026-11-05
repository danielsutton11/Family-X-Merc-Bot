package com.familyx.mercbot.discord;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.util.List;

/**
 * One private "screen" per person. A click on a pinned (public) panel opens it as a private
 * reply; every click inside that private message replaces its content in place, so
 * navigating never stacks up new messages.
 */
public record Screen(MessageEmbed embed, List<ActionRow> rows) {

    public static Screen of(MessageEmbed embed, ActionRow... rows) {
        return new Screen(embed, List.of(rows));
    }

    /** Shows the screen: edits the private message if clicked inside one, otherwise opens one. */
    public void show(GenericComponentInteractionCreateEvent event) {
        if (event.getMessage().isEphemeral()) {
            event.editMessage(editData()).queue();
        } else {
            event.replyEmbeds(embed).setComponents(rows).setEphemeral(true).queue();
        }
    }

    /** After {@code deferEdit()}, for work that takes more than a moment (Torn API calls). */
    public void show(InteractionHook hook) {
        hook.editOriginal(editData()).queue();
    }

    /**
     * Starts slow work from a modal that was opened by a button in a private screen.
     * Returns the hook to pass to {@link #show(InteractionHook)}.
     */
    public static InteractionHook deferInPlace(ModalInteractionEvent event) {
        if (event.getMessage() != null && event.getMessage().isEphemeral()) {
            event.deferEdit().queue();
        } else {
            event.deferReply(true).queue();
        }
        return event.getHook();
    }

    private MessageEditData editData() {
        // Replace the whole message so any old text content is cleared too.
        return new MessageEditBuilder().setReplace(true).setEmbeds(embed).setComponents(rows).build();
    }
}
