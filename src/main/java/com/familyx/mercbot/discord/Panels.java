package com.familyx.mercbot.discord;

import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.keys.ApiKeyRepository;
import com.familyx.mercbot.keys.ApiKeyRepository.FactionKeyCount;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Content for every pinned panel. Phase 1 posts all of them; buttons for features built in
 * later phases reply with a short "coming in phase N" note (see {@code ComingSoonHandler}).
 */
@Component
public class Panels {

    public static final Color BRAND = new Color(0xE8743B);
    public static final Color PRIVATE = new Color(0x3B82D6);
    public static final Color LEADER = new Color(0x8A63D2);
    public static final Color GOOD = new Color(0x2E9E4F);
    public static final Color WARN = new Color(0xE6A817);
    public static final Color BAD = new Color(0xD64545);

    /** A zero-width blank line; Discord trims plain trailing newlines, so this is how to add spacing. */
    public static final String GAP = "\n\u200b";

    /** A blank inline field, used to keep tile grids aligned in threes. */
    private static final MessageEmbed.Field SPACER = new MessageEmbed.Field("​", "​", true);

    /** A message as an embed plus button rows. */
    public record Panel(MessageEmbed embed, List<ActionRow> rows) {
        /** Discord rejects a message whose components share a custom id; fail early with a clear message. */
        public Panel {
            Set<String> seen = new HashSet<>();
            for (ActionRow row : rows) {
                for (var component : row.getActionComponents()) {
                    String id = component.getId();
                    if (id != null && !seen.add(id)) {
                        throw new IllegalStateException("Duplicate component id in panel: " + id);
                    }
                }
            }
        }
    }

    private final TierRepository tiers;
    private final FamilyFactionRepository factions;
    private final ApiKeyRepository keys;
    private final BotSettings settings;

