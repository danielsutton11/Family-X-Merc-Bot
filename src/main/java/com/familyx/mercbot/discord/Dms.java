package com.familyx.mercbot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Direct messages from the bot. Failures (DMs closed) are logged, never thrown. */
@Component
public class Dms {

    private static final Logger log = LoggerFactory.getLogger(Dms.class);

    private final JDA jda;

    public Dms(JDA jda) {
        this.jda = jda;
    }

    /** Sends in the background. */
    public void send(long discordId, MessageEmbed embed, ActionRow... rows) {
        jda.retrieveUserById(discordId)
                .flatMap(user -> user.openPrivateChannel())
                .flatMap(dm -> dm.sendMessage(new MessageCreateBuilder().setEmbeds(embed)
                        .setComponents(List.of(rows)).build()))
                .queue(null, e -> log.info("Couldn't DM {}: {}", discordId, e.getMessage()));
    }

    /** Sends and waits; returns false if the DM couldn't be delivered (used as the sign-up DM check). */
    public boolean sendNow(long discordId, MessageEmbed embed) {
        try {
            jda.retrieveUserById(discordId)
                    .flatMap(user -> user.openPrivateChannel())
                    .flatMap(dm -> dm.sendMessageEmbeds(embed))
                    .complete();
            return true;
        } catch (Exception e) {
            log.info("DM check failed for {}: {}", discordId, e.getMessage());
            return false;
        }
    }
}
