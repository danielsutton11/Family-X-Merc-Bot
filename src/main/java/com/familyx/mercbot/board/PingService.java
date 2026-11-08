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
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
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
 * grouping window are combined, and each ping removes itself after 10 minutes.
 */
@Service
public class PingService {

    private static final Logger log = LoggerFactory.getLogger(PingService.class);
    private static final Duration WINDOW = Duration.ofMinutes(2);
    private static final long DELETE_AFTER_MINUTES = 10;
    private static final int MAX_CARDS = 5;
    private static final String ATTACK_URL = "https://www.torn.com/page.php?sid=attack&user2ID=";

    /** A target that came back, with how to word it. */
    public record Back(BoardTarget target, Long bsp, boolean fromHospital) { }

    private final ObjectProvider<JDA> jda;
    private final MercRepository mercs;
    private final RoleSync roles;
    private final TierRepository tiers;
    private final Map<Integer, Instant> lastSent = new ConcurrentHashMap<>();
    private final Map<Integer, List<Back>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    public PingService(ObjectProvider<JDA> jda, MercRepository mercs, RoleSync roles, TierRepository tiers) {
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
            flush(tier.id());
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
        for (Back b : items.stream().limit(MAX_CARDS).toList()) {
            BoardTarget t = b.target();
            String name = t.name() == null ? "Player " + t.tornId() : t.name();
            String hitsLeft = t.hitsRequired() == null ? "–" : Integer.toString(t.hitsRequired() - t.hitsDone());
            parts.add(Container.of(
                    TextDisplay.of("-# **TARGET BACK · @" + tier.name().toUpperCase() + "**\n### " + name
                            + (b.fromHospital() ? " is out of hospital" : " is back on the board") + "\n-# "
                            + BoardService.contractLabel(t) + " · " + tier.name() + " · " + t.ref()),
                    TextDisplay.of("**BSP** " + BspService.format(b.bsp()) + "   **Level** "
                            + (t.level() == null ? "–" : t.level()) + "   **Hits left** " + hitsLeft),
                    ActionRow.of(Button.link(ATTACK_URL + t.tornId(), "Attack ↗")))
                    .withAccentColor(tier.colour()));
        }
        if (items.size() > MAX_CARDS) {
            parts.add(TextDisplay.of("-# +" + (items.size() - MAX_CARDS) + " more back on the board"));
        }
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
        JDA api = jda.getIfAvailable();
        TextChannel channel = api == null || tier.channelId() == null ? null : api.getTextChannelById(tier.channelId());
        if (channel == null) {
            return;
        }
        channel.sendMessage(new MessageCreateBuilder().useComponentsV2().setComponents(parts)
                        .setAllowedMentions(EnumSet.of(Message.MentionType.USER)).build())
                .queue(m -> m.delete().queueAfter(DELETE_AFTER_MINUTES, TimeUnit.MINUTES, null, ignored -> { }),
                        e -> log.warn("Ping in {} failed: {}", tier.name(), e.getMessage()));
    }
}
