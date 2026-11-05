package com.familyx.mercbot;

import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.keys.KeyCipher;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

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
}
