package com.familyx.mercbot.board;

import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.bsp.BspService;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.merc.MercRepository.PingPref;
import com.familyx.mercbot.merc.RoleSync;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pings in the tier channels (scope doc 5.3): a card when a contract starts and a card when a
 * target comes back. Only mercs whose Pings setting allows it are mentioned, pings within the
 * grouping window are combined, and each ping removes itself after a minute.
 */
@Service
public class PingService {

    private static final Logger log = LoggerFactory.getLogger(PingService.class);
    private static final Duration WINDOW = Duration.ofMinutes(2);
    private static final long DELETE_AFTER_SECONDS = 60;
    private static final int MAX_ROWS = 10;

    /** A target that came back, with how to word it; {@code hittableAt} is set when it's only coming up. */
    public record Back(BoardTarget target, Long bsp, boolean fromHospital, Instant hittableAt) {
        boolean comingUp() {
            return hittableAt != null;
        }
    }

    private final ObjectProvider<JDA> jda;
    private final MercRepository mercs;
    private final RoleSync roles;
    private final TierRepository tiers;
    private final BspService bsp;
    private final Map<Integer, Instant> lastSent = new ConcurrentHashMap<>();
    private final Map<Integer, List<Back>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    public PingService(ObjectProvider<JDA> jda, MercRepository mercs, RoleSync roles, TierRepository tiers,
                       BspService bsp) {
        this.bsp = bsp;
        this.jda = jda;
        this.mercs = mercs;
        this.roles = roles;
        this.tiers = tiers;
    }

    // ------------------------------------------------------------------ target back

