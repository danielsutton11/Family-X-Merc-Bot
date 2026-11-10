package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
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
        Screen.of(
                new EmbedBuilder()
                        .setColor(Panels.LEADER)
                        .setAuthor("MERC MANAGERS")
                        .setTitle("⚙️  Settings")
                        .setDescription("Pick a group to change.\n-# Greyed-out groups arrive in later build phases.")
                        .build(),
                ActionRow.of(
                        Button.primary(CustomId.of("factions", "view"), "Factions").withEmoji(Emoji.fromUnicode("🏰")),
                        Button.primary(CustomId.of("reqdef", "view"), "Requests").withEmoji(Emoji.fromUnicode("📝")),
                        Button.primary(CustomId.of("tiers", "view"), "Tiers").withEmoji(Emoji.fromUnicode("🏷️")),
                        Button.secondary(CustomId.of("soon", "3", "boards"), "Boards").asDisabled(),
                        Button.primary(CustomId.of("repset", "view"), "Reputation").withEmoji(Emoji.fromUnicode("⭐"))),
                ActionRow.of(
                        Button.primary(CustomId.of("claimset", "view"), "Claims").withEmoji(Emoji.fromUnicode("🔒")),
                        Button.secondary(CustomId.of("soon", "4", "billing"), "Billing").asDisabled(),
                        Button.primary(CustomId.of("payset", "view"), "Payments").withEmoji(Emoji.fromUnicode("💳")),
                        Button.primary(CustomId.of("alertset", "view"), "Alerts").withEmoji(Emoji.fromUnicode("🚨"))),
                ActionRow.of(
                        Button.danger(CustomId.of("reset", "view"), "Hard reset…").withEmoji(Emoji.fromUnicode("🧨"))))
                .show(event);
    }
}
