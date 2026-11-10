package com.familyx.mercbot.board;

import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.board.TargetRepository.RosterContract;
import com.familyx.mercbot.board.TargetRepository.TornState;
import com.familyx.mercbot.request.TornLookups;
import com.familyx.mercbot.torn.TornKeyPool;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The target watcher (scope doc 10.4): reads each contract target's status, revivable, early
 * discharge and last action from Torn, keeps "All members" rosters in step with the faction,
 * then refreshes the boards. Targets near or on a board are read every 10 s, others every 60 s.
 */
@Component
public class TargetWatcher {

    private static final Logger log = LoggerFactory.getLogger(TargetWatcher.class);
    private static final Duration FAST = Duration.ofSeconds(10);
    private static final Duration SLOW = Duration.ofSeconds(60);
    private static final Duration WAR_LOCK = Duration.ofSeconds(60);
    private static final Duration SOON = Duration.ofMinutes(2);

    private final TargetRepository targets;
    private final TornKeyPool pool;
    private final TornLookups lookups;
    private final BoardService boards;
    private final Map<String, Instant> lastFetch = new ConcurrentHashMap<>();

    private final org.springframework.beans.factory.ObjectProvider<com.familyx.mercbot.outside.OutsideService> outside;

    public TargetWatcher(TargetRepository targets, TornKeyPool pool, TornLookups lookups, BoardService boards,
                         org.springframework.beans.factory.ObjectProvider<com.familyx.mercbot.outside.OutsideService> outside) {
        this.outside = outside;
        this.targets = targets;
        this.pool = pool;
        this.lookups = lookups;
        this.boards = boards;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void tick() {
        try {
            run();
        } catch (Exception e) {
            log.warn("Target watcher cycle failed: {}", e.getMessage());
        }
    }

    private void run() {
        Instant now = Instant.now();
        syncRosters(now);
        List<BoardTarget> running = targets.running();

        // Group by the faction to read (one members call covers everyone in it); no faction → profile call.
        Map<Long, List<BoardTarget>> byFaction = new HashMap<>();
        List<BoardTarget> loners = new ArrayList<>();
        for (BoardTarget t : running) {
            if (t.factionId() != null) {
                byFaction.computeIfAbsent(t.factionId(), k -> new ArrayList<>()).add(t);
            } else {
                loners.add(t);
            }
        }
        for (Map.Entry<Long, List<BoardTarget>> e : byFaction.entrySet()) {
            if (!due("f" + e.getKey(), e.getValue(), now)) {
                continue;
            }
            readFaction(e.getKey(), e.getValue(), now);
        }
        for (BoardTarget t : loners) {
            if (!due("u" + t.tornId(), List.of(t), now)) {
                continue;
            }
            readPlayer(t, now);
        }
        boards.refresh();
    }

    /** Fast when a target is on a board or about to come out of hospital; slow otherwise. */
    private boolean due(String key, List<BoardTarget> group, Instant now) {
        boolean hot = group.stream().anyMatch(t -> t.onBoard() || t.lastState() == null
                || t.statusUntil() != null && t.statusUntil().isBefore(now.plus(SOON))
                || t.availableAt() != null && t.availableAt().isAfter(now));
        Instant last = lastFetch.get(key);
        if (last != null && last.plus(hot ? FAST : SLOW).isAfter(now.plusMillis(500))) {
            return false;
        }
        lastFetch.put(key, now);
        return true;
    }

    private void readFaction(long factionId, List<BoardTarget> group, Instant now) {
        JsonNode members;
        try {
            members = pool.call(TornKeyPool.Scope.any(), "/faction/" + factionId + "/members",
                    Map.of("striptags", "false")).path("members"); // keeps the attacker link in status.details
        } catch (Exception e) {
            log.info("Couldn't read faction {} members: {}", factionId, e.getMessage());
            return;
        }
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode m : members) {
            byId.put(m.path("id").asLong(), m);
        }
        boolean inWar = lookups.war(factionId).map(w -> w.active(now)).orElse(false);
        for (BoardTarget t : group) {
            JsonNode m = byId.get(t.tornId());
            if (m == null) {
                continue; // left the faction; player contracts re-read their profile next time
            }
            TornState s = new TornState(m.path("status").path("state").asText(null),
                    epoch(m.path("status").path("until")), text(m.path("status").path("details")),
                    m.has("is_revivable") ? m.path("is_revivable").asBoolean() : null,
                    m.has("has_early_discharge") ? m.path("has_early_discharge").asBoolean() : null,
                    epoch(m.path("last_action").path("timestamp")), text(m.path("last_action").path("status")),
                    m.has("level") ? m.path("level").asInt() : null, text(m.path("name")));
            targets.updateState(t.id(), s, availableAt(t, s, inWar, now), inWar);
            wentIntoHospital(t, s, now);
        }
    }

