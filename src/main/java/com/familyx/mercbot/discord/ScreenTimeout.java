package com.familyx.mercbot.discord;

import net.dv8tion.jda.api.interactions.InteractionHook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Closes private screens (the ones with buttons and menus) after they've gone unused for a while.
 * Discord only lets the bot delete a private message within 15 minutes of the last interaction
 * on it, so the timer is just under that and restarts on every click.
 */
public final class ScreenTimeout {

    static final long IDLE_SECONDS = 14 * 60;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private static final Map<Long, ScheduledFuture<?>> PENDING = new ConcurrentHashMap<>();

    private ScreenTimeout() {
    }

    /** (Re)starts the idle timer for this private message, using the newest interaction's hook. */
    public static void restart(long messageId, InteractionHook hook) {
        ScheduledFuture<?> previous = PENDING.put(messageId, SCHEDULER.schedule(() -> {
            PENDING.remove(messageId);
            hook.deleteOriginal().queue(null, ignored -> { });
        }, IDLE_SECONDS, TimeUnit.SECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** Same, when only the hook is known (the message id is looked up first). */
    public static void restart(InteractionHook hook) {
        hook.retrieveOriginal().queue(message -> restart(message.getIdLong(), hook), ignored -> { });
    }

    /** Stops the timer, for a screen that has been replaced by a message that closes itself. */
    public static void cancel(long messageId) {
        ScheduledFuture<?> previous = PENDING.remove(messageId);
        if (previous != null) {
            previous.cancel(false);
        }
    }
}
