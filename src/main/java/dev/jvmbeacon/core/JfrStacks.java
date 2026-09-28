package dev.jvmbeacon.core;

import jdk.jfr.consumer.RecordedEvent;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.*;

/** Immutable, bounded copies of sampling evidence. Never exposes RecordingFile objects to the UI. */
public final class JfrStacks {
    private JfrStacks() { }
    public enum Kind {
        JAVA("jdk.ExecutionSample"), NATIVE("jdk.NativeMethodSample");
        public final String event;
        Kind(String event) { this.event = event; }
        @Override public String toString() { return event; }
    }
    public record Frame(long classId, String className, String method, String descriptor, int line) {
        public String label() { return className + "." + method + (line > 0 ? ":" + line : ""); }
    }
    public record SampledThread(long recordedId, long javaId, String name) {
        @Override public String toString() { return name + " · Java #" + javaId + " / JFR #" + recordedId; }
    }
    public record Sample(Kind kind, SampledThread thread, Instant time, List<Frame> leafFirst, boolean truncated) {
        public Sample { leafFirst = List.copyOf(leafFirst); }
    }
    public record Counts(long observed, long missing, long omitted, long truncated) { }
    public record Data(List<Sample> samples, Map<Kind, Counts> counts, boolean partialScan) {
        public Data { samples = List.copyOf(samples); counts = Map.copyOf(counts); }
        public List<SampledThread> threads(Kind kind) {
            return samples.stream().filter(s -> s.kind == kind).map(Sample::thread).distinct()
                    .sorted(Comparator.comparing(SampledThread::name).thenComparingLong(SampledThread::recordedId)).toList();
        }
    }
    /** Inclusive counts belong to a path; recursion is not flattened into a misleading method total. */
    public record Node(Frame frame, String label, long inclusive, long self, List<Node> children) {
        public Node { children = List.copyOf(children); }
        @Override public String toString() { return inclusive + " incl · " + self + " self   " + label; }
    }
    public record View(Node root, String text, long selected, long omitted, Instant first, Instant last,
                       Kind kind, SampledThread thread, Counts counts, boolean partialScan) { }

    static final int MAX_SAMPLES = 20_000, MAX_FRAMES = 200_000, MAX_UNIQUE = 8_192, MAX_DEPTH = 128;
    static final int MAX_THREADS = 256, MAX_TEXT = 2_097_152, MAX_NODES = 8_192;
    private static final Frame OLDER = new Frame(-1, "", "", "", -1);
    static final class Builder {
        private final List<Sample> samples = new ArrayList<>();
        private final Map<Frame, Frame> frames = new HashMap<>();
        private final Map<Long, SampledThread> threads = new HashMap<>();
        private final long[][] counts = new long[Kind.values().length][4];
        private int frameRefs, textChars;

        void accept(RecordedEvent event) {
            String eventName = event.getEventType().getName();
            Kind kind = Kind.JAVA.event.equals(eventName) ? Kind.JAVA : Kind.NATIVE.event.equals(eventName) ? Kind.NATIVE : null;
            if (kind == null) return;
            acceptSample(kind, event);
        }
        // Package-private conversion seam for deterministic custom-event tests; production names are checked above.
        void acceptSample(Kind kind, RecordedEvent event) {
            long[] c = counts[kind.ordinal()]; c[0]++;
            var stack = event.getStackTrace();
            if (stack == null || stack.getFrames().isEmpty()) { c[1]++; return; }
            int depth = Math.min(stack.getFrames().size(), MAX_DEPTH);
            if (samples.size() >= MAX_SAMPLES || frameRefs + depth > MAX_FRAMES) { c[2]++; return; }
            var recordedThread = event.hasField("sampledThread") ? event.getThread("sampledThread") : null;
            long id = recordedThread == null ? -1 : recordedThread.getId();
            SampledThread thread = threads.get(id);
            if (thread == null) {
                String name = recordedThread == null ? "Unknown sampled thread" : recordedThread.getJavaName();
                if (name == null && recordedThread != null) name = recordedThread.getOSName();
                if (name == null) name = "Unnamed sampled thread";
                if (threads.size() >= MAX_THREADS || name.length() > 512) { c[2]++; return; }
                thread = new SampledThread(id, recordedThread == null ? -1 : recordedThread.getJavaThreadId(), name);
                threads.put(id, thread);
            }
            List<Frame> copied = new ArrayList<>(depth);
            for (int i = 0; i < depth; i++) {
                var frame = stack.getFrames().get(i);
                var method = frame.getMethod();
                // Native Java methods are Java frames. Unknown native symbols cannot be safely identified.
                if (!frame.isJavaFrame() || method.getType() == null || method.getName() == null || method.getDescriptor() == null) { c[2]++; return; }
                String clazz = method.getType().getName(), name = method.getName(), descriptor = method.getDescriptor();
                if (clazz.length() > 512 || name.length() > 512 || descriptor.length() > 512) { c[2]++; return; }
                Frame key = new Frame(method.getType().getId(), clazz, name, descriptor, frame.getLineNumber());
                Frame copy = frames.get(key);
                if (copy == null) {
                    int chars = clazz.length() + name.length() + descriptor.length();
                    if (frames.size() >= MAX_UNIQUE || textChars + chars > MAX_TEXT) { c[2]++; return; }
                    frames.put(key, key); copy = key; textChars += chars;
                }
                copied.add(copy);
            }
            boolean truncated = stack.isTruncated() || depth < stack.getFrames().size();
            if (truncated) c[3]++;
            samples.add(new Sample(kind, thread, event.getStartTime(), copied, truncated)); frameRefs += depth;
        }
        Data finish(boolean partial) {
            Map<Kind, Counts> result = new EnumMap<>(Kind.class);
            for (Kind k : Kind.values()) { long[] c = counts[k.ordinal()]; result.put(k, new Counts(c[0], c[1], c[2], c[3])); }
            return new Data(samples, result, partial);
        }
    }

