package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.PanelRefresher;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.discord.setup.SetupService;
import com.familyx.mercbot.merc.RoleSync;
import com.familyx.mercbot.merc.TierAvailability;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.PriceVersion;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Leader panel → Tiers & pricing (scope doc 6.1): create, edit or retire tiers; every price
 * change is a new price version. Also the two tier settings (scope doc 5.2).
 */
@Component
public class TiersHandler implements InteractionHandler {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 '-]{0,29}$");

    private final TierRepository tiers;
    private final BotSettings settings;
    private final RoleSync roles;
    private final TierAvailability availability;
    private final PanelRefresher panels;
    private final SetupService setup;
    private final AuditService audit;

    public TiersHandler(TierRepository tiers, BotSettings settings, RoleSync roles, TierAvailability availability,
                        PanelRefresher panels, SetupService setup, AuditService audit) {
        this.tiers = tiers;
        this.settings = settings;
        this.roles = roles;
        this.availability = availability;
        this.panels = panels;
        this.setup = setup;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "tiers";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    // ------------------------------------------------------------------ buttons

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "view" -> list(null).show(event);
            case "add" -> event.replyModal(detailsModal(null)).queue();
            case "edit" -> withTier(id, event, t -> {
                event.replyModal(detailsModal(t)).queue();
                return null;
            });
            case "exclusive" -> {
                boolean on = !settings.exclusiveTiers();
                settings.set(BotSettings.EXCLUSIVE_TIERS, on, user);
                audit.log(user, "SETTING_CHANGED", "setting:" + BotSettings.EXCLUSIVE_TIERS, Map.of("value", !on),
                        Map.of("value", on), "Turned exclusive tiers **" + (on ? "on" : "off") + "**");
                roles.syncAllInBackground(availability::evaluate);
                list("✅ Exclusive tiers turned " + (on ? "on" : "off") + ". Merc roles are updating now.").show(event);
            }
            case "autodisable" -> {
                boolean on = !settings.autoDisableEmptyTiers();
                settings.set(BotSettings.AUTO_DISABLE_EMPTY_TIERS, on, user);
                audit.log(user, "SETTING_CHANGED", "setting:" + BotSettings.AUTO_DISABLE_EMPTY_TIERS,
                        Map.of("value", !on), Map.of("value", on),
                        "Turned auto-disable empty tiers **" + (on ? "on" : "off") + "**");
                InteractionHook hook = Screen.deferInPlace(event);
                availability.evaluate();
                list("✅ Auto-disable empty tiers turned " + (on ? "on" : "off") + ".").show(hook);
            }
            case "toggle" -> withTier(id, event, t -> {
                tiers.setEnabled(t.id(), !t.enabled());
                audit.log(user, t.enabled() ? "TIER_SWITCHED_OFF" : "TIER_SWITCHED_ON", "tier:" + t.id(), null, null,
                        "Switched **" + t.name() + "** " + (t.enabled() ? "off" : "on"));
                panels.tiersChanged();
                return tier(t.id(), t.enabled()
                        ? "⏸️ Switched off. It's hidden from the request panel until you switch it back on."
                        : "▶️ Switched on.");
            });
            case "retire" -> withTier(id, event, t -> Screen.of(new EmbedBuilder().setColor(Panels.WARN)
                            .setTitle("🗑️  Retire " + t.name() + "?")
                            .setDescription("It stops being offered, its role is removed from every merc, and its "
                                    + "board channel is deleted. Past contracts keep their records.").build(),
                    ActionRow.of(
                            Button.danger(CustomId.of("tiers", "retireconfirm", t.id()), "Retire"),
                            Button.secondary(CustomId.of("tiers", "open", t.id()), "Cancel"))));
            case "retireconfirm" -> {
                Optional<Tier> t = tiers.find(Integer.parseInt(id.arg(0)));
                if (t.isEmpty()) {
                    list("That tier no longer exists.").show(event);
                    return;
                }
                InteractionHook hook = Screen.deferInPlace(event);
                if (event.getGuild() != null) {
                    setup.removeTier(event.getGuild(), t.get());
                }
                tiers.retire(t.get().id());
                audit.log(user, "TIER_RETIRED", "tier:" + t.get().id(), Map.of("name", t.get().name()), null,
                        "Retired **" + t.get().name() + "**");
                panels.tiersChanged();
                availability.evaluate();
                list("🗑️ Retired **" + t.get().name() + "**.").show(hook);
            }
            case "open" -> withTier(id, event, t -> tier(t.id(), null));
            default -> list("Unknown option.").show(event);
        }
    }

    // ------------------------------------------------------------------ menus

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        String value = event.getValues().getFirst();
        switch (id.sub()) {
            case "pick" -> tier(Integer.parseInt(value), null).show(event);
            case "needs" -> {
                Optional<Tier> t = tiers.find(Integer.parseInt(id.arg(0)));
                if (t.isEmpty()) {
                    list("That tier no longer exists.").show(event);
                    return;
                }
                boolean merits = !"any".equals(value);
                boolean stricken = "stricken".equals(value);
                tiers.setRequirements(t.get().id(), merits, merits, stricken);
                audit.log(user, "TIER_REQUIREMENTS", "tier:" + t.get().id(), Map.of("needs", Panels.tierWhoShort(t.get())),
                        Map.of("needs", value), "Changed what **" + t.get().name() + "** needs to: " + needsLabel(value));
                roles.syncAllInBackground(availability::evaluate);
                panels.tiersChanged();
                tier(t.get().id(), "✅ Requirements saved. Merc roles are updating now.").show(event);
            }
            default -> list("Unknown option.").show(event);
        }
    }

    // ------------------------------------------------------------------ add / edit box

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        InteractionHook hook = Screen.deferInPlace(event);
        boolean adding = "addsubmit".equals(id.sub());
        Optional<Tier> existing = adding ? Optional.empty() : tiers.find(Integer.parseInt(id.arg(0)));
        if (!adding && existing.isEmpty()) {
            list("That tier no longer exists.").show(hook);
            return;
        }

        String name = value(event, "name");
        long price = Money.parse(value(event, "price"));
        Integer colour = parseColour(value(event, "colour"));
        Integer rank = parseInt(value(event, "rank"));
        String problem = !NAME.matcher(name).matches() ? "Names can use letters, numbers, spaces, ' and - (up to 30)."
                : tiers.nameTaken(name, existing.map(Tier::id).orElse(-1)) ? "There's already a tier called " + name + "."
                : price <= 0 ? "The price wasn't understood. Try 2.4m or 2,400,000."
                : colour == null ? "The colour should be a hex code like #C0C0C0."
                : rank == null || rank < 1 || rank > 99 ? "Rank should be a number from 1 (lowest) to 99."
                : null;
        if (problem != null) {
            (adding ? list("❌ " + problem) : tier(existing.get().id(), "❌ " + problem)).show(hook);
            return;
        }

        int tierId;
        String notice;
        if (adding) {
            tierId = tiers.create(name, rank, colour, false, false, false, price, user);
            audit.log(user, "TIER_CREATED", "tier:" + tierId, null,
                    Map.of("name", name, "price", price, "rank", rank),
                    "Created tier **" + name + "** at " + Money.full(price) + " per hit");
            notice = "✅ Created **" + name + "**. Its role and channel have been made. Pick what it needs below.";
        } else {
            Tier t = existing.get();
            tierId = t.id();
            tiers.updateDetails(tierId, name, rank, colour);
            if (price != t.pricePerHit()) {
                tiers.addPrice(tierId, price, user);
            }
            audit.log(user, "TIER_EDITED", "tier:" + tierId,
                    Map.of("name", t.name(), "price", t.pricePerHit(), "rank", t.rank(), "colour", t.colour()),
                    Map.of("name", name, "price", price, "rank", rank, "colour", colour),
                    "Edited tier **" + name + "**" + (price != t.pricePerHit()
                            ? " (price " + Money.full(t.pricePerHit()) + " → " + Money.full(price) + ")" : ""));
            notice = "✅ Saved." + (price != t.pricePerHit() ? " New contracts use the new price." : "");
        }
        Guild guild = event.getGuild();
        if (guild != null) {
            setup.syncTiers(guild);
        }
        panels.tiersChanged();
        roles.syncAllInBackground(availability::evaluate);
        tier(tierId, notice).show(hook);
    }

    // ------------------------------------------------------------------ screens

    private Screen list(String notice) {
        List<Tier> active = tiers.findActive();
        Map<Integer, Integer> holders = roles.holderCounts();
        boolean exclusive = settings.exclusiveTiers();
        boolean autoDisable = settings.autoDisableEmptyTiers();

        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER).setAuthor("MERC MANAGERS")
                .setTitle("🏷️  Tiers & pricing")
                .setDescription((notice == null ? "" : notice + "\n\n")
                        + "Prices apply to new contracts; running contracts keep the price they were quoted."
                        + Panels.GAP);
        for (Tier t : active) {
            int n = holders.getOrDefault(t.id(), 0);
            eb.addField(Panels.tierIcon(t) + "  " + t.name(), "**" + Money.shortForm(t.pricePerHit()) + "** per hit\n-# "
                    + Panels.tierWhoShort(t) + " · " + n + (n == 1 ? " merc" : " mercs") + " · "
                    + Panels.tierStatus(t) + Panels.GAP, true);
        }
        for (int i = active.size() % 3; i != 0 && i < 3; i++) {
            eb.addBlankField(true);
        }
        eb.addField("⚙️  Tier settings", "Exclusive tiers: **" + (exclusive ? "On" : "Off") + "**\n"
                + "-# each merc holds only their highest tier\n"
                + "Auto-disable empty tiers: **" + (autoDisable ? "On" : "Off") + "**\n"
                + "-# tiers nobody holds are hidden from the request panel", false);

        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("tiers", "pick"))
                .setPlaceholder("Edit a tier…");
        for (Tier t : active) {
            pick.addOption(t.name(), Integer.toString(t.id()), Money.shortForm(t.pricePerHit()) + " per hit",
                    Emoji.fromUnicode(Panels.tierIcon(t)));
        }
        List<ActionRow> rows = new java.util.ArrayList<>();
        if (!active.isEmpty()) {
            rows.add(ActionRow.of(pick.build()));
        }
        rows.add(ActionRow.of(
                Button.secondary(CustomId.of("settings", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️")),
                Button.primary(CustomId.of("tiers", "add"), "Add tier").withEmoji(Emoji.fromUnicode("➕")),
                Button.secondary(CustomId.of("tiers", "exclusive"), "Exclusive tiers: " + (exclusive ? "On" : "Off"))
                        .withEmoji(Emoji.fromUnicode("🔀")),
                Button.secondary(CustomId.of("tiers", "autodisable"), "Auto-disable: " + (autoDisable ? "On" : "Off"))
                        .withEmoji(Emoji.fromUnicode("💤"))));
        return new Screen(eb.build(), rows);
    }

    private Screen tier(int tierId, String notice) {
        Optional<Tier> found = tiers.find(tierId);
        if (found.isEmpty()) {
            return list("That tier no longer exists.");
        }
        Tier t = found.get();
        int n = roles.holderCounts().getOrDefault(t.id(), 0);
        StringBuilder history = new StringBuilder();
        for (PriceVersion v : tiers.priceHistory(t.id(), 5)) {
            history.append("-# ").append(Money.full(v.price())).append(" from ").append(DAY.format(v.from()))
                    .append(v.createdBy() == null ? "" : " (set by <@" + v.createdBy() + ">)").append('\n');
        }
        EmbedBuilder eb = new EmbedBuilder().setColor(new Color(t.colour())).setAuthor("TIERS & PRICING")
                .setTitle(Panels.tierIcon(t) + "  " + t.name())
                .setDescription((notice == null ? "" : notice) + Panels.GAP)
                .addField("Price", Money.full(t.pricePerHit()) + " per hit" + Panels.GAP, true)
                .addField("Needs", Panels.tierWhoShort(t) + Panels.GAP, true)
                .addField("Status", Panels.tierStatus(t) + " · " + n + (n == 1 ? " merc" : " mercs") + Panels.GAP, true)
                .addField("Price history", history.toString().trim(), false)
                .setFooter("Rank " + t.rank() + " · colour #" + String.format("%06X", t.colour() & 0xFFFFFF));

        String needs = t.requiresStricken() ? "stricken" : t.requiresMerits() ? "merits" : "any";
        StringSelectMenu needsMenu = StringSelectMenu.create(CustomId.of("tiers", "needs", t.id()))
                .addOption("Needs: any merc", "any")
                .addOption("Needs: 10/10 merits + hosp perk", "merits")
                .addOption("Needs: merits + hosp perk + Stricken", "stricken")
                .setDefaultValues(needs).build();
        return Screen.of(eb.build(),
                ActionRow.of(needsMenu),
                ActionRow.of(
                        Button.secondary(CustomId.of("tiers", "view"), "Tiers").withEmoji(Emoji.fromUnicode("⬅️")),
                        Button.primary(CustomId.of("tiers", "edit", t.id()), "Edit details").withEmoji(Emoji.fromUnicode("✏️")),
                        t.enabled()
                                ? Button.secondary(CustomId.of("tiers", "toggle", t.id()), "Switch off").withEmoji(Emoji.fromUnicode("⏸️"))
                                : Button.success(CustomId.of("tiers", "toggle", t.id()), "Switch on").withEmoji(Emoji.fromUnicode("▶️")),
                        Button.danger(CustomId.of("tiers", "retire", t.id()), "Retire").withEmoji(Emoji.fromUnicode("🗑️"))));
    }

    private Modal detailsModal(Tier t) {
        int nextRank = tiers.findActive().stream().mapToInt(Tier::rank).max().orElse(0) + 1;
        TextInput name = TextInput.create("name", TextInputStyle.SHORT)
                .setRequired(true).setMaxLength(30).setValue(t == null ? null : t.name()).build();
        TextInput price = TextInput.create("price", TextInputStyle.SHORT)
                .setRequired(true).setMaxLength(20).setPlaceholder("e.g. 2.4m")
                .setValue(t == null ? null : Money.shortForm(t.pricePerHit()).replace("$", "")).build();
        TextInput colour = TextInput.create("colour", TextInputStyle.SHORT)
                .setRequired(true).setMaxLength(7).setPlaceholder("#C0C0C0")
                .setValue(t == null ? null : String.format("#%06X", t.colour() & 0xFFFFFF)).build();
        TextInput rank = TextInput.create("rank", TextInputStyle.SHORT)
                .setRequired(true).setMaxLength(2)
                .setValue(Integer.toString(t == null ? nextRank : t.rank())).build();
        return Modal.create(t == null ? CustomId.of("tiers", "addsubmit") : CustomId.of("tiers", "editsubmit", t.id()),
                        t == null ? "Add a tier" : "Edit " + t.name())
                .addComponents(Label.of("Name (also the role and channel name)", name), Label.of("Price per hit", price),
                        Label.of("Colour (hex)", colour), Label.of("Rank (1 = lowest)", rank))
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private void withTier(CustomId id, ButtonInteractionEvent event, java.util.function.Function<Tier, Screen> work) {
        Optional<Tier> t = tiers.find(Integer.parseInt(id.arg(0)));
        if (t.isEmpty()) {
            list("That tier no longer exists.").show(event);
            return;
        }
        Screen screen = work.apply(t.get());
        if (screen != null) {
            screen.show(event);
        }
    }

    private static String value(ModalInteractionEvent event, String field) {
        return event.getValue(field) == null ? "" : event.getValue(field).getAsString().trim();
    }

    private static Integer parseColour(String text) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (!hex.matches("[0-9A-Fa-f]{6}")) {
            return null;
        }
        return Integer.parseInt(hex, 16);
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String needsLabel(String value) {
        return switch (value) {
            case "stricken" -> "merits + hosp perk + Stricken";
            case "merits" -> "10/10 merits + hosp perk";
            default -> "any merc";
        };
    }
}
