package com.familyx.mercbot.discord;

import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Edits pinned messages (boards, panels, payout embeds) in place, debounced so a burst of
 * changes becomes one edit (scope doc 10.1). The content supplier runs at flush time,
 * so the edit always shows the latest state.
 */
@Component
public class MessageUpdater {

    private static final Logger log = LoggerFactory.getLogger(MessageUpdater.class);
    private static final long DEBOUNCE_MS = 2000;

    /** Where to edit and what to put there. */
    public record Edit(long channelId, long messageId, MessageEditData data) { }

    private final JDA jda;
    private final PanelHashes hashes;
    private final Map<String, Supplier<Optional<Edit>>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    public MessageUpdater(JDA jda, PanelHashes hashes) {
        this.jda = jda;
        this.hashes = hashes;
    }

    /**
     * Schedules an edit for {@code key} (for example "board:gold"). Requests for the same key
     * within the debounce window are merged.
     */
    public void request(String key, Supplier<Optional<Edit>> content) {
        if (pending.put(key, content) == null) {
            scheduler.schedule(() -> flush(key), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flush(String key) {
        Supplier<Optional<Edit>> supplier = pending.remove(key);
        if (supplier == null) {
            return;
        }
        try {
            supplier.get().ifPresent(edit -> {
                if (!hashes.changed(edit.messageId(), edit.data())) {
                    return; // nothing new to show
                }
                MessageChannel channel = jda.getChannelById(MessageChannel.class, edit.channelId());
                if (channel == null) {
                    log.warn("Channel {} for {} not found", edit.channelId(), key);
                    return;
                }
                channel.editMessageById(edit.messageId(), edit.data())
                        .queue(done -> hashes.remember(edit.messageId(), edit.data()),
                                e -> log.warn("Edit of {} failed: {}", key, e.getMessage()));
            });
        } catch (Exception e) {
            log.error("Building update for {} failed", key, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
