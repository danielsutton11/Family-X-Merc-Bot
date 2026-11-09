package com.familyx.mercbot;

import com.familyx.mercbot.board.BoardRules;
import com.familyx.mercbot.board.TargetRepository.BoardTarget;
import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.keys.KeyCipher;
import com.familyx.mercbot.merc.Eligibility;
import com.familyx.mercbot.request.Choices;
import com.familyx.mercbot.request.Pricing;
import com.familyx.mercbot.request.Rules;
import com.familyx.mercbot.request.Scenarios;
import com.familyx.mercbot.tier.TierRepository.Tier;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnitTests {

    private static BotProperties props(String master) {
        return new BotProperties("t", 1, 1, 1, 1, 1, master, "9.4.7", 90);
    }

    private static String newMaster() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void keyCipherRoundTripsAndUsesFreshIvs() {
        KeyCipher cipher = new KeyCipher(props(newMaster()));
        byte[] a = cipher.encrypt("AbCdEfGh12345678");
        byte[] b = cipher.encrypt("AbCdEfGh12345678");
        assertNotEquals(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b));
        assertEquals("AbCdEfGh12345678", cipher.decrypt(a));
    }

    @Test
    void keyCipherRejectsWrongMasterKey() {
        byte[] stored = new KeyCipher(props(newMaster())).encrypt("AbCdEfGh12345678");
        KeyCipher other = new KeyCipher(props(newMaster()));
        assertThrows(IllegalStateException.class, () -> other.decrypt(stored));
    }

    @Test
    void keyCipherRequires32ByteMaster() {
        assertThrows(IllegalStateException.class, () -> new KeyCipher(props("dG9vc2hvcnQ=")));
        assertThrows(IllegalStateException.class, () -> new KeyCipher(props("")));
    }

    @Test
    void keyHashIsStableAndTrimmed() {
        assertEquals(KeyCipher.hash("AbCdEfGh12345678"), KeyCipher.hash(" AbCdEfGh12345678 "));
    }

    @Test
    void customIdRoundTrip() {
        String raw = CustomId.of("factions", "setrole", 9357);
        assertEquals("merc:factions:setrole:9357", raw);
        CustomId id = CustomId.parse(raw);
        assertEquals("factions", id.action());
        assertEquals("setrole", id.sub());
        assertEquals(9357L, id.longArg(0));
        assertNull(id.arg(1));
        assertNull(CustomId.parse("other-bot:thing"));
    }

    @Test
    void moneyFormatsLikeTheMockups() {
        assertEquals("$2,900,000", Money.full(2_900_000));
        assertEquals("$2.9m", Money.shortForm(2_900_000));
        assertEquals("$1.24b", Money.shortForm(1_240_000_000));
        assertEquals("$1m", Money.shortForm(1_000_000));
        assertEquals("$850k", Money.shortForm(850_000));
        assertEquals("$62.64m", Money.shortForm(62_640_000));
    }

    @Test
    void moneyParsesWhatLeadersType() {
        assertEquals(2_400_000, Money.parse("2.4m"));
        assertEquals(2_400_000, Money.parse("$2,400,000"));
        assertEquals(900_000, Money.parse("900k"));
        assertEquals(1_000_000_000, Money.parse("1B"));
        assertEquals(-1, Money.parse("abc"));
        assertEquals(-1, Money.parse("0"));
        assertEquals(-1, Money.parse("1.5"));
        assertEquals(-1, Money.parse(""));
    }

    private static Tier tier(int id, int rank, boolean merits, boolean stricken) {
        return new Tier(id, "T" + id, rank, 0, merits, merits, stricken, null, null, null, true, false, 1_000_000);
    }

    @Test
    void eligibilityFollowsScopeDocTiers() {
        List<Tier> tiers = List.of(tier(1, 1, false, false), tier(2, 2, true, false), tier(3, 3, true, true));
        Eligibility.Facts bronzeOnly = new Eligibility.Facts(false, true, false, Set.of());
        Eligibility.Facts silver = new Eligibility.Facts(true, true, false, Set.of());
        Eligibility.Facts gold = new Eligibility.Facts(true, true, true, Set.of());
        Eligibility.Facts noPerk = new Eligibility.Facts(true, false, true, Set.of());
        Eligibility.Facts strickenNoMerits = new Eligibility.Facts(false, true, true, Set.of());

        assertEquals(Set.of(1), Eligibility.tiersToHold(tiers, bronzeOnly, true));
        assertEquals(Set.of(2), Eligibility.tiersToHold(tiers, silver, true));
        assertEquals(Set.of(3), Eligibility.tiersToHold(tiers, gold, true));
        assertEquals(Set.of(1, 2, 3), Eligibility.tiersToHold(tiers, gold, false));
        assertEquals(Set.of(1), Eligibility.tiersToHold(tiers, noPerk, true));
        assertEquals(Set.of(1), Eligibility.tiersToHold(tiers, strickenNoMerits, true));

        Eligibility.Facts goldOptedOut = new Eligibility.Facts(true, true, true, Set.of(3));
        assertEquals(Set.of(2), Eligibility.tiersToHold(tiers, goldOptedOut, true));
        Eligibility.Facts allOut = new Eligibility.Facts(true, true, true, Set.of(1, 2, 3));
        assertEquals(Set.of(), Eligibility.tiersToHold(tiers, allOut, true));

        assertEquals(true, Eligibility.strickenWouldUnlock(tiers, silver));
        assertEquals(false, Eligibility.strickenWouldUnlock(tiers, gold));
        assertEquals(false, Eligibility.strickenWouldUnlock(tiers, bronzeOnly));
    }

    @Test
    void pricingMatchesScopeDocExample() {
        // 7.5: a Gold hit at $2.9m with a 10% discount bills $2,610,000.
        assertEquals(2_610_000, Pricing.discounted(2_900_000, new BigDecimal("10")));
        assertEquals(2_900_000, Pricing.discounted(2_900_000, BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, Pricing.applicableDiscount(false, new BigDecimal("10")));
        assertEquals(new BigDecimal("10"), Pricing.applicableDiscount(true, new BigDecimal("10")));
        assertEquals(8_700_000, Pricing.total(2_900_000, 3, 1));
    }

    @Test
    void rulesFollowScopeDocTable() {
        Set<Long> family = Set.of(9357L, 1L);
        Set<Long> protectedF = Set.of(777L);
        // outsiders can't target the family
        assertEquals(true, Rules.check(Choices.ForTarget.PERSONAL_FACTION, false, 555L, family, protectedF,
                Map.of(9357L, "Capacity X")).isPresent());
        // family Personal can't target family members
        assertEquals(true, Rules.check(Choices.ForTarget.PERSONAL_PLAYERS, false, 1L, family, protectedF,
                Map.of(9357L, "Capacity X")).isPresent());
        // family My faction can target another family faction
        assertEquals(Optional.empty(), Rules.check(Choices.ForTarget.MY_FACTION_OTHER, false, 1L, family, protectedF,
                Map.of(9357L, "Capacity X")));
        // protected factions are refused
        assertEquals(Optional.of("Shield is a protected faction."), Rules.check(Choices.ForTarget.MY_FACTION_OTHER,
                false, 1L, family, protectedF, Map.of(777L, "Shield")));
        // My faction needs a faction
        assertEquals(true, Rules.check(Choices.ForTarget.MY_FACTION_PLAYERS, false, null, family, protectedF,
                Map.of()).isPresent());
        // Merc me is always allowed
        assertEquals(Optional.empty(), Rules.check(null, true, null, family, protectedF, Map.of()));
        // anyone can target an outside faction
        assertEquals(Optional.empty(), Rules.check(Choices.ForTarget.PERSONAL_FACTION, false, 555L, family, protectedF,
                Map.of(888L, "Other")));
    }

    @Test
    void scenariosPickTheRightDefaults() {
        Instant now = Instant.parse("2026-11-06T20:00:00Z");
        Scenarios.War scheduled = new Scenarios.War(42, "Enemy", now.plus(Duration.ofHours(10)), null);
        Scenarios.War active = new Scenarios.War(42, "Enemy", now.minus(Duration.ofHours(1)), null);
        var hosp = Choices.Attack.HOSPITALIZE;

        // A: own faction at war; starts at the war, 24h, idle 30+, don't hit revivable
        var a = Scenarios.defaults(new Scenarios.Situation(Choices.ForTarget.MY_FACTION_MINE, false, 1L, scheduled,
                null, hosp, null), now);
        assertEquals(Scenarios.Scenario.A, a.scenario());
        assertEquals(Choices.Status.AWAY, a.conditions().status());
        assertEquals(30, a.conditions().awayMinutes());
        assertEquals(false, a.conditions().allowRevivable());
        assertEquals(scheduled.start(), a.start());
        assertEquals(scheduled.start().plus(Duration.ofHours(24)), a.end());

        // D: own faction, no war
        assertEquals(Scenarios.Scenario.D, Scenarios.pick(new Scenarios.Situation(Choices.ForTarget.MY_FACTION_MINE,
                false, 1L, null, null, hosp, null), now));

        // B: the enemy, war scheduled: online, now until war start
        var b = Scenarios.defaults(new Scenarios.Situation(Choices.ForTarget.MY_FACTION_OTHER, false, 42L, scheduled,
                null, Choices.Attack.MUG, null), now);
        assertEquals(Scenarios.Scenario.B, b.scenario());
        assertEquals(Choices.Status.ONLINE, b.conditions().status());
        assertEquals(scheduled.start(), b.end());
        assertEquals(false, b.conditions().allowEarlyDischarge());

        // active war against the enemy: no defaults
        var w = Scenarios.defaults(new Scenarios.Situation(Choices.ForTarget.MY_FACTION_OTHER, false, 42L, active,
                null, hosp, null), now);
        assertEquals(Scenarios.Scenario.WAR_ACTIVE, w.scenario());
        assertNull(w.conditions());

        // C: personal against players
        assertEquals(Scenarios.Scenario.C, Scenarios.pick(new Scenarios.Situation(Choices.ForTarget.PERSONAL_PLAYERS,
                false, null, null, null, hosp, null), now));
        // E: another faction, no war
        var e = Scenarios.defaults(new Scenarios.Situation(Choices.ForTarget.PERSONAL_FACTION, false, 99L, null, null,
                hosp, null), now);
        assertEquals(Scenarios.Scenario.E, e.scenario());
        assertNull(e.end());
        assertEquals(false, e.conditions().allowEarlyDischarge());
        // G: another faction at war, offensive: online until their war start
        Scenarios.War theirs = new Scenarios.War(5, "X", now.plus(Duration.ofHours(3)), null);
        var g = Scenarios.defaults(new Scenarios.Situation(Choices.ForTarget.MY_FACTION_OTHER, false, 99L, null, theirs,
                hosp, Choices.Posture.OFFENSIVE), now);
        assertEquals(Scenarios.Scenario.G, g.scenario());
        assertEquals(Choices.Status.ONLINE, g.conditions().status());
        assertEquals(theirs.start(), g.end());
        // F: merc me
        assertEquals(Scenarios.Scenario.F, Scenarios.pick(new Scenarios.Situation(null, true, null, null, null, hosp,
                null), now));
    }

    private static BoardTarget target(String state, boolean revivable, String lastActionStatus, int minutesAgo,
                                      com.familyx.mercbot.request.Conditions c, Instant now) {
        return new BoardTarget(1, 1, "C-0001", "ACTIVE", 1, 0, now, true, "Foes", 10, Choices.Attack.HOSPITALIZE, c,
                null, null, 42, "Target", 50, 7L, state, null, null, revivable, false,
                now.minus(Duration.ofMinutes(minutesAgo)), lastActionStatus, null, false, null, 0, false, false, now);
    }

    @Test
    void boardShowsOnlyWhoTheConditionsAllow() {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        var away30 = new com.familyx.mercbot.request.Conditions(Choices.Status.AWAY, 30, false, true);
        var online = new com.familyx.mercbot.request.Conditions(Choices.Status.ONLINE, 30, true, true);
        assertEquals(BoardRules.Verdict.SHOW, BoardRules.verdict(target("Okay", false, "Offline", 45, away30, now), now));
        assertEquals(BoardRules.Verdict.HIDDEN_ONLINE, BoardRules.verdict(target("Okay", false, "Idle", 10, away30, now), now));
        assertEquals(BoardRules.Verdict.HIDDEN_ONLINE, BoardRules.verdict(target("Okay", false, "Online", 0, away30, now), now));
        assertEquals(BoardRules.Verdict.HIDDEN_REVIVABLE, BoardRules.verdict(target("Okay", true, "Offline", 45, away30, now), now));
        assertEquals(BoardRules.Verdict.NOT_YET, BoardRules.verdict(target("Hospital", false, "Offline", 45, away30, now), now));
        assertEquals(BoardRules.Verdict.HIDDEN_TRAVELLING, BoardRules.verdict(target("Traveling", false, "Offline", 45, away30, now), now));
        assertEquals(BoardRules.Verdict.SHOW, BoardRules.verdict(target("Okay", true, "Online", 0, online, now), now));
        assertEquals(BoardRules.Verdict.HIDDEN_OFFLINE, BoardRules.verdict(target("Okay", false, "Offline", 5, online, now), now));
        assertEquals("Away 45m", BoardRules.activity(target("Okay", false, "Offline", 45, away30, now), now));
        assertEquals("Online now", BoardRules.activity(target("Okay", false, "Online", 0, online, now), now));
    }

    @Test
    void playerIdsAcceptProfileLinks() {
        var parse = (java.util.function.Function<String, List<Long>>) com.familyx.mercbot.request.RequestService::parsePlayerIds;
        assertEquals(List.of(1561637L), parse.apply("https://www.torn.com/profiles.php?XID=1561637"));
        assertEquals(List.of(1561637L), parse.apply("1561637"));
        assertEquals(List.of(1561637L, 2023001L, 3503365L), parse.apply(
                "https://www.torn.com/profiles.php?XID=1561637#/, 2023001\n[3503365] 1561637"));
        assertEquals(List.of(2L), parse.apply("torn.com/profiles.php?foo=1&XID=2"));
        assertEquals(null, parse.apply("1561637 Dsuttz"));
        assertEquals(List.of(), parse.apply("  "));
    }

    private static BoardTarget inHospital(Instant until, boolean revivable, boolean inWar,
                                          com.familyx.mercbot.request.Conditions c, Instant now) {
        return new BoardTarget(1, 1, "C-0001", "ACTIVE", 1, 0, now, true, "Foes", 10, Choices.Attack.HOSPITALIZE, c,
                null, null, 42, "Target", 50, 7L, "Hospital", until, null, revivable, false,
                now.minus(Duration.ofMinutes(45)), "Offline", null, false, null, 0, false, inWar, now);
    }

    @Test
    void claimsShowTargetsEarly() {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        var away30 = new com.familyx.mercbot.request.Conditions(Choices.Status.AWAY, 30, false, true);
        Duration window = Duration.ofMinutes(2);
        assertEquals(true, BoardRules.showEarly(inHospital(now.plusSeconds(90), false, false, away30, now), now, window));
        assertEquals(false, BoardRules.showEarly(inHospital(now.plusSeconds(300), false, false, away30, now), now, window));
        // Ranked war: 60 s lock after leaving hospital, so 90 s + 60 s is outside a 2 minute window.
        assertEquals(now.plusSeconds(150), BoardRules.hittableAt(inHospital(now.plusSeconds(90), false, true, away30, now), now));
        assertEquals(false, BoardRules.showEarly(inHospital(now.plusSeconds(90), false, true, away30, now), now, window));
        // Still hidden by the contract's conditions (revivable, and this contract skips them).
        assertEquals(false, BoardRules.showEarly(inHospital(now.plusSeconds(90), true, false, away30, now), now, window));
        // Already hittable targets aren't "early".
        assertEquals(false, BoardRules.showEarly(target("Okay", false, "Offline", 45, away30, now), now, window));
    }

    @Test
    void hitsAreBilledAndSplitAsTheScopeDocSays() {
        Tier bronze = new Tier(1, "Bronze", 1, 0, false, false, false, null, null, null, true, false, 1_000_000);
        Tier silver = new Tier(2, "Silver", 2, 0, true, true, false, null, null, null, true, false, 1_500_000);
        Tier gold = new Tier(3, "Gold", 3, 0, true, true, true, null, null, null, true, false, 2_900_000);
        var all = List.of(bronze, silver, gold);
        assertEquals(gold, com.familyx.mercbot.hit.HitRules.billedTier(all, gold, true, true, true, false));
        assertEquals(silver, com.familyx.mercbot.hit.HitRules.billedTier(all, gold, false, true, true, false));
        assertEquals(silver, com.familyx.mercbot.hit.HitRules.billedTier(all, gold, true, true, true, true));
        assertEquals(bronze, com.familyx.mercbot.hit.HitRules.billedTier(all, gold, true, false, true, false));
        assertEquals(silver, com.familyx.mercbot.hit.HitRules.billedTier(all, silver, true, true, true, false));

        // Scope doc 7.5 example: $2,610,000 billed, 1% cut, $2,583,900 for the mercs.
        long cut = com.familyx.mercbot.hit.HitRules.factionCut(2_610_000, BigDecimal.ONE);
        assertEquals(26_100L, cut);
        long left = 2_610_000 - cut;
        BigDecimal p75 = BigDecimal.valueOf(75);
        BigDecimal p100 = BigDecimal.valueOf(100);
        var solo = com.familyx.mercbot.hit.HitRules.split(left, 1, List.of(), 0, p75, null, false, p100);
        assertEquals(1, solo.size());
        assertEquals(left, solo.get(0).amount());
        var pair = com.familyx.mercbot.hit.HitRules.split(left, 1, List.of(2L), 0, p75, null, false, p100);
        assertEquals(1_937_925L, pair.get(0).amount());
        assertEquals(645_975L, pair.get(1).amount());
        var forfeited = com.familyx.mercbot.hit.HitRules.split(left, 1, List.of(), 1, p75, null, false, p100);
        assertEquals(left, forfeited.get(0).amount());
        var claimed = com.familyx.mercbot.hit.HitRules.split(left, 1, List.of(2L), 0, p75, 2L, true, p100);
        assertEquals(1, claimed.size());
        assertEquals(2L, claimed.get(0).mercId());
        var noShow = com.familyx.mercbot.hit.HitRules.split(left, 1, List.of(), 0, p75, 5L, false, p100);
        assertEquals(1, noShow.size());
        assertEquals(5L, noShow.get(0).mercId());
        assertEquals(left, noShow.get(0).amount());
    }

    @Test
    void assistsJoinTheFightTheyEndedWith() {
        // From a real Capacity X attack log: Dizzy's assist has its own code but ended with Dsuttz's hit.
        var hit = new com.familyx.mercbot.hit.TornAttack(557925139, "7d3470f9", Instant.ofEpochSecond(1794230290),
                Instant.ofEpochSecond(1794230312), 1561637L, "Dsuttz", 9357L, 3489751, "Pillowyendx", "Hospitalized",
                false, null);
        var assist = new com.familyx.mercbot.hit.TornAttack(557925159, "1c474c8e", Instant.ofEpochSecond(1794230300),
                Instant.ofEpochSecond(1794230312), 1806971L, "Dizzy", 9357L, 3489751, "Pillowyendx", "Assist",
                false, null);
        var other = new com.familyx.mercbot.hit.TornAttack(557925773, "9b999952", Instant.ofEpochSecond(1794230535),
                Instant.ofEpochSecond(1794230538), 1561637L, "Dsuttz", 9357L, 3165250, "Pillowyend", "Hospitalized",
                true, 81);
        var fights = com.familyx.mercbot.hit.TornAttack.fights(List.of(other, assist, hit));
        assertEquals(2, fights.size());
        assertEquals(0, fights.get(0).assists().size());
        assertEquals(List.of(assist), fights.get(1).assists());
    }

    @Test
    void depositsAreReadFromFactionNews() {
        String text = "<a href = \"http://www.torn.com/profiles.php?XID=2087183\">Hoezey</a> increased "
                + "<a href = \"http://www.torn.com/profiles.php?XID=2311136\">mercs</a>'s money balance by "
                + "$131,280,000 from $1,000 to $131,281,000";
        var p = com.familyx.mercbot.payment.NewsReconciler.parse(text).orElseThrow();
        assertEquals(2087183L, p.actorId());
        assertEquals("Hoezey", p.actorName());
        assertEquals("increased", p.direction());
        assertEquals(2311136L, p.accountId());
        assertEquals(131_280_000L, p.amount());
        assertEquals(131_281_000L, p.after());
        assertEquals(true, com.familyx.mercbot.payment.NewsReconciler.parse("Hoezey deposited $5,000").isEmpty());
    }
}
