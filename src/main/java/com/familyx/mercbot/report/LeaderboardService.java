package com.familyx.mercbot.report;

import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.MessageUpdater;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.report.ReportRepository.Standing;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The pinned standings in #merc-leaderboard (scope doc 6.7): this week (from Monday 00:00 UTC)
 * and this month so far, ranked by accepted hits with earnings beside each name, the best
 * average hospital time (3+ hits), and last period's top merc. Edited in place every 10 minutes.
 */
@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);
    private static final Color GOLD = new Color(0xD9A400);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.UK);
    private static final int TOP = 10;
    private static final int MIN_HOSP_HITS = 3;

    private final ReportRepository reports;
    private final MessageUpdater updater;
    private final Layout layout;

    public LeaderboardService(ReportRepository reports, MessageUpdater updater, Layout layout) {
        this.reports = reports;
        this.updater = updater;
        this.layout = layout;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 90_000)
    public void refresh() {
        updater.request("leaderboard", () -> {
            Optional<Long> channel = layout.get(Layout.CH_LEADERBOARD);
            Optional<Long> message = layout.get(Layout.MSG_LEADERBOARD);
            if (channel.isEmpty() || message.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(new MessageUpdater.Edit(channel.get(), message.get(),
                        new MessageEditBuilder().setEmbeds(embeds(Instant.now())).setComponents().build()));
            } catch (Exception e) {
                log.warn("Leaderboard refresh failed: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    public List<MessageEmbed> embeds(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate month = today.withDayOfMonth(1);
        MessageEmbed w = board("THIS WEEK", "🏆  Week of " + DAY_MONTH.format(week), start(week), now,
                start(week.minusWeeks(1)), start(week), "Last week's top merc");
        MessageEmbed m = board("THIS MONTH", "🏆  " + MONTH.format(month), start(month), now,
                start(month.minusMonths(1)), start(month), MONTH.format(month.minusMonths(1)) + "'s top merc");
        return List.of(w, m);
    }

    private MessageEmbed board(String author, String title, Instant from, Instant to, Instant lastFrom, Instant lastTo,
                               String lastLabel) {
        List<Standing> top = reports.standings(from, to, TOP);
        EmbedBuilder eb = new EmbedBuilder().setColor(GOLD).setAuthor("LEADERBOARD · " + author).setTitle(title);
        StringBuilder d = new StringBuilder();
        if (top.isEmpty()) {
            d.append("No hits yet. Standings appear once mercs land hits.\n");
        } else {
            int w = top.stream().mapToInt(s -> name(s).length()).max().orElse(10);
            d.append("```\n");
            for (int i = 0; i < top.size(); i++) {
                Standing s = top.get(i);
                String place = i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : String.format("%2d", i + 1);
                d.append(place).append(' ').append(pad(name(s), w)).append("  ")
                        .append(String.format("%3d", s.hits())).append(s.hits() == 1 ? " hit " : " hits")
                        .append("  ").append(Money.full(s.earned())).append('\n');
            }
            d.append("```");
            reports.bestHospital(from, to, MIN_HOSP_HITS).ifPresent(b -> d.append("⏱️ Best average hospital: **")
                    .append(b.name()).append("** · ").append(hours(b.avgSeconds())).append(" (").append(b.hits())
                    .append(" hits)\n"));
        }
        List<Standing> last = reports.standings(lastFrom, lastTo, 1);
        d.append("-# ").append(last.isEmpty() ? lastLabel + ": nobody yet" : lastLabel + ": " + name(last.getFirst()) + " · "
                + last.getFirst().hits() + (last.getFirst().hits() == 1 ? " hit" : " hits")).append(" · updated every 10 min");
        return eb.setDescription(d.toString()).build();
    }

    private static String name(Standing s) {
        return s.name() == null ? "Player " + s.mercId() : s.name();
    }

    static String hours(int seconds) {
        return seconds / 3600 + "h " + String.format("%02dm", (seconds % 3600) / 60);
    }

    private static Instant start(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }
}