    private void readPlayer(BoardTarget t, Instant now) {
        JsonNode p;
        try {
            p = pool.call(TornKeyPool.Scope.any(), "/user/" + t.tornId() + "/profile", Map.of("striptags", "false"))
                    .path("profile");
        } catch (Exception e) {
            log.info("Couldn't read player {}: {}", t.tornId(), e.getMessage());
            return;
        }
        TornState s = new TornState(p.path("status").path("state").asText(null), epoch(p.path("status").path("until")),
                text(p.path("status").path("details")), p.has("revivable") ? p.path("revivable").asBoolean() : null,
                null, epoch(p.path("last_action").path("timestamp")), text(p.path("last_action").path("status")),
                p.has("level") ? p.path("level").asInt() : null, text(p.path("name")));
        targets.updateState(t.id(), s, availableAt(t, s, false, now), false);
        wentIntoHospital(t, s, now);
    }

    /** Phase 5a: who put a target in hospital, to spot other merc services (scope doc 7.8). */
    private void wentIntoHospital(BoardTarget before, TornState now, Instant at) {
        if ("Hospital".equals(now.state()) && before.lastState() != null && !"Hospital".equals(before.lastState())) {
            outside.getObject().hospitalised(before, now.details(), at);
        }
    }

    /**
     * In a ranked war, Torn blocks outsiders for 60 s after a target leaves hospital (scope doc 7.2),
     * so a target just out of hospital isn't hittable until then.
     */
    private static Instant availableAt(BoardTarget before, TornState now, boolean inWar, Instant at) {
        boolean justOut = "Hospital".equals(before.lastState()) && "Okay".equals(now.state());
        if (justOut && inWar) {
            Instant left = before.statusUntil() != null && before.statusUntil().isBefore(at) ? before.statusUntil() : at;
            return left.plus(WAR_LOCK);
        }
        if ("Hospital".equals(now.state()) && inWar && now.until() != null) {
            return now.until().plus(WAR_LOCK);
        }
        return before.availableAt() != null && before.availableAt().isAfter(at) ? before.availableAt() : null;
    }

    /** "All members" contracts follow the faction: joiners are added, leavers dropped. */
    private void syncRosters(Instant now) {
        for (RosterContract rc : targets.allMemberContracts()) {
            if (!due("r" + rc.contractId(), List.of(), now)) {
                continue;
            }
            JsonNode members;
            try {
                members = pool.call(TornKeyPool.Scope.any(), "/faction/" + rc.factionId() + "/members", Map.of())
                        .path("members");
            } catch (Exception e) {
                continue;
            }
            Map<Long, Long> roster = targets.roster(rc.contractId());
            Set<Long> present = new HashSet<>();
            for (JsonNode m : members) {
                long id = m.path("id").asLong();
                present.add(id);
                if (!roster.containsKey(id)) {
                    targets.addMember(rc.contractId(), id, text(m.path("name")),
                            m.has("level") ? m.path("level").asInt() : null, rc.factionId(), rc.hitsPerTarget());
                }
            }
            if (!present.isEmpty()) {
                roster.forEach((tornId, rowId) -> {
                    if (!present.contains(tornId)) {
                        targets.markLeft(rowId);
                    }
                });
            }
        }
    }

    private static Instant epoch(JsonNode n) {
        long v = n.asLong(0);
        return v > 0 ? Instant.ofEpochSecond(v) : null;
    }

    private static String text(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
