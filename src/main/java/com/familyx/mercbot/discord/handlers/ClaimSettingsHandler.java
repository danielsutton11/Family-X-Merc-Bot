package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.board.BoardService;
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

/** Settings → Claims (scope doc section 9, Claims group). Every change goes in #merc-audit. */
@Component
public class ClaimSettingsHandler implements InteractionHandler {

    private final BotSettings settings;
    private final AuditService audit;
    private final BoardService boards;

    public ClaimSettingsHandler(BotSettings settings, AuditService audit, BoardService boards) {
        this.settings = settings;
        this.audit = audit;
        this.boards = boards;
    }

    @Override
    public String action() {
        return "claimset";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "toggle" -> {
                boolean on = !settings.claimsOn();
                settings.set(BotSettings.CLAIMS_ON, on, user);
                audit.log(user, "SETTING_CHANGED", "setting:" + BotSettings.CLAIMS_ON, Map.of("value", !on),
                        Map.of("value", on), "Turned claims **" + (on ? "on" : "off") + "**");
                InteractionHook hook = Screen.deferInPlace(event);
                boards.refresh(); // claim buttons appear or go, and open claims are released when off
                screen("✅ Claims turned " + (on ? "on" : "off") + ". The boards have updated.").show(hook);
            }
            case "edit" -> event.replyModal(Modal.create(CustomId.of("claimset", "save",
                                    Long.toString(System.nanoTime() % 1_000_000_000L, 36)), "Claim settings")
                    .addComponents(
                            box("early", "Show-early window (minutes)", settings.claimShowEarlyMinutes(), "e.g. 2"),
                            box("lock", "Claim lock (seconds)", settings.claimLockSeconds(), "e.g. 60"),
                            box("max", "Max claims per merc", settings.maxClaims(), "e.g. 2"),
                            Label.of("No-show cooldown: in a row / minutes", TextInput.create("cooldown",
                                    TextInputStyle.SHORT).setRequired(true).setMaxLength(9).setPlaceholder("e.g. 2 / 10")
                                    .setValue(settings.claimCooldownNoShows() + " / " + settings.claimCooldownMinutes())
                                    .build()),
                            Label.of("Leader alert: no-shows / hours", TextInput.create("alert",
                                    TextInputStyle.SHORT).setRequired(true).setMaxLength(9).setPlaceholder("e.g. 3 / 24")
                                    .setValue(settings.claimAlertNoShows() + " / " + settings.claimAlertHours())
                                    .build()))
                    .build()).queue();
            default -> screen(null).show(event);
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        long user = event.getUser().getIdLong();
        Integer early = number(event, "early");
        Integer lock = number(event, "lock");
        Integer max = number(event, "max");
        int[] cooldown = pair(event, "cooldown");
        int[] alert = pair(event, "alert");
        String error = early == null || early < 0 || early > 30 ? "Show-early window should be 0 to 30 minutes."
                : lock == null || lock < 5 || lock > 300 ? "Claim lock should be 5 to 300 seconds."
                : max == null || max < 1 || max > 10 ? "Max claims should be 1 to 10."
                : cooldown == null || cooldown[0] < 1 || cooldown[0] > 20 || cooldown[1] < 1 || cooldown[1] > 1440
                ? "No-show cooldown should look like 2 / 10 (1–20 in a row, 1–1440 minutes)."
                : alert == null || alert[0] < 1 || alert[0] > 50 || alert[1] < 1 || alert[1] > 720
                ? "Leader alert should look like 3 / 24 (1–50 no-shows, 1–720 hours)." : null;
        if (error != null) {
            screen("❌ " + error + " Nothing was changed.").show(hook);
            return;
        }
        Map<String, Object> before = current();
        settings.set(BotSettings.CLAIM_SHOW_EARLY_MIN, early, user);
        settings.set(BotSettings.CLAIM_LOCK_S, lock, user);
        settings.set(BotSettings.CLAIM_MAX, max, user);
        settings.set(BotSettings.CLAIM_COOLDOWN_COUNT, cooldown[0], user);
        settings.set(BotSettings.CLAIM_COOLDOWN_MIN, cooldown[1], user);
        settings.set(BotSettings.CLAIM_ALERT_COUNT, alert[0], user);
        settings.set(BotSettings.CLAIM_ALERT_HOURS, alert[1], user);
        Map<String, Object> after = current();
        if (!before.equals(after)) {
            audit.log(user, "SETTING_CHANGED", "settings:claims", before, after, "Changed the claim settings");
        }
        boards.refresh();
        screen("✅ Claim settings saved.").show(hook);
    }

    private Screen screen(String notice) {
        boolean on = settings.claimsOn();
        String body = (notice == null ? "" : notice + "\n\n")
                + "Claims: **" + (on ? "On" : "Off") + "**\n"
                + "Show-early window: **" + settings.claimShowEarlyMinutes() + " min**\n"
                + "Claim lock: **" + settings.claimLockSeconds() + " s**\n"
                + "Max claims per merc: **" + settings.maxClaims() + "**\n"
                + "No-show cooldown: **" + settings.claimCooldownNoShows() + " in a row → "
                + settings.claimCooldownMinutes() + " min**\n"
                + "No-show leader alert: **" + settings.claimAlertNoShows() + " in " + settings.claimAlertHours() + " h**\n"
                + "-# No-shows are counted once hits are tracked." + Panels.GAP;
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("SETTINGS")
                        .setTitle("🔒  Claims").setDescription(body).build(),
                ActionRow.of(
                        on ? Button.secondary(CustomId.of("claimset", "toggle"), "Turn claims off")
                                .withEmoji(Emoji.fromUnicode("⏸️"))
                                : Button.success(CustomId.of("claimset", "toggle"), "Turn claims on")
                                .withEmoji(Emoji.fromUnicode("▶️")),
                        Button.primary(CustomId.of("claimset", "edit"), "Edit numbers").withEmoji(Emoji.fromUnicode("✏️")),
                        Button.secondary(CustomId.of("settings", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private Map<String, Object> current() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("showEarlyMinutes", settings.claimShowEarlyMinutes());
        m.put("lockSeconds", settings.claimLockSeconds());
        m.put("maxPerMerc", settings.maxClaims());
        m.put("cooldown", settings.claimCooldownNoShows() + "/" + settings.claimCooldownMinutes());
        m.put("alert", settings.claimAlertNoShows() + "/" + settings.claimAlertHours());
        return m;
    }

    private static Label box(String id, String label, int value, String placeholder) {
        return Label.of(label, TextInput.create(id, TextInputStyle.SHORT).setRequired(true).setMaxLength(4)
                .setPlaceholder(placeholder).setValue(Integer.toString(value)).build());
    }

    private static Integer number(ModalInteractionEvent event, String id) {
        var v = event.getValue(id);
        try {
            return v == null ? null : Integer.parseInt(v.getAsString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "2 / 10", "2/10" or "2 10" → {2, 10}. */
    private static int[] pair(ModalInteractionEvent event, String id) {
        var v = event.getValue(id);
        if (v == null) {
            return null;
        }
        String[] parts = v.getAsString().trim().split("\\s*[/ ,]\\s*");
        try {
            return parts.length == 2 ? new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
