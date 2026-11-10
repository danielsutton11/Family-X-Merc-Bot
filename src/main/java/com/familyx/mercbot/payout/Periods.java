package com.familyx.mercbot.payout;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Settlement periods and payout strings: plain logic, no Discord or database (unit tested). */
public final class Periods {

    public static final String PER_CONTRACT = "PER_CONTRACT";
    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK);

    private Periods() { }

    /** A settlement period: from start (inclusive) to end (exclusive). */
    public record Period(Instant start, Instant end) { }

    /**
     * The period that closed most recently at or before {@code now}: the week to Monday 00:00 UTC,
     * or the month to the 1st. Empty for per-contract settlement.
     */
    public static Optional<Period> lastClosed(String mode, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (WEEKLY.equals(mode)) {
            LocalDate end = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return Optional.of(new Period(start(end.minusWeeks(1)), start(end)));
        }
        if (MONTHLY.equals(mode)) {
            LocalDate end = today.withDayOfMonth(1);
            return Optional.of(new Period(start(end.minusMonths(1)), start(end)));
        }
        return Optional.empty();
    }

    /** When the current period closes; null for per-contract settlement. */
    public static Instant nextClose(String mode, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (WEEKLY.equals(mode)) {
            return start(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
        }
        if (MONTHLY.equals(mode)) {
            return start(today.withDayOfMonth(1).plusMonths(1));
        }
        return null;
    }

    /** "week 2–8 Nov", "week 26 Oct–1 Nov", or "October 2026" for a whole calendar month. */
    public static String label(Instant start, Instant end) {
        LocalDate s = start.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = end.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        if (s.getDayOfMonth() == 1 && last.plusDays(1).getDayOfMonth() == 1 && s.getMonth() == last.getMonth()) {
            return MONTH_YEAR.format(s);
        }
        if (s.equals(last)) {
            return DAY_MONTH.format(s);
        }
        String from = s.getMonth() == last.getMonth() ? Integer.toString(s.getDayOfMonth()) : DAY_MONTH.format(s);
        return "week " + from + "–" + DAY_MONTH.format(last);
    }

    /** {@code [[playerId,amount],…]} for the payment script, in the order given. */
    public static String payoutString(List<long[]> lines) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(lines.get(i)[0]).append(',').append(lines.get(i)[1]).append(']');
        }
        return sb.append(']').toString();
    }

    private static Instant start(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
