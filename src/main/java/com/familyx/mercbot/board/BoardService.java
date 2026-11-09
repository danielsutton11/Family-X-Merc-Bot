package com.familyx.mercbot.board;

import com.familyx.mercbot.board.BoardRules.Verdict;
import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.bsp.BspService;
import com.familyx.mercbot.discord.MessageUpdater;
import com.familyx.mercbot.discord.PanelHashes;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import com.familyx.mercbot.discord.CustomId;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tier boards (scope doc 5.3): one message per tier channel, drawn with Discord's newer
 * layout so each target row has its Attack button beside it, and edited in place.
 */
@Service
public class BoardService {

    /** Rows per board without claims (scope doc 5.3). */
    public static final int MAX_ROWS = 10;
    private static final String ATTACK_URL = "https://www.torn.com/page.php?sid=attack&user2ID=";

    /** Rows per board while claims are on: each row then needs its own button row (Discord's 40-part limit). */
    public static final int MAX_ROWS_CLAIMS = 8;

    /**
     * One row as drawn on a board. {@code early}: shown before it's hittable so it can be
     * claimed (Attack is greyed out); {@code claim}: who holds it, if anyone.
     */
    public record Row(BoardTarget target, Long bsp, boolean early, ClaimRepository.OpenClaim claim) { }

    /**
     * What a board shows: the top rows, every row in order (for the private pages), how many
     * more there are beyond the top, and why the rest are hidden.
     */
    public record Board(Tier tier, List<Row> rows, List<Row> all, int more, Map<Verdict, Integer> hidden,
                        boolean claims) {
        public int perPage() {
            return claims ? MAX_ROWS_CLAIMS : MAX_ROWS;
        }

        public int pages() {
            return Math.max(1, (all.size() + perPage() - 1) / perPage());
        }
    }

    private final TargetRepository targets;
    private final TierRepository tiers;
    private final BspService bsp;
    private final MessageUpdater updater;
    private final PanelHashes hashes;
    private final PingService pings;
    private final ClaimService claims;
    private final com.familyx.mercbot.settings.BotSettings settings;
    /** Open claims by target row, as of the last refresh. */
    private volatile Map<Long, ClaimRepository.OpenClaim> openClaims = Map.of();
    /** Each target's verdict at the last refresh, to spot targets coming back. */
    private final Map<Long, Verdict> lastVerdict = new ConcurrentHashMap<>();

    /** A merc's open "See all" page, kept up to date until Discord stops letting the bot edit it. */
    private record OpenPage(int tierId, int page, net.dv8tion.jda.api.interactions.InteractionHook hook,
                            Instant until, String lastDrawn) {
        OpenPage drawn(String text) {
            return new OpenPage(tierId, page, hook, until, text);
        }
    }

    /** Discord allows edits to a private message for 15 minutes after the last click; stop just before. */
    private static final java.time.Duration PAGE_LIVE_FOR = java.time.Duration.ofMinutes(14);
    private final Map<Long, OpenPage> openPages = new ConcurrentHashMap<>();

    public BoardService(TargetRepository targets, TierRepository tiers, BspService bsp, MessageUpdater updater,
                        PanelHashes hashes, PingService pings, ClaimService claims,
                        com.familyx.mercbot.settings.BotSettings settings) {
        this.claims = claims;
        this.settings = settings;
        this.targets = targets;
        this.tiers = tiers;
        this.bsp = bsp;
        this.updater = updater;
        this.hashes = hashes;
        this.pings = pings;
    }

    /** Recomputes every board, pings for targets that came back, and edits boards that changed. */
    public synchronized void refresh() {
        Instant now = Instant.now();
        List<BoardTarget> running = targets.running();
        openClaims = claims.sweep(running, now);
        Map<Integer, List<BoardTarget>> byTier = new HashMap<>();
        for (BoardTarget t : running) {
            byTier.computeIfAbsent(t.tierId(), k -> new ArrayList<>()).add(t);
        }
        for (Tier tier : tiers.findActive()) {
            List<BoardTarget> list = byTier.getOrDefault(tier.id(), List.of());
            Board board = build(tier, list, now);
            List<BoardTarget> back = new ArrayList<>();
            Map<Long, Boolean> fromHospital = new HashMap<>();
            for (BoardTarget t : list) {
                Verdict v = BoardRules.verdict(t, now);
                Verdict before = lastVerdict.put(t.id(), v);
                boolean shown = v == Verdict.SHOW;
                if (shown != t.onBoard()) {
                    targets.setOnBoard(t.id(), shown);
                }
                if (v == Verdict.SHOW && before != null && before != Verdict.SHOW && before != Verdict.UNKNOWN) {
                    // Includes targets returning because their contract was resumed.
                    back.add(t);
                    fromHospital.put(t.id(), before == Verdict.NOT_YET);
                }
            }
            if (!back.isEmpty()) {
                Map<Long, Long> est = bsp.estimates(back.stream().map(BoardTarget::tornId).toList());
                pings.targetsBack(tier, back.stream().map(t -> new PingService.Back(t, est.get(t.tornId()),
                        fromHospital.getOrDefault(t.id(), false))).toList());
            }
            updateOpenPages(board, now);
            if (tier.channelId() != null && tier.boardMessageId() != null) {
                MessageEditData data = editData(board);
                updater.request("board:" + tier.id(), () -> Optional.of(
                        new MessageUpdater.Edit(tier.channelId(), tier.boardMessageId(), data)));
            }
        }
    }