    /** Targets on this tier's board that just became hittable again. */
    public synchronized void targetsBack(Tier tier, List<Back> back) {
        pending.computeIfAbsent(tier.id(), k -> new ArrayList<>()).addAll(back);
        Instant last = lastSent.get(tier.id());
        Instant now = Instant.now();
        if (last == null || !last.plus(WINDOW).isAfter(now)) {
            scheduler.execute(() -> flush(tier.id())); // off the board thread: may wait for BSP
        } else if (pending.get(tier.id()).size() == back.size()) {
            // First item of a new group: send it when the window ends.
            long delay = Duration.between(now, last.plus(WINDOW)).toMillis();
            scheduler.schedule(() -> flush(tier.id()), Math.max(1, delay), TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void flush(int tierId) {
        List<Back> items = pending.remove(tierId);
        if (items == null || items.isEmpty()) {
            return;
        }
        Tier tier = tiers.find(tierId).orElse(null);
        if (tier == null) {
            return;
        }
        lastSent.put(tierId, Instant.now());
        List<MessageTopLevelComponent> parts = new ArrayList<>();
        String mentions = mentions(tier, false);
        if (!mentions.isEmpty()) {
            parts.add(TextDisplay.of(mentions));
        }
        // Wait for BSP estimates the board hadn't needed yet, so the ping isn't sent with "?".
        Map<Long, Long> bsp = this.bsp.estimatesNow(items.stream().map(b -> b.target().tornId()).distinct().toList());
        // A target that came back twice within the window is listed once.
        Map<Long, Back> unique = new java.util.LinkedHashMap<>();
        items.forEach(b -> unique.put(b.target().id(), b));
        items = new ArrayList<>(unique.values());
        List<Back> sorted = new ArrayList<>(items);
        sorted.sort(java.util.Comparator.comparing((Back b) -> bsp.getOrDefault(b.target().tornId(), b.bsp()),
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        List<Back> shown = sorted.stream().limit(MAX_ROWS).toList();

        List<net.dv8tion.jda.api.components.container.ContainerChildComponent> rows = new ArrayList<>();
        boolean allComing = items.stream().allMatch(Back::comingUp);
        String are = items.size() == 1 ? "1 target is" : items.size() + " targets are";
        rows.add(TextDisplay.of(allComing
                ? "-# **TARGETS COMING UP · @" + tier.name().toUpperCase() + "**\n### " + Panels.tierIcon(tier) + "  " + are
                + " coming up\n-# Claim them on the board before they're hittable"
                : "-# **TARGETS BACK · @" + tier.name().toUpperCase() + "**\n### " + Panels.tierIcon(tier) + "  " + are
                + " back on the board\n-# " + (items.stream().anyMatch(Back::comingUp)
                ? "Attack them from the board, or claim the ones coming up" : "Attack them from the board")));
        Instant now = Instant.now();
        for (Back b : shown) {
            BoardTarget t = b.target();
            String name = t.name() == null ? "Player " + t.tornId() : t.name();
            String where = "Hospital".equals(t.lastState()) ? "🏥 " : "Jail".equals(t.lastState()) ? "⛓️ " : "⚔️ ";
            String line3 = b.comingUp()
                    ? t.ref() + " · " + where + "hittable <t:" + b.hittableAt().getEpochSecond() + ":R>"
                    + (t.hitsRequired() == null ? "" : " · " + (t.hitsRequired() - t.hitsDone()) + " hits left")
                    : t.ref() + " · " + (b.fromHospital() ? "out of hospital" : "back on the board") + " · "
                    + BoardRules.activity(t, now)
                    + (t.hitsRequired() == null ? "" : " · " + (t.hitsRequired() - t.hitsDone()) + " hits left");
            rows.add(TextDisplay.of(
                    "**" + name + "** [" + t.tornId() + "]" + (t.level() == null ? "" : " · Level " + t.level())
                            + "\nBSP **" + BspService.format(bsp.getOrDefault(t.tornId(), b.bsp())) + "** · "
                            + BoardService.contractLabel(t) + "\n-# " + line3));
        }
        if (items.size() > MAX_ROWS) {
            rows.add(TextDisplay.of("-# +" + (items.size() - MAX_ROWS) + " more: see the board"));
        }
        parts.add(Container.of(rows).withAccentColor(tier.colour()));
        send(tier, parts);
    }

    // ------------------------------------------------------------------ new contract

    public void newContract(Tier tier, String ref, String title, String details) {
        List<MessageTopLevelComponent> parts = new ArrayList<>();
        String mentions = mentions(tier, true);
        if (!mentions.isEmpty()) {
            parts.add(TextDisplay.of(mentions));
        }
        parts.add(Container.of(TextDisplay.of("-# **NEW CONTRACT · @" + tier.name().toUpperCase() + "**\n### "
                        + Panels.tierIcon(tier) + "  " + ref + " · " + title + "\n-# " + details))
                .withAccentColor(tier.colour()));
        send(tier, parts);
    }

    // ------------------------------------------------------------------ urgent (scope doc 7.8)

    /** Another merc service is hitting a contract's targets: pings the tier, deleted after 10 minutes. */
    public void urgent(Tier tier, String ref, String title, String body, String footer) {
        List<MessageTopLevelComponent> parts = new ArrayList<>();
        String mentions = mentions(tier, true);
        if (!mentions.isEmpty()) {
            parts.add(TextDisplay.of(mentions));
        }
        parts.add(Container.of(TextDisplay.of("-# **⚠️ URGENT · " + ref + " · " + title.toUpperCase() + "**\n### "
                        + "Another merc service is hitting this contract's targets\n" + body + "\n-# " + footer))
                .withAccentColor(Panels.BAD));
        send(tier, parts, 600);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Mercs holding this tier whose settings allow the ping, as mentions. Discord can only ping
     * a whole role or listed people, so the bot lists the people who want it.
     */
    private String mentions(Tier tier, boolean newContract) {
        List<Merc> active = mercs.findActive();
        Map<Long, Set<Integer>> optOuts = mercs.allOptedOut();
        var tierList = tiers.findActive();
        List<String> ids = new ArrayList<>();
        for (Merc m : active) {
            if (!m.available() || m.pingPref() == PingPref.OFF || (m.pingPref() == PingPref.NEW_ONLY && !newContract)) {
                continue;
            }
            if (roles.tiersFor(m, tierList, optOuts.getOrDefault(m.tornId(), Set.of())).contains(tier.id())) {
                ids.add("<@" + m.discordId() + ">");
            }
        }
        return ids.isEmpty() ? "" : "-# 🔔 " + String.join(" ", ids);
    }

    private void send(Tier tier, List<MessageTopLevelComponent> parts) {
        send(tier, parts, DELETE_AFTER_SECONDS);
    }

    private void send(Tier tier, List<MessageTopLevelComponent> parts, long deleteAfterSeconds) {
        JDA api = jda.getIfAvailable();
        TextChannel channel = api == null || tier.channelId() == null ? null : api.getTextChannelById(tier.channelId());
        if (channel == null) {
            return;
        }
        channel.sendMessage(new MessageCreateBuilder().useComponentsV2().setComponents(parts)
                        .setAllowedMentions(EnumSet.of(Message.MentionType.USER)).build())
                .queue(m -> m.delete().queueAfter(deleteAfterSeconds, TimeUnit.SECONDS, null, ignored -> { }),
                        e -> log.warn("Ping in {} failed: {}", tier.name(), e.getMessage()));
    }
}
