package com.familyx.mercbot.hit;

import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.hit.HitRepository.Unnotified;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.DmPref;
import com.familyx.mercbot.merc.MercRepository.Merc;
import net.dv8tion.jda.api.EmbedBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** The hourly hit summary for mercs who chose "Hourly" (scope doc 5.5). */
@Component
public class HitNotifier {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
    private static final int MAX_LINES = 25;

    private final HitRepository hits;
    private final MercRepository mercs;
    private final Dms dms;

    public HitNotifier(HitRepository hits, MercRepository mercs, Dms dms) {
        this.hits = hits;
        this.mercs = mercs;
        this.dms = dms;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void hourly() {
        Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS);
        for (long mercId : hits.mercsWithUnnotified()) {
            Optional<Merc> m = mercs.findByTorn(mercId);
            List<Unnotified> list = hits.unnotified(mercId);
            if (list.isEmpty()) {
                continue;
            }
            if (m.isPresent() && m.get().active() && m.get().dmPref() == DmPref.HOURLY) {
                long total = list.stream().mapToLong(Unnotified::amount).sum();
                StringBuilder lines = new StringBuilder();
                list.stream().limit(MAX_LINES).forEach(u -> lines.append(u.ref()).append(" · ").append(u.targetName())
                        .append(" · ").append(u.tierName() == null ? "?" : u.tierName())
                        .append(u.downgrade() == null ? "" : " ⚡")
                        .append(" · **").append(Money.shortForm(u.amount())).append("**")
                        .append(role(u.role())).append('\n'));
                if (list.size() > MAX_LINES) {
                    lines.append("-# +").append(list.size() - MAX_LINES).append(" more\n");
                }
                dms.send(m.get().discordId(), com.familyx.mercbot.discord.Dms.Clean.after("HOURLY", java.time.Duration.ofHours(24)), new EmbedBuilder().setColor(Panels.PRIVATE)
                        .setAuthor("YOUR HITS · " + HOUR.format(to.minus(1, ChronoUnit.HOURS)) + "–" + HOUR.format(to) + " UTC")
                        .setTitle("✅  " + list.size() + (list.size() == 1 ? " hit" : " hits") + " accepted · "
                                + Money.full(total) + " to you")
                        .setDescription(lines + "-# Pending until the requesters pay. Rejected hits are DMed straight away.")
                        .build());
            }
            hits.markNotified(list.stream().map(Unnotified::shareId).toList());
        }
    }

    private static String role(String role) {
        return switch (role) {
            case "ASSIST" -> " (joined)";
            case "CLAIMER" -> " (your claim)";
            default -> "";
        };
    }
}
