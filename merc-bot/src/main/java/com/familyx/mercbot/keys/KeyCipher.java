package com.familyx.mercbot.keys;

import com.familyx.mercbot.config.BotProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Encrypts Torn API keys at rest with AES-256-GCM. The master key comes from the
 * MERC_KEY_MASTER environment variable and never touches the database.
 * Stored format: 12-byte IV followed by the ciphertext and tag.
 */
@Component
public class KeyCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public KeyCipher(BotProperties props) {
        if (props.keyMaster() == null || props.keyMaster().isBlank()) {
            throw new IllegalStateException("MERC_KEY_MASTER is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] raw = Base64.getDecoder().decode(props.keyMaster().trim());
        if (raw.length != 32) {
            throw new IllegalStateException("MERC_KEY_MASTER must decode to 32 bytes (got " + raw.length + ")");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt key", e);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt key (wrong MERC_KEY_MASTER?)", e);
        }
    }

    /** One-way hash used to stop the same key being added twice, without storing it in clear. */
    public static String hash(String apiKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(apiKey.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
