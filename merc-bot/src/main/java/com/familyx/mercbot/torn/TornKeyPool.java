package com.familyx.mercbot.torn;

import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.keys.ApiKeyRepository;
import com.familyx.mercbot.keys.ApiKeyRepository.Kind;
import com.familyx.mercbot.keys.ApiKeyRepository.StoredKey;
import com.familyx.mercbot.keys.KeyCipher;
import com.familyx.mercbot.keys.KeyUnhealthyEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Picks the least-used healthy key for each call and keeps every key under Torn's
 * per-minute limit (scope doc 10.2). Faction endpoints (attacks, news, balance, upgrades)
 * must use a leader key from that faction; public lookups can use any healthy key.
 */
@Component
public class TornKeyPool {

    private static final Logger log = LoggerFactory.getLogger(TornKeyPool.class);
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_ATTEMPTS = 3;

    /** Which keys a call may use. */
    public sealed interface Scope permits Scope.AnyKey, Scope.FactionLeader, Scope.BspLeader {
        record AnyKey() implements Scope { }
        record FactionLeader(long factionId) implements Scope { }
        record BspLeader() implements Scope { }

        static Scope any() { return new AnyKey(); }
        static Scope faction(long factionId) { return new FactionLeader(factionId); }
        static Scope bsp() { return new BspLeader(); }
    }

    /** A decrypted key handed to a caller (used by BSP calls, which go to a different host). */
    public record LeasedKey(long id, String key) { }

    public static class PoolExhaustedException extends Exception {
        public PoolExhaustedException(String message) { super(message); }
    }

    private final ApiKeyRepository repo;
    private final KeyCipher cipher;
    private final TornClient torn;
    private final ApplicationEventPublisher events;
    private final int perMinute;

    private final Map<Long, Deque<Long>> usage = new ConcurrentHashMap<>();
    private volatile List<StoredKey> cache;

    public TornKeyPool(ApiKeyRepository repo, KeyCipher cipher, TornClient torn,
                       ApplicationEventPublisher events, BotProperties props) {
        this.repo = repo;
        this.cipher = cipher;
        this.torn = torn;
        this.events = events;
        this.perMinute = props.callsPerKeyPerMinute() > 0 ? props.callsPerKeyPerMinute() : 90;
    }

    /** Call after adding, removing or re-checking keys. */
    public void invalidate() {
        cache = null;
    }

    public JsonNode call(Scope scope, String path, Map<String, String> params)
            throws TornApiException, PoolExhaustedException {
        TornApiException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            StoredKey key = pick(scope);
            String plain = cipher.decrypt(key.keyEnc());
            record(key.id());
            try {
                return torn.get(path, params, plain);
            } catch (TornApiException e) {
                last = e;
                if (e.keyIsDead()) {
                    markUnhealthy(key, e.getMessage());
                } else if (e.lacksFactionAccess() && key.kind() == Kind.LEADER && scope instanceof Scope.FactionLeader) {
                    markUnhealthy(key, "No faction API access: " + e.getMessage());
                } else if (e.code() == TornApiException.TOO_MANY_REQUESTS) {
                    saturate(key.id());
                } else {
                    throw e; // A genuine error about the request itself; another key won't help.
                }
            }
        }
        throw last;
    }

    /** Hands out a decrypted BSP-active leader key for a BSP API call, counting it against the key's budget. */
    public LeasedKey leaseBspKey() throws PoolExhaustedException {
        StoredKey key = pick(Scope.bsp());
        record(key.id());
        return new LeasedKey(key.id(), cipher.decrypt(key.keyEnc()));
    }

    /** Share of the pool's per-minute capacity used right now, 0.0 to 1.0. */
    public double usageRatio() {
        List<StoredKey> healthy = keys().stream().filter(StoredKey::healthy).toList();
        if (healthy.isEmpty()) {
            return 1.0;
        }
        long now = System.currentTimeMillis();
        int used = healthy.stream().mapToInt(k -> recent(k.id(), now)).sum();
        return (double) used / (healthy.size() * (double) perMinute);
    }

    private StoredKey pick(Scope scope) throws PoolExhaustedException {
        Predicate<StoredKey> eligible = switch (scope) {
            case Scope.AnyKey a -> k -> true;
            case Scope.FactionLeader f -> k -> k.kind() == Kind.LEADER && Long.valueOf(f.factionId()).equals(k.factionId());
            case Scope.BspLeader b -> k -> k.kind() == Kind.LEADER && k.bspActive();
        };
        long now = System.currentTimeMillis();
        return keys().stream()
                .filter(StoredKey::healthy)
                .filter(eligible)
                .filter(k -> recent(k.id(), now) < perMinute)
                .min(Comparator.comparingInt(k -> recent(k.id(), now)))
                .orElseThrow(() -> new PoolExhaustedException("No healthy key with spare capacity for " + scope));
    }

    private List<StoredKey> keys() {
        List<StoredKey> c = cache;
        if (c == null) {
            c = repo.findAll();
            cache = c;
        }
        return c;
    }

    private synchronized void record(long keyId) {
        usage.computeIfAbsent(keyId, k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    /** Torn said this key is rate-limited: treat it as full for the rest of the window. */
    private synchronized void saturate(long keyId) {
        Deque<Long> q = usage.computeIfAbsent(keyId, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        while (q.size() < perMinute) {
            q.addLast(now);
        }
    }

    private synchronized int recent(long keyId, long now) {
        Deque<Long> q = usage.get(keyId);
        if (q == null) {
            return 0;
        }
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
            q.pollFirst();
        }
        return q.size();
    }

    private void markUnhealthy(StoredKey key, String reason) {
        log.warn("Key {} (owner {}) marked unhealthy: {}", key.id(), key.ownerTornId(), reason);
        repo.markUnhealthy(key.id(), reason);
        invalidate();
        events.publishEvent(new KeyUnhealthyEvent(key.id(), key.ownerDiscordId(), key.factionId(), key.kind(), reason));
    }
}
