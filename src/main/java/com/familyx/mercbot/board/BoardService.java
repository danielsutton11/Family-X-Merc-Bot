package com.familyx.mercbot.board;

import com.familyx.mercbot.board.BoardRules.Verdict;
import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.bsp.BspService;
import com.familyx.mercbot.discord.MessageUpdater;
import com.familyx.mercbot.discord.PanelHashes;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
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
    private static final String ATTACK_URL = "https://www.torn.com/loader.php?sid=attack&user2ID=";

    /** One row as drawn on a board. */
    public record Row(BoardTarget target, Long bsp) { }

    /** What a board shows: the rows, how many more there are, and why the rest are hidden. */
    public record Board(Tier tier, List<Row> rows, int more, Map<Verdict, Integer> hidden) { }

    private final TargetRepository targets;
    private final TierRepository tiers;
    private final BspService bsp;
    private final MessageUpdater updater;
    private final PanelHashes hashes;
    private final PingService pings;
    /** Each target's verdict at the last refresh, to spot targets coming back. */
    private final Map<Long, Verdict> lastVerdict = new ConcurrentHashMap<>();

    public BoardService(TargetRepository targets, TierRepository tiers, BspService bsp, MessageUpdater updater,
                        PanelHashes hashes, PingService pings) {
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
                if (v == Verdict.SHOW && before != null && before != Verdict.SHOW && before != Verdict.UNKNOWN
                        && before != Verdict.PAUSED) {
                    back.add(t);
                    fromHospital.put(t.id(), before == Verdict.NOT_YET);
                }
            }
            if (!back.isEmpty()) {
                Map<Long, Long> est = bsp.estimates(back.stream().map(BoardTarget::tornId).toList());
                pings.targetsBack(tier, back.stream().map(t -> new PingService.Back(t, est.get(t.tornId()),
                        fromHospital.getOrDefault(t.id(), false))).toList());
            }
            if (tier.channelId() != null && tier.boardMessageId() != null) {
                MessageEditData data = editData(board);
                updater.request("board:" + tier.id(), () -> Optional.of(
                        new MessageUpdater.Edit(tier.channelId(), tier.boardMessageId(), data)));
            }
        }
    }

    /** The board for one tier, from its running targets. */
    public Board build(Tier tier, List<BoardTarget> list, Instant now) {
        List<BoardTarget> shown = new ArrayList<>();
        Map<Verdict, Integer> hidden = new EnumMap<>(Verdict.class);
        for (BoardTarget t : list) {
            Verdict v = BoardRules.verdict(t, now);
            if (v == Verdict.SHOW) {
                shown.add(t);
            } else if (v.hiddenByConditions()) {
                hidden.merge(v, 1, Integer::sum);
            }
        }
        shown.sort(Comparator.comparingInt(BoardTarget::priority).reversed()
                .thenComparing(BoardTarget::contractCreated, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(t -> t.name() == null ? "" : t.name().toLowerCase()));
        List<BoardTarget> top = shown.subList(0, Math.min(MAX_ROWS, shown.size()));
        Map<Long, Long> estimates = bsp.estimates(top.stream().map(BoardTarget::tornId).toList());
        List<Row> rows = top.stream().map(t -> new Row(t, estimates.get(t.tornId()))).toList();
        return new Board(tier, rows, shown.size() - rows.size(), hidden);
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
                + "-# Attack opens the target in Torn");
        parts.add(TextDisplay.of(header));
        if (!b.rows().isEmpty()) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            Instant now = Instant.now();
            for (Row r : b.rows()) {
                parts.add(Section.of(Button.link(ATTACK_URL + r.target().tornId(), "Attack ↗"),
                        TextDisplay.of(rowText(tier, r, now))));
            }
        }
        String footer = footer(b);
        if (!footer.isEmpty()) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(TextDisplay.of(footer));
        }
        return List.of(Container.of(parts).withAccentColor(tier.colour()));
    }

    static String rowText(Tier tier, Row r, Instant now) {
        BoardTarget t = r.target();
        boolean gold = tier.requiresStricken();
        boolean revivableOnGold = gold && Boolean.TRUE.equals(t.revivable()) && t.conditions().allowRevivable();
        String name = t.name() == null ? "Player " + t.tornId() : t.name();
        String line1 = "**" + name + "** [" + t.tornId() + "]" + (t.level() == null ? "" : " · Level " + t.level());
        String what = revivableOnGold ? "⚠️ **Revivable: don't use Stricken**" : contractLabel(t);
        String line2 = "BSP **" + BspService.format(r.bsp()) + "** · " + what;
        List<String> hints = new ArrayList<>();
        hints.add(t.ref());
        if (revivableOnGold) {
            hints.add("billed at Silver");
        }
        hints.add(BoardRules.activity(t, now));
        if (t.hasOverride()) {
            hints.add("requester override, 1 hit");
        } else if (gold && !revivableOnGold) {
            hints.add("Needs Stricken for Gold");
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
        if (b.more() > 0) {
            lines.add("+" + b.more() + " more waiting below the top " + MAX_ROWS);
        }
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

    // ------------------------------------------------------------------ setup

    /**
     * Makes sure the tier channel has a board message in the newer layout. Boards posted before
     * the layout change are replaced, because Discord can't switch an old message over.
     */
    public long ensureBoard(TextChannel channel, Tier tier) {
        Board empty = new Board(tier, List.of(), 0, Map.of());
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
