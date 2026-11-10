package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.merc.FactionPerkService;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.merc.MercService;
import com.familyx.mercbot.merc.MercStatsRepository;
import com.familyx.mercbot.merc.RoleSync;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
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
    private final MercStatsRepository stats;
    private final com.familyx.mercbot.audit.AuditService audit;

    public MercsHandler(MercRepository mercs, MercService service, RoleSync roles, TierRepository tiers,
                        FamilyFactionRepository factions, FactionPerkService perks,
                        com.familyx.mercbot.suspension.SuspensionService suspensions, MercStatsRepository stats,
                        com.familyx.mercbot.audit.AuditService audit) {
        this.stats = stats;
        this.audit = audit;
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
                                Button.danger(CustomId.of("mercs", "blask", m.get().tornId()), "Remove and blacklist…"),
                                Button.secondary(CustomId.of("mercs", "open", m.get().tornId()), "Keep")))
                        .show(event);
            }
            case "blask" -> event.replyModal(Modal.create(CustomId.of("mercs", "blsave", id.longArg(0),
                                    Long.toString(System.nanoTime() % 1_000_000_000L, 36)), "Blacklist this merc")
                    .addComponents(Label.of("Reason (kept for leaders, not sent)", TextInput.create("reason",
                            TextInputStyle.SHORT).setRequired(false).setMaxLength(200).build()))
                    .build()).queue();
            case "claims" -> claims(id.longArg(0)).show(event);
            case "recheck" -> {
                Optional<Merc> m = mercs.findByTorn(id.longArg(0)).filter(Merc::active);
                if (m.isEmpty()) {
                    list("That merc has left the service.").show(event);
                    return;
                }
                InteractionHook hook = Screen.deferInPlace(event);
                String problem = service.recheck(m.get());
                Merc now = mercs.findByTorn(m.get().tornId()).orElse(m.get());
                RoleSync.Result r = roles.sync(now);
                merc(now.tornId(), problem != null ? "❌ " + problem
                        : "🔄 Checked just now: merits " + (now.meritsOk() ? "10/10 ✅" : "not 10/10 ❌") + " · faction perk "
                        + (perks.has(now.factionId()) ? "✅" : "❌") + " · roles " + (r == null ? "unchanged" : "synced") + ".").show(hook);
            }
            case "blist" -> blacklist(null).show(event);
            case "unbl" -> {
                InteractionHook hook = Screen.deferInPlace(event);
                long torn = id.longArg(0);
                boolean done = stats.unblacklist(torn);
                if (done) {
                    audit.log(event.getUser().getIdLong(), "MERC_UNBLACKLISTED", "merc:" + torn, null, null,
                            "Took **[" + torn + "]** off the merc blacklist");
                }
                blacklist(done ? "✅ Taken off the blacklist. They can sign up again." : "They weren't on it.").show(hook);
            }
            case "removeyes" -> {
                Optional<Merc> m = mercs.findByTorn(id.longArg(0)).filter(Merc::active);
                if (m.isEmpty()) {
                    list("That merc has already left.").show(event);
                    return;
                }
                InteractionHook hook = Screen.deferInPlace(event);
                service.remove(m.get(), event.getUser().getIdLong(), false);
                list("✅ Removed **" + m.get().name() + "**.").show(hook);
            }
            default -> list("Unknown option.").show(event);
        }
    }

    @Override
    public void modal(net.dv8tion.jda.api.events.interaction.ModalInteractionEvent event, CustomId id) {
        if (!"blsave".equals(id.sub())) {
            return;
        }
        InteractionHook hook = Screen.deferInPlace(event);
        Optional<Merc> m = mercs.findByTorn(id.longArg(0));
        if (m.isEmpty()) {
            list("That merc wasn't found.").show(hook);
            return;
        }
        String reason = event.getValue("reason") == null ? null : event.getValue("reason").getAsString().strip();
        String why = reason == null || reason.isEmpty() ? null : reason;
        long user = event.getUser().getIdLong();
        stats.blacklist(m.get().tornId(), m.get().discordId(), m.get().name(), why, user);
        if (m.get().active()) {
            service.remove(m.get(), user, true);
        }
        audit.log(user, "MERC_BLACKLISTED", "merc:" + m.get().tornId(), null, why == null ? null : Map.of("reason", why),
                "Blacklisted **" + m.get().name() + "** [" + m.get().tornId() + "] from the merc service"
                        + (why == null ? "" : ": " + why));
        list("⛔ Removed and blacklisted **" + m.get().name() + "**. They can't sign up again.").show(hook);
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        if ("unpick".equals(id.sub()) && !event.getValues().isEmpty()) {
            long torn = Long.parseLong(event.getValues().getFirst());
            Screen.of(new EmbedBuilder().setColor(Panels.WARN).setTitle("Take [" + torn + "] off the merc blacklist?")
                            .setDescription("They'll be able to sign up again.").build(),
                    ActionRow.of(Button.success(CustomId.of("mercs", "unbl", torn), "Unblacklist"),
                            Button.secondary(CustomId.of("mercs", "blist"), "Back"))).show(event);
            return;
        }
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
        rows.add(ActionRow.of(Button.secondary(CustomId.of("mercs", "blist"), "Blacklist").withEmoji(Emoji.fromUnicode("⛔")),
                Button.secondary(CustomId.of("mercs", "close"), "Close").withEmoji(Emoji.fromUnicode("⬅️"))));
        return new Screen(eb.build(), rows);
    }

    private Screen blacklist(String notice) {
        List<MercStatsRepository.Blacklisted> all = stats.blacklist();
        StringBuilder sb = new StringBuilder(notice == null ? "" : notice + "\n\n");
        if (all.isEmpty()) {
            sb.append("Nobody is blacklisted.");
        }
        all.stream().limit(15).forEach(b -> sb.append("**").append(b.name() == null ? "Player" : b.name()).append("** [")
                .append(b.tornId()).append("] · ").append(b.addedAt() == null ? "" : DAY.format(b.addedAt()))
                .append(b.addedBy() == null ? "" : " by <@" + b.addedBy() + ">")
                .append(b.reason() == null ? "" : "\n-# " + b.reason()).append('\n'));
        List<ActionRow> rows = new ArrayList<>();
        if (!all.isEmpty()) {
            StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("mercs", "unpick"))
                    .setPlaceholder("Take someone off the blacklist…");
            all.stream().limit(25).forEach(b -> pick.addOption((b.name() == null ? "Player" : b.name()) + " [" + b.tornId() + "]",
                    Long.toString(b.tornId())));
            rows.add(ActionRow.of(pick.build()));
        }
        rows.add(ActionRow.of(Button.secondary(CustomId.of("mercs", "view"), "Mercs").withEmoji(Emoji.fromUnicode("⬅️"))));
        return new Screen(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("MERCS").setTitle("⛔  Merc blacklist")
                .setDescription(sb.toString().strip() + Panels.GAP).build(), rows);
    }

    private Screen claims(long tornId) {
        Optional<Merc> m = mercs.findByTorn(tornId);
        String name = m.map(Merc::name).orElse("Player " + tornId);
        List<MercStatsRepository.ClaimLine> lines = stats.claims(tornId, 15);
        StringBuilder t = new StringBuilder();
        if (lines.isEmpty()) {
            t.append("No claims yet.");
        } else {
            int rw = lines.stream().mapToInt(l -> l.ref().length()).max().orElse(7);
            t.append("```\n");
            for (var l : lines) {
                String target = l.target().length() > 12 ? l.target().substring(0, 11) + "…" : l.target();
                String outcome = l.outcome() == null ? "… open"
                        : switch (l.outcome()) {
                            case "HIT" -> "✓ hit";
                            case "NOSHOW" -> "✖ no-show (" + (l.hitBy() == null ? "expired" : "hit by " + l.hitBy()) + ")";
                            case "RELEASED" -> "↺ released (hit by an outsider)";
                            default -> l.joined() ? "✓ took part" : "✖ expired";
                        };
                t.append(CLAIM_WHEN.format(l.at())).append("  ").append(pad(l.ref(), rw)).append("  ")
                        .append(pad(target, 12)).append("  ").append(outcome).append('\n');
            }
            t.append("```");
        }
        List<String> sus = stats.suspensions(tornId, 5);
        String foot = sus.isEmpty() ? "-# No suspensions." : "-# Suspensions: " + String.join(" · ", sus);
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor(("MERCS · " + name + " · CLAIMS").toUpperCase())
                        .setTitle("🔒  Last " + Math.min(15, lines.size()) + " claims")
                        .setDescription(t + "\n" + foot).build(),
                ActionRow.of(Button.primary(CustomId.of("mercs", "open", tornId), name).withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private static final DateTimeFormatter CLAIM_WHEN = DateTimeFormatter.ofPattern("EEE HH:mm", java.util.Locale.UK)
            .withZone(ZoneOffset.UTC);

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String hosp(Integer secs) {
        if (secs == null) {
            return "–";
        }
        return secs / 3600 + "h " + String.format("%02dm", (secs % 3600) / 60);
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
        MercStatsRepository.Stats st = stats.stats(m.tornId());
        Map<String, Integer> tierSplit = stats.byTier(m.tornId());
        String table = "```\n" + pad("", 14) + pad("7 days", 13) + pad("30 days", 13) + "All time\n"
                + pad("Hits", 14) + pad(Integer.toString(st.hits7()), 13) + pad(Integer.toString(st.hits30()), 13) + st.hitsAll() + "\n"
                + pad("Earned", 14) + pad(Money.full(st.earned7()), 13) + pad(Money.full(st.earned30()), 13) + Money.full(st.earnedAll()) + "\n"
                + pad("Avg hospital", 14) + pad(hosp(st.avgHosp7()), 13) + hosp(st.avgHosp30()) + "\n"
                + (tierSplit.isEmpty() ? "" : "By tier       " + tierSplit.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(" · ")) + "\n")
                + "```";
        eb.addField("📊  Hits", table + "-# " + (st.lastHitAt() == null ? "No hits yet"
                : "Last hit " + CLAIM_WHEN.format(st.lastHitAt()) + " on " + st.lastTarget() + " (" + st.lastRef() + ")")
                + " · " + st.joins7() + (st.joins7() == 1 ? " hit" : " hits") + " as joiner this week", false);
        MercStatsRepository.ClaimWeek cw = stats.claimWeek(m.tornId());
        eb.addField("🔒  Claims · last 7 days", cw.claims() + (cw.claims() == 1 ? " claim" : " claims") + " · " + cw.hit()
                + " hit · " + cw.noShows() + (cw.noShows() == 1 ? " no-show" : " no-shows") + " · " + cw.released()
                + " released\n-# No-show run " + stats.noShowRun(m.tornId()) + Panels.GAP, false);
        return Screen.of(eb.build(), claimRow, ActionRow.of(
                Button.secondary(CustomId.of("mercs", "claims", m.tornId()), "Claim history").withEmoji(Emoji.fromUnicode("🔒")),
                Button.secondary(CustomId.of("mercs", "recheck", m.tornId()), "Re-check now").withEmoji(Emoji.fromUnicode("🔄")),
                Button.danger(CustomId.of("mercs", "remove", m.tornId()), "Remove…").withEmoji(Emoji.fromUnicode("🚪")),
                Button.primary(CustomId.of("mercs", "view"), "Mercs").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private static String trim(String s) {
        return s.length() <= 100 ? s : s.substring(0, 99) + "…";
    }

    private static String initial(Merc m) {
        String n = m.name() == null ? "" : m.name();
        return !n.isEmpty() && Character.isLetter(n.charAt(0)) ? n.substring(0, 1).toUpperCase() : "#";
    }
}
