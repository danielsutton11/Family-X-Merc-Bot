package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.reputation.ReputationService;
import com.familyx.mercbot.request.RequestRepositories;
import com.familyx.mercbot.request.RequesterAdminRepository;
import com.familyx.mercbot.request.RequesterAdminRepository.Row;
import com.familyx.mercbot.request.TornLookups;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.torn.TornKeyPool;
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
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leader panel → Requesters (scope doc 6.5): reputation and history, the requester blacklist,
 * and personal and faction discounts (Gold only). Merc Managers only.
 */
@Component
public class RequestersHandler implements InteractionHandler {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter EVENT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
            .withZone(ZoneOffset.UTC);
    private static final Pattern ID = Pattern.compile("(?:XID=)?(\\d{1,10})\\b");

    private final RequesterAdminRepository admin;
    private final RequestRepositories requesters;
    private final ReputationService reputation;
    private final FamilyFactionRepository factions;
    private final TornLookups lookups;
    private final TornKeyPool pool;
    private final BotSettings settings;
    private final AuditService audit;
    private final Map<Long, String> factionNames = new ConcurrentHashMap<>();

    public RequestersHandler(RequesterAdminRepository admin, RequestRepositories requesters, ReputationService reputation,
                             FamilyFactionRepository factions, TornLookups lookups, TornKeyPool pool,
                             BotSettings settings, AuditService audit) {
        this.admin = admin;
        this.requesters = requesters;
        this.reputation = reputation;
        this.factions = factions;
        this.lookups = lookups;
        this.pool = pool;
        this.settings = settings;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "reqs";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        switch (id.sub()) {
            case "view" -> list(null).show(event);
            case "open" -> page(id.longArg(0), null).show(event);
            case "hist" -> history(id.longArg(0)).show(event);
            case "blist" -> blacklist(null).show(event);
            case "fdisc" -> factionDiscounts(null).show(event);
            case "notnow" -> event.deferEdit().queue(h -> h.deleteOriginal().queue(null, ignored -> { }));
            case "find" -> event.replyModal(Modal.create(CustomId.of("reqs", "findsave", fresh()), "Find a requester")
                    .addComponents(Label.of("Torn ID, profile link or name", TextInput.create("q", TextInputStyle.SHORT)
                            .setRequired(true).setMaxLength(120).setPlaceholder("e.g. 2873311 or Lord_Bugs").build()))
                    .build()).queue();
            case "adj" -> event.replyModal(Modal.create(CustomId.of("reqs", "adjsave", id.longArg(0), fresh()),
                            "Adjust score (" + score(id.longArg(0)) + ")")
                    .addComponents(
                            Label.of("Change, e.g. +10 or -20", TextInput.create("change", TextInputStyle.SHORT)
                                    .setRequired(true).setMaxLength(5).build()),
                            Label.of("Reason", TextInput.create("reason", TextInputStyle.SHORT).setRequired(true)
                                    .setMaxLength(200).build()))
                    .build()).queue();
            case "disc" -> {
                String now = requesters.personalDiscount(id.longArg(0)).map(p -> p.stripTrailingZeros().toPlainString())
                        .orElse("0");
                event.replyModal(Modal.create(CustomId.of("reqs", "discsave", id.longArg(0), fresh()), "Personal discount")
                        .addComponents(Label.of("% off Gold Personal (0 removes it)",
                                TextInput.create("pct", TextInputStyle.SHORT).setRequired(true).setMaxLength(6)
                                        .setValue(now).build()))
                        .build()).queue();
            }
            case "bl" -> event.replyModal(Modal.create(CustomId.of("reqs", "blsave", id.longArg(0),
                                    id.args().size() > 1 ? id.arg(1) : "s", fresh()), "Blacklist requester")
                    .addComponents(Label.of("Reason (shown when a request is refused)",
                            TextInput.create("reason", TextInputStyle.SHORT).setRequired(true).setMaxLength(200).build()))
                    .build()).queue();
            case "unbl" -> {
                InteractionHook hook = Screen.deferInPlace(event);
                long torn = id.longArg(0);
                admin.setBlacklisted(torn, false, null, null);
                audit.log(event.getUser().getIdLong(), "REQUESTER_UNBLACKLISTED", "requester:" + torn, null, null,
                        "Took **" + who(torn) + "** off the requester blacklist");
                page(torn, "✅ Taken off the blacklist.").show(hook);
            }
            case "fdother" -> event.replyModal(Modal.create(CustomId.of("reqs", "fdfind", fresh()), "Faction discount")
                    .addComponents(Label.of("Faction ID or name", TextInput.create("q", TextInputStyle.SHORT)
                                    .setRequired(true).setMaxLength(80).build()),
                            Label.of("% off Gold My faction (0 removes it)",
                                    TextInput.create("pct", TextInputStyle.SHORT).setRequired(true).setMaxLength(6).build()))
                    .build()).queue();
            default -> list(null).show(event);
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        String v = event.getValues().getFirst();
        switch (id.sub()) {
            case "pick" -> page(Long.parseLong(v), null).show(event);
            case "fdpick" -> {
                long faction = Long.parseLong(v);
                String now = requesters.factionDiscount(faction).map(p -> p.stripTrailingZeros().toPlainString()).orElse("0");
                event.replyModal(Modal.create(CustomId.of("reqs", "fdsave", faction, fresh()),
                                trim("Discount · " + factionName(faction), 45))
                        .addComponents(Label.of("% off Gold My faction (0 removes it)",
                                TextInput.create("pct", TextInputStyle.SHORT).setRequired(true).setMaxLength(6)
                                        .setValue(now).build()))
                        .build()).queue();
            }
            default -> list(null).show(event);
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "findsave" -> {
                InteractionHook hook = Screen.deferInPlace(event);
                find(value(event, "q")).show(hook);
            }
            case "adjsave" -> {
                InteractionHook hook = Screen.deferInPlace(event);
                long torn = id.longArg(0);
                Integer change = parseChange(value(event, "change"));
                String reason = value(event, "reason");
                if (change == null || change == 0 || Math.abs(change) > 100) {
                    page(torn, "❌ Enter a change like +10 or -20 (up to 100). Nothing was changed.").show(hook);
                    return;
                }
                int after = reputation.change(torn, change, "by a leader: " + reason, null, null, user);
                audit.log(user, "REPUTATION_ADJUSTED", "requester:" + torn, null, Map.of("change", change, "score", after),
                        "Changed **" + who(torn) + "**'s reputation by " + signed(change) + " to " + after + ": " + reason);
                page(torn, "⭐ Score now " + after + ".").show(hook);
            }
            case "discsave" -> {
                InteractionHook hook = Screen.deferInPlace(event);
                long torn = id.longArg(0);
                BigDecimal pct = parsePct(value(event, "pct"));
                if (pct == null) {
                    page(torn, "❌ Enter a percent from 0 to 100. Nothing was changed.").show(hook);
                    return;
                }
                admin.setPersonalDiscount(torn, pct, user);
                audit.log(user, "DISCOUNT_SET", "requester:" + torn, null, Map.of("pct", pct),
                        pct.signum() == 0 ? "Removed **" + who(torn) + "**'s personal discount"
                                : "Set **" + who(torn) + "**'s personal discount to " + pct.toPlainString() + "%");
                page(torn, pct.signum() == 0 ? "🏷️ Personal discount removed."
                        : "🏷️ Personal discount set to " + pct.toPlainString() + "%. It applies from their next quote.")
                        .show(hook);
            }
            case "blsave" -> {
                long torn = id.longArg(0);
                String reason = value(event, "reason");
                boolean fromCard = "card".equals(id.arg(1));
                InteractionHook hook = fromCard ? event.deferEdit().complete() : Screen.deferInPlace(event);
                if (admin.find(torn).isEmpty()) {
                    ensureRequester(torn);
                }
                admin.setBlacklisted(torn, true, reason, user);
                audit.log(user, "REQUESTER_BLACKLISTED", "requester:" + torn, null, Map.of("reason", reason),
                        "Blacklisted **" + who(torn) + "** as a requester: " + reason);
                if (fromCard) {
                    hook.editOriginal(new MessageEditBuilder().setReplace(true).setContent("⛔ **" + who(torn)
                            + "** blacklisted by <@" + user + ">: " + reason).build()).queue(null, ignored -> { });
                } else {
                    page(torn, "⛔ Blacklisted. Their requests are refused from now on.").show(hook);
                }
            }
            case "fdsave" -> {
                InteractionHook hook = Screen.deferInPlace(event);
                saveFactionDiscount(id.longArg(0), parsePct(value(event, "pct")), user).show(hook);
            }
            case "fdfind" -> {
                InteractionHook hook = Screen.deferInPlace(event);
                BigDecimal pct = parsePct(value(event, "pct"));
                String q = value(event, "q");
                Long faction = null;
                Matcher m = Pattern.compile("^\\s*(\\d{1,10})\\s*$").matcher(q);
                if (m.matches()) {
                    faction = Long.parseLong(m.group(1));
                } else {
                    try {
                        var found = lookups.searchFactions(q);
                        if (found.size() == 1) {
                            faction = found.getFirst().id();
                            factionNames.put(faction, found.getFirst().name());
                        } else if (!found.isEmpty()) {
                            factionDiscounts("Several factions match \"" + q + "\". Use the faction ID: " + String.join(", ",
                                    found.stream().limit(8).map(f -> f.name() + " [" + f.id() + "]").toList()) + ".").show(hook);
                            return;
                        }
                    } catch (Exception e) {
                        factionDiscounts("❌ Torn didn't answer the faction search. Try the faction ID.").show(hook);
                        return;
                    }
                }
                if (faction == null) {
                    factionDiscounts("❌ No faction found for \"" + q + "\".").show(hook);
                    return;
                }
                saveFactionDiscount(faction, pct, user).show(hook);
            }
            default -> list(null).show(Screen.deferInPlace(event));
        }
    }

    // ------------------------------------------------------------------ screens

    private Screen list(String notice) {
        var counts = admin.counts();
        List<Row> recent = admin.recent(25);
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER).setAuthor("MERC MANAGERS").setTitle("👥  Requesters")
                .setDescription((notice == null ? "" : notice + "\n\n") + "**" + counts.requesters() + "** requesters · "
                        + counts.blacklisted() + " blacklisted · " + counts.personal() + " personal discounts · "
                        + counts.faction() + " faction discounts\n-# Auto-accept at " + settings.autoAcceptScore()
                        + " or more" + (settings.autoAcceptFamily() ? " · family always auto-accepted" : "") + Panels.GAP);
        if (!recent.isEmpty()) {
            int w = recent.stream().limit(10).mapToInt(r -> label(r).length()).max().orElse(10);
            StringBuilder t = new StringBuilder("```\n");
            recent.stream().limit(10).forEach(r -> t.append(pad(label(r), w)).append("  ").append(padLeft(Integer.toString(r.score()), 3))
                    .append("  ").append(state(r)).append(r.lastRef() == null ? "" : "  " + r.lastRef()).append('\n'));
            eb.addField("Most recent", t.append("```").toString(), false);
        }
        List<ActionRow> rows = new ArrayList<>();
        if (!recent.isEmpty()) {
            rows.add(ActionRow.of(picker(recent, "Pick a requester (25 most recent)…")));
        }
        rows.add(ActionRow.of(
                Button.primary(CustomId.of("reqs", "find"), "Find by ID or name…").withEmoji(Emoji.fromUnicode("🔎")),
                Button.secondary(CustomId.of("reqs", "blist"), "Blacklist").withEmoji(Emoji.fromUnicode("⛔")),
                Button.secondary(CustomId.of("reqs", "fdisc"), "Faction discounts").withEmoji(Emoji.fromUnicode("🏰"))));
        return new Screen(eb.build(), rows);
    }

