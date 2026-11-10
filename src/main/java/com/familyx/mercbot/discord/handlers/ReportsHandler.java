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
import com.familyx.mercbot.report.ReportRepository;
import com.familyx.mercbot.report.ReportRepository.AuditLine;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leader panel → Reports (scope doc 6.7): Revenue, Mercs, Contracts, Hospital time and
 * Requesters for 7, 30 or 90 days or all time, optionally narrowed to one family faction's
 * mercs, plus the audit view. Merc Managers only. The current choice lives in the custom ids.
 */
@Component
public class ReportsHandler implements InteractionHandler {

    enum Kind {
        REV("Revenue", "💰"), MERC("Mercs", "🗡️"), CON("Contracts", "📋"), HOSP("Hospital time", "⏱️"),
        REQ("Requesters", "👥");

        final String label;
        final String icon;

        Kind(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        boolean byFaction() {
            return this == REV || this == MERC || this == HOSP;
        }
    }

    enum Period {
        D7("7 days", 7), D30("30 days", 30), D90("90 days", 90), ALL("All time", 0);

        final String label;
        final int days;

        Period(String label, int days) {
            this.label = label;
            this.days = days;
        }

        Instant since() {
            return days == 0 ? null : Instant.now().minus(Duration.ofDays(days));
        }
    }

    enum AuditCat {
        ALL("All", List.of()),
        MONEY("Money", List.of("INVOICE", "PAYMENT", "PAYOUT", "SHARE", "SETTLEMENT", "DEPOSIT")),
        CONTRACTS("Contracts", List.of("CONTRACT", "OUTSIDE", "MESSAGE", "REQUEST", "APPROV", "REJECT")),
        MERCS("Mercs", List.of("MERC", "CLAIMS", "NOSHOW")),
        REQUESTERS("Requesters", List.of("REQUESTER", "REPUTATION", "DISCOUNT")),
        SETTINGS("Settings", List.of("SETTING", "TIER", "FACTION", "LEADER_KEY", "KEY", "SETUP"));

        final String label;
        final List<String> prefixes;

        AuditCat(String label, List<String> prefixes) {
            this.label = label;
            this.prefixes = prefixes;
        }
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)
            .withZone(ZoneOffset.UTC);
    private static final Pattern REF = Pattern.compile("^[A-Za-z]{1,4}-\\d+$");

    private final ReportRepository reports;
    private final FamilyFactionRepository factions;

    public ReportsHandler(ReportRepository reports, FamilyFactionRepository factions) {
        this.reports = reports;
        this.factions = factions;
    }

