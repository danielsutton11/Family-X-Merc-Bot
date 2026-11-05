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
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /** A message as an embed plus button rows. */
    public record Panel(MessageEmbed embed, List<ActionRow> rows) {
        /** Discord rejects a message whose components share a custom id; fail early with a clear message. */
        public Panel {
            java.util.Set<String> seen = new java.util.HashSet<>();
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

    public Panel guide() {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(BRAND)
                .setAuthor("FAMILY X · MERC SERVICE")
                .setTitle("How to hire us")
                .setDescription("""
                        **1** Choose tier, target, attack type and mode
                        **2** Enter the players, or pick faction members
                        **3** Check the quote; conditions are pre-filled
                        **4** Pay the invoice in Torn when it ends

                        *Tip: Repeat last contract fills everything in.*""");
        return new Panel(eb.build(), List.of());
    }

    public Panel requestPanel() {
        String prices = tiers.findActive().stream()
                .filter(Tier::enabled)
                .map(t -> "**" + t.name() + "** " + Money.shortForm(t.pricePerHit()))
                .collect(Collectors.joining(" · "));
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(BRAND)
                .setTitle("Hire the Family X mercs")
                .setDescription((prices.isBlank() ? "No tiers available right now." : prices + " per hit")
                        + "\n-# Silver and Gold mercs: 10/10 merits + max hospitalisation perk."
                        + "\n-# Merc me makes you the target, billed at Gold.");
        return new Panel(eb.build(), List.of(
                ActionRow.of(
                        Button.primary(CustomId.of("soon", "3", "request-a-contract"), "Request a contract"),
                        Button.secondary(CustomId.of("soon", "3", "merc-me-gold"), "Merc me (Gold)")),
                ActionRow.of(
                        Button.secondary(CustomId.of("soon", "3", "repeat-last-contract"), "Repeat last contract"),
                        Button.secondary(CustomId.of("help", "requests"), "Help"))));
    }

    /** The private guide behind the Help button. */
    public MessageEmbed requestHelp() {
        EmbedBuilder eb = new EmbedBuilder().setColor(PRIVATE).setTitle("How merc contracts work");
        for (Tier t : tiers.findActive()) {
            eb.addField(t.name() + " · " + Money.full(t.pricePerHit()) + " per hit", t.requirements(), false);
        }
        eb.addField("Attack types", "**Hospitalize** puts targets in hospital. **Mug** steals from them. "
                + "One type per contract.", false);
        eb.addField("Modes", "**Hit count**: a set number of hits per target. "
                + "**Duration**: hit whenever possible between a start and end time.", false);
        eb.addField("Conditions", "Choose whether targets must be online or away, whether revivable targets "
                + "count, and whether to skip early discharge. Hits that break your conditions aren't billed.", false);
        eb.addField("Paying", "When the contract ends you get an invoice by DM. Pay the named leader in Torn.", false);
        return eb.build();
    }

    // ------------------------------------------------------------------ #merc-signup

    public Panel signupPanel() {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x3FA39A))
                .setAuthor("FAMILY X · MERC SERVICE")
                .setTitle("Become a merc")
                .setDescription("Get paid for every hospitalisation you land.\n"
                        + "-# You'll need a Limited API key for the merits check.");
        return new Panel(eb.build(), List.of(ActionRow.of(
                Button.primary(CustomId.of("soon", "2", "join"), "Join"),
                Button.secondary(CustomId.of("soon", "2", "my-profile"), "My profile"))));
    }

    // ------------------------------------------------------------------ tier boards

    public Panel tierBoard(Tier tier) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(tier.colour()))
                .setAuthor(tier.name().toUpperCase() + " CONTRACTS")
                .setTitle("No targets on the board")
                .setDescription("Targets appear here when a " + tier.name() + " contract is live.");
        return new Panel(eb.build(), List.of());
    }

    // ------------------------------------------------------------------ #merc-leaderboard

    public Panel leaderboard() {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0xD9A400))
                .setAuthor("LEADERBOARD")
                .setTitle("Top mercs")
                .setDescription("No hits yet. Standings appear here once contracts start.");
        return new Panel(eb.build(), List.of());
    }

    // ------------------------------------------------------------------ #merc-leaders

    public Panel leaderPanel() {
        int min = settings.minLeaderKeysPerFaction();
        Map<Long, FactionKeyCount> counts = keys.leaderKeyCountsByFaction();
        StringBuilder keyLine = new StringBuilder();
        boolean anyBelow = false;
        for (FamilyFaction f : factions.findActive()) {
            FactionKeyCount c = counts.getOrDefault(f.tornFactionId(), new FactionKeyCount(f.tornFactionId(), 0, 0, 0));
            boolean below = c.healthy() < min;
            anyBelow |= below;
            if (!keyLine.isEmpty()) keyLine.append(" · ");
            keyLine.append(f.name()).append(' ').append(c.healthy());
            if (c.bsp() > 0) keyLine.append(" (").append(c.bsp()).append(" BSP)");
            if (below) keyLine.append(" ⚠️");
        }
        String tierLine = tiers.findActive().stream()
                .map(t -> t.name() + (t.enabled() ? " on" : " off"))
                .collect(Collectors.joining(" · "));

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(LEADER)
                .setAuthor("MERC MANAGERS")
                .setTitle("Merc control panel")
                .addField("Leader keys", keyLine.isEmpty() ? "No family factions set up." : keyLine.toString(), false)
                .addField("Tiers", tierLine, false);
        if (anyBelow) {
            eb.setFooter("⚠️ Every faction needs at least " + min + " healthy leader keys. Use Keys to add one.");
        }
        return new Panel(eb.build(), List.of(
                ActionRow.of(
                        Button.secondary(CustomId.of("soon", "3", "contracts"), "Contracts"),
                        Button.secondary(CustomId.of("soon", "4", "invoices-payouts"), "Invoices & payouts"),
                        Button.secondary(CustomId.of("soon", "5", "mercs"), "Mercs"),
                        Button.secondary(CustomId.of("soon", "5", "requesters"), "Requesters")),
                ActionRow.of(
                        Button.secondary(CustomId.of("soon", "2", "tiers-pricing"), "Tiers & pricing"),
                        Button.secondary(CustomId.of("soon", "5", "reports"), "Reports"),
                        Button.primary(CustomId.of("keys", "view"), "Keys"),
                        Button.primary(CustomId.of("settings", "view"), "Settings"))));
    }

    // ------------------------------------------------------------------ #merc-payouts

    public Panel payouts(FamilyFaction faction) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(BRAND)
                .setAuthor(faction.name().toUpperCase() + " PAYOUTS")
                .setTitle("Nothing owed yet")
                .setDescription("Merc shares and settlements for " + faction.name() + " appear here.");
        return new Panel(eb.build(), List.of());
    }
}
