package com.familyx.mercbot.torn;

/**
 * An error returned by the Torn API, or a transport failure (code -1).
 * Codes: https://www.torn.com/api.html (Errors section).
 */
public class TornApiException extends Exception {

    /** Incorrect key. */
    public static final int INCORRECT_KEY = 2;
    /** Too many requests on this key. */
    public static final int TOO_MANY_REQUESTS = 5;
    /** The key owner isn't allowed this faction data. */
    public static final int NOT_ALLOWED = 7;
    /** Key disabled because the owner has been inactive. */
    public static final int KEY_OWNER_INACTIVE = 13;
    /** Key access level too low for this selection. */
    public static final int ACCESS_TOO_LOW = 16;
    /** Key paused by its owner. */
    public static final int KEY_PAUSED = 18;

    private final int code;

    public TornApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** The key itself is unusable and should be marked unhealthy. */
    public boolean keyIsDead() {
        return code == INCORRECT_KEY || code == KEY_OWNER_INACTIVE || code == KEY_PAUSED;
    }

    /** The key works but can't see faction data. */
    public boolean lacksFactionAccess() {
        return code == ACCESS_TOO_LOW || code == NOT_ALLOWED;
    }
}
