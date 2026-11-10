package com.familyx.mercbot.outside;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads who put a target in hospital from Torn's {@code status.details} (scope doc 7.8):
 * "Hospitalized by X", "Mugged by X" or "Attacked by X" (a leave), where X is a profile link
 * (with striptags=false), a plain name, or "someone". Plain logic, unit tested.
 */
public final class HospitalCause {

    public enum Kind { HOSPITALIZED, MUGGED, ATTACKED }

    /** attackerId is null for "someone" or a name without a link; name is null for "someone". */
    public record Cause(Kind kind, Long attackerId, String attackerName) {
        public boolean unknown() {
            return attackerId == null;
        }
    }

    private static final Pattern CAUSE = Pattern.compile("^\\s*(Hospitalized|Mugged|Attacked) by\\s+(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<a\\s+href\\s*=\\s*\"[^\"]*XID=(\\d+)[^\"]*\"[^>]*>([^<]*)</a>",
            Pattern.CASE_INSENSITIVE);

    private HospitalCause() { }

    public static Optional<Cause> parse(String details) {
        if (details == null) {
            return Optional.empty();
        }
        Matcher m = CAUSE.matcher(details);
        if (!m.matches()) {
            return Optional.empty();
        }
        Kind kind = Kind.valueOf(m.group(1).toUpperCase());
        String who = m.group(2);
        Matcher link = LINK.matcher(who);
        if (link.find()) {
            return Optional.of(new Cause(kind, Long.parseLong(link.group(1)), link.group(2).strip()));
        }
        String plain = who.replaceAll("<[^>]+>", "").strip();
        if (plain.isEmpty() || plain.equalsIgnoreCase("someone")) {
            return Optional.of(new Cause(kind, null, null));
        }
        return Optional.of(new Cause(kind, null, plain));
    }
}
