package com.familyx.mercbot.dm;

import com.familyx.mercbot.dm.DmRepository.Panel;
import com.familyx.mercbot.dm.DmRepository.Tracked;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * DM cleanup: once a minute, deletes DMs whose time is up or whose event has happened, and
 * control panels 7 days after their contract ended and was paid. A DM someone already deleted,
 * or a user the bot can't reach, is simply forgotten.
 */
@Service
public class DmCleanup {

    private static final Logger log = LoggerFactory.getLogger(DmCleanup.class);
    private static final int BATCH = 50;

    private final DmRepository repo;
    private final ObjectProvider<JDA> jda;

    public DmCleanup(DmRepository repo, ObjectProvider<JDA> jda) {
        this.repo = repo;
        this.jda = jda;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void run() {
        JDA api = jda.getIfAvailable();
        if (api == null) {
            return;
        }
        try {
            repo.markEventsDone();
            for (Tracked t : repo.due(BATCH)) {
                if (delete(api, t.userId(), t.messageId()) || t.deleteAt().isBefore(Instant.now().minus(Duration.ofDays(2)))) {
                    repo.forget(t.id());
                }
            }
            for (Panel p : repo.panelsDue()) {
                delete(api, p.requesterDiscordId(), p.messageId());
                repo.panelDeleted(p.contractId());
            }
        } catch (Exception e) {
            log.warn("DM cleanup failed: {}", e.getMessage());
        }
    }

    /** True when the message is gone (deleted now, or already gone). */
    private boolean delete(JDA api, long userId, long messageId) {
        try {
            api.openPrivateChannelById(userId).flatMap(ch -> ch.deleteMessageById(messageId)).complete();
            return true;
        } catch (ErrorResponseException e) {
            return e.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE || e.getErrorResponse() == ErrorResponse.UNKNOWN_USER
                    || e.getErrorResponse() == ErrorResponse.UNKNOWN_CHANNEL || e.getErrorResponse() == ErrorResponse.CANNOT_SEND_TO_USER;
        } catch (Exception e) {
            return false;
        }
    }
}
