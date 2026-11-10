package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.settings.BotSettings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Settings → Alerts (scope doc 9): other merc service triggers, urgent pings, availability-off reminder. */
@Component
public class AlertSettingsHandler implements InteractionHandler {

    private final BotSettings settings;
    private final AuditService audit;

    public AlertSettingsHandler(BotSettings settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "alertset";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        if (!"edit".equals(id.sub())) {
            screen(null).show(event);
            return;
        }
        event.replyModal(Modal.create(CustomId.of("alertset", "save", Long.toString(System.nanoTime() % 1_000_000_000L, 36)),
                        "Alert settings")
                .addComponents(
                        pair("offensive", "Offensive service trigger: count / minutes",
                                settings.offensiveCount() + " / " + settings.offensiveMinutes(), "e.g. 3 / 30"),
                        pair("pings", "Urgent pings: how many / minutes apart",
                                settings.urgentPings() + " / " + settings.urgentPingGapMinutes(), "e.g. 3 / 10"),
                        pair("defensive", "Defensive service trigger: count / minutes",
                                settings.defensiveCount() + " / " + settings.defensiveMinutes(), "e.g. 3 / 30"),
                        Label.of("Availability-off reminder gap (hours)", TextInput.create("avail",
                                TextInputStyle.SHORT).setRequired(true).setMaxLength(3).setPlaceholder("e.g. 6")
                                .setValue(Integer.toString(settings.availabilityOffHours())).build()))
                .build()).queue();
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        long user = event.getUser().getIdLong();
        int[] off = pair(event, "offensive");
        int[] pings = pair(event, "pings");
        int[] def = pair(event, "defensive");
        Integer avail = number(event, "avail");
        String error = off == null || off[0] < 1 || off[0] > 50 || off[1] < 1 || off[1] > 1440
                ? "Offensive trigger should look like 3 / 30 (1–50 events, 1–1440 minutes)."
                : pings == null || pings[0] < 0 || pings[0] > 10 || pings[1] < 1 || pings[1] > 120
                ? "Urgent pings should look like 3 / 10 (0–10 pings, 1–120 minutes apart)."
                : def == null || def[0] < 1 || def[0] > 50 || def[1] < 1 || def[1] > 1440
                ? "Defensive trigger should look like 3 / 30 (1–50 events, 1–1440 minutes)."
                : avail == null || avail < 1 || avail > 168 ? "Availability-off reminder should be 1 to 168 hours." : null;
        if (error != null) {
            screen("❌ " + error + " Nothing was changed.").show(hook);
            return;
        }
        Map<String, Object> before = current();
        settings.set(BotSettings.ALERT_OFFENSIVE_COUNT, off[0], user);
        settings.set(BotSettings.ALERT_OFFENSIVE_MIN, off[1], user);
        settings.set(BotSettings.ALERT_PING_COUNT, pings[0], user);
        settings.set(BotSettings.ALERT_PING_GAP_MIN, pings[1], user);
        settings.set(BotSettings.ALERT_DEFENSIVE_COUNT, def[0], user);
        settings.set(BotSettings.ALERT_DEFENSIVE_MIN, def[1], user);
        settings.set(BotSettings.ALERT_AVAIL_HOURS, avail, user);
        Map<String, Object> after = current();
        if (!before.equals(after)) {
            audit.log(user, "SETTING_CHANGED", "settings:alerts", before, after, "Changed the alert settings");
        }
        screen("✅ Alert settings saved.").show(hook);
    }

    private Screen screen(String notice) {
        String body = (notice == null ? "" : notice + "\n\n")
                + "Offensive service trigger: **" + settings.offensiveCount() + " in " + settings.offensiveMinutes() + " min**\n"
                + "-# Mugs or attacks by outsiders on one contract; sends urgent pings to its tier\n"
                + "Urgent pings: **" + settings.urgentPings() + ", " + settings.urgentPingGapMinutes() + " min apart**\n"
                + "Defensive service trigger: **" + settings.defensiveCount() + " in " + settings.defensiveMinutes() + " min**\n"
                + "-# Hospitalisations by outsiders on one contract; a card for leaders\n"
                + "Availability-off reminder: **every " + settings.availabilityOffHours() + " h at most**\n"
                + "-# The no-show leader alert is in Settings → Claims." + Panels.GAP;
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("SETTINGS").setTitle("🚨  Alerts")
                        .setDescription(body).build(),
                ActionRow.of(Button.primary(CustomId.of("alertset", "edit"), "Edit…").withEmoji(Emoji.fromUnicode("✏️")),
                        Button.secondary(CustomId.of("settings", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private Map<String, Object> current() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("offensive", settings.offensiveCount() + "/" + settings.offensiveMinutes());
        m.put("urgentPings", settings.urgentPings() + "/" + settings.urgentPingGapMinutes());
        m.put("defensive", settings.defensiveCount() + "/" + settings.defensiveMinutes());
        m.put("availabilityOffHours", settings.availabilityOffHours());
        return m;
    }

    private static Label pair(String id, String label, String value, String placeholder) {
        return Label.of(label, TextInput.create(id, TextInputStyle.SHORT).setRequired(true).setMaxLength(9)
                .setPlaceholder(placeholder).setValue(value).build());
    }

    private static int[] pair(ModalInteractionEvent event, String id) {
        var v = event.getValue(id);
        if (v == null) {
            return null;
        }
        String[] parts = v.getAsString().split("/");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer number(ModalInteractionEvent event, String id) {
        var v = event.getValue(id);
        try {
            return v == null ? null : Integer.parseInt(v.getAsString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
