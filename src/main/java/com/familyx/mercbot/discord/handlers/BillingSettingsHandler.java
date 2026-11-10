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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings → Billing (scope doc section 9, Billing group; 7.5). Each contract keeps the terms it
 * was quoted with, so a change only applies to contracts made afterwards.
 */
@Component
public class BillingSettingsHandler implements InteractionHandler {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BotSettings settings;
    private final AuditService audit;

    public BillingSettingsHandler(BotSettings settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "billset";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        if ("edit".equals(id.sub())) {
            event.replyModal(Modal.create(CustomId.of("billset", "save",
                                    Long.toString(System.nanoTime() % 1_000_000_000L, 36)), "Billing settings")
                    .addComponents(
                            box("cut", "Faction cut (%, 0–50)", settings.factionCutPct(), "e.g. 1"),
                            box("starter", "Starter's share, unclaimed target (%)", settings.starterPct(), "e.g. 75"),
                            box("claimer", "Claimer's share if they take no part (%)", settings.claimerNoShowPct(),
                                    "e.g. 100"))
                    .build()).queue();
            return;
        }
        screen(null).show(event);
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        long user = event.getUser().getIdLong();
        BigDecimal cut = pct(event, "cut");
        BigDecimal starter = pct(event, "starter");
        BigDecimal claimer = pct(event, "claimer");
        String error = check(cut, starter, claimer);
        if (error != null) {
            screen("❌ " + error + " Nothing was changed.").show(hook);
            return;
        }
        Map<String, Object> before = current();
        settings.set(BotSettings.MONEY_CUT, cut, user);
        settings.set(BotSettings.MONEY_STARTER, starter, user);
        settings.set(BotSettings.MONEY_CLAIMER, claimer, user);
        Map<String, Object> after = current();
        if (!before.equals(after)) {
            audit.log(user, "SETTING_CHANGED", "settings:billing", before, after, "Changed the billing settings");
        }
        screen("✅ Billing settings saved. They apply to contracts made from now on.").show(hook);
    }

    public static String check(BigDecimal cut, BigDecimal starter, BigDecimal claimer) {
        return cut == null || cut.signum() < 0 || cut.compareTo(BigDecimal.valueOf(50)) > 0
                ? "Faction cut should be 0 to 50%."
                : starter == null || starter.signum() < 0 || starter.compareTo(HUNDRED) > 0
                ? "Starter's share should be 0 to 100%."
                : claimer == null || claimer.signum() < 0 || claimer.compareTo(HUNDRED) > 0
                ? "Claimer's share should be 0 to 100%." : null;
    }

    /** "2.5", "2.5%" or "2,5" → 2.5, at most two decimal places; null if it isn't a number. */
    public static BigDecimal parsePercent(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().replace("%", "").replace(',', '.').trim();
        try {
            BigDecimal d = new BigDecimal(v);
            return d.scale() > 2 ? null : d.stripTrailingZeros().scale() < 0 ? d.setScale(0) : d.stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Screen screen(String notice) {
        BigDecimal starter = settings.starterPct();
        String body = (notice == null ? "" : notice + "\n\n")
                + "Faction cut: **" + show(settings.factionCutPct()) + "%** of each billed hit\n"
                + "Unclaimed target split: **" + show(starter) + "%** starter · **" + show(HUNDRED.subtract(starter))
                + "%** shared by joiners\n"
                + "Claimer who takes no part: **" + show(settings.claimerNoShowPct()) + "%** of the merc share\n"
                + "-# Changes apply to contracts made from now on. Live contracts keep the terms they were quoted "
                + "with. Discounts are on each requester's page and under Requesters → Faction discounts."
                + Panels.GAP;
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("SETTINGS")
                        .setTitle("💷  Billing").setDescription(body).build(),
                ActionRow.of(
                        Button.primary(CustomId.of("billset", "edit"), "Edit numbers").withEmoji(Emoji.fromUnicode("✏️")),
                        Button.secondary(CustomId.of("settings", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private Map<String, Object> current() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("factionCutPct", show(settings.factionCutPct()));
        m.put("starterPct", show(settings.starterPct()));
        m.put("claimerNoShowPct", show(settings.claimerNoShowPct()));
        return m;
    }

    private static String show(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    private static Label box(String id, String label, BigDecimal value, String placeholder) {
        return Label.of(label, TextInput.create(id, TextInputStyle.SHORT).setRequired(true).setMaxLength(6)
                .setPlaceholder(placeholder).setValue(show(value)).build());
    }

    private static BigDecimal pct(ModalInteractionEvent event, String id) {
        var v = event.getValue(id);
        return v == null ? null : parsePercent(v.getAsString());
    }
}
