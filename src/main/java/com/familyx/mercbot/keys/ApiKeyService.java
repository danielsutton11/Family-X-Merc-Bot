package com.familyx.mercbot.keys;

import com.familyx.mercbot.bsp.BspClient;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.keys.ApiKeyRepository.Kind;
import com.familyx.mercbot.torn.TornApiException;
import com.familyx.mercbot.torn.TornClient;
import com.familyx.mercbot.torn.TornKeyPool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validating and storing API keys. Phase 1 handles leader keys; merc keys reuse
 * {@link #verifyOwner} at sign-up in phase 2.
 */
@Service
public class ApiKeyService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9]{16}$");

    /** Outcome of adding a key, worded for the person who added it. */
    public sealed interface AddResult {
        record Added(long keyId, FamilyFaction faction, String tornName, boolean bspActive) implements AddResult { }
        record Rejected(String reason) implements AddResult { }
    }

    /** The Torn account a key belongs to. */
    public record KeyOwner(long tornId, String name, Long factionId) { }

    private final ApiKeyRepository repo;
    private final FamilyFactionRepository factions;
    private final KeyCipher cipher;
    private final TornClient torn;
    private final TornKeyPool pool;
    private final BspClient bsp;

    public ApiKeyService(ApiKeyRepository repo, FamilyFactionRepository factions, KeyCipher cipher,
                         TornClient torn, TornKeyPool pool, BspClient bsp) {
        this.repo = repo;
        this.factions = factions;
        this.cipher = cipher;
        this.torn = torn;
        this.pool = pool;
        this.bsp = bsp;
    }

    public AddResult addLeaderKey(String rawKey, long discordUserId) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (!KEY_FORMAT.matcher(key).matches()) {
            return new AddResult.Rejected("That doesn't look like a Torn API key (16 letters and numbers).");
        }
        String hash = KeyCipher.hash(key);
        if (repo.existsByHash(hash)) {
            return new AddResult.Rejected("That key has already been added.");
        }

        KeyOwner owner;
        try {
            owner = verifyOwner(key, discordUserId);
        } catch (OwnerMismatchException e) {
            return new AddResult.Rejected(e.getMessage());
        } catch (TornApiException e) {
            return new AddResult.Rejected("Torn rejected the key: " + e.getMessage());
        }

        if (owner.factionId() == null || !factions.isFamily(owner.factionId())) {
            return new AddResult.Rejected("Leader keys must belong to a member of a Family X faction.");
        }
        try {
            torn.get("/faction/news", Map.of("cat", "depositFunds", "limit", "1", "striptags", "false"), key);
        } catch (TornApiException e) {
            if (e.lacksFactionAccess()) {
                return new AddResult.Rejected("This key can't read faction data. Create a Limited key and make sure your "
                        + "faction position has API access.");
            }
            return new AddResult.Rejected("Torn rejected the faction check: " + e.getMessage());
        }

        long id = repo.insert(owner.tornId(), discordUserId, owner.factionId(), Kind.LEADER, cipher.encrypt(key), hash);
        boolean bspActive = refreshBsp(id, key);
        pool.invalidate();
        FamilyFaction faction = factions.find(owner.factionId()).orElseThrow();
        return new AddResult.Added(id, faction, owner.name(), bspActive);
    }

    /**
     * Checks the key works and that the Torn account's linked Discord is the person submitting it
     * (scope doc 5.1). Throws if the key is invalid or belongs to someone else.
     */
    public KeyOwner verifyOwner(String key, long discordUserId) throws TornApiException, OwnerMismatchException {
        JsonNode discord = torn.get("/user/discord", Map.of(), key).path("discord");
        String linked = discord.path("discord_id").asText("");
        if (linked.isBlank() || "null".equals(linked)) {
            throw new OwnerMismatchException("Your Torn account has no linked Discord. Link it on Torn first, then try again.");
        }
        if (!linked.equals(Long.toString(discordUserId))) {
            throw new OwnerMismatchException("This key belongs to a Torn account linked to a different Discord user.");
        }
        JsonNode profile = torn.get("/user/profile", Map.of(), key).path("profile");
        JsonNode faction = profile.path("faction_id");
        return new KeyOwner(profile.path("id").asLong(), profile.path("name").asText("?"),
                faction.isNull() || faction.isMissingNode() ? null : faction.asLong());
    }

    /** Removes every leader key the user added. */
    public int removeLeaderKeys(long discordUserId) {
        int removed = repo.deleteByOwner(discordUserId, Kind.LEADER);
        pool.invalidate();
        return removed;
    }

    /** Checks the key's BSP subscription and stores the result. */
    public boolean refreshBsp(long keyId, String key) {
        Optional<BspClient.Subscription> sub = bsp.subscription(key);
        boolean active = sub.map(BspClient.Subscription::active).orElse(false);
        repo.setBsp(keyId, active, sub.map(BspClient.Subscription::expiresAt).orElse(null));
        return active;
    }

    public static class OwnerMismatchException extends Exception {
        public OwnerMismatchException(String message) { super(message); }
    }
}
