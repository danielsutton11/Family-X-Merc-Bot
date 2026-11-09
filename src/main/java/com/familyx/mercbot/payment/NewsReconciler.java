package com.familyx.mercbot.payment;

import com.familyx.mercbot.payment.InvoiceRepository.Deposit;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.torn.TornKeyPool;
import com.familyx.mercbot.torn.TornKeyPool.Scope;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Capacity X's faction news for money added to the mercs pool account every 5 min (scope
 * doc 10.4, 10.5) and hands new deposits to {@link InvoiceService}. Each news entry is used once.
 */
@Component
public class NewsReconciler {

    private static final Logger log = LoggerFactory.getLogger(NewsReconciler.class);

    /** Scope doc 10.5: actor, direction, account, amount, before, after (with striptags=false). */
    static final Pattern ENTRY = Pattern.compile(
            "<a href\\s*=\\s*\"[^\"]*XID=(\\d+)\"[^>]*>([^<]+)</a>\\s*(increased|decreased)\\s*"
                    + "<a href\\s*=\\s*\"[^\"]*XID=(\\d+)\"[^>]*>([^<]+)</a>'s money balance by \\$([\\d,]+)"
                    + "(?: from \\$(-?[\\d,]+) to \\$(-?[\\d,]+))?");

    private final TornKeyPool pool;
    private final InvoiceRepository invoices;
    private final InvoiceService service;
    private final BotSettings settings;
    private Instant lastWarned;

    public NewsReconciler(TornKeyPool pool, InvoiceRepository invoices, InvoiceService service, BotSettings settings) {
        this.pool = pool;
        this.invoices = invoices;
        this.service = service;
        this.settings = settings;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void tick() {
        long faction = settings.paymentFactionId();
        JsonNode news;
        try {
            news = pool.call(Scope.faction(faction), "/faction/news",
                    Map.of("cat", "depositFunds", "striptags", "false", "limit", "100", "sort", "DESC"));
        } catch (Exception e) {
            if (lastWarned == null || lastWarned.isBefore(Instant.now().minusSeconds(3600))) {
                lastWarned = Instant.now();
                log.warn("Couldn't read faction news for {} (needs a leader key with faction API access): {}",
                        faction, e.getMessage());
            }
            return;
        }
        long pool = settings.poolAccountId();
        for (JsonNode n : news.path("news")) {
            String id = n.path("id").asText(null);
            if (id == null) {
                continue;
            }
            Optional<Parsed> p = parse(n.path("text").asText(""));
            if (p.isEmpty() || p.get().accountId() != pool || !"increased".equals(p.get().direction())) {
                continue;
            }
            Deposit d = new Deposit(id, Instant.ofEpochSecond(n.path("timestamp").asLong()), p.get().actorId(),
                    p.get().actorName(), p.get().accountId(), p.get().amount());
            if (invoices.remember(faction, d, p.get().direction(), p.get().before(), p.get().after())) {
                try {
                    service.depositSpotted(d);
                } catch (Exception e) {
                    log.warn("Couldn't match deposit {}: {}", id, e.getMessage());
                }
            }
        }
    }

    public record Parsed(long actorId, String actorName, String direction, long accountId, String accountName, long amount,
                  Long before, Long after) { }

    public static Optional<Parsed> parse(String text) {
        Matcher m = ENTRY.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(Long.parseLong(m.group(1)), m.group(2).trim(), m.group(3),
                Long.parseLong(m.group(4)), m.group(5).trim(), money(m.group(6)), moneyOrNull(m.group(7)),
                moneyOrNull(m.group(8))));
    }

    private static long money(String s) {
        return Long.parseLong(s.replace(",", ""));
    }

    private static Long moneyOrNull(String s) {
        return s == null ? null : money(s);
    }
}
