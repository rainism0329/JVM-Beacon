package dev.jvmbeacon.core;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Half-open selection. Duration events overlap the range; their evidence is never clipped. */
public record JfrTimeRange(Instant from, Instant until) {
    public JfrTimeRange {
        Objects.requireNonNull(from); Objects.requireNonNull(until);
        if (!from.isBefore(until)) throw new IllegalArgumentException("Range start must be before its exclusive end.");
    }
    public boolean includes(Instant start, Instant end) {
        if (end.isBefore(start)) return false;
        return start.equals(end) ? !start.isBefore(from) && start.isBefore(until)
                : start.isBefore(until) && end.isAfter(from);
    }
    public String describe() {
        return "Applied time range (UTC): [" + from + ", " + until + ")\n"
                + "Instant events: start included, end excluded. Duration events: any positive overlap.\n"
                + "Full event durations/fields are retained, NOT clipped or prorated to this range.\n"
                + "Counts, allocation weights and sample shares are scoped; overlapping evidence does not establish causality.";
    }
    public static String offset(Instant origin, Instant value) {
        var d = Duration.between(origin, value);
        return BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9)).stripTrailingZeros().toPlainString();
    }
    public static JfrTimeRange offsets(Instant origin, Instant last, String from, String until) {
        try {
            var range = new JfrTimeRange(at(origin, from), at(origin, until));
            if (range.until.isAfter(last.plusNanos(1))) throw new IllegalArgumentException("End exceeds the inspected recording window.");
            return range;
        } catch (ArithmeticException | java.time.DateTimeException e) {
            throw new IllegalArgumentException("Offsets are outside the supported time range.");
        }
    }
    private static Instant at(Instant origin, String text) {
        String value = text.strip();
        if (value.length() > 40 || !value.matches("[0-9]+(?:\\.[0-9]{1,9})?"))
            throw new IllegalArgumentException("Use non-negative seconds, with up to 9 decimal places.");
        BigDecimal seconds = new BigDecimal(value);
        long whole = seconds.toBigInteger().longValueExact();
        long nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValueExact();
        return origin.plusSeconds(whole).plusNanos(nanos);
    }
    public static JfrTimeRange around(Instant start, Instant end, Instant first, Instant last) {
        Instant from = start.minusMillis(100), until = end.plusMillis(100);
        if (from.isBefore(first)) from = first;
        if (until.isAfter(last)) until = last.plusNanos(1);
        return new JfrTimeRange(from, until);
    }
}
