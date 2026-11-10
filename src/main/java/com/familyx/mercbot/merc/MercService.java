package com.familyx.mercbot.merc;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.keys.ApiKeyRepository;
import com.familyx.mercbot.keys.ApiKeyRepository.Kind;
import com.familyx.mercbot.keys.ApiKeyRepository.StoredKey;
import com.familyx.mercbot.keys.ApiKeyService;
import com.familyx.mercbot.keys.ApiKeyService.KeyOwner;
import com.familyx.mercbot.keys.KeyCipher;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.torn.TornApiException;
import com.familyx.mercbot.torn.TornClient;
import com.familyx.mercbot.torn.TornKeyPool;
import com.fasterxml.jackson.databind.JsonNode;
import net.dv8tion.jda.api.EmbedBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Sign-up, re-checks and leaving (scope doc 5.1). */
@Service
public class MercService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9]{16}$");

    /** The outcome of Join, Replace key or Re-check, shown as a list of checks. */
    public record CheckResult(boolean ok, List<String> lines, String fix, Merc merc, RoleSync.Result roles) {
        static CheckResult failed(List<String> lines, String fix) {
            return new CheckResult(false, lines, fix, null, RoleSync.Result.NONE);
        }
    }

    private final MercRepository mercs;
    private final ApiKeyRepository keys;
    private final ApiKeyService keyService;
    private final KeyCipher cipher;
    private final TornClient torn;
    private final TornKeyPool pool;
    private final FamilyFactionRepository factions;
    private final FactionPerkService perks;
    private final RoleSync roles;
    private final TierAvailability availability;
    private final Dms dms;
    private final AuditService audit;

    private static final String BLACKLISTED = "You can't sign up as a merc. Message a Merc Manager if you think this is a mistake.";
    private final MercStatsRepository blacklist;

    public MercService(MercRepository mercs, ApiKeyRepository keys, ApiKeyService keyService, KeyCipher cipher,
                       TornClient torn, TornKeyPool pool, FamilyFactionRepository factions,
                       FactionPerkService perks, RoleSync roles, TierAvailability availability, Dms dms,
                       AuditService audit, MercStatsRepository blacklist) {
        this.blacklist = blacklist;
        this.mercs = mercs;
        this.keys = keys;
        this.keyService = keyService;
        this.cipher = cipher;
        this.torn = torn;
        this.pool = pool;
        this.factions = factions;
        this.perks = perks;
        this.roles = roles;
        this.availability = availability;
        this.dms = dms;
        this.audit = audit;
    }

    /** Join, or Replace key for an existing merc: runs the five checks and saves only if they pass. */
    public CheckResult join(String rawKey, long discordId) {
        List<String> lines = new ArrayList<>();
        String key = rawKey == null ? "" : rawKey.trim();
        if (!KEY_FORMAT.matcher(key).matches()) {
            return CheckResult.failed(lines, "That doesn't look like a Torn API key (16 letters and numbers).");
        }
        if (blacklist.blacklisted(null, discordId)) {
            return CheckResult.failed(lines, BLACKLISTED);
        }
        String hash = KeyCipher.hash(key);
        Optional<StoredKey> existing = keys.findByHash(hash);
        if (existing.isPresent() && existing.get().ownerDiscordId() != discordId) {
            return CheckResult.failed(lines, "That key is already in use by someone else.");
        }

        // 1-2. The key works, and its Torn account is linked to this Discord account.
        KeyOwner owner;
        try {
            owner = keyService.verifyOwner(key, discordId);
        } catch (ApiKeyService.OwnerMismatchException e) {
            lines.add("❌  Torn account linked to this Discord account");
            return CheckResult.failed(lines, e.getMessage());
        } catch (TornApiException e) {
            return CheckResult.failed(lines, "Torn rejected the key: " + e.getMessage());
        }
        if (blacklist.blacklisted(owner.tornId(), discordId)) {
            return CheckResult.failed(lines, BLACKLISTED);
        }
        Optional<FamilyFaction> faction = Optional.ofNullable(owner.factionId()).flatMap(factions::find)
                .filter(FamilyFaction::active);
        if (faction.isEmpty()) {
            lines.add("❌  Member of a Family X faction");
            return CheckResult.failed(lines, "Merc sign-up is for members of Family X factions.");
        }
        lines.add("✅  Member of **" + faction.get().name() + "**");
        lines.add("✅  Torn account linked to this Discord account");

        // 3-4. Merits and the faction perk decide tiers; they don't block sign-up.
        boolean merits;
        try {
            merits = TornChecks.hospMeritsMaxed(torn.get("/user/merits", Map.of(), key));
        } catch (TornApiException e) {
            return CheckResult.failed(lines, "Torn wouldn't show your merits: " + e.getMessage());
        }
        lines.add(merits ? "✅  10/10 merits in Hospitalisation"
                : "➖  Merits in Hospitalisation aren't 10/10 yet (needed for Silver and Gold)");
        boolean perk = perks.refresh(faction.get().tornFactionId());
        lines.add(perk ? "✅  " + faction.get().name() + " has Hospitalization X"
                : "➖  " + faction.get().name() + " doesn't have Hospitalization X (needed for Silver and Gold)");

        // 5. A DM gets through, since hit results and alerts arrive by DM.
        boolean dmOk = dms.sendNow(discordId, com.familyx.mercbot.discord.Dms.Clean.after("DM_TEST", java.time.Duration.ofMinutes(1)), new EmbedBuilder().setColor(Panels.GOOD)
                .setTitle("👋  DMs are working")
                .setDescription("Hit results and alerts from Family X Mercs will arrive here.").build());
        if (!dmOk) {
            lines.add("❌  DMs from the bot get through");
            return CheckResult.failed(lines, "The bot couldn't DM you. Turn on **Direct Messages** from server "
                    + "members (Server menu → Privacy Settings), then try again.");
        }
        lines.add("✅  DMs from the bot get through");

        // Save: one merc key per person. A key already stored for this person (their leader key) is reused.
        if (existing.isEmpty()) {
            keys.deleteByOwner(discordId, Kind.MERC);
            keys.insert(owner.tornId(), discordId, owner.factionId(), Kind.MERC, cipher.encrypt(key), hash);
            pool.invalidate();
        }
        boolean rejoin = mercs.findByTorn(owner.tornId()).map(Merc::active).orElse(false);
        mercs.upsert(owner.tornId(), discordId, owner.name(), owner.factionId(), merits);
        Merc merc = mercs.findByTorn(owner.tornId()).orElseThrow();
        RoleSync.Result result = roles.sync(merc);
        availability.evaluate();
        audit.log(discordId, rejoin ? "MERC_KEY_REPLACED" : "MERC_JOINED", "merc:" + merc.tornId(), null,
                Map.of("faction", owner.factionId(), "merits", merits, "perk", perk),
                (rejoin ? "Replaced their merc key" : "Signed up as a merc") + " (" + owner.name() + " ["
                        + owner.tornId() + "], " + faction.get().name() + ")");
        return new CheckResult(true, lines, null, merc, result);
    }

    /** The key used for this merc's own checks: their merc key, or their leader key if they reused it. */
    public Optional<String> keyFor(Merc m) {
        return keys.findByOwner(m.discordId(), Kind.MERC).stream().filter(StoredKey::healthy).findFirst()
                .or(() -> keys.findByOwner(m.discordId(), Kind.LEADER).stream().filter(StoredKey::healthy).findFirst())
                .map(k -> cipher.decrypt(k.keyEnc()));
    }

    /** Is the merc's key working? */
    public boolean keyWorking(Merc m) {
        return keyFor(m).isPresent();
    }

    /**
     * Re-reads merits, faction and the faction perk, then syncs roles. Used by Re-check now and
     * the daily check. Returns null on success, or a short reason the check couldn't run.
     */
    public String recheck(Merc m) {
        Optional<String> key = keyFor(m);
        if (key.isEmpty()) {
            return "Your key isn't working. Use **Replace key** to add a new one.";
        }
        try {
            JsonNode profile = torn.get("/user/profile", Map.of(), key.get()).path("profile");
            JsonNode factionNode = profile.path("faction_id");
            Long factionId = factionNode.isNull() || factionNode.isMissingNode() ? null : factionNode.asLong();
            boolean merits = TornChecks.hospMeritsMaxed(torn.get("/user/merits", Map.of(), key.get()));
            mercs.updateCheck(m.tornId(), profile.path("name").asText(null), factionId, merits);
            if (factionId != null && factions.isFamily(factionId)) {
                perks.refresh(factionId);
            }
            return null;
        } catch (TornApiException e) {
            return "Torn didn't answer the check: " + e.getMessage();
        }
    }

    /** A Merc Manager removes a merc from the service (leader panel → Mercs). */
    public void remove(Merc m, long leaderDiscordId) {
        remove(m, leaderDiscordId, false);
    }

    /** Removes a merc; blacklisted ones are told they can't sign up again. */
    public void remove(Merc m, long leaderDiscordId, boolean blacklisted) {
        mercs.markLeft(m.tornId());
        keys.deleteByOwner(m.discordId(), Kind.MERC);
        pool.invalidate();
        roles.sync(mercs.findByTorn(m.tornId()).orElseThrow());
        availability.evaluate();
        audit.log(leaderDiscordId, "MERC_REMOVED", "merc:" + m.tornId(), null, null,
                "Removed **" + m.name() + "** [" + m.tornId() + "] from the merc service");
        dms.send(m.discordId(), new EmbedBuilder().setColor(Panels.WARN).setAuthor("FAMILY X MERCS")
                .setTitle("🚪  You've been removed from the merc service")
                .setDescription(blacklisted
                        ? "A Merc Manager removed you, so your merc roles and stored key are gone, and you can't sign up "
                        + "again. If you think this was a mistake, ask a Merc Manager."
                        : "A Merc Manager removed you, so your merc roles and stored key are gone. If you think "
                        + "this was a mistake, ask a Merc Manager. You can join again from the merc sign-up channel.")
                .build());
    }

    public void leave(Merc m) {
        mercs.markLeft(m.tornId());
        keys.deleteByOwner(m.discordId(), Kind.MERC);
        pool.invalidate();
        roles.sync(mercs.findByTorn(m.tornId()).orElseThrow());
        availability.evaluate();
        audit.log(m.discordId(), "MERC_LEFT", "merc:" + m.tornId(), null, null,
                "Left the merc service (" + m.name() + " [" + m.tornId() + "])");
    }
}
