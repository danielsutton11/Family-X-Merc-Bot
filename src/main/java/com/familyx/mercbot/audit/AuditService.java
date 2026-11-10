package com.familyx.mercbot.audit;

import com.familyx.mercbot.discord.Layout;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * Append-only record of every leader action (scope doc 6.7), stored in the database and
 * mirrored as one line in #merc-audit. Mentions in the channel never ping anyone.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final DateTimeFormatter TCT = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper, Layout layout, ObjectProvider<JDA> jda) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.layout = layout;
        this.jda = jda;
    }

    /**
     * @param actorDiscordId who did it (null for the bot itself)
     * @param summary        one readable line, for example "Added a leader key for Capacity X"
     */
    public void log(Long actorDiscordId, String action, String entity, Object before, Object after, String summary) {
        jdbc.sql("""
                        INSERT INTO audit_event (actor_discord_id, action, entity, before_json, after_json, summary)
                        VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)""")
                .params(actorDiscordId, action, entity, json(before), json(after), summary)
                .update();

        layout.get(Layout.CH_AUDIT).ifPresent(channelId -> {
            JDA api = jda.getIfAvailable();
            TextChannel channel = api == null ? null : api.getTextChannelById(channelId);
            if (channel == null) {
                return;
            }
            String who = actorDiscordId == null ? "Merc Bot" : "<@" + actorDiscordId + ">";
            String line = "`" + TCT.format(ZonedDateTime.now(ZoneOffset.UTC)) + "` " + who + " · " + summary;
            channel.sendMessage(line).setAllowedMentions(Collections.emptySet())
                    .queue(null, e -> log.warn("Could not post audit line: {}", e.getMessage()));
        });
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"unserialisable\":true}";
        }
    }
}
