package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.Choices.Status;
import com.familyx.mercbot.request.Conditions;
import com.familyx.mercbot.request.Scenarios;
import com.familyx.mercbot.request.Scenarios.Early;
import com.familyx.mercbot.request.Scenarios.Entry;
import com.familyx.mercbot.request.Scenarios.Key;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Settings → Requests → Request defaults (scope doc 4.3, 9): what each scenario pre-fills on a
 * new request. Changes save as soon as they're picked and are logged in #merc-audit.
 */
@Component
public class RequestDefaultsHandler implements InteractionHandler {

    private final BotSettings settings;
    private final TierRepository tiers;
    private final AuditService audit;

    public RequestDefaultsHandler(BotSettings settings, TierRepository tiers, AuditService audit) {
        this.settings = settings;
        this.tiers = tiers;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "reqdef";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "view" -> list(null).show(event);
            case "open" -> scenario(Key.valueOf(id.arg(0)), null).show(event);
            case "reset" -> {
                Key k = Key.valueOf(id.arg(0));
                save(user, k, Scenarios.scope(k));
                scenario(k, "↩️ Back to the scope doc's defaults.").show(event);
            }
            case "mins" -> {
                Key k = Key.valueOf(id.arg(0));
                event.replyModal(Modal.create(CustomId.of("reqdef", "minsave", k.name(), fresh()), "Idle or offline for how long?")
                        .addComponents(Label.of("Minutes since their last action", TextInput.create("v", TextInputStyle.SHORT)
                                .setRequired(true).setMaxLength(4)
                                .setValue(Integer.toString(settings.requestDefaults(k).awayMinutes())).build()))
                        .build()).queue();
            }
            case "len" -> {
                Key k = Key.valueOf(id.arg(0));
                Integer h = settings.requestDefaults(k).lengthHours();
                event.replyModal(Modal.create(CustomId.of("reqdef", "lensave", k.name(), fresh()), "Scenario " + shortName(k) + " · length")
                        .addComponents(Label.of("Hours from the start (blank for no end)", TextInput.create("v", TextInputStyle.SHORT)
                                .setRequired(false).setMaxLength(4).setValue(h == null ? null : Integer.toString(h)).build()))
                        .build()).queue();
            }
            case "hits" -> {
                Key k = Key.valueOf(id.arg(0));
                event.replyModal(Modal.create(CustomId.of("reqdef", "hitsave", k.name(), fresh()), "Scenario " + shortName(k) + " · hits")
                        .addComponents(Label.of("Hits per target", TextInput.create("v", TextInputStyle.SHORT)
                                .setRequired(true).setMaxLength(3)
                                .setValue(Integer.toString(settings.requestDefaults(k).hits())).build()))
                        .build()).queue();
            }
            default -> list(null).show(event);
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        String v = event.getValues().isEmpty() ? null : event.getValues().getFirst();
        if ("pick".equals(id.sub())) {
            scenario(Key.valueOf(v), null).show(event);
            return;
        }
        long user = event.getUser().getIdLong();
        Key k = Key.valueOf(id.arg(0));
        Entry e = settings.requestDefaults(k);
        Entry changed = switch (id.sub()) {
            case "tier" -> e.withTier(Integer.parseInt(v));
            case "am" -> {
                String[] p = v.split("\\.");
                yield e.withAttackMode(Attack.valueOf(p[0]), Mode.valueOf(p[1]));
            }
            case "status" -> e.withStatus(Status.valueOf(v), e.awayMinutes());
            case "re" -> {
                String[] p = v.split("\\.");
                yield e.withRevivableEarly(Boolean.parseBoolean(p[0]), Early.valueOf(p[1]));
            }
            default -> e;
        };
        save(user, k, changed);
        scenario(k, "✅ Saved.").show(event);
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        long user = event.getUser().getIdLong();
        Key k = Key.valueOf(id.arg(0));
        Entry e = settings.requestDefaults(k);
        String raw = event.getValue("v") == null ? "" : event.getValue("v").getAsString().trim();
        Integer n;
        try {
            n = raw.isEmpty() ? null : Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            scenario(k, "❌ That wasn't a whole number. Nothing was changed.").show(hook);
            return;
        }
        switch (id.sub()) {
            case "minsave" -> {
                if (n == null || n < 1 || n > 1440) {
                    scenario(k, "❌ Minutes should be 1 to 1440. Nothing was changed.").show(hook);
                    return;
                }
                save(user, k, e.withStatus(e.status(), n));
            }
            case "lensave" -> {
                if (n != null && (n < 1 || n > 720)) {
                    scenario(k, "❌ Length should be 1 to 720 hours, or blank for no end. Nothing was changed.").show(hook);
                    return;
                }
                save(user, k, e.withLength(n));
            }
            case "hitsave" -> {
                if (n == null || n < 1 || n > 100) {
                    scenario(k, "❌ Hits should be 1 to 100. Nothing was changed.").show(hook);
                    return;
                }
                save(user, k, e.withHits(n));
            }
            default -> { }
        }
        scenario(k, "✅ Saved.").show(hook);
    }

