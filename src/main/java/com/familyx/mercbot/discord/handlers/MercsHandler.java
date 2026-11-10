package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.merc.FactionPerkService;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.merc.MercService;
import com.familyx.mercbot.merc.RoleSync;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Leader panel → Mercs (scope doc 6.1, 6.4): the signed-up mercs, each merc's details, and
 * Remove from the service. Stats, claim history, suspensions and Re-check now come in phase 5.
 */
@Component
public class MercsHandler implements InteractionHandler {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneOffset.UTC);
    private static final int LIST_SIZE = 25;
    private static final int MAX_LISTS = 4;

    private final MercRepository mercs;
    private final MercService service;
    private final RoleSync roles;
    private final TierRepository tiers;
    private final FamilyFactionRepository factions;
    private final FactionPerkService perks;

    private final com.familyx.mercbot.suspension.SuspensionService suspensions;

    public MercsHandler(MercRepository mercs, MercService service, RoleSync roles, TierRepository tiers,
                        FamilyFactionRepository factions, FactionPerkService perks,
                        com.familyx.mercbot.suspension.SuspensionService suspensions) {
        this.suspensions = suspensions;
        this.mercs = mercs;
        this.service = service;
        this.roles = roles;
        this.tiers = tiers;
        this.factions = factions;
        this.perks = perks;
    }

    @Override
    public String action() {
        return "mercs";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        switch (id.sub()) {
            case "view" -> list(null).show(event);
            case "close" -> event.editMessage(new MessageEditBuilder().setReplace(true).setContent("Closed.").build())
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            case "open" -> merc(id.longArg(0), null).show(event);
            case "remove" -> {
                Optional<Merc> m = mercs.findByTorn(id.longArg(0)).filter(Merc::active);
                if (m.isEmpty()) {
                    list("That merc has already left.").show(event);
                    return;
                }
                Screen.of(new EmbedBuilder().setColor(Panels.WARN)
                                .setTitle("🚪  Remove " + m.get().name() + " from the merc service?")
                                .setDescription("Their merc roles come off, their stored key is deleted, and they get a "
                                        + "DM. They can join again later.").build(),
                        ActionRow.of(Button.danger(CustomId.of("mercs", "removeyes", m.get().tornId()), "Remove"),
                                Button.secondary(CustomId.of("mercs", "open", m.get().tornId()), "Cancel")))
                        .show(event);
            }
            case "removeyes" -> {
                Optional<Merc> m = mercs.findByTorn(id.longArg(0)).filter(Merc::active);
                if (m.isEmpty()) {
                    list("That merc has already left.").show(event);
                    return;
                }
                InteractionHook hook = Screen.deferInPlace(event);
                service.remove(m.get(), event.getUser().getIdLong());
                list("✅ Removed **" + m.get().name() + "**.").show(hook);
            }
            default -> list("Unknown option.").show(event);
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        if ("pick".equals(id.sub()) && !event.getValues().isEmpty()) {
            merc(Long.parseLong(event.getValues().getFirst()), null).show(event);
        } else {
            list("Unknown option.").show(event);
        }
    }

    // ------------------------------------------------------------------ screens

    private Screen list(String notice) {
        List<Tier> active = tiers.findActive();
        Map<Long, Set<Integer>> optOuts = mercs.allOptedOut();
        List<Merc> all = new ArrayList<>(mercs.findActive());
        all.sort(Comparator.comparing(m -> m.name() == null ? "" : m.name().toLowerCase()));

        Map<Integer, Integer> perTier = new LinkedHashMap<>();
        Map<Long, Optional<Tier>> topTier = new LinkedHashMap<>();
        for (Merc m : all) {
            Set<Integer> held = roles.tiersFor(m, active, optOuts.getOrDefault(m.tornId(), Set.of()));
            Optional<Tier> top = active.stream().filter(t -> held.contains(t.id())).max(Comparator.comparingInt(Tier::rank));
            topTier.put(m.tornId(), top);
            top.ifPresent(t -> perTier.merge(t.id(), 1, Integer::sum));
        }
        StringBuilder summary = new StringBuilder("**").append(all.size()).append("** signed up");
        active.stream().sorted(Comparator.comparingInt(Tier::rank).reversed()).forEach(t -> {
            int n = perTier.getOrDefault(t.id(), 0);
            if (n > 0) {
                summary.append(" · ").append(Panels.tierIcon(t)).append(' ').append(n).append(' ').append(t.name());
            }
        });

        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER).setAuthor("MERC MANAGERS").setTitle("🗡️  Mercs")
                .setDescription((notice == null ? "" : notice + "\n\n") + summary + Panels.GAP);
        List<ActionRow> rows = new ArrayList<>();
        if (all.isEmpty()) {
            eb.addField("​", "Nobody has signed up yet.", false);
        }
        if (all.size() > LIST_SIZE * MAX_LISTS) {
            eb.addField("​", "-# Only the first " + LIST_SIZE * MAX_LISTS + " are listed.", false);
        }
        for (int i = 0; i < MAX_LISTS && i * LIST_SIZE < all.size(); i++) {
            List<Merc> chunk = all.subList(i * LIST_SIZE, Math.min(all.size(), (i + 1) * LIST_SIZE));
            StringSelectMenu.Builder menu = StringSelectMenu.create(CustomId.of("mercs", "pick", i))
                    .setPlaceholder(initial(chunk.getFirst()) + "–" + initial(chunk.getLast()) + " (" + chunk.size()
                            + "): pick a merc…");
            for (Merc m : chunk) {
                Optional<Tier> top = topTier.get(m.tornId());
                String faction = Optional.ofNullable(m.factionId()).flatMap(factions::find).map(FamilyFaction::name)
                        .orElse("No family faction");
                menu.addOption(m.name() == null ? "Player " + m.tornId() : m.name(), Long.toString(m.tornId()),
                        trim("[" + m.tornId() + "] · " + top.map(Tier::name).orElse("No tier") + " · " + faction
                                + (m.joinedAt() == null ? "" : " · joined " + DAY.format(m.joinedAt()))),
                        Emoji.fromUnicode(top.map(Panels::tierIcon).orElse("➖")));
            }
            rows.add(ActionRow.of(menu.build()));
        }
        rows.add(ActionRow.of(Button.secondary(CustomId.of("mercs", "close"), "Close").withEmoji(Emoji.fromUnicode("⬅️"))));
        return new Screen(eb.build(), rows);
    }

    /** A merc's page, for other leader screens that return to it (phase 5a: suspensions). */
    public Screen screen(long tornId, String notice) {
        return merc(tornId, notice);
    }

    private Screen merc(long tornId, String notice) {
        Optional<Merc> found = mercs.findByTorn(tornId).filter(Merc::active);
        if (found.isEmpty()) {
            return list("That merc has left the service.");
        }
        Merc m = found.get();
        List<Tier> active = tiers.findActive();
        Set<Integer> held = roles.tiersFor(m, active, mercs.optedOut(m.tornId()));
        Optional<Tier> top = active.stream().filter(t -> held.contains(t.id())).max(Comparator.comparingInt(Tier::rank));
        String faction = Optional.ofNullable(m.factionId()).flatMap(factions::find).map(FamilyFaction::name)
                .orElse("No family faction");
        boolean perk = perks.has(m.factionId());
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER).setAuthor("MERCS")
                .setTitle("🗡️  " + m.name() + " [" + m.tornId() + "]")
                .setDescription((notice == null ? "" : notice + "\n\n") + faction
                        + (m.joinedAt() == null ? "" : " · signed up " + DAY.format(m.joinedAt())) + Panels.GAP)
                .addField(top.map(Panels::tierIcon).orElse("➖") + "  Tier", top.map(Tier::name).orElse("None") + Panels.GAP, true)
                .addField("🎖️  Merits", (m.meritsOk() ? "10/10 ✅" : "Not 10/10 ❌") + Panels.GAP, true)
                .addField("🏥  Faction perk", (perk ? "Hospitalization X ✅" : "No Hospitalization X ❌") + Panels.GAP, true)
                .addField("⚡  Stricken", (m.strickenActive() ? "On" : "Off") + Panels.GAP, true)
                .addField((m.available() ? "🟢" : "🔴") + "  Availability", (m.available() ? "On" : "Off") + Panels.GAP, true)
                .addField("🔑  Key", (service.keyWorking(m) ? "Working ✅" : "Not working ❌") + Panels.GAP, true);
        var state = suspensions.state(m.tornId());
        boolean suspended = state.map(x -> x.suspended(java.time.Instant.now())).orElse(false);
        eb.addField(suspended ? "⛔  Claiming" : "🔒  Claiming", suspended
                ? "Suspended " + com.familyx.mercbot.suspension.SuspensionService.until(state.get().suspendedUntil(),
                state.get().indefinite()) + (state.get().reason() == null ? "" : "\n-# " + state.get().reason())
                + Panels.GAP : "Allowed" + Panels.GAP, false);
        ActionRow claimRow;
        if (suspended) {
            claimRow = ActionRow.of(Button.success(CustomId.of("noshow", "lift", m.tornId()), "Lift suspension")
                    .withEmoji(Emoji.fromUnicode("✅")));
        } else {
            var pick = net.dv8tion.jda.api.components.selections.StringSelectMenu.create(
                    CustomId.of("noshow", "suspend", m.tornId())).setPlaceholder("Suspend claiming…");
            for (var l : com.familyx.mercbot.suspension.SuspensionService.Length.values()) {
                pick.addOption(l.label, l.name(), Emoji.fromUnicode("⛔"));
            }
            claimRow = ActionRow.of(pick.build());
        }
        return Screen.of(eb.build(), claimRow, ActionRow.of(
                Button.secondary(CustomId.of("mercs", "view"), "Mercs").withEmoji(Emoji.fromUnicode("⬅️")),
                Button.danger(CustomId.of("mercs", "remove", m.tornId()), "Remove from the service")
                        .withEmoji(Emoji.fromUnicode("🚪"))));
    }

    private static String trim(String s) {
        return s.length() <= 100 ? s : s.substring(0, 99) + "…";
    }

    private static String initial(Merc m) {
        String n = m.name() == null ? "" : m.name();
        return !n.isEmpty() && Character.isLetter(n.charAt(0)) ? n.substring(0, 1).toUpperCase() : "#";
    }
}
