package dev.jvmbeacon.core;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Immutable presentation data. Capture on a worker; never retain remote object graphs in Swing. */
public record StructuredValue(StructuredValue.Node root, int nodeCount, boolean incomplete) {
    public static final int MAX_NODES = 512, MAX_CHARS = 32_768, MAX_DEPTH = 6, MAX_ITEMS = 100;
    public enum Kind { VALUE, NULL, COMPOSITE, TABLE, ARRAY, UNSUPPORTED, ERROR, LIMIT }
    public record Node(String name, String type, String value, Kind kind, List<Node> children, boolean limited) {
        public Node { children = List.copyOf(children); }
        @Override public String toString() {
            String preview = value.replace('\n', ' ').replace('\r', ' ');
            return name + "  ·  " + (preview.length() > 100 ? preview.substring(0, 100) + "…" : preview);
        }
    }

    /** One shared budget per attribute batch, in addition to the per-value limits. */
    public static final class Budget {
        private int nodes, chars;
        public Budget(int nodes, int chars) {
            if (nodes < 1 || chars < 1) throw new IllegalArgumentException("Positive capture budget required");
            this.nodes = nodes; this.chars = chars;
        }
        public boolean available() { return nodes > 0 && chars > 0; }
    }

    public static StructuredValue capture(String name, Object value) {
        return capture(name, value, new Budget(MAX_NODES, MAX_CHARS));
    }

    /** Returns null only when the shared batch budget was already exhausted. */
    public static StructuredValue capture(String name, Object value, Budget budget) {
        if (!budget.available()) return null;
        Builder builder = new Builder(budget);
        Node root = builder.read(name, value, 0);
        return new StructuredValue(root, builder.nodes, builder.incomplete);
    }

    private static final class Builder {
        private final Budget budget;
        private final IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
        private int nodes, chars;
        private boolean incomplete;
        private Builder(Budget budget) { this.budget = budget; }
        private boolean available() { return nodes < MAX_NODES && chars < MAX_CHARS && budget.available(); }
        private String text(String value, int max) {
            if (value == null) return "";
            int length = Math.min(max, Math.min(MAX_CHARS - chars, budget.chars));
            length = Math.max(0, Math.min(length, value.length()));
            chars += length; budget.chars -= length;
            if (length < value.length()) incomplete = true;
            return value.substring(0, length);
        }
        private Node read(String name, Object value, int depth) {
            nodes++; budget.nodes--;
            String label = text(name, 256);
            String type = text(value == null ? "null" : value.getClass().getTypeName(), 256);
            List<Node> children = new ArrayList<>();
            Kind kind = Kind.VALUE;
            String summary = "";
            boolean limited = label.length() != name.length();
            boolean entered = false;
            try {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                if (value == null) { kind = Kind.NULL; summary = "null"; }
                else if (value.getClass() == String.class) summary = (String) value;
                else if (scalar(value)) summary = value.toString();
                else if (value.getClass() == BigInteger.class) {
                    if (((BigInteger) value).bitLength() > 16_384) { kind = Kind.LIMIT; summary = "Integer exceeds display conversion limit"; }
                    else summary = value.toString();
                } else if (value.getClass() == BigDecimal.class) {
                    if (((BigDecimal) value).precision() > 4_096) { kind = Kind.LIMIT; summary = "Decimal exceeds display conversion limit"; }
                    else summary = value.toString();
                } else if (value.getClass() == ObjectName.class) summary = ((ObjectName) value).getCanonicalName();
                else if (value.getClass() == Date.class) summary = Instant.ofEpochMilli(((Date) value).getTime()).toString();
                else if (path.containsKey(value)) { kind = Kind.LIMIT; summary = "Cycle in captured value"; }
                else if (value instanceof CompositeData || value instanceof TabularData || value.getClass().isArray()) {
                    if (depth >= MAX_DEPTH) { kind = Kind.LIMIT; summary = "Maximum depth reached"; }
                    else {
                        path.put(value, Boolean.TRUE);
                        entered = true;
                        if (value instanceof CompositeData composite) {
                            kind = Kind.COMPOSITE;
                            var compositeType = composite.getCompositeType();
                            type = text(compositeType.getTypeName(), 256);
                            var keys = compositeType.keySet();
                            for (String key : keys) {
                                if (!available() || children.size() >= MAX_ITEMS) { limited = true; break; }
                                Object child;
                                try { child = composite.get(key); }
                                catch (RuntimeException e) {
                                    children.add(error(key, e)); continue;
                                }
                                children.add(read(key, child, depth + 1));
                            }
                            summary = children.size() + " / " + keys.size() + " fields captured";
                        } else if (value instanceof TabularData table) {
                            kind = Kind.TABLE;
                            var tableType = table.getTabularType();
                            type = text(tableType.getTypeName(), 256);
                            StringBuilder indexes = new StringBuilder();
                            for (String index : tableType.getIndexNames()) {
                                if (indexes.length() >= 256) { limited = true; break; }
                                if (!indexes.isEmpty()) indexes.append(", ");
                                indexes.append(index, 0, Math.min(128, index.length()));
                            }
                            for (Object row : table.values()) {
                                if (!available() || children.size() >= MAX_ITEMS) { limited = true; break; }
                                children.add(read("Row " + (children.size() + 1), row, depth + 1));
                            }
                            summary = children.size() + " / " + table.size() + " rows captured · Index: " + indexes + ". Row order is not identity.";
                        } else {
                            kind = Kind.ARRAY;
                            int size = Array.getLength(value);
                            for (int i = 0; i < size; i++) {
                                if (!available() || children.size() >= MAX_ITEMS) { limited = true; break; }
                                children.add(read("[" + i + "]", Array.get(value, i), depth + 1));
                            }
                            summary = children.size() + " / " + size + " elements captured";
                        }
                    }
                } else { kind = Kind.UNSUPPORTED; summary = "Unsupported result type; arbitrary toString() is not called"; }
            } catch (java.util.concurrent.CancellationException e) { throw e; }
            catch (RuntimeException e) { kind = Kind.ERROR; summary = "Value unavailable: " + e.getClass().getSimpleName(); }
            finally { if (entered) path.remove(value); }
            String bounded = text(summary, MAX_CHARS);
            limited |= bounded.length() != summary.length() || kind == Kind.LIMIT;
            if (limited || kind == Kind.ERROR || kind == Kind.UNSUPPORTED) incomplete = true;
            return new Node(label, type, bounded, kind, children, limited);
        }
        private Node error(String name, RuntimeException error) {
            nodes++; budget.nodes--; incomplete = true;
            return new Node(text(name, 256), "", text("Value unavailable: " + error.getClass().getSimpleName(), 256), Kind.ERROR, List.of(), false);
        }
        private static boolean scalar(Object value) {
            Class<?> c = value.getClass();
            return c == Boolean.class || c == Byte.class || c == Short.class || c == Integer.class
                    || c == Long.class || c == Float.class || c == Double.class || c == Character.class;
        }
    }
}
