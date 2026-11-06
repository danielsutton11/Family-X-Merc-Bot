package com.familyx.mercbot.discord;

/**
 * The style the server uses for channel and category names: small caps, "・" for spaces and
 * hyphens, and "┃" in front of channel names (e.g. "┃ᴍᴇʀᴄ・ꜱɪɢɴᴜᴘ", category "ᴍᴇʀᴄ・ꜱᴇʀᴠɪᴄᴇ").
 * Q and X have no small-caps form, so they stay as plain lowercase letters.
 */
public final class Fancy {

    private static final String PLAIN = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘqʀꜱᴛᴜᴠᴡxʏᴢ";

    private Fancy() {
    }

    /** "merc-signup" becomes "┃ᴍᴇʀᴄ・ꜱɪɢɴᴜᴘ". */
    public static String channel(String name) {
        return "┃" + spaced(name);
    }

    /** "Merc Service" becomes "ᴍᴇʀᴄ・ꜱᴇʀᴠɪᴄᴇ". */
    public static String category(String name) {
        return spaced(name);
    }

    private static String spaced(String name) {
        return smallCaps(name.trim()).replaceAll("[\\s-]+", "・");
    }

    public static String smallCaps(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            int i = PLAIN.indexOf(Character.toLowerCase(c));
            sb.append(i >= 0 ? SMALL.charAt(i) : c);
        }
        return sb.toString();
    }
}