    public static View aggregate(Data data, Kind kind, SampledThread thread) throws InterruptedIOException {
        return aggregate(data, kind, thread, MAX_NODES);
    }
    static View aggregate(Data data, Kind kind, SampledThread thread, int nodeLimit) throws InterruptedIOException {
        if (nodeLimit < 1 || nodeLimit > MAX_NODES) throw new IllegalArgumentException("Invalid node limit.");
        Mutable root = new Mutable(null);
        int nodes = 1;
        long selected = 0, omitted = 0;
        Instant first = null, last = null;
        for (Sample sample : data.samples) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stack aggregation cancelled.");
            if (sample.kind != kind || thread != null && !sample.thread.equals(thread)) continue;
            selected++;
            List<Frame> path = new ArrayList<>();
            if (sample.truncated) path.add(OLDER);
            for (int i = sample.leafFirst.size() - 1; i >= 0; i--) path.add(sample.leafFirst.get(i));
            Mutable cursor = root;
            int missing = 0;
            for (Frame frame : path) {
                cursor = cursor == null ? null : cursor.children.get(frame);
                if (cursor == null) missing++;
            }
            // Admit whole paths only: a capped child must not become a fabricated self sample on its parent.
            if (nodes + missing > nodeLimit) { omitted++; continue; }
            nodes += missing; root.inclusive++; cursor = root;
            for (Frame frame : path) { cursor = cursor.children.computeIfAbsent(frame, Mutable::new); cursor.inclusive++; }
            cursor.self++;
            if (first == null || sample.time.isBefore(first)) first = sample.time;
            if (last == null || sample.time.isAfter(last)) last = sample.time;
        }
        Counts counts = data.counts.getOrDefault(kind, new Counts(0, 0, 0, 0));
        String text = "JFR / SAMPLED STACKS\nEvent: " + kind.event + "\nThread filter: " + (thread == null ? "All retained sampled threads" : thread)
                + "\nInspected samples of this event kind: " + counts.observed + " · Missing/empty stacks: " + counts.missing
                + " · Omitted by retention/metadata limits: " + counts.omitted
                + "\nTruncated stacks retained (before thread filter): " + counts.truncated
                + "\nSelected retained samples: " + selected + " · Represented in tree: " + root.inclusive + " · Omitted by tree limit: " + omitted
                + "\nRepresented sample window: " + (first == null ? "No represented samples" : first + " → " + last)
                + "\nScan: " + (data.partialScan ? "PARTIAL; event/time limit reached" : "Reached end of file")
                + "\n\nWidth and shares count represented samples of ONE event kind, not CPU time, wall time or invocation count."
                + "\nInclusive = samples passing through this path. Self = samples ending at this frame. Recursion retains separate paths."
                + "\nJava and native method samples are never pooled. Native samples can include waiting inside native methods."
                + "\nObserved stacks do not establish all-thread or virtual-thread coverage. No samples does not mean no activity."
                + "\nFrames are grouped by recorded class ID, method descriptor and line; class IDs are local to this recording."
                + "\nOlder missing frames are marked explicitly. Retention uses file traversal order, not guaranteed chronological order."
                + "\nLimits: 20,000 retained samples / 200,000 frame references / 128 frames per stack / 256 threads / 8,192 unique frames"
                + "\n        2 Mi characters of frame metadata / 512 characters per name or descriptor / 8,192 tree nodes."
                + "\nThe original file is NOT redacted. Source filename, source version and target identity are not authenticated.\n";
        return new View(freeze(root), text, selected, omitted, first, last, kind, thread, counts, data.partialScan);
    }
    private static final class Mutable {
        final Frame frame;
        long inclusive, self;
        final Map<Frame, Mutable> children = new LinkedHashMap<>();
        Mutable(Frame frame) { this.frame = frame; }
    }
    private static Node freeze(Mutable n) {
        List<Node> children = n.children.values().stream().map(JfrStacks::freeze)
                .sorted(Comparator.comparingLong(Node::inclusive).reversed().thenComparing(Node::label)).toList();
        return new Node(n.frame == OLDER ? null : n.frame,
                n.frame == null ? "Represented samples" : n.frame == OLDER ? "[older frames not captured]" : n.frame.label(), n.inclusive, n.self, children);
    }
}