    private Screen find(String q) {
        Matcher m = ID.matcher(q == null ? "" : q);
        if (m.find() && (q.contains("XID=") || q.strip().matches("\\d+"))) {
            long torn = Long.parseLong(m.group(1));
            if (admin.find(torn).isPresent()) {
                return page(torn, null);
            }
            return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("REQUESTERS")
                            .setTitle("🔎  [" + torn + "] hasn't requested anything")
                            .setDescription("You can still blacklist them, so any request they make is refused.").build(),
                    ActionRow.of(Button.danger(CustomId.of("reqs", "bl", torn), "Blacklist…").withEmoji(Emoji.fromUnicode("⛔")),
                            Button.primary(CustomId.of("reqs", "view"), "Requesters").withEmoji(Emoji.fromUnicode("⬅️"))));
        }
        List<Row> found = admin.byName(q == null ? "" : q.strip(), 25);
        if (found.isEmpty()) {
            return list("No requester matches \"" + q + "\".");
        }
        if (found.size() == 1) {
            return page(found.getFirst().tornId(), null);
        }
        return new Screen(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("REQUESTERS")
                .setTitle("🔎  " + found.size() + " match \"" + q.strip() + "\"").build(),
                List.of(ActionRow.of(picker(found, "Pick one…")),
                        ActionRow.of(Button.primary(CustomId.of("reqs", "view"), "Requesters").withEmoji(Emoji.fromUnicode("⬅️")))));
    }

    private Screen page(long tornId, String notice) {
        Optional<Row> found = admin.find(tornId);
        if (found.isEmpty()) {
            return list("That requester wasn't found.");
        }
        Row r = found.get();
        boolean family = r.factionId() != null && factions.isFamily(r.factionId());
        String factionLine = r.factionId() == null ? "No faction" : "Faction: " + factionName(r.factionId())
                + " [" + r.factionId() + "]" + (family ? " · family" : "");
        String first = admin.firstRequest(tornId).map(i -> " · first request " + DAY.format(i)).orElse("");
        boolean auto = family && settings.autoAcceptFamily() || r.score() >= settings.autoAcceptScore();
        var pay = admin.payments(tornId);
        Optional<BigDecimal> disc = requesters.personalDiscount(tornId).filter(p -> p.signum() > 0);
        EmbedBuilder eb = new EmbedBuilder().setColor(r.blacklisted() ? Panels.BAD : Panels.LEADER).setAuthor("REQUESTERS")
                .setTitle((r.blacklisted() ? "⛔  " : "👤  ") + label(r))
                .setDescription((notice == null ? "" : notice + "\n\n") + factionLine + first
                        + (r.blacklisted() ? "\n**Blacklisted:** " + (r.blacklistReason() == null ? "no reason given" : r.blacklistReason()) : "")
                        + Panels.GAP)
                .addField("⭐  Reputation", r.score() + " / 100\n" + bar(r.score()) + "\n-# " + (auto ? "auto-accepted" : "needs approval (below "
                        + settings.autoAcceptScore() + ")") + Panels.GAP, true)
                .addField("🧾  Payments", pay.onTime() + " on time · " + pay.late() + " late\n-# "
                        + (pay.unpaid() == 0 ? "nothing unpaid" : pay.unpaid() + " unpaid · " + Money.full(pay.unpaidDue())) + Panels.GAP, true)
                .addField("🏷️  Discount", disc.map(p -> p.stripTrailingZeros().toPlainString() + "% personal\n-# Gold, Personal requests")
                        .orElse("None\n-# personal") + Panels.GAP, true);
        var lines = admin.contracts(tornId, 5);
        if (!lines.isEmpty()) {
            int w = lines.stream().mapToInt(l -> l.ref().length()).max().orElse(7);
            StringBuilder t = new StringBuilder("```\n");
            for (var l : lines) {
                t.append(pad(l.ref(), w)).append("  ").append(pad(l.tier() == null ? "?" : l.tier(), 7))
                        .append(padLeft(l.hits() + (l.hits() == 1 ? " hit " : " hits"), 8)).append("  ")
                        .append(padLeft(Money.full(l.billed()), 12)).append("  ")
                        .append(l.payment() == null ? "no invoice" : l.payment()).append('\n');
            }
            long[] tot = admin.totals(tornId);
            t.append("```-# ").append(tot[0]).append(tot[0] == 1 ? " contract · " : " contracts · ").append(Money.full(tot[1]))
                    .append(" billed · ").append(Money.full(tot[2])).append(" paid");
            eb.addField("📋  Contracts", t.toString(), false);
        }
        Button bl = r.blacklisted()
                ? Button.success(CustomId.of("reqs", "unbl", tornId), "Unblacklist").withEmoji(Emoji.fromUnicode("✅"))
                : Button.danger(CustomId.of("reqs", "bl", tornId), "Blacklist…").withEmoji(Emoji.fromUnicode("⛔"));
        return Screen.of(eb.build(), ActionRow.of(
                Button.secondary(CustomId.of("reqs", "adj", tornId), "Adjust score…").withEmoji(Emoji.fromUnicode("⭐")),
                Button.secondary(CustomId.of("reqs", "disc", tornId), "Discount…").withEmoji(Emoji.fromUnicode("🏷️")),
                Button.secondary(CustomId.of("reqs", "hist", tornId), "Reputation history").withEmoji(Emoji.fromUnicode("📜")),
                bl,
                Button.primary(CustomId.of("reqs", "view"), "Requesters").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private Screen history(long tornId) {
        var events = admin.events(tornId, 15);
        int score = score(tornId);
        StringBuilder t = new StringBuilder();
        if (events.isEmpty()) {
            t.append("No changes yet.");
        } else {
            t.append("```\n");
            for (var e : events) {
                String why = e.reason();
                t.append(pad(EVENT_DAY.format(e.at()), 11)).append(padLeft(signed(e.change()), 5)).append("  ")
                        .append(trim(why, 46)).append(e.ref() == null ? "" : "  " + e.ref()).append('\n');
            }
            t.append("```");
        }
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER)
                        .setAuthor(("REQUESTERS · " + who(tornId) + " · REPUTATION").toUpperCase())
                        .setTitle("⭐  " + score + " now · new requesters start at " + settings.repStart())
                        .setDescription(t.toString()).build(),
                ActionRow.of(Button.primary(CustomId.of("reqs", "open", tornId), who(tornId)).withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private Screen blacklist(String notice) {
        List<Row> rows = admin.blacklistedRows(25);
        StringBuilder sb = new StringBuilder(notice == null ? "" : notice + "\n\n");
        if (rows.isEmpty()) {
            sb.append("Nobody is blacklisted.");
        }
        rows.stream().limit(15).forEach(r -> sb.append("**").append(label(r)).append("**")
                .append(r.blacklistReason() == null ? "" : "\n-# " + r.blacklistReason()).append('\n'));
        List<ActionRow> out = new ArrayList<>();
        if (!rows.isEmpty()) {
            out.add(ActionRow.of(picker(rows, "Open one…")));
        }
        out.add(ActionRow.of(Button.danger(CustomId.of("reqs", "find"), "Add someone…").withEmoji(Emoji.fromUnicode("➕")),
                Button.primary(CustomId.of("reqs", "view"), "Requesters").withEmoji(Emoji.fromUnicode("⬅️"))));
        return new Screen(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("REQUESTERS").setTitle("⛔  Requester blacklist")
                .setDescription(sb.toString().strip() + "\n-# Add someone: find them by ID, then Blacklist." + Panels.GAP).build(), out);
    }

    private Screen factionDiscounts(String notice) {
        List<FamilyFaction> family = factions.findActive();
        Map<Long, BigDecimal> set = new java.util.LinkedHashMap<>();
        admin.factionDiscounts().forEach(d -> set.put(d.factionId(), d.pct()));
        StringBuilder t = new StringBuilder("```\nFamily\n");
        int w = 22;
        for (FamilyFaction f : family) {
            t.append(pad(f.name() + " [" + f.tornFactionId() + "]", w)).append("  ")
                    .append(Optional.ofNullable(set.get(f.tornFactionId())).map(p -> p.stripTrailingZeros().toPlainString() + "%").orElse("–"))
                    .append('\n');
        }
        List<Long> others = set.keySet().stream().filter(id -> family.stream().noneMatch(f -> f.tornFactionId() == id)).toList();
        if (!others.isEmpty()) {
            t.append("Others\n");
            others.forEach(id -> t.append(pad(factionName(id) + " [" + id + "]", w)).append("  ")
                    .append(set.get(id).stripTrailingZeros().toPlainString()).append("%\n"));
        }
        t.append("```");
        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("reqs", "fdpick")).setPlaceholder("Pick a faction to change…");
        family.stream().limit(20).forEach(f -> pick.addOption(f.name(), Long.toString(f.tornFactionId()), "Family faction",
                Emoji.fromUnicode("🏰")));
        others.stream().limit(25 - Math.min(20, family.size())).forEach(id -> pick.addOption(trim(factionName(id), 100),
                Long.toString(id), Emoji.fromUnicode("🏷️")));
        List<ActionRow> rows = new ArrayList<>();
        if (!family.isEmpty() || !others.isEmpty()) {
            rows.add(ActionRow.of(pick.build()));
        }
        rows.add(ActionRow.of(Button.primary(CustomId.of("reqs", "fdother"), "Another faction…").withEmoji(Emoji.fromUnicode("➕")),
                Button.secondary(CustomId.of("reqs", "view"), "Requesters").withEmoji(Emoji.fromUnicode("⬅️"))));
        return new Screen(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("REQUESTERS · FACTION DISCOUNTS")
                .setTitle("🏰  Faction discounts")
                .setDescription((notice == null ? "" : notice + "\n\n") + "For My faction requests by members of that faction. "
                        + "Gold only.\n" + t).build(), rows);
    }

    private Screen saveFactionDiscount(long faction, BigDecimal pct, long user) {
        if (pct == null) {
            return factionDiscounts("❌ Enter a percent from 0 to 100. Nothing was changed.");
        }
        admin.setFactionDiscount(faction, pct, user);
        String name = factionName(faction);
        audit.log(user, "DISCOUNT_SET", "faction:" + faction, null, Map.of("pct", pct),
                pct.signum() == 0 ? "Removed the faction discount for **" + name + "**"
                        : "Set the faction discount for **" + name + "** to " + pct.toPlainString() + "%");
        return factionDiscounts(pct.signum() == 0 ? "🏷️ Removed the discount for " + name + "."
                : "🏷️ " + name + ": " + pct.toPlainString() + "% from the next quote.");
    }

    // ------------------------------------------------------------------ helpers

    private StringSelectMenu picker(List<Row> rows, String placeholder) {
        StringSelectMenu.Builder b = StringSelectMenu.create(CustomId.of("reqs", "pick")).setPlaceholder(placeholder);
        rows.stream().limit(25).forEach(r -> b.addOption(trim(label(r), 100), Long.toString(r.tornId()),
                trim("Score " + r.score() + " · " + state(r) + (r.lastRef() == null ? "" : " · " + r.lastRef()), 100),
                Emoji.fromUnicode(r.blacklisted() ? "⛔" : r.unpaid() > 0 ? "⏳" : "👤")));
        return b.build();
    }

    private void ensureRequester(long torn) {
        try {
            var p = lookups.player(torn);
            admin.ensure(torn, 0L, p.name(), p.factionId(), settings.repStart());
        } catch (Exception e) {
            admin.ensure(torn, 0L, null, null, settings.repStart());
        }
    }

    private String state(Row r) {
        if (r.blacklisted()) {
            return "⛔ blacklisted";
        }
        if (r.unpaid() > 0) {
            return "⏳ " + r.unpaid() + " unpaid";
        }
        return r.factionId() != null && factions.isFamily(r.factionId()) ? "✓ family" : "✓";
    }

    private int score(long torn) {
        return admin.find(torn).map(Row::score).orElse(settings.repStart());
    }

    private String who(long torn) {
        return admin.find(torn).map(RequestersHandler::label).orElse("[" + torn + "]");
    }

    private static String label(Row r) {
        return (r.name() == null ? "Player" : r.name()) + " [" + r.tornId() + "]";
    }

    private String factionName(long id) {
        Optional<String> family = factions.find(id).map(FamilyFaction::name);
        if (family.isPresent()) {
            return family.get();
        }
        return factionNames.computeIfAbsent(id, f -> {
            try {
                return pool.call(TornKeyPool.Scope.any(), "/faction/" + f + "/basic", Map.of()).path("basic").path("name")
                        .asText("Faction " + f);
            } catch (Exception e) {
                return "Faction " + f;
            }
        });
    }

    private static String bar(int score) {
        int filled = Math.round(score / 10f);
        return "🟩".repeat(filled) + "⬛".repeat(10 - filled);
    }

    public static Integer parseChange(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip().replace("−", "-").replace("+", "");
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal parsePct(String s) {
        if (s == null) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(s.strip().replace("%", "")).setScale(2, RoundingMode.HALF_UP);
            return v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String signed(int n) {
        return n > 0 ? "+" + n : n < 0 ? "−" + Math.abs(n) : "0";
    }

    private static String value(ModalInteractionEvent event, String id) {
        return event.getValue(id) == null ? "" : event.getValue(id).getAsString().strip();
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String padLeft(String s, int w) {
        return s.length() >= w ? s : " ".repeat(w - s.length()) + s;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String fresh() {
        return Long.toString(System.nanoTime() % 1_000_000_000L, 36);
    }
}
