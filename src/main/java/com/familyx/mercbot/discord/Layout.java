package com.familyx.mercbot.discord;

import com.familyx.mercbot.settings.SettingsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * IDs of the channels, roles and pinned messages that {@code /merc-setup} creates,
 * stored in the settings table so setup can be re-run safely.
 */
@Component
public class Layout {

    public static final String SETTINGS_KEY = "discord.layout";

    // Categories
    public static final String CAT_SERVICE = "cat.service";
    public static final String CAT_LEADERS = "cat.leaders";
    // Channels
    public static final String CH_SIGNUP = "ch.signup";
    public static final String CH_RESULTS = "ch.results";
    public static final String CH_LEADERBOARD = "ch.leaderboard";
    public static final String CH_LEADERS = "ch.leaders";
    public static final String CH_INBOX = "ch.inbox";
    public static final String CH_PAYOUTS = "ch.payouts";
    public static final String CH_AUDIT = "ch.audit";
    // Roles
    public static final String ROLE_MERC = "role.merc";
    public static final String ROLE_MERITS = "role.merits";
    public static final String ROLE_STRICKEN = "role.stricken";
    // Pinned messages
    public static final String MSG_GUIDE = "msg.requests.guide";
    public static final String MSG_REQUEST_PANEL = "msg.requests.panel";
    public static final String MSG_SIGNUP = "msg.signup";
    public static final String MSG_LEADERBOARD = "msg.leaderboard";
    public static final String MSG_LEADERS = "msg.leaders";

    public static String payoutsMessage(long factionId) {
        return "msg.payouts." + factionId;
    }

    private static final TypeReference<Map<String, Long>> TYPE = new TypeReference<>() { };

    private final SettingsRepository settings;

    public Layout(SettingsRepository settings) {
        this.settings = settings;
    }

    public Optional<Long> get(String key) {
        return Optional.ofNullable(all().get(key));
    }

    public synchronized void put(String key, long id) {
        Map<String, Long> map = new HashMap<>(all());
        map.put(key, id);
        settings.put(SETTINGS_KEY, map, null);
    }

    public Map<String, Long> all() {
        return settings.get(SETTINGS_KEY, TYPE).orElseGet(Map::of);
    }
}
