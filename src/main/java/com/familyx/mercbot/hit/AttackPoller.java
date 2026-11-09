package com.familyx.mercbot.hit;

import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.settings.SettingsRepository;
import com.familyx.mercbot.torn.TornKeyPool;
import com.familyx.mercbot.torn.TornKeyPool.Scope;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads each family faction's attack log every 30 s (scope doc 10.4) with that faction's leader
 * key, combines every faction's rows (a joiner from another family faction only shows in their own
 * faction's log), and hands them to {@link HitService}. Each faction keeps a cursor in settings, so
 * a redeploy picks up where it left off; it re-reads a few minutes back because a fight only
 * shows once it ends, and already-recorded attacks are skipped.
 */
@Component
public class AttackPoller {

    private static final Logger log = LoggerFactory.getLogger(AttackPoller.class);
    private static final long OVERLAP_S = 300;
    private static final int PAGE = 100;
    private static final int MAX_PAGES = 5;

    private final FamilyFactionRepository factions;
    private final TornKeyPool pool;
    private final HitService hits;
    private final SettingsRepository settings;
    private final Map<Long, Instant> lastWarned = new ConcurrentHashMap<>();

    public AttackPoller(FamilyFactionRepository factions, TornKeyPool pool, HitService hits, SettingsRepository settings) {
        this.factions = factions;
        this.pool = pool;
        this.hits = hits;
        this.settings = settings;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void tick() {
        Instant started = Instant.now();
        boolean allRead = true;
        Map<Long, TornAttack> combined = new java.util.LinkedHashMap<>();
        for (FamilyFaction f : factions.findActive()) {
            try {
                poll(f.tornFactionId(), combined);
            } catch (Exception e) {
                allRead = false;
                Instant last = lastWarned.get(f.tornFactionId());
                if (last == null || last.isBefore(started.minusSeconds(3600))) {
                    lastWarned.put(f.tornFactionId(), started);
                    log.warn("Couldn't read the attack log for faction {} (needs a leader key with faction API access): {}",
                            f.tornFactionId(), e.getMessage());
                }
            }
        }
        // Fights that only just ended wait a pass, so every faction's log has them; the overlap re-reads them.
        Instant settled = started.minusSeconds(20);
        hits.process(new java.util.ArrayList<>(combined.values()), settled);
        if (allRead) {
            // Every log has been read: claims whose lock ended more than a fight-length ago are settled.
            hits.judgeClaims(started.minusSeconds(300)); // a fight only shows in the log once it ends
        }
    }

    private void poll(long factionId, Map<Long, TornAttack> combined) throws Exception {
        String key = "attacks.cursor." + factionId;
        long cursor = settings.get(key, Long.class).orElse(Instant.now().getEpochSecond() - 120);
        long from = Math.max(0, cursor - OVERLAP_S);
        long newest = cursor;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode res = pool.call(Scope.faction(factionId), "/faction/attacks",
                    Map.of("from", Long.toString(from), "sort", "ASC", "limit", Integer.toString(PAGE)));
            List<TornAttack> batch = TornAttack.parse(res.path("attacks"));
            if (batch.isEmpty()) {
                break;
            }
            batch.forEach(a -> combined.putIfAbsent(a.id(), a));
            long last = batch.stream().mapToLong(a -> a.started().getEpochSecond()).max().orElse(from);
            newest = Math.max(newest, last);
            if (batch.size() < PAGE || last <= from) {
                break;
            }
            from = last;
        }
        if (newest != cursor) {
            settings.put(key, newest, null);
        }
    }
}
