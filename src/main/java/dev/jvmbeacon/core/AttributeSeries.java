package dev.jvmbeacon.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

/** Session-local, bounded numeric evidence. No arbitrary remote object survives conversion. */
public final class AttributeSeries {
    public static final int LIMIT = 120;
    private static final Set<String> TYPES = Set.of("byte", "short", "int", "long", "float", "double",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
            "java.lang.Double", "java.math.BigInteger", "java.math.BigDecimal");
    private final ArrayDeque<Reading> readings = new ArrayDeque<>();

    public static boolean supports(String type) { return TYPES.contains(type); }
    public void add(Reading reading) {
        if (readings.size() == LIMIT) readings.removeFirst();
        readings.addLast(reading);
    }
    public void clear() { readings.clear(); }
    public List<Reading> readings() { return List.copyOf(readings); }

    public record Reading(long start, long end, String exact, Double plotted, String error) {
        public Reading {
            if (start > end) {
                exact = null; plotted = null;
                error = "Client clock moved backwards during the getter read. Original timestamps retained; numeric value discarded.";
            }
        }
        public static Reading missing(long start, long end, String error) {
            String safe = error == null ? "Unavailable" : error;
            return new Reading(start, end, null, null, safe.substring(0, Math.min(512, safe.length())));
        }
        public static Reading from(long start, long end, Object value) {
            if (value == null) return missing(start, end, "The getter returned null; no numeric value.");
            // Exact class checks also prevent invoking user overrides of Number/toString.
            Class<?> type = value.getClass();
            if (type != Byte.class && type != Short.class && type != Integer.class && type != Long.class
                    && type != Float.class && type != Double.class && type != BigInteger.class && type != BigDecimal.class)
                return missing(start, end, "The getter did not return a supported numeric scalar.");
            if (value instanceof BigInteger integer && integer.bitLength() > 4096)
                return missing(start, end, "Numeric value exceeds the display limit.");
            if (value instanceof BigDecimal decimal && (decimal.precision() > 1000 || Math.abs((long) decimal.scale()) > 1000))
                return missing(start, end, "Numeric precision or scale exceeds the display limit.");
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) return missing(start, end, "Value is non-finite or outside the chart's numeric range.");
            return new Reading(start, end, value.toString(), number, null);
        }
    }
}
