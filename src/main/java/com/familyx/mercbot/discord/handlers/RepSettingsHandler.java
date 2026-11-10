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

/** Settings → Reputation (scope doc 6.5, 9): the starting score and each change. */
@Component
public class RepSettingsHandler implements InteractionHandler {

    private final BotSettings settings;
    private final AuditService audit;

    public RepSettingsHandler(BotSettings settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "repset";
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
        event.replyModal(Modal.create(CustomId.of("repset", "save", Long.toString(System.nanoTime() % 1_000_000_000L, 36)),
                        "Reputation settings")
                .addComponents(
                        Label.of("Starting score (0–100)", TextInput.create("start", TextInputStyle.SHORT).setRequired(true)
                                .setMaxLength(3).setValue(Integer.toString(settings.repStart())).build()),
                        pair("plus", "Paid on time / completed: points added",
                                settings.repOnTime() + " / " + settings.repCompleted(), "e.g. 5 / 2"),
                        Label.of("Taken off: late / part paid / unpaid 7 days", TextInput.create("minus",
                                TextInputStyle.SHORT).setRequired(true).setMaxLength(14).setPlaceholder("e.g. 10 / 15 / 25")
                                .setValue(settings.repLate() + " / " + settings.repPartial() + " / " + settings.repUnpaidWeek())
                                .build()))
                .build()).queue();
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        long user = event.getUser().getIdLong();
        Integer start = number(event, "start");
        int[] plus = pair(event, "plus");
        int[] minus = triple(event, "minus");
        String error = start == null || start < 0 || start > 100 ? "Starting score should be 0 to 100."
                : plus == null || plus[0] < 0 || plus[0] > 100 || plus[1] < 0 || plus[1] > 100
                ? "Points added should look like 5 / 2 (0–100 each)."
                : minus == null || minus[0] < 0 || minus[0] > 100 || minus[1] < 0 || minus[1] > 100 || minus[2] < 0 || minus[2] > 100
                ? "Points taken off should look like 10 / 15 / 25 (0–100 each)." : null;
        if (error != null) {
            screen("❌ " + error + " Nothing was changed.").show(hook);
            return;
        }
        Map<String, Object> before = current();
        settings.set(BotSettings.REP_START, start, user);
        settings.set(BotSettings.REP_ON_TIME, plus[0], user);
        settings.set(BotSettings.REP_COMPLETED, plus[1], user);
        settings.set(BotSettings.REP_LATE, minus[0], user);
        settings.set(BotSettings.REP_PARTIAL, minus[1], user);
        settings.set(BotSettings.REP_UNPAID_WEEK, minus[2], user);
        Map<String, Object> after = current();
        if (!before.equals(after)) {
            audit.log(user, "SETTING_CHANGED", "settings:reputation", before, after, "Changed the reputation settings");
        }
        screen("✅ Reputation settings saved.").show(hook);
    }

    private Screen screen(String notice) {
        String body = (notice == null ? "" : notice + "\n\n")
                + "Starting score: **" + settings.repStart() + "**\n"
                + "Paid on time / completed: **+" + settings.repOnTime() + " / +" + settings.repCompleted() + "**\n"
                + "Late / part paid at due / unpaid after 7 days: **−" + settings.repLate() + " / −" + settings.repPartial()
                + " / −" + settings.repUnpaidWeek() + "**\n"
                + "-# Scores stay between 0 and 100. The auto-accept threshold is in Settings → Requests." + Panels.GAP;
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("SETTINGS").setTitle("⭐  Reputation")
                        .setDescription(body).build(),
                ActionRow.of(Button.primary(CustomId.of("repset", "edit"), "Edit…").withEmoji(Emoji.fromUnicode("✏️")),
                        Button.secondary(CustomId.of("settings", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private Map<String, Object> current() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", settings.repStart());
        m.put("plus", settings.repOnTime() + "/" + settings.repCompleted());
        m.put("minus", settings.repLate() + "/" + settings.repPartial() + "/" + settings.repUnpaidWeek());
        return m;
    }

    private static int[] triple(ModalInteractionEvent event, String id) {
        var v = event.getValue(id);
        if (v == null) {
            return null;
        }
        String[] parts = v.getAsString().split("/");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
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
