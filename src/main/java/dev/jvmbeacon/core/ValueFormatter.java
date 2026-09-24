package dev.jvmbeacon.core;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.IdentityHashMap;

/** Bounded rendering; unknown objects are never asked to execute toString(). */
public final class ValueFormatter {
    public static final int MAX_CHARS = 32_768;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_DEPTH = 6;

    private ValueFormatter() { }

    public static String format(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value, 0, new IdentityHashMap<>());
        if (output.length() > MAX_CHARS) return output.substring(0, MAX_CHARS) + "\n… [output truncated]";
        return output.toString();
    }

    private static void append(StringBuilder out, Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (out.length() >= MAX_CHARS) return;
        if (value == null) { out.append("null"); return; }
        if (value instanceof String string) { literal(out, string); return; }
        Class<?> type = value.getClass();
        if (type == BigInteger.class && ((BigInteger) value).bitLength() > 16_384
                || type == BigDecimal.class && ((BigDecimal) value).precision() > 4_096) {
            out.append("[number exceeds display conversion limit]"); return;
        }
        if (type == Boolean.class || type == Byte.class || type == Short.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class || type == BigInteger.class || type == BigDecimal.class || type == Character.class) {
            literal(out, value.toString()); return;
        }
        if (value instanceof ObjectName name) { literal(out, name.getCanonicalName()); return; }
        if (depth >= MAX_DEPTH) { out.append("[maximum depth]"); return; }
        if (seen.put(value, true) != null) { out.append("[cycle]"); return; }
        try {
            if (value instanceof CompositeData composite) {
                out.append("{\n");
                int count = 0;
                for (String key : composite.getCompositeType().keySet()) {
                    if (count++ == MAX_ITEMS || out.length() >= MAX_CHARS) { out.append("… [items truncated]\n"); break; }
                    indent(out, depth + 1); literal(out, key); out.append(": "); append(out, composite.get(key), depth + 1, seen); out.append('\n');
                }
                indent(out, depth); out.append('}');
            } else if (value instanceof TabularData table) {
                out.append("Table indexed by "); literal(out, table.getTabularType().getIndexNames().toString()); out.append(" [\n");
                int count = 0;
                for (Object row : table.values()) {
                    if (count++ == MAX_ITEMS || out.length() >= MAX_CHARS) { out.append("… [rows truncated]\n"); break; }
                    indent(out, depth + 1); append(out, row, depth + 1, seen); out.append('\n');
                }
                indent(out, depth); out.append(']');
            } else if (type.isArray()) {
                int length = Array.getLength(value);
                out.append('[');
                for (int i = 0; i < Math.min(length, MAX_ITEMS) && out.length() < MAX_CHARS; i++) {
                    if (i > 0) out.append(", "); append(out, Array.get(value, i), depth + 1, seen);
                }
                if (length > MAX_ITEMS) out.append(", … [").append(length - MAX_ITEMS).append(" more]");
                out.append(']');
            } else {
                out.append("[unsupported result type: "); literal(out, type.getName()); out.append(']');
            }
        } catch (RuntimeException failure) {
            out.append("[value unavailable: ").append(failure.getClass().getSimpleName()).append(']');
        } finally { seen.remove(value); }
    }

    private static void literal(StringBuilder out, String text) {
        int remaining = Math.max(0, MAX_CHARS - out.length() + 1);
        out.append(text, 0, Math.min(text.length(), remaining));
    }
    private static void indent(StringBuilder out, int depth) { out.append("  ".repeat(depth)); }
}
