package com.familyx.mercbot.discord;

import com.familyx.mercbot.settings.SettingsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers what each bot message last looked like, so unchanged messages aren't edited again.
 * Discord limits how fast a bot can edit messages in one channel; skipping no-op edits keeps
 * /merc-setup and panel refreshes clear of that limit. Embed timestamps are ignored when
 * comparing, so a "Last updated" time alone never counts as a change.
 */
@Component
public class PanelHashes {

    private static final String SETTINGS_KEY = "discord.messageHashes";
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() { };

    private final SettingsRepository settings;
    private volatile Map<String, String> cache;

    public PanelHashes(SettingsRepository settings) {
        this.settings = settings;
    }

    /** True if this content differs from what was last sent to the message. */
    public boolean changed(long messageId, MessageEditData data) {
        return !hash(data).equals(hashes().get(Long.toString(messageId)));
    }

    /** Records what the message now shows. */
    public synchronized void remember(long messageId, MessageEditData data) {
        Map<String, String> map = new HashMap<>(hashes());
        map.put(Long.toString(messageId), hash(data));
        settings.put(SETTINGS_KEY, map, null);
        cache = new ConcurrentHashMap<>(map);
    }

    private Map<String, String> hashes() {
        Map<String, String> c = cache;
        if (c == null) {
            c = new ConcurrentHashMap<>(settings.get(SETTINGS_KEY, TYPE).orElseGet(Map::of));
            cache = c;
        }
        return c;
    }

    static String hash(MessageEditData data) {
        String json = data.toData().toString().replaceAll("\"timestamp\"\\s*:\\s*\"[^\"]*\"", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
