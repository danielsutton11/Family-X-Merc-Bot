package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.springframework.stereotype.Component;

/**
 * Leader panel → Settings. Grouped as in scope doc section 9; each group is switched on
 * in the phase that builds it. Factions is live from phase 1.
 */
@Component
public class SettingsHandler implements InteractionHandler {

    @Override
    public String action() {
        return "settings";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(Panels.LEADER)
                .setTitle("Settings")
                .setDescription("Pick a group. Groups that are greyed out arrive in later build phases.");
        event.replyEmbeds(eb.build()).setComponents(
                ActionRow.of(
                        Button.primary(CustomId.of("factions", "view"), "Factions"),
                        Button.secondary(CustomId.of("soon", "3"), "Requests").asDisabled(),
                        Button.secondary(CustomId.of("soon", "2"), "Tiers").asDisabled(),
                        Button.secondary(CustomId.of("soon", "3"), "Boards").asDisabled()),
                ActionRow.of(
                        Button.secondary(CustomId.of("soon", "3"), "Claims").asDisabled(),
                        Button.secondary(CustomId.of("soon", "4"), "Billing").asDisabled(),
                        Button.secondary(CustomId.of("soon", "4"), "Payments").asDisabled(),
                        Button.secondary(CustomId.of("soon", "5"), "Alerts").asDisabled()))
                .setEphemeral(true).queue();
    }
}