    // ------------------------------------------------------------------ screens

    private Screen list(String notice) {
        StringBuilder body = new StringBuilder(notice == null ? "" : notice + "\n\n")
                .append("What each situation pre-fills on a new request. Pick one to change it.\n");
        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("reqdef", "pick"))
                .setPlaceholder("Pick a scenario to edit…");
        for (Key k : Key.values()) {
            Entry e = settings.requestDefaults(k);
            body.append("\n**").append(k.title).append("**\n-# ").append(summary(k, e));
            if (k == Key.A) {
                body.append("\n**D · Your faction, no war**\n-# Uses A's defaults, starting now");
            }
            pick.addOption(trim(k.title, 100), k.name(), trim(summary(k, e), 100));
        }
        body.append("\n\n-# The enemy with the war already active has no defaults: the requester picks every option.")
                .append(Panels.GAP);
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("SETTINGS · REQUESTS")
                        .setTitle("📝  Request defaults").setDescription(body.toString()).build(),
                ActionRow.of(pick.build()),
                ActionRow.of(Button.secondary(CustomId.of("settings", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private Screen scenario(Key k, String notice) {
        Entry e = settings.requestDefaults(k);
        boolean mercMe = k == Key.F;
        String body = (notice == null ? "" : notice + "\n\n") + k.about + "\n\n" + summary(k, e)
                + (mercMe ? "\n-# Tier, attack type and hits are fixed for Merc me." : "") + Panels.GAP;

        StringSelectMenu.Builder tier = StringSelectMenu.create(CustomId.of("reqdef", "tier", k.name()))
                .setPlaceholder("Tier…");
        Optional<Tier> current = tierFor(e);
        for (Tier t : tiers.findActive()) {
            tier.addOption("Tier: " + t.name(), Integer.toString(t.id()), null, Emoji.fromUnicode(Panels.tierIcon(t)));
        }
        current.ifPresent(t -> tier.setDefaultValues(Integer.toString(t.id())));

        StringSelectMenu.Builder am = StringSelectMenu.create(CustomId.of("reqdef", "am", k.name()))
                .setPlaceholder("Attack and mode…");
        for (Attack a : Attack.values()) {
            for (Mode m : Mode.values()) {
                am.addOption("Attack: " + a.label + " · Mode: " + m.label, a.name() + "." + m.name(), null,
                        Emoji.fromUnicode(a == Attack.HOSPITALIZE ? "🏥" : "💸"));
            }
        }
        am.setDefaultValues(e.attack().name() + "." + e.mode().name());

        StringSelectMenu.Builder status = StringSelectMenu.create(CustomId.of("reqdef", "status", k.name()))
                .setPlaceholder("Status…")
                .addOption("Status: Any", Status.ANY.name(), null, Emoji.fromUnicode("🌐"))
                .addOption("Status: Online", Status.ONLINE.name(), null, Emoji.fromUnicode("🟢"))
                .addOption("Status: Idle/Offline " + e.awayMinutes() + "+ min", Status.AWAY.name(), null,
                        Emoji.fromUnicode("💤"));
        status.setDefaultValues(e.status().name());

        StringSelectMenu.Builder re = StringSelectMenu.create(CustomId.of("reqdef", "re", k.name()))
                .setPlaceholder("Revivable and early discharge…");
        for (boolean r : new boolean[] {false, true}) {
            for (Early x : Early.values()) {
                re.addOption("Revivable: " + (r ? "Hit" : "Don't hit") + " · Early discharge: " + earlyLabel(x),
                        r + "." + x.name(), null, Emoji.fromUnicode("♻️"));
            }
        }
        re.setDefaultValues(e.revivable() + "." + e.early().name());

        Button mins = Button.secondary(CustomId.of("reqdef", "mins", k.name()), "Change idle mins")
                .withEmoji(Emoji.fromUnicode("⏳"));
        Button amount;
        if (e.mode() == Mode.HITS) {
            amount = Button.secondary(CustomId.of("reqdef", "hits", k.name()), "Change hits").withEmoji(Emoji.fromUnicode("🔢"));
            if (mercMe) {
                amount = amount.asDisabled();
            }
        } else {
            amount = Button.secondary(CustomId.of("reqdef", "len", k.name()), k.lengthEditable() ? "Change length" : "Ends at war start")
                    .withEmoji(Emoji.fromUnicode("🕒"));
            if (!k.lengthEditable()) {
                amount = amount.asDisabled();
            }
        }
        List<Button> buttons = new java.util.ArrayList<>(List.of(
                e.status() == Status.AWAY ? mins : mins.asDisabled(),
                amount,
                Button.secondary(CustomId.of("reqdef", "reset", k.name()), "Reset to scope doc").withEmoji(Emoji.fromUnicode("↩️"))));
        if (k == Key.G_DEF || k == Key.G_OFF) {
            Key other = k == Key.G_DEF ? Key.G_OFF : Key.G_DEF;
            buttons.add(Button.secondary(CustomId.of("reqdef", "open", other.name()),
                    other == Key.G_OFF ? "Offensive" : "Defensive").withEmoji(Emoji.fromUnicode("🔁")));
        }
        buttons.add(Button.primary(CustomId.of("reqdef", "view"), "All scenarios").withEmoji(Emoji.fromUnicode("⬅️")));

        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("REQUEST DEFAULTS")
                        .setTitle(k.title).setDescription(body).build(),
                ActionRow.of(mercMe ? tier.build().asDisabled() : tier.build()),
                ActionRow.of(mercMe ? am.build().asDisabled() : am.build()),
                ActionRow.of(status.build()),
                ActionRow.of(re.build()),
                ActionRow.of(buttons));
    }

    // ------------------------------------------------------------------ helpers

    private void save(long user, Key k, Entry after) {
        Entry before = settings.requestDefaults(k);
        if (before.equals(after)) {
            return;
        }
        settings.set(BotSettings.requestDefaultsKey(k), after, user);
        audit.log(user, "SETTING_CHANGED", "setting:" + BotSettings.requestDefaultsKey(k), before, after,
                "Changed the request defaults for **" + k.title + "**: " + summary(k, after));
    }

    /** "Gold · Hospitalize · Duration, war start (or now) + 24h · Idle/Offline 30+ min · Revivable: Don't hit · …" */
    private String summary(Key k, Entry e) {
        String tier = tierFor(e).map(Tier::name).orElse(e.gold() ? "Gold" : "Silver") + (k == Key.F ? " (fixed)" : "");
        String amount = e.mode() == Mode.HITS ? "Hit count, " + e.hits() + (e.hits() == 1 ? " hit" : " hits") + (k == Key.F ? "" : " each")
                : "Duration, " + when(k, e);
        return tier + " · " + e.attack().label + " · " + amount + " · "
                + new Conditions(e.status(), e.awayMinutes(), e.revivable(), false).statusLabel()
                + " · Revivable: " + (e.revivable() ? "Hit" : "Don't hit")
                + " · Early discharge: " + earlyLabel(e.early());
    }

    private static String when(Key k, Entry e) {
        String len = e.lengthHours() == null ? ", no end" : " + " + e.lengthHours() + "h";
        return switch (k) {
            case A -> "war start (or now)" + len;
            case G_DEF -> "their war start (or now)" + len;
            case E, C, F -> "now" + len;
            case B -> "now until war start";
            case G_OFF -> "now until their war start";
        };
    }

    private static String earlyLabel(Early x) {
        return switch (x) {
            case RULE -> "by attack type";
            case HIT -> "Hit";
            case SKIP -> "Skip";
        };
    }

    /** The tier these defaults pick: the chosen one, else the scope doc's Gold or Silver. */
    private Optional<Tier> tierFor(Entry e) {
        List<Tier> active = tiers.findActive();
        if (e.tierId() != null) {
            Optional<Tier> t = active.stream().filter(x -> x.id() == e.tierId()).findFirst();
            if (t.isPresent()) {
                return t;
            }
        }
        return e.gold()
                ? active.stream().filter(Tier::requiresStricken).max(java.util.Comparator.comparingInt(Tier::rank))
                : active.stream().filter(x -> x.name().equalsIgnoreCase("Silver")).findFirst();
    }

    private static String shortName(Key k) {
        return switch (k) {
            case G_DEF -> "G (Defensive)";
            case G_OFF -> "G (Offensive)";
            default -> k.name();
        };
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String fresh() {
        return Long.toString(System.nanoTime() % 1_000_000_000L, 36);
    }
}
