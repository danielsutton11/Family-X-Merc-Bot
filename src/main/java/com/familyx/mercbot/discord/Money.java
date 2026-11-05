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

    private static String trim(double v) {
        String s = v >= 100 ? String.format(Locale.UK, "%.0f", v) : String.format(Locale.UK, "%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
