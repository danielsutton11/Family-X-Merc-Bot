package com.familyx.mercbot.discord;

import net.dv8tion.jda.api.interactions.InteractionHook;

import java.util.concurrent.TimeUnit;

/**
 * Private messages that need no further input remove themselves after a while, so nobody has to
 * click "Dismiss message". Screens with buttons or menus are never dismissed.
 */
public final class AutoDismiss {

    /** One-line notices: "not switched on yet", "only leaders can use this", errors. */
    public static final int SHORT = 10;
    /** The /merc-setup report. */
    public static final int REPORT = 20;
    /** Longer reading, such as Help. */
    public static final int READING = 60;

    private AutoDismiss() {
    }

    /** Deletes the interaction's original private reply after {@code seconds}. */
    public static void after(InteractionHook hook, int seconds) {
        hook.deleteOriginal().queueAfter(seconds, TimeUnit.SECONDS, null, ignored -> { });
    }

    /** Deletes a private follow-up message after {@code seconds}. */
    public static void followUp(InteractionHook hook, long messageId, int seconds) {
        hook.deleteMessageById(messageId).queueAfter(seconds, TimeUnit.SECONDS, null, ignored -> { });
    }
}
