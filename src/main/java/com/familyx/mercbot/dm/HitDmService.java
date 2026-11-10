package com.familyx.mercbot.dm;

import com.familyx.mercbot.contract.ResultsFeed;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DM cleanup #13: one "your hits" message per merc per contract, edited (silently) as their
 * accepted hits land and deleted 24 hours after the last one. A hit after that starts a new
 * message. Mercs on the every-hit setting only.
 */
@Service
public class HitDmService {

    private static final Logger log = LoggerFactory.getLogger(HitDmService.class);
    public static final String KIND = "HITS";
    private static final Duration KEEP = Duration.ofHours(24);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
    private static final String ATTACK_LOG = "https://www.torn.com/page.php?sid=attackLog&ID=";
    private static final int MAX_LINES = 15;

    private record Line(Instant started, String target, long targetId, String tier, long share, Integer hosp,
                        String role, BigDecimal pct, String downgrade, String code, Long attackerId, long mercId) { }

    private final JdbcClient jdbc;
    private final DmRepository tracked;
    private final ContractRepository contracts;
    private final ResultsFeed feed;
    private final ObjectProvider<JDA> jda;

    public HitDmService(JdbcClient jdbc, DmRepository tracked, ContractRepository contracts, ResultsFeed feed,
                        ObjectProvider<JDA> jda) {
        this.jdbc = jdbc;
        this.tracked = tracked;
        this.contracts = contracts;
        this.feed = feed;
        this.jda = jda;
    }

    /** A hit on this contract was accepted with a share for this merc: update (or start) their message. */
    public synchronized void hitAccepted(long mercId, long mercDiscordId, long contractId) {
        JDA api = jda.getIfAvailable();
        Optional<Contract> c = contracts.find(contractId);
        if (api == null || c.isEmpty()) {
            return;
        }
        String ref = "contract:" + contractId + ":merc:" + mercId;
        Optional<DmRepository.Tracked> live = tracked.live(mercDiscordId, KIND, ref);
        Instant since = live.map(DmRepository.Tracked::createdAt).orElse(Instant.now().minus(Duration.ofMinutes(5)));
        MessageEmbed embed = embed(c.get(), lines(mercId, contractId, since));
        Instant deleteAt = Instant.now().plus(KEEP);
        if (live.isPresent()) {
            try {
                api.openPrivateChannelById(mercDiscordId)
                        .flatMap(ch -> ch.editMessageEmbedsById(live.get().messageId(), embed)).complete();
                tracked.setDeleteAt(live.get().id(), deleteAt);
                return;
            } catch (Exception gone) {
                tracked.forget(live.get().id()); // deleted by the merc: start a new one
            }
        }
        try {
            Message m = api.openPrivateChannelById(mercDiscordId).flatMap(ch -> ch.sendMessageEmbeds(embed)).complete();
            tracked.track(mercDiscordId, m.getIdLong(), KIND, ref, deleteAt);
        } catch (Exception e) {
            log.info("Couldn't DM hits to {}: {}", mercDiscordId, e.getMessage());
        }
    }

    private List<Line> lines(long mercId, long contractId, Instant since) {
        return jdbc.sql("""
                        SELECT h.started, coalesce(ct.target_name, 'Player') AS target, ct.target_torn_id, t.name AS tier,
                               s.amount, h.hosp_seconds, s.role, s.pct, h.downgrade_reason, h.attack_code, h.attacker_id
                        FROM hit_share s JOIN hit h ON h.id = s.hit_id
                        JOIN contract_target ct ON ct.id = h.contract_target_id
                        LEFT JOIN tier t ON t.id = h.billed_tier_id
                        WHERE s.merc_id = ? AND ct.contract_id = ? AND h.accepted AND h.created_at >= ?
                        ORDER BY h.started DESC""")
                .params(mercId, contractId, OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query((rs, n) -> new Line(((Timestamp) rs.getTimestamp("started")).toInstant(), rs.getString("target"),
                        rs.getLong("target_torn_id"), rs.getString("tier"), rs.getLong("amount"),
                        rs.getObject("hosp_seconds", Integer.class), rs.getString("role"), rs.getBigDecimal("pct"),
                        rs.getString("downgrade_reason"), rs.getString("attack_code"),
                        rs.getObject("attacker_id", Long.class), mercId))
                .list();
    }

    private MessageEmbed embed(Contract c, List<Line> lines) {
        long total = lines.stream().mapToLong(Line::share).sum();
        boolean anyDowngrade = lines.stream().anyMatch(l -> l.downgrade() != null);
        StringBuilder table = new StringBuilder("```\n");
        int w = Math.min(26, lines.stream().limit(MAX_LINES).mapToInt(l -> who(l).length()).max().orElse(10));
        for (Line l : lines.stream().limit(MAX_LINES).toList()) {
            String who = who(l);
            table.append(TIME.format(l.started())).append("  ").append(pad(who.length() > w ? who.substring(0, w - 1) + "…" : who, w))
                    .append("  ").append(pad(l.tier() == null ? "?" : l.tier(), 6)).append(" ")
                    .append(pad(Money.full(l.share()), 12)).append(pad(hosp(l.hosp()), 8)).append(role(l)).append('\n');
        }
        if (lines.size() > MAX_LINES) {
            table.append("+").append(lines.size() - MAX_LINES).append(" earlier\n");
        }
        table.append("```");
        StringBuilder notes = new StringBuilder();
        lines.stream().filter(l -> l.downgrade() != null).limit(3).forEach(l -> notes.append("⚡ ").append(l.target())
                .append(": ").append(l.downgrade()).append(", so billed at ").append(l.tier()).append(".\n"));
        lines.stream().filter(l -> "CLAIMER".equals(l.role()) && l.attackerId() != null && l.attackerId() != l.mercId())
                .limit(3).forEach(l -> notes.append("🔒 ").append(l.target())
                        .append(": you didn't join this fight, so your claim counts as a no-show.\n"));
        String latest = lines.isEmpty() || lines.getFirst().code() == null ? ""
                : " · latest: [Attack log ↗](" + ATTACK_LOG + lines.getFirst().code() + ")";
        return new EmbedBuilder().setColor(anyDowngrade ? Panels.WARN : Panels.GOOD)
                .setAuthor("YOUR HITS · " + c.ref() + " · " + feed.targetName(c, contracts.targetCount(c.id())).toUpperCase())
                .setTitle("✅  " + lines.size() + (lines.size() == 1 ? " hit" : " hits") + " accepted · " + Money.full(total)
                        + " to you")
                .setDescription(table + "\n" + notes + "-# Pending until the requester pays" + latest)
                .build();
    }

    private static String who(Line l) {
        return l.target() + " [" + l.targetId() + "]";
    }

    private static String role(Line l) {
        String pct = l.pct() == null ? "" : l.pct().stripTrailingZeros().toPlainString() + "%";
        return switch (l.role() == null ? "" : l.role()) {
            case "STARTER" -> l.pct() != null && l.pct().compareTo(BigDecimal.valueOf(100)) >= 0 ? "100% · solo"
                    : pct + " · started the fight";
            case "ASSIST" -> pct + " · joined";
            case "CLAIMER" -> pct + " · your claim";
            case "HITTER" -> pct + " · hit a claimed target";
            default -> pct;
        };
    }

    private static String hosp(Integer secs) {
        return secs == null ? "–" : secs / 3600 + "h " + String.format("%02dm", (secs % 3600) / 60);
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }
}
