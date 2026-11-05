package com.familyx.mercbot.keys;

import com.familyx.mercbot.keys.ApiKeyRepository.Kind;
import com.familyx.mercbot.keys.ApiKeyRepository.StoredKey;
import com.familyx.mercbot.torn.TornApiException;
import com.familyx.mercbot.torn.TornClient;
import com.familyx.mercbot.torn.TornKeyPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Daily key health and BSP subscription check (scope doc 10.4, "Key health" and "BSP").
 * Dead keys are flagged and their owners DMed; keys that start working again are restored.
 */
@Component
public class KeyHealthJob {

    private static final Logger log = LoggerFactory.getLogger(KeyHealthJob.class);

    private final ApiKeyRepository repo;
    private final KeyCipher cipher;
    private final TornClient torn;
    private final TornKeyPool pool;
    private final ApiKeyService keys;
    private final ApplicationEventPublisher events;

    public KeyHealthJob(ApiKeyRepository repo, KeyCipher cipher, TornClient torn, TornKeyPool pool,
                        ApiKeyService keys, ApplicationEventPublisher events) {
        this.repo = repo;
        this.cipher = cipher;
        this.torn = torn;
        this.pool = pool;
        this.keys = keys;
        this.events = events;
    }

    /** 04:17 UTC daily, a quiet time in TCT, off the hour to avoid scheduler pile-ups. */
    @Scheduled(cron = "0 17 4 * * *", zone = "UTC")
    public void run() {
        int checked = 0;
        int failed = 0;
        for (StoredKey k : repo.findAll()) {
            checked++;
            String plain = cipher.decrypt(k.keyEnc());
            String problem = probe(k, plain);
            if (problem == null) {
                if (!k.healthy()) {
                    repo.markHealthy(k.id());
                }
                if (k.kind() == Kind.LEADER) {
                    keys.refreshBsp(k.id(), plain);
                }
            } else {
                failed++;
                if (k.healthy()) {
                    repo.markUnhealthy(k.id(), problem);
                    events.publishEvent(new KeyUnhealthyEvent(k.id(), k.ownerDiscordId(), k.factionId(), k.kind(), problem));
                }
            }
        }
        pool.invalidate();
        log.info("Key health check: {} keys checked, {} failing", checked, failed);
    }

    /** Returns null if the key is fine, or a short reason. Transient network errors don't count as failures. */
    private String probe(StoredKey k, String plain) {
        try {
            torn.get("/user/basic", Map.of(), plain);
            if (k.kind() == Kind.LEADER) {
                torn.get("/faction/news", Map.of("cat", "depositFunds", "limit", "1", "striptags", "false"), plain);
            }
            return null;
        } catch (TornApiException e) {
            if (e.keyIsDead()) {
                return e.getMessage();
            }
            if (e.lacksFactionAccess() && k.kind() == Kind.LEADER) {
                return "No faction API access";
            }
            return null;
        }
    }
}
