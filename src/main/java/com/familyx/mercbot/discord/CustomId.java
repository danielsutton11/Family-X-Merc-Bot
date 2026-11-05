package com.familyx.mercbot.discord;

import java.util.Arrays;
import java.util.List;

/**
 * Custom-id scheme for every button, select and modal: {@code merc:<action>:<sub>[:<arg>...]}.
 * The action picks the handler; sub and args are for the handler to interpret.
 */
public record CustomId(String action, String sub, List<String> args) {

    public static final String PREFIX = "merc";

    public static String of(String action, String sub, Object... args) {
        StringBuilder sb = new StringBuilder(PREFIX).append(':').append(action).append(':').append(sub);
        for (Object a : args) {
            sb.append(':').append(a);
        }
        if (sb.length() > 100) {
            throw new IllegalArgumentException("Custom id too long: " + sb);
        }
        return sb.toString();
    }

    /** Returns null for ids that aren't ours (for example from another bot's components). */
    public static CustomId parse(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(":");
        if (parts.length < 3 || !PREFIX.equals(parts[0])) {
            return null;
        }
        return new CustomId(parts[1], parts[2], List.copyOf(Arrays.asList(parts).subList(3, parts.length)));
    }

    public String arg(int index) {
        return index < args.size() ? args.get(index) : null;
    }

    public long longArg(int index) {
        return Long.parseLong(arg(index));
    }
}
