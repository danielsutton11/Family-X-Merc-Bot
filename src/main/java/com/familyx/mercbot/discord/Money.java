package com.familyx.mercbot.discord;

import java.text.NumberFormat;
import java.util.Locale;

/** Formats Torn dollars the way the mockups show them. */
public final class Money {

    private Money() {
    }

    /** $2,900,000 */
    public static String full(long dollars) {
        return "$" + NumberFormat.getIntegerInstance(Locale.UK).format(dollars);
    }

    /** $2.9m, $1.24b, $850k */
    public static String shortForm(long dollars) {
        double abs = Math.abs(dollars);
        String sign = dollars < 0 ? "-" : "";
        if (abs >= 1_000_000_000) return sign + "$" + trim(abs / 1_000_000_000d) + "b";
        if (abs >= 1_000_000) return sign + "$" + trim(abs / 1_000_000d) + "m";
        if (abs >= 1_000) return sign + "$" + trim(abs / 1_000d) + "k";
        return full(dollars);
    }

    /**
     * Reads an amount typed by a leader: "2.4m", "$2,400,000", "900k", "1b". Returns -1 if it
     * isn't a positive amount.
     */
    public static long parse(String text) {
        if (text == null) {
            return -1;
        }
        String t = text.trim().toLowerCase(Locale.ROOT).replace("$", "").replace(",", "").replace(" ", "");
        if (t.isEmpty()) {
            return -1;
        }
        long multiplier = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            multiplier = last == 'k' ? 1_000L : last == 'm' ? 1_000_000L : 1_000_000_000L;
            t = t.substring(0, t.length() - 1);
        }
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(t).multiply(java.math.BigDecimal.valueOf(multiplier));
            if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0) {
                return -1;
            }
            return value.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    private static String trim(double v) {
        String s = v >= 100 ? String.format(Locale.UK, "%.0f", v) : String.format(Locale.UK, "%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
