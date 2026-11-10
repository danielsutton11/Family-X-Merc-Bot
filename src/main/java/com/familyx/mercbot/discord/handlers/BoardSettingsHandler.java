package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.board.BoardRules;
import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.keys.KeyPoolMonitor;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Settings → Boards (scope doc section 9, Boards group). Every change goes in #merc-audit. */
@Component
public class BoardSettingsHandler implements InteractionHandler {

    private final BotSettings settings;
    private final AuditService audit;
    private final BoardService boards;
    private final KeyPoolMonitor monitor;

    public BoardSettingsHandler(BotSettings settings, AuditService audit, BoardService boards, KeyPoolMonitor monitor) {
        this.settings = settings;
        this.audit = audit;
        this.boards = boards;
        this.monitor = monitor;
    }

    @Override
    public String action() {
        return "boardset";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        if ("edit".equals(id.sub())) {
            event.replyModal(Modal.create(CustomId.of("boardset", "save",
                                    Long.toString(System.nanoTime() % 1_000_000_000L, 36)), "Board settings")
                    .addComponents(
                            box("fast", "Fast check (seconds, 5–60)", settings.boardFastSeconds(), "e.g. 10"),
                            box("slow", "Slow check (seconds, 30–600)", settings.boardSlowSeconds(), "e.g. 60"),
                            box("group", "Ping grouping window (minutes, 0–10)", settings.pingGroupMinutes(), "e.g. 2"),
                            box("war", "Ranked-war outsider window (seconds)", settings.warLockSeconds(), "e.g. 60"))
                    .build()).queue();
            return;
        }
        screen(null).show(event);
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        long user = event.getUser().getIdLong();
        Integer fast = number(event, "fast");
        Integer slow = number(event, "slow");
        Integer group = number(event, "group");
        Integer war = number(event, "war");
        String error = check(fast, slow, group, war);
        if (error != null) {
            screen("❌ " + error + " Nothing was changed.").show(hook);
            return;
        }
        Map<String, Object> before = current();
        settings.set(BotSettings.BOARD_FAST_S, fast, user);
        settings.set(BotSettings.BOARD_SLOW_S, slow, user);
        settings.set(BotSettings.BOARD_GROUP_MIN, group, user);
        settings.set(BotSettings.BOARD_WAR_S, war, user);
        BoardRules.warLock(Duration.ofSeconds(war));
        Map<String, Object> after = current();
        if (!before.equals(after)) {
            audit.log(user, "SETTING_CHANGED", "settings:boards", before, after, "Changed the board settings");
        }
        boards.refresh();
        screen("✅ Board settings saved. They apply from the next check.").show(hook);
    }

    /** Why these numbers can't be saved, or null if they can. */
    public static String check(Integer fast, Integer slow, Integer group, Integer war) {
        return fast == null || fast < 5 || fast > 60 ? "Fast check should be 5 to 60 seconds."
                : slow == null || slow < 30 || slow > 600 ? "Slow check should be 30 to 600 seconds."
                : slow < fast ? "Slow check can't be faster than the fast check."
                : group == null || group < 0 || group > 10 ? "Ping grouping window should be 0 to 10 minutes."
                : war == null || war < 0 || war > 300 ? "Ranked-war outsider window should be 0 to 300 seconds." : null;
    }

    private Screen screen(String notice) {
        String body = (notice == null ? "" : notice + "\n\n")
                + "Target checks: every **" + settings.boardFastSeconds() + " s** on the board or about to come out · "
                + "every **" + settings.boardSlowSeconds() + " s** otherwise\n"
                + "Ping grouping window: **" + (settings.pingGroupMinutes() == 0 ? "off (each target pings on its own)"
                : settings.pingGroupMinutes() + " min") + "**\n"
                + "Ranked-war outsider window: **" + settings.warLockSeconds() + " s**\n"
                + "-# Key pool use right now: " + monitor.usageLine() + Panels.GAP;
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("SETTINGS")
                        .setTitle("📋  Boards").setDescription(body).build(),
                ActionRow.of(
                        Button.primary(CustomId.of("boardset", "edit"), "Edit numbers").withEmoji(Emoji.fromUnicode("✏️")),
                        Button.secondary(CustomId.of("settings", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private Map<String, Object> current() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fastCheckSeconds", settings.boardFastSeconds());
        m.put("slowCheckSeconds", settings.boardSlowSeconds());
        m.put("pingGroupMinutes", settings.pingGroupMinutes());
        m.put("warLockSeconds", settings.warLockSeconds());
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
}
