package com.familyx.mercbot.discord;

import com.familyx.mercbot.dm.DmRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Direct messages from the bot. Failures (DMs closed) are logged, never thrown. A DM sent with
 * a {@link Clean} is deleted later by the DM cleanup: after a time, when what it says stops
 * being true, or whichever comes first. DMs sent without one are kept.
 */
@Component
public class Dms {

    private static final Logger log = LoggerFactory.getLogger(Dms.class);

    /**
     * When a DM goes. {@code kind} names the DM; {@code ref} the thing it's about ("invoice:12",
     * "merc:2104455"), for kinds deleted on an event; {@code after} is the time limit, if any.
     */
    public record Clean(String kind, String ref, Duration after, boolean replaces) {
        public static Clean after(String kind, Duration after) {
            return new Clean(kind, null, after, false);
        }

        /** Deleted when its event happens (the DM cleanup checks each kind's condition). */
        public static Clean when(String kind, String ref) {
            return new Clean(kind, ref, null, false);
        }

        /** Deleted when its event happens, or after the time limit, whichever is first. */
        public static Clean whenOr(String kind, String ref, Duration after) {
            return new Clean(kind, ref, after, false);
        }

        /** Like {@link #when}, and the earlier DM of the same kind and subject is deleted now. */
        public static Clean whenReplacing(String kind, String ref) {
            return new Clean(kind, ref, null, true);
        }

        Instant deleteAt() {
            return after == null ? null : Instant.now().plus(after);
        }
    }

    private final JDA jda;
    private final DmRepository tracked;

    public Dms(JDA jda, DmRepository tracked) {
        this.jda = jda;
        this.tracked = tracked;
    }

    /** Sends in the background and keeps it. */
    public void send(long discordId, MessageEmbed embed, ActionRow... rows) {
        send(discordId, null, embed, rows);
    }

    /** Sends in the background; deleted later as {@code clean} says (null keeps it). */
    public void send(long discordId, Clean clean, MessageEmbed embed, ActionRow... rows) {
        jda.retrieveUserById(discordId)
                .flatMap(user -> user.openPrivateChannel())
                .flatMap(dm -> dm.sendMessage(new MessageCreateBuilder().setEmbeds(embed)
                        .setComponents(List.of(rows)).build()))
                .queue(m -> track(discordId, m, clean), e -> log.info("Couldn't DM {}: {}", discordId, e.getMessage()));
    }

    /** Sends plain text in the background; deleted later as {@code clean} says. */
    public void sendText(long discordId, Clean clean, String text) {
        jda.retrieveUserById(discordId)
                .flatMap(user -> user.openPrivateChannel())
                .flatMap(dm -> dm.sendMessage(text))
                .queue(m -> track(discordId, m, clean), e -> log.info("Couldn't DM {}: {}", discordId, e.getMessage()));
    }

    /**
     * Sends the embed, then the file as its own message straight after (Discord shows a file
     * above the embed when they share a message). Both are kept.
     */
    public void send(long discordId, MessageEmbed embed, net.dv8tion.jda.api.utils.FileUpload file, ActionRow... rows) {
        jda.retrieveUserById(discordId)
                .flatMap(user -> user.openPrivateChannel())
                .flatMap(dm -> dm.sendMessage(new MessageCreateBuilder().setEmbeds(embed)
                                .setComponents(List.of(rows)).build())
                        .flatMap(sent -> dm.sendFiles(file)))
                .queue(null, e -> log.info("Couldn't DM {}: {}", discordId, e.getMessage()));
    }

    /** Sends and waits; returns false if the DM couldn't be delivered (used as the sign-up DM check). */
    public boolean sendNow(long discordId, MessageEmbed embed) {
        return sendNow(discordId, null, embed);
    }

    public boolean sendNow(long discordId, Clean clean, MessageEmbed embed) {
        try {
            Message m = jda.retrieveUserById(discordId)
                    .flatMap(user -> user.openPrivateChannel())
                    .flatMap(dm -> dm.sendMessageEmbeds(embed))
                    .complete();
            track(discordId, m, clean);
            return true;
        } catch (Exception e) {
            log.info("DM check failed for {}: {}", discordId, e.getMessage());
            return false;
        }
    }

    private void track(long discordId, Message m, Clean clean) {
        if (clean == null || m == null) {
            return;
        }
        try {
            if (clean.replaces() && clean.ref() != null) {
                tracked.expire(discordId, clean.kind(), clean.ref());
            }
            tracked.track(discordId, m.getIdLong(), clean.kind(), clean.ref(), clean.deleteAt());
        } catch (Exception e) {
            log.warn("Couldn't record DM {} for cleanup: {}", m.getIdLong(), e.getMessage());
        }
    }
}