    @Override
    public String action() {
        return "rep";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        switch (id.sub()) {
            case "view" -> slow(event, () -> report(Kind.REV, Period.D30, 0));
            case "show" -> slow(event, () -> report(Kind.valueOf(id.arg(0)), Period.valueOf(id.arg(1)), id.longArg(2)));
            case "audit" -> slow(event, () -> audit(AuditCat.valueOf(id.arg(0)), null));
            case "afind" -> event.replyModal(Modal.create(CustomId.of("rep", "afindsave",
                                    Long.toString(System.nanoTime() % 1_000_000_000L, 36)), "Find in the audit log")
                    .addComponents(Label.of("Contract ref, Torn ID, faction ID or a word", TextInput.create("q",
                            TextInputStyle.SHORT).setRequired(true).setMaxLength(60).setPlaceholder("e.g. MC-0153").build()))
                    .build()).queue();
            case "close" -> event.editMessage(new MessageEditBuilder().setReplace(true).setContent("Closed.").build())
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            default -> slow(event, () -> report(Kind.REV, Period.D30, 0));
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        String v = event.getValues().getFirst();
        switch (id.sub()) {
            case "k" -> slow(event, () -> report(Kind.valueOf(v), Period.valueOf(id.arg(0)), id.longArg(1)));
            case "p" -> slow(event, () -> report(Kind.valueOf(id.arg(0)), Period.valueOf(v), id.longArg(1)));
            case "f" -> slow(event, () -> report(Kind.valueOf(id.arg(0)), Period.valueOf(id.arg(1)), Long.parseLong(v)));
            case "acat" -> slow(event, () -> audit(AuditCat.valueOf(v), null));
            default -> slow(event, () -> report(Kind.REV, Period.D30, 0));
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        String q = event.getValue("q") == null ? "" : event.getValue("q").getAsString().strip();
        audit(AuditCat.ALL, q.isEmpty() ? null : q).show(hook);
    }

    private interface Work {
        Screen build();
    }

    /** Reports can take a moment: acknowledge first, then draw in place. */
    private static void slow(net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent event,
                             Work work) {
        if (event.getMessage().isEphemeral()) {
            InteractionHook hook = Screen.deferInPlace(event);
            work.build().show(hook);
        } else {
            work.build().show(event);
        }
    }

    // ------------------------------------------------------------------ reports

    private Screen report(Kind kind, Period period, long faction) {
        Long f = kind.byFaction() && faction != 0 ? faction : null;
        String factionLabel = !kind.byFaction() ? null : f == null ? "ALL FACTIONS"
                : factions.find(f).map(FamilyFaction::name).orElse("Faction " + f).toUpperCase();
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER)
                .setAuthor("REPORTS · " + kind.label.toUpperCase() + " · " + period.label.toUpperCase()
                        + (factionLabel == null ? "" : " · " + factionLabel));
        Instant since = period.since();
        switch (kind) {
            case REV -> revenue(eb, since, f);
            case MERC -> mercs(eb, since, f);
            case CON -> contracts(eb, since);
            case HOSP -> hospital(eb, since, f);
            case REQ -> requesters(eb, since);
        }
        return new Screen(eb.build(), menus(kind, period, faction));
    }

    private List<ActionRow> menus(Kind kind, Period period, long faction) {
        List<ActionRow> rows = new ArrayList<>();
        StringSelectMenu.Builder k = StringSelectMenu.create(CustomId.of("rep", "k", period.name(), faction));
        for (Kind x : Kind.values()) {
            k.addOption("Report: " + x.label, x.name(), Emoji.fromUnicode(x.icon));
        }
        rows.add(ActionRow.of(k.setDefaultValues(kind.name()).build()));
        StringSelectMenu.Builder p = StringSelectMenu.create(CustomId.of("rep", "p", kind.name(), faction));
        for (Period x : Period.values()) {
            p.addOption("Period: " + x.label, x.name());
        }
        rows.add(ActionRow.of(p.setDefaultValues(period.name()).build()));
        if (kind.byFaction()) {
            StringSelectMenu.Builder fm = StringSelectMenu.create(CustomId.of("rep", "f", kind.name(), period.name()));
            fm.addOption("Faction: All factions", "0");
            factions.findActive().stream().limit(24).forEach(x -> fm.addOption("Faction: " + x.name(),
                    Long.toString(x.tornFactionId())));
            boolean known = faction == 0 || factions.findActive().stream().anyMatch(x -> x.tornFactionId() == faction);
            rows.add(ActionRow.of(fm.setDefaultValues(known ? Long.toString(faction) : "0").build()));
        }
        rows.add(ActionRow.of(
                Button.secondary(CustomId.of("rep", "show", kind.name(), period.name(), faction), "Refresh")
                        .withEmoji(Emoji.fromUnicode("🔄")),
                Button.secondary(CustomId.of("rep", "audit", AuditCat.ALL.name()), "Audit").withEmoji(Emoji.fromUnicode("📜")),
                Button.secondary(CustomId.of("rep", "close"), "Close").withEmoji(Emoji.fromUnicode("✖️"))));
        return rows;
    }

    private void revenue(EmbedBuilder eb, Instant since, Long f) {
        var r = reports.revenue(since, f);
        eb.setTitle("💰  " + Money.full(r.billed()) + " billed")
                .addField("Paid", Money.full(r.paid()) + "\n-# " + pct(r.paid(), r.billed()) + Panels.GAP, true)
                .addField("Outstanding", Money.full(r.outstanding()) + "\n-# " + r.outstandingInvoices()
                        + (r.outstandingInvoices() == 1 ? " invoice" : " invoices") + " open" + Panels.GAP, true)
                .addField("Faction cut", Money.full(r.cut()) + "\n-# kept in mercs" + Panels.GAP, true);
        var tiers = reports.revenueByTier(since, f);
        if (!tiers.isEmpty()) {
            int w = tiers.stream().mapToInt(t -> t.tier().length()).max().orElse(6);
            eb.addField("By tier", fit(code(tiers.stream().map(t -> pad(t.tier(), w) + "  " + padLeft(t.hits() + " hits", 9)
                    + "  " + padLeft(Money.full(t.billed()), 14)
                    + (t.discounts() > 0 ? "   discounts " + Money.full(t.discounts()) : "")).toList())), false);
        }
        var shares = reports.sharesByFaction(since, f);
        if (!shares.isEmpty()) {
            List<String> lines = shares.stream().map(s -> pad(fname(s.factionId()), 13) + padLeft(Money.full(s.amount()), 14)
                    + "   credited " + padLeft(Money.full(s.credited()), 14)).toList();
            eb.addField("Merc shares by faction", fit(code(lines)), false);
        }
        if (r.billed() == 0) {
            eb.setDescription("No accepted hits in this period.");
        }
    }

    private void mercs(EmbedBuilder eb, Instant since, Long f) {
        int[] counts = reports.hitCounts(since, f);
        eb.setTitle("🗡️  " + counts[0] + " hits accepted · " + counts[1] + " rejected");
        var lines = reports.mercs(since, f, 15);
        if (lines.isEmpty()) {
            eb.setDescription("No merc activity in this period.");
        } else {
            int w = Math.min(14, lines.stream().mapToInt(l -> mname(l).length()).max().orElse(8));
            List<String> rows = new ArrayList<>();
            rows.add(pad("", w) + "  Hits  " + pad("Earned", 13) + "Avg hosp  Strick.  No-shows");
            for (var l : lines) {
                String n = mname(l);
                rows.add(pad(n.length() > w ? n.substring(0, w - 1) + "…" : n, w) + "  " + padLeft(Integer.toString(l.hits()), 4)
                        + "  " + pad(Money.full(l.earned()), 13) + pad(hours(l.avgHosp()), 10)
                        + pad(l.strickenPct() == null ? "–" : l.strickenPct() + "%", 9) + l.noShows());
            }
            eb.setDescription(code(rows));
        }
        var rejects = reports.rejections(since, f);
        if (!rejects.isEmpty()) {
            int w = rejects.stream().mapToInt(r -> r.label().length()).max().orElse(10);
            eb.addField("Rejected hits by reason", fit(code(rejects.stream().map(r -> pad(r.label(), w) + "  "
                    + padLeft(Integer.toString(r.count()), 4)).toList())), false);
        }
    }

    private void contracts(EmbedBuilder eb, Instant since) {
        var c = reports.contracts(since);
        eb.setTitle("📋  " + c.started() + (c.started() == 1 ? " contract" : " contracts") + " went live")
                .addField("Completed", c.completed() + "\n-# " + pct(c.completed(), c.started()) + Panels.GAP, true)
                .addField("Ended early", c.ended() + "\n-# by requester or leader" + Panels.GAP, true)
                .addField("Cancelled", c.cancelled() + "\n-# no hits" + Panels.GAP, true)
                .addField("First hit", duration(c.firstHitMedianS()) + "\n-# median after going live" + Panels.GAP, true)
                .addField("Hit-count done", duration(c.doneMedianS()) + "\n-# median to complete" + Panels.GAP, true)
                .addField("Paused by leaders", c.pausedByLeaders() + (c.pausedByLeaders() == 1 ? " time" : " times") + "\n-# "
                        + c.pausedDefensive() + " for defensive services" + Panels.GAP, true);
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (var k : reports.contractKinds(since)) {
            String[] parts = k.label().split(":", 2);
            String label = switch (parts[1]) {
                case "HOSPITALIZE" -> "Hospitalize";
                case "MUG" -> "Mug";
                default -> parts[1];
            };
            groups.computeIfAbsent(parts[0], x -> new ArrayList<>()).add(label + " " + k.count());
        }
        if (!groups.isEmpty()) {
            eb.addField("By kind", fit(code(groups.values().stream().map(v -> String.join(" · ", v)).toList())), false);
        }
    }

    private void hospital(EmbedBuilder eb, Instant since, Long f) {
        eb.setTitle("⏱️  Achieved against expected, per tier");
        var lines = reports.hospital(since, f);
        if (lines.isEmpty()) {
            eb.setDescription("No hits with a captured hospital time in this period.");
            return;
        }
        int w = lines.stream().mapToInt(l -> l.tier().length()).max().orElse(6);
        List<String> rows = new ArrayList<>();
        rows.add(pad("", w) + "  Hits  " + pad("Median", 9) + pad("Expected", 11) + pad("In range", 10) + "Below");
        for (var l : lines) {
            String expected = l.min() == null || l.max() == null ? "not set"
                    : TiersHandler.hoursRange(new Integer[] {l.min(), l.max()}) + "h";
            rows.add(pad(l.tier(), w) + "  " + padLeft(Integer.toString(l.hits()), 4) + "  " + pad(hours(l.median()), 9)
                    + pad(expected, 11) + pad(l.inPct() == null ? "–" : l.inPct() + "%", 10)
                    + (l.belowPct() == null ? "–" : l.belowPct() + "%"));
        }
        eb.setDescription(code(rows) + "\n-# Only hits with a captured hospital time count. More time than the range "
                + "counts as in range. Set ranges in Tiers & pricing.");
    }

    private void requesters(EmbedBuilder eb, Instant since) {
        var t = reports.requesterTotals(since);
        eb.setTitle("👥  " + t.requesters() + (t.requesters() == 1 ? " requester" : " requesters") + " · "
                + Money.full(t.billed()) + " invoiced");
        var lines = reports.requesters(since, 10);
        StringBuilder d = new StringBuilder();
        if (!lines.isEmpty()) {
            int w = Math.min(16, lines.stream().mapToInt(l -> rname(l).length()).max().orElse(10));
            List<String> rows = new ArrayList<>();
            rows.add(pad("", w) + "  Contracts  " + pad("Invoiced", 14) + pad("Avg to pay", 12) + "Outstanding");
            for (var l : lines) {
                String n = rname(l);
                rows.add(pad(n.length() > w ? n.substring(0, w - 1) + "…" : n, w) + "  " + padLeft(Integer.toString(l.contracts()), 9)
                        + "  " + pad(Money.full(l.billed()), 14) + pad(duration(l.avgPayS()), 12)
                        + (l.outstanding() == 0 ? "–" : Money.full(l.outstanding())));
            }
            d.append(code(rows)).append('\n');
        }
        d.append("Average time to pay: **").append(duration(t.avgPayS())).append("** · paid on time ")
                .append(t.onTimePct() == null ? "–" : t.onTimePct() + "%").append(" · ").append(t.outstanding())
                .append(" outstanding (").append(Money.full(t.outstandingDue())).append(")");
        eb.setDescription(d.toString());
    }

    // ------------------------------------------------------------------ audit

    private Screen audit(AuditCat cat, String find) {
        List<String> entities = new ArrayList<>();
        if (find != null) {
            if (REF.matcher(find).matches()) {
                reports.contractIdByRef(find).ifPresent(cid -> entities.add("contract:" + cid));
            }
            Matcher digits = Pattern.compile("^\\d{1,10}$").matcher(find);
            if (digits.matches()) {
                for (String e : List.of("merc:", "requester:", "faction:", "contract:", "invoice:", "tier:")) {
                    entities.add(e + find);
                }
            }
        }
        List<AuditLine> lines = reports.audit(find == null ? cat.prefixes : List.of(), find, entities, 15);
        StringBuilder d = new StringBuilder();
        for (AuditLine l : lines) {
            String text = l.summary() != null ? l.summary()
                    : l.action().toLowerCase(Locale.ROOT).replace('_', ' ') + (l.entity() == null ? "" : " · " + l.entity());
            String line = "`" + WHEN.format(l.at()) + "` " + (l.actor() == null ? "Merc Bot" : "<@" + l.actor() + ">")
                    + " · " + (text.length() > 180 ? text.substring(0, 179) + "…" : text) + "\n";
            if (d.length() + line.length() > 3900) {
                break;
            }
            d.append(line);
        }
        if (lines.isEmpty()) {
            d.append(find == null ? "Nothing logged yet." : "Nothing matches \"" + find + "\".");
        }
        MessageEmbed embed = new EmbedBuilder().setColor(Panels.LEADER)
                .setAuthor("AUDIT · " + (find == null ? cat.label.toUpperCase() : "FIND \"" + find.toUpperCase() + "\"")
                        + " · LATEST " + Math.min(15, lines.size()))
                .setTitle("📜  Audit log").setDescription(d.toString().strip()).build();
        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("rep", "acat"));
        for (AuditCat c : AuditCat.values()) {
            pick.addOption("Show: " + c.label, c.name());
        }
        return Screen.of(embed, ActionRow.of(pick.setDefaultValues(cat.name()).build()),
                ActionRow.of(Button.primary(CustomId.of("rep", "afind"), "Find a contract, player or faction…")
                                .withEmoji(Emoji.fromUnicode("🔎")),
                        Button.secondary(CustomId.of("rep", "view"), "Reports").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    // ------------------------------------------------------------------ helpers

    private String fname(Long id) {
        return id == null ? "No faction" : factions.find(id).map(FamilyFaction::name).orElse("Faction " + id);
    }

    private static String mname(ReportRepository.MercLine l) {
        return l.name() == null ? "Player " + l.mercId() : l.name();
    }

    private static String rname(ReportRepository.RequesterLine l) {
        return l.name() == null ? "Player " + l.tornId() : l.name();
    }

    private static String code(List<String> lines) {
        return "```\n" + String.join("\n", lines) + "\n```";
    }

    /** Embed fields hold 1,024 characters: drop lines from the end of the code block until it fits. */
    private static String fit(String s) {
        if (s.length() <= 1024) {
            return s;
        }
        List<String> lines = new ArrayList<>(List.of(s.substring(4, s.length() - 4).split("\n")));
        while (!lines.isEmpty() && code(lines).length() + 12 > 1024) {
            lines.removeLast();
        }
        lines.add("…");
        return code(lines);
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "–" : Math.round(100.0 * part / whole) + "%";
    }

    static String hours(Integer seconds) {
        if (seconds == null) {
            return "–";
        }
        return seconds / 3600 + "h " + String.format("%02dm", (seconds % 3600) / 60);
    }

    static String duration(Long seconds) {
        if (seconds == null) {
            return "–";
        }
        if (seconds < 3600) {
            return Math.max(1, seconds / 60) + " min";
        }
        if (seconds < 172_800) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            return h + "h" + (m == 0 ? "" : " " + m + "m");
        }
        return String.format(Locale.UK, "%.1f days", seconds / 86400.0);
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String padLeft(String s, int w) {
        return s.length() >= w ? s : " ".repeat(w - s.length()) + s;
    }
}
