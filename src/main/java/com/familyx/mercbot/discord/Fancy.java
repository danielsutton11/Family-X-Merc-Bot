package com.familyx.mercbot.discord;

/**
 * The style the server uses for channel and category names: small caps, "・" for spaces and
 * hyphens, and "┃" in front of channel names (e.g. "┃ᴍᴇʀᴄ・ꜱɪɢɴᴜᴘ", categories "⋄ Family X Merc Service").
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

    /** Categories keep normal letters with a diamond in front: "⋄ Family X Merc Service". */
    public static String category(String name) {
        return "⋄ " + name.trim();
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
