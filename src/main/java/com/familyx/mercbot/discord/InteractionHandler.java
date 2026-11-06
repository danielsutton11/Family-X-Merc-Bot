package com.familyx.mercbot.discord;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

/**
 * One handler per custom-id action (for example "keys" for {@code merc:keys:*}).
 * Handlers run on virtual threads, so blocking calls are fine, but anything slower than
 * about two seconds must defer the reply first.
 */
public interface InteractionHandler {

    /** The action segment this handler owns. */
    String action();

    /** Who may use this handler's components. */
    default Access.Level access() {
        return Access.Level.ANYONE;
    }

    default void button(ButtonInteractionEvent event, CustomId id) throws Exception {
        throw new UnsupportedOperationException(id.toString());
    }

    default void stringSelect(StringSelectInteractionEvent event, CustomId id) throws Exception {
        throw new UnsupportedOperationException(id.toString());
    }

    default void entitySelect(EntitySelectInteractionEvent event, CustomId id) throws Exception {
        throw new UnsupportedOperationException(id.toString());
    }

    default void modal(ModalInteractionEvent event, CustomId id) throws Exception {
        throw new UnsupportedOperationException(id.toString());
    }

    /** Slash commands this handler owns (by name). The only one in the design is /merc-setup. */
    default boolean handlesCommand(String name) {
        return false;
    }

    default void command(SlashCommandInteractionEvent event) throws Exception {
        throw new UnsupportedOperationException(event.getName());
    }
}
