package dev.jvmbeacon.core;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Bounded evidence for completed wait events, not a lock ownership graph or CPU attribution. */
public final class JfrWaits {
    private JfrWaits() { }
    public enum Kind {
        ENTER("jdk.JavaMonitorEnter", "Monitor entry"), WAIT("jdk.JavaMonitorWait", "Object.wait"), PARK("jdk.ThreadPark", "Park");
        public final String event, label;
        Kind(String event, String label) { this.event = event; this.label = label; }
        @Override public String toString() { return label; }
    }
    public record ThreadRef(long recordedId, long javaId, String name, Boolean virtual) {
        @Override public String toString() { return name + " · Java #" + javaId + " / JFR #" + recordedId
                + (virtual == null ? " · Kind unreported" : virtual ? " · Virtual" : " · Platform/native"); }
    }
    public record Target(long classId, String name) { }
    public enum StackState { RECORDED, NOT_RECORDED, TRUNCATED, OMITTED }
    public record Event(Kind kind, ThreadRef thread, Target target, ThreadRef relatedThread,
                        Instant start, Instant end, long nanos, List<JfrStacks.Frame> stack, StackState stackState) {
        public Event { stack = List.copyOf(stack); }
        public String evidence() {
            return "JFR / WAIT EVENT\nSource: " + kind.event + "\nEvent thread: " + thread
                    + "\nTarget class: " + target.name + " · Recorded class #" + target.classId
                    + "\nWindow: " + start + " → " + end + "\nDuration: " + nanos + " ns"
                    + (kind == Kind.PARK ? "\nPark wake-up reason and owner are not established by this event."
                    : "\n" + (kind == Kind.ENTER ? "Previous owner" : "Notifier") + ": "
                    + (relatedThread == null ? "Not reported" : relatedThread) + " (historical field, NOT current ownership)")
                    + "\nStack: " + stackState + " · " + stack.size() + " retained leaf-first frames"
                    + "\n" + meaning(kind) + "\nNo object identity, live wait graph or deadlock conclusion is inferred.\n";
        }
    }
    public record Counts(long observed, long invalid, long omitted, long missingStacks, long omittedStacks, long truncatedStacks) { }
    public record Data(List<Event> events, Map<Kind, Counts> counts, boolean partial, String text) {
        public Data { events = List.copyOf(events); counts = Map.copyOf(counts); }
        Data withScope(String scope) { return new Data(events, counts, partial, scope + "\n\n" + text); }
    }
    public record Key(Kind kind, Target target, JfrStacks.Frame leaf) { }
    public record Hotspot(Key key, List<Event> events, BigInteger totalNanos, long maxNanos) {
        public Hotspot { events = List.copyOf(events); }
    }
    public record View(List<Event> events, List<Hotspot> hotspots, String text) {
        public View { events = List.copyOf(events); hotspots = List.copyOf(hotspots); }
    }
    static final int MAX_EVENTS = 4096, MAX_REFS = 65536, MAX_DEPTH = 64, MAX_UNIQUE = 4096, MAX_TEXT = 1_048_576;
    static final class Builder {
        private final List<Event> events = new ArrayList<>();
        private final Map<JfrStacks.Frame, JfrStacks.Frame> frames = new HashMap<>();
        private final long[][] counts = new long[Kind.values().length][6];
        private final int eventLimit, refLimit;
        private int refs, symbolChars, metadataChars;
        Builder() { this(MAX_EVENTS, MAX_REFS); }
        Builder(int events, int refs) { eventLimit = events; refLimit = refs; }
        void accept(RecordedEvent event) {
            for (Kind kind : Kind.values()) if (kind.event.equals(event.getEventType().getName())) { accept(kind, event); break; }
        }
        // Package-private for custom schema/missing-stack tests; production uses only the event allowlist.
        void accept(Kind kind, RecordedEvent event) {
            long[] c = counts[kind.ordinal()]; c[0]++;
            if (events.size() >= eventLimit) { c[2]++; return; }
            try {
                long nanos = event.getDuration().toNanos();
                if (nanos < 0) throw new IllegalArgumentException();
                ThreadRef thread = thread(event.getThread());
                var clazz = event.getClass(kind == Kind.PARK ? "parkedClass" : "monitorClass");
                Target target = clazz == null ? new Target(-1, "Not reported") : new Target(clazz.getId(), bounded(clazz.getName()));
                ThreadRef related = null;
                if (kind != Kind.PARK) {
                    String field = kind == Kind.ENTER ? "previousOwner" : "notifier";
                    var value = event.hasField(field) ? event.getThread(field) : null;
                    if (value != null) related = thread(value);
                }
                int chars = thread.name.length() + target.name.length() + (related == null ? 0 : related.name.length());
                if (metadataChars + chars > MAX_TEXT) { c[2]++; return; }
                StackCopy stack = copyStack(event);
                if (stack.state == StackState.NOT_RECORDED) c[3]++;
                if (stack.state == StackState.OMITTED) c[4]++;
                if (stack.state == StackState.TRUNCATED) c[5]++;
                events.add(new Event(kind, thread, target, related, event.getStartTime(), event.getEndTime(), nanos, stack.frames, stack.state));
                metadataChars += chars;
            } catch (IllegalArgumentException | ArithmeticException e) { c[1]++; }
        }
        private record StackCopy(List<JfrStacks.Frame> frames, StackState state) { }
        private StackCopy copyStack(RecordedEvent event) {
            var stack = event.getStackTrace();
            if (stack == null || stack.getFrames().isEmpty()) return new StackCopy(List.of(), StackState.NOT_RECORDED);
            int depth = Math.min(MAX_DEPTH, stack.getFrames().size());
            if (refs + depth > refLimit) return new StackCopy(List.of(), StackState.OMITTED);
            var copies = new ArrayList<JfrStacks.Frame>(depth);
            for (int i = 0; i < depth; i++) {
                var frame = stack.getFrames().get(i); var method = frame.getMethod();
                if (!frame.isJavaFrame() || method == null || method.getType() == null) return new StackCopy(List.of(), StackState.OMITTED);
                try {
                    var key = new JfrStacks.Frame(method.getType().getId(), bounded(method.getType().getName()),
                            bounded(method.getName()), bounded(method.getDescriptor()), frame.getLineNumber());
                    var copy = frames.get(key);
                    if (copy == null) {
                        int chars = key.className().length() + key.method().length() + key.descriptor().length();
                        if (frames.size() >= MAX_UNIQUE || symbolChars + chars > MAX_TEXT) return new StackCopy(List.of(), StackState.OMITTED);
                        frames.put(key, key); copy = key; symbolChars += chars;
                    }
                    copies.add(copy);
                } catch (IllegalArgumentException e) { return new StackCopy(List.of(), StackState.OMITTED); }
            }
            refs += depth;
            return new StackCopy(copies, stack.isTruncated() || depth < stack.getFrames().size() ? StackState.TRUNCATED : StackState.RECORDED);
        }
        Data finish(boolean partial, Instant first, Instant last) {
            Map<Kind, Counts> stats = new EnumMap<>(Kind.class);
            StringBuilder text = new StringBuilder("JFR / WAIT COVERAGE\n\nSource: JDK RecordingFile · ")
                    .append(partial ? "PARTIAL scan" : "Reached end of file").append("\nInspected event window: ").append(first).append(" → ").append(last);
            for (Kind k : Kind.values()) {
                long[] c = counts[k.ordinal()]; stats.put(k, new Counts(c[0], c[1], c[2], c[3], c[4], c[5]));
                text.append("\n").append(k.event).append(": ").append(c[0]).append(" observed / ").append(c[1]).append(" invalid or unsupported / ")
                        .append(c[2]).append(" omitted; stacks: ").append(c[3]).append(" unrecorded / ").append(c[4]).append(" omitted / ").append(c[5]).append(" truncated");
            }
            text.append("\n\n").append(meaning(Kind.ENTER)).append('\n').append(meaning(Kind.WAIT)).append('\n').append(meaning(Kind.PARK))
                    .append("\nOnly completed, inspected events are represented. Events disabled, below threshold, unfinished or lost may be absent.")
                    .append("\nMissing events never rule out contention or deadlock. Current recording settings cannot reconstruct past coverage.")
                    .append("\nEvent thread uses eventThread, not sampledThread. Virtual/platform labels use recorded metadata; not an all-thread census.")
                    .append("\nVirtual-thread parks may not produce ThreadPark events on some JDKs (observed with ordinary parks on Corretto 21).")
                    .append("\nPrevious owner / notifier are historical fields, not current owners. Park does not supply an owner or wake-up reason.")
                    .append("\nObject addresses are not retained. Same class is NOT the same lock instance; no wait-for graph is inferred.")
                    .append("\nHotspots group event kind + target class ID/name + recorded leaf frame. Different callers may share a leaf.")
                    .append("\nDuration sum overlaps across threads and can exceed wall time. It is NOT CPU time, lock hold time or a wall-time percentage.")
                    .append("\nStack duration is NOT attributed to individual frames. Source candidates do not verify source version/class loader.")
                    .append("\n\nLimits: 4,096 events / 65,536 frame refs / 64 frames per stack / 4,096 unique frames;")
                    .append("\n1 Mi characters each for event and frame metadata / 512 characters per name or descriptor.")
                    .append("\nAll kinds share budgets; retention follows file traversal, not chronological order or representative sampling.")
                    .append("\nFile scan: 64 MiB / 200k events / 5 s soft checks. Original file is NOT redacted; nothing is uploaded.\n");
            return new Data(events.stream().sorted(Comparator.comparing(Event::start)).toList(), stats, partial, text.toString());
        }
        private static ThreadRef thread(RecordedThread thread) {
            if (thread == null) return new ThreadRef(-1, -1, "Not reported", null);
            String name = thread.getJavaName(); if (name == null) name = thread.getOSName();
            return new ThreadRef(thread.getId(), thread.getJavaThreadId(), name == null ? "Unnamed thread" : bounded(name),
                    thread.hasField("virtual") ? thread.getBoolean("virtual") : null);
        }
        private static String bounded(String text) { if (text == null || text.length() > 512) throw new IllegalArgumentException(); return text; }
    }
    public static View filter(Data data, Kind kind, String query) throws InterruptedIOException {
        if (query.length() > 512) throw new IllegalArgumentException("Search is limited to 512 characters.");
        String find = query.strip().toLowerCase(Locale.ROOT);
        List<Event> selected = new ArrayList<>(); Map<Key, List<Event>> groups = new LinkedHashMap<>();
        for (Event e : data.events) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Wait analysis cancelled.");
            if (kind != null && kind != e.kind) continue;
            if (!find.isEmpty() && !e.thread.toString().toLowerCase(Locale.ROOT).contains(find) && !e.target.name.toLowerCase(Locale.ROOT).contains(find)
                    && e.stack.stream().noneMatch(f -> f.label().toLowerCase(Locale.ROOT).contains(find))) continue;
            selected.add(e);
            groups.computeIfAbsent(new Key(e.kind, e.target, e.stack.isEmpty() ? null : e.stack.getFirst()), k -> new ArrayList<>()).add(e);
        }
        List<Hotspot> hotspots = new ArrayList<>();
        groups.forEach((key, events) -> hotspots.add(new Hotspot(key, events,
                events.stream().map(e -> BigInteger.valueOf(e.nanos)).reduce(BigInteger.ZERO, BigInteger::add), events.stream().mapToLong(Event::nanos).max().orElse(0))));
        hotspots.sort(Comparator.comparing(Hotspot::totalNanos).reversed());
        return new View(selected, hotspots, "Active kind: " + (kind == null ? "All (kept in separate groups)" : kind.event)
                + "\nSearch: " + (find.isEmpty() ? "None" : query.strip()) + "\nSelected retained events: " + selected.size()
                + " · Hotspots: " + hotspots.size() + "\n\n" + data.text);
    }
    private static String meaning(Kind kind) { return switch (kind) {
        case ENTER -> "Monitor entry: recorded contention while entering a Java monitor.";
        case WAIT -> "Object.wait: monitor condition waiting; duration alone does not isolate notification or monitor reacquisition.";
        case PARK -> "Park: scheduling/synchronization wait, which can be normal idle work; not automatically lock contention.";
    }; }
}
