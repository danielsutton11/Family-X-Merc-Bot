package com.familyx.mercbot.bsp;

import com.familyx.mercbot.torn.TornKeyPool;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Battle-stat estimates for board rows (scope doc 10.3), cached for a day. Missing estimates are
 * fetched in the background so a board is never held up waiting for BSP.
 */
@Service
public class BspService {

    private final JdbcClient jdbc;
    private final BspClient bsp;
    private final TornKeyPool pool;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public BspService(JdbcClient jdbc, BspClient bsp, TornKeyPool pool) {
        this.jdbc = jdbc;
        this.bsp = bsp;
        this.pool = pool;
    }

    /** Cached total battle-stat estimates for these players (missing ones are fetched for next time). */
    public Map<Long, Long> estimates(List<Long> tornIds) {
        Map<Long, Long> out = new HashMap<>();
        if (tornIds.isEmpty()) {
            return out;
        }
        String in = String.join(",", tornIds.stream().map(String::valueOf).toList());
        jdbc.sql("SELECT target_torn_id, tbs, fetched_at > now() - interval '1 day' AS fresh FROM bsp_prediction "
                        + "WHERE target_torn_id IN (" + in + ")")
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    long tbs = rs.getLong("tbs");
                    if (tbs > 0) {
                        out.put(rs.getLong("target_torn_id"), tbs);
                    }
                    if (!rs.getBoolean("fresh")) {
                        fetchLater(rs.getLong("target_torn_id"));
                    }
                });
        for (long id : tornIds) {
            if (!out.containsKey(id)) {
                fetchLater(id);
            }
        }
        return out;
    }

    /**
     * Like {@link #estimates}, but waits (up to a few seconds) for any missing estimates instead of
     * leaving them for next time. Used for pings, which are only sent once.
     */
    public Map<Long, Long> estimatesNow(List<Long> tornIds) {
        Map<Long, Long> have = estimates(tornIds);
        List<Long> missing = tornIds.stream().filter(id -> !have.containsKey(id)).toList();
        if (missing.isEmpty()) {
            return have;
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && missing.stream().anyMatch(inFlight::contains)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Map<Long, Long> again = new HashMap<>(have);
        again.putAll(cached(missing));
        return again;
    }

    private Map<Long, Long> cached(List<Long> tornIds) {
        Map<Long, Long> out = new HashMap<>();
        if (tornIds.isEmpty()) {
            return out;
        }
        String in = String.join(",", tornIds.stream().map(String::valueOf).toList());
        jdbc.sql("SELECT target_torn_id, tbs FROM bsp_prediction WHERE tbs > 0 AND target_torn_id IN (" + in + ")")
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        out.put(rs.getLong("target_torn_id"), rs.getLong("tbs")));
        return out;
    }

    private void fetchLater(long tornId) {
        if (!inFlight.add(tornId)) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                TornKeyPool.LeasedKey key = pool.leaseBspKey();
                Optional<BspClient.Prediction> p = bsp.predict(key.key(), tornId);
                p.ifPresent(pr -> jdbc.sql("""
                                INSERT INTO bsp_prediction (target_torn_id, tbs, score, result, fetched_at)
                                VALUES (?, ?, ?, ?, now())
                                ON CONFLICT (target_torn_id) DO UPDATE SET tbs = EXCLUDED.tbs, score = EXCLUDED.score,
                                    result = EXCLUDED.result, fetched_at = now()""")
                        .params(tornId, pr.tbs(), pr.score(), pr.result()).update());
            } catch (Exception ignored) {
                // No BSP-active key, or BSP unavailable: the row shows "BSP ?" until it works.
            } finally {
                inFlight.remove(tornId);
            }
        });
    }

    /** 1.24b, 86.2m, 640k */
    public static String format(Long tbs) {
        if (tbs == null || tbs <= 0) {
            return "?";
        }
        double v = tbs;
        if (v >= 1e12) return trim(v / 1e12) + "t";
        if (v >= 1e9) return trim(v / 1e9) + "b";
        if (v >= 1e6) return trim(v / 1e6) + "m";
        if (v >= 1e3) return trim(v / 1e3) + "k";
        return Long.toString(tbs);
    }

    private static String trim(double v) {
        String s = v >= 100 ? String.format(java.util.Locale.UK, "%.0f", v)
                : v >= 10 ? String.format(java.util.Locale.UK, "%.1f", v) : String.format(java.util.Locale.UK, "%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