    /** The board for one tier, from its running targets. */
    public Board build(Tier tier, List<BoardTarget> list, Instant now) {
        boolean claimsOn = settings.claimsOn();
        java.time.Duration window = java.time.Duration.ofMinutes(settings.claimShowEarlyMinutes());
        List<BoardTarget> shown = new ArrayList<>();
        java.util.Set<Long> early = new java.util.HashSet<>();
        Map<Verdict, Integer> hidden = new EnumMap<>(Verdict.class);
        for (BoardTarget t : list) {
            Verdict v = BoardRules.verdict(t, now);
            if (v == Verdict.SHOW) {
                shown.add(t);
            } else if (claimsOn && BoardRules.showEarly(t, now, window)) {
                shown.add(t); // coming out soon: claimable, Attack greyed out
                early.add(t.id());
            } else if (v.hiddenByConditions()) {
                hidden.merge(v, 1, Integer::sum);
            }
        }
        // Contract priority first, then the strongest targets (highest BSP estimate); no estimate goes last.
        Map<Long, Long> estimates = bsp.estimates(shown.stream().map(BoardTarget::tornId).toList());
        Map<Long, ClaimRepository.OpenClaim> held = claimsOn ? openClaims : Map.of();
        List<Row> all = new ArrayList<>(shown.stream().map(t -> new Row(t, estimates.get(t.tornId()),
                early.contains(t.id()), held.get(t.id()))).toList());
        all.sort(Comparator.comparingInt((Row r) -> r.target().priority()).reversed()
                .thenComparing(Row::bsp, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.target().contractCreated(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.target().name() == null ? "" : r.target().name().toLowerCase()));
        int perPage = claimsOn ? MAX_ROWS_CLAIMS : MAX_ROWS;
        List<Row> rows = all.subList(0, Math.min(perPage, all.size()));
        return new Board(tier, List.copyOf(rows), List.copyOf(all), all.size() - rows.size(), hidden, claimsOn);
    }

    // ------------------------------------------------------------------ drawing

    public static MessageEditData editData(Board b) {
        return new MessageEditBuilder().useComponentsV2().setComponents(draw(b)).build();
    }

    static List<MessageTopLevelComponent> draw(Board b) {
        Tier tier = b.tier();
        List<ContainerChildComponent> parts = new ArrayList<>();
        int n = b.rows().size() + b.more();
        String header = "-# **" + tier.name().toUpperCase() + " CONTRACTS**\n"
                + (n == 0 ? "## " + Panels.tierIcon(tier) + "  No targets right now\n-# Targets appear here as soon as a "
                + tier.name() + " contract has someone to hit."
                : "## " + Panels.tierIcon(tier) + "  " + n + (n == 1 ? " target" : " targets") + " on the board\n"
                + (b.claims() ? "-# Claim reserves a target for you · Attack opens it in Torn"
                : "-# Attack opens the target in Torn"));
        parts.add(TextDisplay.of(header));
        if (!b.rows().isEmpty()) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            Instant now = Instant.now();
            for (Row r : b.rows()) {
                addRow(parts, b, r, now);
            }
        }
        String footer = footer(b);
        if (!footer.isEmpty()) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(TextDisplay.of(footer));
        }
        if (b.more() > 0) {
            parts.add(ActionRow.of(Button.secondary(CustomId.of("board", "page", tier.id(), 2),
                    "See all " + n + " (page 2 of " + b.pages() + ") ▶").withEmoji(Emoji.fromUnicode("📄"))));
        }
        return List.of(Container.of(parts).withAccentColor(tier.colour()));
    }

    /**
     * One row: with claims on, the text then a button row (Claim, Attack); with claims off, the
     * text with Attack beside it.
     */
    private static void addRow(List<ContainerChildComponent> parts, Board b, Row r, Instant now) {
        Button attack = Button.link(ATTACK_URL + r.target().tornId(), "Attack ↗");
        if (!b.claims()) {
            parts.add(Section.of(attack, TextDisplay.of(rowText(b.tier(), r, now))));
            return;
        }
        parts.add(TextDisplay.of(rowText(b.tier(), r, now)));
        Button claim = r.claim() == null
                ? Button.primary(CustomId.of("claim", "take", r.target().id()), "Claim").withEmoji(Emoji.fromUnicode("🔒"))
                : Button.secondary(CustomId.of("claim", "held", r.target().id()), ClaimService.name(r.claim()))
                .withEmoji(Emoji.fromUnicode("🔒")).asDisabled();
        parts.add(ActionRow.of(claim, r.early() ? attack.asDisabled() : attack));
    }

    static String rowText(Tier tier, Row r, Instant now) {
        BoardTarget t = r.target();
        boolean gold = tier.requiresStricken();
        boolean revivableOnGold = gold && Boolean.TRUE.equals(t.revivable()) && t.conditions().allowRevivable();
        String name = t.name() == null ? "Player " + t.tornId() : t.name();
        String line1 = "**" + name + "** [" + t.tornId() + "]" + (t.level() == null ? "" : " · Level " + t.level());
        String line2 = "BSP **" + BspService.format(r.bsp()) + "** · " + contractLabel(t);
        List<String> hints = new ArrayList<>();
        hints.add(t.ref());
        if (revivableOnGold) {
            hints.add("billed at Silver");
        }
        if (r.early()) {
            String where = "Hospital".equals(t.lastState()) ? "🏥 " : "Jail".equals(t.lastState()) ? "⛓️ " : "⚔️ ";
            hints.add(where + "hittable <t:" + BoardRules.hittableAt(t, now).getEpochSecond() + ":R>");
        } else {
            hints.add(BoardRules.activity(t, now));
        }
        if (r.claim() != null) {
            ClaimRepository.OpenClaim c = r.claim();
            hints.add("🔒 **" + ClaimService.name(c) + "** · " + (c.started(now)
                    ? "releases <t:" + c.expiresAt().getEpochSecond() + ":R>" : "lock starts when hittable"));
        }
        if (t.hasOverride()) {
            // Overrides on Gold always need Stricken, even on a revivable target.
            hints.add("📌 **Requester Override** (1 hit)");
            if (gold) {
                hints.add("⚡ **Stricken Required**");
            }
        } else if (revivableOnGold) {
            hints.add("🔴 **Don't Use Stricken**");
        } else if (gold) {
            hints.add("⚡ **Stricken Required**");
        }
        if (t.hitsRequired() != null && t.hitsRequired() > 1) {
            hints.add((t.hitsRequired() - t.hitsDone()) + " hits left");
        }
        return line1 + "\n" + line2 + "\n-# " + String.join(" · ", hints);
    }

    static String contractLabel(BoardTarget t) {
        if (t.factionTarget()) {
            return (t.targetFactionName() == null ? "Faction" : t.targetFactionName()) + " (faction)";
        }
        return t.targetCount() <= 1 ? "Single-target contract" : t.targetCount() + "-player contract";
    }

    static String footer(Board b) {
        List<String> lines = new ArrayList<>();
        Map<Verdict, String> labels = new LinkedHashMap<>();
        labels.put(Verdict.HIDDEN_REVIVABLE, "revivable");
        labels.put(Verdict.HIDDEN_ONLINE, "online (contract wants offline)");
        labels.put(Verdict.HIDDEN_OFFLINE, "offline (contract wants online)");
        labels.put(Verdict.HIDDEN_EARLY_DISCHARGE, "early discharge");
        labels.put(Verdict.HIDDEN_TRAVELLING, "travelling");
        List<String> parts = new ArrayList<>();
        labels.forEach((v, label) -> {
            int c = b.hidden().getOrDefault(v, 0);
            if (c > 0) {
                parts.add(c + " " + label);
            }
        });
        if (!parts.isEmpty()) {
            lines.add("Hidden by conditions: " + String.join(" · ", parts));
        }
        return lines.isEmpty() ? "" : "-# " + String.join("\n-# ", lines);
    }

    /** The board for one tier as it stands right now (for a merc's private pages). */
    public Board boardFor(int tierId) {
        Tier tier = tiers.find(tierId).orElse(null);
        if (tier == null) {
            return null;
        }
        List<BoardTarget> list = targets.running().stream().filter(t -> t.tierId() == tierId).toList();
        return build(tier, list, Instant.now());
    }

    /** Remembers a "See all" page so it keeps up with the board (replaces any earlier record of it). */
    public void trackPage(long messageId, int tierId, int page, net.dv8tion.jda.api.interactions.InteractionHook hook,
                          Board drawnFrom, Instant asOf) {
        openPages.put(messageId, new OpenPage(tierId, page, hook, Instant.now().plus(PAGE_LIVE_FOR),
                pageText(drawnFrom, page, asOf)));
    }

    /** Re-draws the open pages of this board whose content changed since they were last drawn. */
    private void updateOpenPages(Board board, Instant now) {
        openPages.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
        for (Map.Entry<Long, OpenPage> e : openPages.entrySet()) {
            OpenPage open = e.getValue();
            if (open.tierId() != board.tier().id()) {
                continue;
            }
            String text = pageText(board, open.page(), now);
            if (text.equals(open.lastDrawn())) {
                continue;
            }
            long messageId = e.getKey();
            openPages.put(messageId, open.drawn(text));
            open.hook().editOriginalComponents(drawPage(board, open.page(), now)).useComponentsV2()
                    .queue(null, failed -> openPages.remove(messageId)); // dismissed or expired
        }
    }

    /** What a page shows, without its timestamp, to tell whether it needs re-drawing. */
    private static String pageText(Board b, int page, Instant asOf) {
        int p = Math.max(1, Math.min(page, b.pages()));
        int from = (p - 1) * b.perPage();
        StringBuilder sb = new StringBuilder().append(p).append('/').append(b.pages()).append('/').append(b.all().size());
        for (Row r : b.all().subList(Math.min(from, b.all().size()), Math.min(from + b.perPage(), b.all().size()))) {
            sb.append('\n').append(rowText(b.tier(), r, asOf));
        }
        return sb.toString();
    }

    /** One private page of a board: rows {@code (page-1)*10} onwards, with page buttons. */
    public static List<MessageTopLevelComponent> drawPage(Board b, int page, Instant asOf) {
        Tier tier = b.tier();
        int pages = b.pages();
        int p = Math.max(1, Math.min(page, pages));
        int from = (p - 1) * b.perPage();
        List<Row> rows = b.all().subList(Math.min(from, b.all().size()), Math.min(from + b.perPage(), b.all().size()));
        List<ContainerChildComponent> parts = new ArrayList<>();
        String header = "-# **" + tier.name().toUpperCase() + " CONTRACTS · PAGE " + p + " OF " + pages + "**\n"
                + (rows.isEmpty() ? "### No targets on this page"
                : "### Targets " + (from + 1) + "–" + (from + rows.size()) + " of " + b.all().size())
                + "\n-# As of " + java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(asOf) + " UTC · updates automatically";
        parts.add(TextDisplay.of(header));
        if (!rows.isEmpty()) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            for (Row r : rows) {
                addRow(parts, b, r, asOf);
            }
        }
        Button prev = Button.secondary(CustomId.of("board", "prev", tier.id(), p - 1), "Page " + (p - 1))
                .withEmoji(Emoji.fromUnicode("◀️"));
        Button next = Button.secondary(CustomId.of("board", "next", tier.id(), p + 1), "Page " + (p + 1))
                .withEmoji(Emoji.fromUnicode("▶️"));
        parts.add(ActionRow.of(
                p > 1 ? prev : Button.secondary(CustomId.of("board", "prev", tier.id(), 1), "Page 1")
                        .withEmoji(Emoji.fromUnicode("◀️")).asDisabled(),
                Button.primary(CustomId.of("board", "refresh", tier.id(), p), "Refresh").withEmoji(Emoji.fromUnicode("🔄")),
                p < pages ? next : Button.secondary(CustomId.of("board", "next", tier.id(), pages), "Page " + pages)
                        .withEmoji(Emoji.fromUnicode("▶️")).asDisabled()));
        return List.of(Container.of(parts).withAccentColor(tier.colour()));
    }

    // ------------------------------------------------------------------ setup

    /**
     * Makes sure the tier channel has a board message in the newer layout. Boards posted before
     * the layout change are replaced, because Discord can't switch an old message over.
     */
    public long ensureBoard(TextChannel channel, Tier tier) {
        Board empty = new Board(tier, List.of(), List.of(), 0, Map.of(), settings.claimsOn());
        if (tier.boardMessageId() != null) {
            try {
                Message m = channel.retrieveMessageById(tier.boardMessageId()).complete();
                if (m.isUsingComponentsV2()) {
                    return m.getIdLong();
                }
                m.delete().complete();
            } catch (ErrorResponseException e) {
                if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                    throw e;
                }
            }
        }
        MessageEditData data = editData(empty);
        long id = channel.sendMessage(new MessageCreateBuilder().useComponentsV2().setComponents(draw(empty)).build())
                .complete().getIdLong();
        hashes.remember(id, data);
        return id;
    }
}
