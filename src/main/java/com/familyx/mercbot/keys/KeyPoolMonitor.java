package com.familyx.mercbot.keys;

import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.settings.SettingsRepository;
import com.familyx.mercbot.torn.TornKeyPool;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Scope doc 10.2: when the key pool passes 80% of its calls a minute, single-player checks on
 * the boards slow to every 20 s and leaders get one card, edited as the figure changes. Once
 * use stays under 60% for 10 minutes, normal speed returns and the card is deleted.
 */
@Component
public class KeyPoolMonitor {

    private static final Logger log = LoggerFactory.getLogger(KeyPoolMonitor.class);
    static final double BUSY_AT = 0.80;
    static final double CALM_BELOW = 0.60;
    static final Duration CALM_FOR = Duration.ofMinutes(10);
    static final String CARD_KEY = "keys.busyCardId";

    private final TornKeyPool pool;
    private final SettingsRepository settings;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;
    private volatile boolean busy;
    private Instant calmSince;
    private String lastText;

    public KeyPoolMonitor(TornKeyPool pool, SettingsRepository settings, Layout layout, ObjectProvider<JDA> jda) {
        this.pool = pool;
        this.settings = settings;
        this.layout = layout;
        this.jda = jda;
    }

    /** True while single-player checks are slowed. */
    public boolean busy() {
        return busy;
    }

    /** "31% of 4,500 calls a minute (50 keys)" for the Boards screen. */
    public String usageLine() {
        long cap = pool.capacityPerMinute();
        int keys = pool.perKeyPerMinute() == 0 ? 0 : (int) (cap / pool.perKeyPerMinute());
        return Math.round(pool.usageRatio() * 100) + "% of " + String.format("%,d", cap) + " calls a minute ("
                + keys + (keys == 1 ? " key)" : " keys)") + (busy ? " · busy: single players slowed to 20 s" : "");
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void check() {
        try {
            run(pool.usageRatio(), Instant.now());
        } catch (Exception e) {
            log.warn("Key pool check failed: {}", e.getMessage());
        }
    }

    synchronized void run(double ratio, Instant now) {
        Optional<Long> card = settings.get(CARD_KEY, Long.class);
        State next = next(busy, calmSince, ratio, now);
        busy = next.busy();
        calmSince = next.calmSince();
        if (busy) {
            show(card, ratio);
        } else if (card.isPresent()) {
            delete(card.get()); // also tidies a card left over from before a restart
        }
    }

    /** What the monitor does with a reading: pure, so it can be unit tested. */
    public record State(boolean busy, Instant calmSince) { }

    public static State next(boolean busy, Instant calmSince, double ratio, Instant now) {
        if (ratio >= BUSY_AT) {
            return new State(true, null);
        }
        if (!busy) {
            return new State(false, null);
        }
        if (ratio >= CALM_BELOW) {
            return new State(true, null);
        }
        Instant since = calmSince == null ? now : calmSince;
        return since.plus(CALM_FOR).isAfter(now) ? new State(true, since) : new State(false, null);
    }

    private void show(Optional<Long> card, double ratio) {
        TextChannel ch = channel();
        if (ch == null) {
            return;
        }
        long cap = pool.capacityPerMinute();
        String title = "🔑  " + Math.round(ratio * 100) + "% of " + String.format("%,d", cap) + " calls a minute in use";
        if (card.isPresent() && title.equals(lastText)) {
            return;
        }
        MessageEmbed embed = new EmbedBuilder().setColor(Panels.WARN).setAuthor("KEY POOL BUSY").setTitle(title)
                .setDescription("Single-player targets on the boards are being checked every **20 s** to stay under "
                        + "Torn's limit. Faction targets aren't affected.\n-# Back to normal once use stays under 60% "
                        + "for 10 minutes. More merc or leader keys add " + pool.perKeyPerMinute()
                        + " calls a minute each.")
                .build();
        ActionRow row = ActionRow.of(
                Button.secondary(CustomId.of("keys", "view"), "Open Keys").withEmoji(Emoji.fromUnicode("🔑")),
                Button.secondary(CustomId.of("boardset", "view"), "Board settings").withEmoji(Emoji.fromUnicode("📋")));
        try {
            if (card.isPresent()) {
                ch.editMessageEmbedsById(card.get(), embed).setComponents(row).complete();
            } else {
                long id = ch.sendMessageEmbeds(embed).setComponents(row).complete().getIdLong();
                settings.put(CARD_KEY, id, null);
            }
            lastText = title;
        } catch (Exception e) {
            // The card was deleted by hand: post a fresh one next time.
            settings.put(CARD_KEY, null, null);
            lastText = null;
            log.info("Couldn't update the key pool card: {}", e.getMessage());
        }
    }

    private void delete(long id) {
        TextChannel ch = channel();
        if (ch != null) {
            ch.deleteMessageById(id).queue(null, ignored -> { });
        }
        settings.put(CARD_KEY, null, null);
        lastText = null;
    }

    private TextChannel channel() {
        JDA api = jda.getIfAvailable();
        return api == null ? null : layout.get(Layout.CH_LEADERS).map(api::getTextChannelById).orElse(null);
    }
}