    public Panels(TierRepository tiers, FamilyFactionRepository factions, ApiKeyRepository keys,
                  BotSettings settings) {
        this.tiers = tiers;
        this.factions = factions;
        this.keys = keys;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ #merc-requests

    /** The one pinned message in #merc-requests: tiers, how it works, and the buttons. */
    public Panel requestPanel() {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(PRIVATE)
                .setAuthor("FAMILY X · MERC SERVICE")
                .setTitle("⚔️  Hire the Family X mercs");
        List<Tier> enabled = tiers.findActive().stream().filter(Tier::offered).toList();
        if (enabled.isEmpty()) {
            eb.addField("No tiers available", "Check back soon.", false);
        }
        for (Tier t : enabled) {
            eb.addField(tierIcon(t) + "  " + t.name(),
                    "**" + Money.shortForm(t.pricePerHit()) + "** per hit\n-# " + tierWhoShort(t) + GAP, true);
        }
        pad(eb, enabled.size());
        eb.addField("📖  How it works", """
                **1**  Choose tier, target, attack type and mode
                **2**  Enter the players, or pick faction members
                **3**  Check the quote; conditions are pre-filled
                **4**  Pay the invoice in Torn when it ends""" + GAP, false);
        eb.setFooter("🔁 Repeat last contract fills everything in  ·  🎯 Merc me is billed at Gold");
        return new Panel(eb.build(), List.of(
                ActionRow.of(
                        Button.primary(CustomId.of("soon", "3", "request-a-contract"), "Request a contract")
                                .withEmoji(Emoji.fromUnicode("⚔️")),
                        Button.secondary(CustomId.of("soon", "3", "merc-me-gold"), "Merc me (Gold)")
                                .withEmoji(Emoji.fromUnicode("🎯"))),
                ActionRow.of(
                        Button.secondary(CustomId.of("soon", "3", "repeat-last-contract"), "Repeat last contract")
                                .withEmoji(Emoji.fromUnicode("🔁")),
                        Button.secondary(CustomId.of("help", "requests"), "Help")
                                .withEmoji(Emoji.fromUnicode("❓")))));
    }

    /** The private guide behind the Help button. */
    public MessageEmbed requestHelp() {
        EmbedBuilder eb = new EmbedBuilder().setColor(PRIVATE).setTitle("❓  How merc contracts work");
        for (Tier t : tiers.findActive()) {
            eb.addField(tierIcon(t) + "  " + t.name() + " · " + Money.full(t.pricePerHit()) + " per hit",
                    tierWho(t), false);
        }
        eb.addField("🗡️  Attack types", "**Hospitalize** puts targets in hospital. **Mug** steals from them. "
                + "One type per contract.", false);
        eb.addField("⏱️  Modes", "**Hit count**: a set number of hits per target.\n"
                + "**Duration**: hit whenever possible between a start and end time.", false);
        eb.addField("🎛️  Conditions", "Choose whether targets must be online or away, whether revivable targets "
                + "count, and whether to skip early discharge. Hits that break your conditions aren't billed.", false);
        eb.addField("💰  Paying", "When the contract ends you get an invoice by DM. Pay the named leader in Torn.",
                false);
        return eb.build();
    }

    // ------------------------------------------------------------------ #merc-signup

    public Panel signupPanel() {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x3FA39A))
                .setAuthor("FAMILY X · MERC SERVICE")
                .setTitle("🗡️  Become a merc")
                .setDescription("Earn from every hit you land on a Family X contract." + GAP);
        List<Tier> active = tiers.findActive();
        for (Tier t : active) {
            eb.addField(tierIcon(t) + "  " + t.name(), signupNeeds(t) + GAP, true);
        }
        pad(eb, active.size());
        eb.addField("📖  How it works", """
                **1**  Join and link your Torn account
                **2**  Your tiers are checked daily and given as roles
                **3**  Claim and hit targets on your tier boards
                **4**  Get paid your share when the contract settles""" + GAP, false);
        eb.setFooter("🔑 You'll need a Limited Torn API key for the merits check");
        return new Panel(eb.build(), List.of(ActionRow.of(
                Button.primary(CustomId.of("merc", "join"), "Join").withEmoji(Emoji.fromUnicode("✅")),
                Button.secondary(CustomId.of("merc", "profile"), "My profile")
                        .withEmoji(Emoji.fromUnicode("👤")))));
    }

    /** What a merc needs for a tier, kept short so the three cards line up. */
    static String signupNeeds(Tier t) {
        if (t.requiresStricken()) return "Silver\n-# + Stricken";
        if (t.requiresMerits()) return "10/10 merits\n-# + max hosp perk";
        return "Open to all\n-# no requirements";
    }

    // ------------------------------------------------------------------ tier boards

    public Panel tierBoard(Tier tier) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(tier.colour()))
                .setAuthor(tier.name().toUpperCase() + " CONTRACTS")
                .setTitle(tierIcon(tier) + "  No targets right now")
                .setDescription("Targets appear here as soon as a " + tier.name() + " contract goes live.\n"
                        + "-# You'll be pinged when there's work.");
        return new Panel(eb.build(), List.of());
    }

    // ------------------------------------------------------------------ #merc-leaderboard

    public Panel leaderboard() {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0xD9A400))
                .setAuthor("LEADERBOARD")
                .setTitle("🏆  Top mercs")
                .setDescription("No hits yet. Standings appear here once contracts start.");
        return new Panel(eb.build(), List.of());
    }

    // ------------------------------------------------------------------ #merc-leaders

    public Panel leaderPanel() {
        int min = settings.minLeaderKeysPerFaction();
        List<FamilyFaction> family = factions.findActive();

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(LEADER)
                .setAuthor("MERC MANAGERS")
                .setTitle("🛡️  Merc control panel")
                .setTimestamp(Instant.now())
                .setFooter("Last updated");

        // ---- leader keys: one tile per faction
        eb.addField("🔑  Leader keys", "-# Each faction needs " + min + " healthy keys to track attacks and payments.",
                false);
        int short_ = addKeyTiles(eb, family, min);

        // ---- tiers
        List<Tier> tierList = tiers.findActive();
        eb.addField("🏷️  Tiers", "-# Prices per hit. Change them in Tiers & pricing.", false);
        for (Tier t : tierList) {
            eb.addField(tierIcon(t) + "  " + t.name(),
                    Money.shortForm(t.pricePerHit()) + " per hit\n-# " + tierStatus(t) + GAP, true);
        }
        pad(eb, tierList.size());

        eb.setDescription((family.isEmpty()
                ? "⚠️ No family factions set up yet. Open **Settings → Factions**."
                : short_ == 0
                ? "✅ All factions have enough keys."
                : "⚠️ **" + short_ + " of " + family.size() + "** factions need more keys. Use **Keys** to add one.") + GAP);

        return new Panel(eb.build(), List.of(
                ActionRow.of(
                        Button.secondary(CustomId.of("soon", "3", "contracts"), "Contracts")
                                .withEmoji(Emoji.fromUnicode("📋")),
                        Button.secondary(CustomId.of("soon", "4", "invoices-payouts"), "Invoices & payouts")
                                .withEmoji(Emoji.fromUnicode("💰")),
                        Button.secondary(CustomId.of("soon", "5", "mercs"), "Mercs")
                                .withEmoji(Emoji.fromUnicode("🗡️")),
                        Button.secondary(CustomId.of("soon", "5", "requesters"), "Requesters")
                                .withEmoji(Emoji.fromUnicode("🧾"))),
                ActionRow.of(
                        Button.secondary(CustomId.of("tiers", "view"), "Tiers & pricing")
                                .withEmoji(Emoji.fromUnicode("🏷️")),
                        Button.secondary(CustomId.of("soon", "5", "reports"), "Reports")
                                .withEmoji(Emoji.fromUnicode("📊")),
                        Button.primary(CustomId.of("keys", "view"), "Keys")
                                .withEmoji(Emoji.fromUnicode("🔑")),
                        Button.primary(CustomId.of("settings", "view"), "Settings")
                                .withEmoji(Emoji.fromUnicode("⚙️")))));
    }

    // ------------------------------------------------------------------ #merc-payouts

    public Panel payouts(FamilyFaction faction) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(BRAND)
                .setAuthor(faction.name().toUpperCase() + " PAYOUTS")
                .setTitle("💰  Nothing owed yet")
                .setDescription("Merc shares and settlements for " + faction.name() + " appear here.");
        return new Panel(eb.build(), List.of());
    }

    // ------------------------------------------------------------------ helpers

    /** On, switched off by a Merc Manager, or hidden because nobody holds it. */
    public static String tierStatus(Tier t) {
        if (!t.enabled()) return "⏸️ off";
        if (t.autoHidden()) return "💤 hidden (empty)";
        return "✅ on";
    }

    public static String tierIcon(Tier t) {
        return switch (t.rank()) {
            case 1 -> "🥉";
            case 2 -> "🥈";
            case 3 -> "🥇";
            default -> "🏷️";
        };
    }

    /** Who does the hits at this tier, in requester-friendly words. */
    public static String tierWho(Tier t) {
        if (t.requiresStricken()) return "10/10 merits, max hosp perk and Stricken";
        if (t.requiresMerits()) return "10/10 merits and max hosp perk";
        return "Any merc";
    }

    /** Shorter version for side-by-side tier cards. */
    public static String tierWhoShort(Tier t) {
        if (t.requiresStricken()) return "Silver + Stricken";
        if (t.requiresMerits()) return "Merits + hosp perk";
        return "Any merc";
    }

    /** One tile per faction with its key count; returns how many factions are below the minimum. */
    private int addKeyTiles(EmbedBuilder eb, List<FamilyFaction> family, int min) {
        Map<Long, FactionKeyCount> counts = keys.leaderKeyCountsByFaction();
        int belowMin = 0;
        for (FamilyFaction f : family) {
            FactionKeyCount c = counts.getOrDefault(f.tornFactionId(), new FactionKeyCount(f.tornFactionId(), 0, 0, 0));
            String dot = c.healthy() >= min ? "🟢" : c.healthy() > 0 ? "🟠" : "🔴";
            if (c.healthy() < min) belowMin++;
            eb.addField(dot + "  " + f.name(), "**" + c.healthy() + " / " + min + "** keys"
                    + (c.bsp() > 0 ? "\n-# " + c.bsp() + " with BSP" : "\n-# no BSP") + GAP, true);
        }
        pad(eb, family.size());
        return belowMin;
    }

    /** Pads an inline-field grid to a multiple of three so the next section starts on its own line. */
    private static void pad(EmbedBuilder eb, int tiles) {
        int remainder = tiles % 3;
        if (remainder != 0) {
            for (int i = remainder; i < 3; i++) {
                eb.addField(SPACER);
            }
        }
    }
}
