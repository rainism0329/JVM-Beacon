package dev.jvmbeacon.core;

import dev.jvmbeacon.core.JmxClient.Identity;
import dev.jvmbeacon.core.JmxClient.ThreadDump;
import dev.jvmbeacon.core.JmxClient.ThreadRecord;
import dev.jvmbeacon.core.SnapshotStore.Snapshot;

import java.time.Instant;
import java.util.*;

/** Pure comparison of bounded observations; never queries a JVM or infers a thread's lifecycle. */
public final class ThreadComparison {
    public static final int MAX_DIFFERENCES = 200;
    public static final int MAX_TEXT = 32_768;
    public static final int MAX_FIELD_TEXT = 256;

    public enum Status { COMPARABLE, MISSING_IDENTITY, DIFFERENT_JVM, MISSING_CAPTURE, INVALID_CAPTURE }
    public enum Kind { NEWLY_OBSERVED, NO_LONGER_OBSERVED, CHANGED }
    public enum Field { NAME, STATE, STACK, LOCK }

    public record Window(long captureStart, long captureEnd, int capturedThreads, boolean truncated) { }
    public record Counts(int newlyObserved, int noLongerObserved, int matchedIds, int stateChanges,
                         int stackChanges, int lockChanges, int nameChanges, int unchanged) { }
    /** Strings are capped and escaped; no original ThreadRecord or arbitrary server value is retained. */
    public record ThreadView(long id, String name, String state, String topFrame, String lockName, String lockOwnerName, Long lockOwnerId) { }
    /** One-based frame depth. A missing frame is an observation about the captured stack only. */
    public record FrameChange(int depth, String before, String after) { }
    public record Difference(long id, Kind kind, Set<Field> fields, ThreadView before, ThreadView after, FrameChange frameChange) {
        public Difference { fields = Set.copyOf(fields); }
    }
    /** counts is null when comparison is unavailable: unavailable must not be displayed as zero. */
    public record Report(Status status, String explanation, Window before, Window after, Counts counts,
                         List<Difference> differences, boolean differencesTruncated, boolean inputTruncated,
                         boolean stackDepthLimited, boolean chronological) {
        public Report { differences = List.copyOf(differences); }
        public boolean comparable() { return status == Status.COMPARABLE; }
    }

    private static final String LIMITATIONS = "Only captured platform threads are compared; virtual threads are not covered. Thread lists and stacks are not captured atomically. "
            + "Newly observed does not mean newly created; no longer observed does not mean terminated. Truncation or exit during collection may affect observations. "
            + "Matching IDs are candidates only: IDs may be reused and threads renamed, so observations do not prove continuous thread identity. "
            + "Identical states or stacks do not prove continuous blocking or deadlock, and do not rule out intervening changes. "
            + "Lock comparisons use names and owner IDs when captured on both sides. Missing owner IDs (including version 1 files) are unknown, not a change. Identical lock names do not prove lock identity.";

    private ThreadComparison() { }

    public static Report compare(Identity beforeIdentity, ThreadDump before, Identity afterIdentity, ThreadDump after) {
        Window first = window(before), second = window(after);
        ConnectionIdentity.Match identityMatch = ConnectionIdentity.compare(beforeIdentity, afterIdentity);
        if (identityMatch == ConnectionIdentity.Match.FIRST || identityMatch == ConnectionIdentity.Match.INCOMPLETE)
            return unavailable(Status.MISSING_IDENTITY, "Target identity is missing or incomplete; thread IDs cannot be compared.", first, second);
        if (identityMatch == ConnectionIdentity.Match.CHANGED) {
            boolean sameRuntime = beforeIdentity.runtimeName().equals(afterIdentity.runtimeName());
            return unavailable(Status.DIFFERENT_JVM, sameRuntime
                    ? "Runtime labels match, but start times or VM details differ. A restart or PID reuse is possible; thread IDs cannot be compared."
                    : "Target JVM identities differ; thread IDs cannot be compared.", first, second);
        }
        if (before == null || after == null)
            return unavailable(Status.MISSING_CAPTURE, "At least one snapshot has no thread capture. Thread changes cannot be determined; not captured does not mean zero threads.", first, second);
        if (!validWindow(before) || !validWindow(after))
            return unavailable(Status.INVALID_CAPTURE, "A capture window is invalid; thread changes cannot be interpreted reliably.", first, second);

        SortedMap<Long, ThreadRecord> left = index(before), right = index(after);
        if (left == null || right == null)
            return unavailable(Status.INVALID_CAPTURE, "The processed records contain duplicate or invalid thread IDs or states; comparison by ID is unavailable.", first, second);
        boolean stackLimited = hasLimitedStack(left) || hasLimitedStack(right);
        boolean inputTruncated = first.truncated() || second.truncated();
        SortedSet<Long> ids = new TreeSet<>(left.keySet()); ids.addAll(right.keySet());
        List<Difference> details = new ArrayList<>();
        int appeared = 0, absent = 0, matched = 0, states = 0, stacks = 0, locks = 0, names = 0, unchanged = 0, changedRows = 0;
        for (long id : ids) {
            ThreadRecord previous = left.get(id), current = right.get(id);
            Kind kind;
            Set<Field> changes = EnumSet.noneOf(Field.class);
            FrameChange frame = null;
            if (previous == null) { appeared++; kind = Kind.NEWLY_OBSERVED; }
            else if (current == null) { absent++; kind = Kind.NO_LONGER_OBSERVED; }
            else {
                matched++;
                if (!Objects.equals(previous.name(), current.name())) { changes.add(Field.NAME); names++; }
                if (!Objects.equals(previous.state(), current.state())) { changes.add(Field.STATE); states++; }
                frame = firstFrameChange(previous.frames(), current.frames());
                if (frame != null) { changes.add(Field.STACK); stacks++; }
                if (!empty(previous.lockName()).equals(empty(current.lockName()))
                        || !empty(previous.lockOwnerName()).equals(empty(current.lockOwnerName()))
                        || previous.lockOwnerId() != null && current.lockOwnerId() != null
                        && !previous.lockOwnerId().equals(current.lockOwnerId())) { changes.add(Field.LOCK); locks++; }
                if (changes.isEmpty()) { unchanged++; continue; }
                kind = Kind.CHANGED;
            }
            changedRows++;
            if (details.size() < MAX_DIFFERENCES) details.add(new Difference(id, kind, changes, view(previous), view(current), frame));
        }
        Counts counts = new Counts(appeared, absent, matched, states, stacks, locks, names, unchanged);
        return new Report(Status.COMPARABLE, "Runtime name, start time, VM name and version match. Captured records are compared under this identity assumption.",
                first, second, counts, details, changedRows > MAX_DIFFERENCES, inputTruncated, stackLimited,
                after.captureStart() > before.captureEnd());
    }

    public static String describeComparison(Snapshot before, Snapshot after) {
        return describe(compare(before == null ? null : before.identity(), before == null ? null : before.threads(),
                after == null ? null : after.identity(), after == null ? null : after.threads()));
    }

    public static String describe(Report report) {
        Objects.requireNonNull(report, "report");
        BoundedText text = new BoundedText();
        text.append("Thread snapshot comparison (A → B)\n" + report.explanation() + "\n");
        text.append("A thread capture window: " + describeWindow(report.before()) + "\n");
        text.append("B thread capture window: " + describeWindow(report.after()) + "\n");
        if (!report.comparable()) {
            text.append("Thread changes: cannot be determined; change counts are unavailable.\n" + LIMITATIONS + "\n");
            return text.toString();
        }
        Counts counts = report.counts();
        text.append("Captured scope: newly observed " + counts.newlyObserved() + ", no longer observed " + counts.noLongerObserved()
                + ", matching-ID candidates " + counts.matchedIds() + ".\n");
        text.append("Changes among matching-ID candidates: state " + counts.stateChanges() + ", stack " + counts.stackChanges() + ", lock " + counts.lockChanges()
                + ", name " + counts.nameChanges() + "; no observed difference in compared fields " + counts.unchanged() + ". Change categories may overlap.\n");
        if (report.inputTruncated()) text.append("Limited scope: at least one list was truncated or exceeds the 512-record limit per side. Counts cover only the processed subset.\n");
        if (report.stackDepthLimited()) text.append("Limited stack depth: at least one stack reaches the depth limit. Only the first 64 frames are compared; deeper calls are unknown.\n");
        if (!report.chronological()) text.append("Capture order: B is not strictly later than the complete A window. Windows may overlap, match, be reversed, or reflect clock changes. The arrow indicates selection order, not a time trend.\n");
        text.append(LIMITATIONS + "\n");
        if (report.differencesTruncated()) text.append("Difference details retain only the first " + MAX_DIFFERENCES + " records sorted by ID; counts above include all processed records.\n");
        if (report.differences().isEmpty()) text.append("No differences were observed in the compared fields.\n");
        for (Difference difference : report.differences()) {
            if (text.full) break;
            text.append("\n#" + difference.id() + " ");
            if (difference.kind() == Kind.NEWLY_OBSERVED) text.append("Newly observed · " + difference.after().name() + " · " + difference.after().state() + "\nTop frame: " + difference.after().topFrame() + "\n");
            else if (difference.kind() == Kind.NO_LONGER_OBSERVED) text.append("No longer observed · " + difference.before().name() + " · A state " + difference.before().state() + "\n");
            else {
                text.append("Matching-ID candidate · " + difference.before().name() + " → " + difference.after().name() + "\n");
                if (difference.fields().contains(Field.NAME)) text.append("Name changed; renaming or ID reuse is possible. Identity continuity is unconfirmed.\n");
                if (difference.fields().contains(Field.STATE)) text.append("State: " + difference.before().state() + " → " + difference.after().state() + "\n");
                if (difference.fields().contains(Field.STACK)) text.append("First differing captured frame (depth " + difference.frameChange().depth() + "):\nA " + difference.frameChange().before() + "\nB " + difference.frameChange().after() + "\n");
                if (difference.fields().contains(Field.LOCK)) text.append("Lock / owner:\nA " + lockLabel(difference.before()) + "\nB " + lockLabel(difference.after()) + "\n");
            }
        }
        return text.toString();
    }

    private static Report unavailable(Status status, String explanation, Window before, Window after) {
        return new Report(status, explanation, before, after, null, List.of(), false,
                before != null && before.truncated() || after != null && after.truncated(), false, false);
    }
    private static boolean validWindow(ThreadDump dump) { return dump.captureStart() >= 0 && dump.captureEnd() >= dump.captureStart(); }
    private static Window window(ThreadDump dump) {
        return dump == null ? null : new Window(dump.captureStart(), dump.captureEnd(), Math.min(JmxClient.MAX_THREADS, dump.threads().size()), dump.truncated() || dump.threads().size() > JmxClient.MAX_THREADS);
    }
    private static SortedMap<Long, ThreadRecord> index(ThreadDump dump) {
        SortedMap<Long, ThreadRecord> result = new TreeMap<>();
        for (int i = 0; i < Math.min(JmxClient.MAX_THREADS, dump.threads().size()); i++) {
            ThreadRecord thread = dump.threads().get(i);
            if (thread.id() <= 0 || thread.state() == null || thread.name() == null
                    || thread.lockOwnerId() != null && (thread.lockOwnerId() == 0 || thread.lockOwnerId() < -1)) return null;
            try { Thread.State.valueOf(thread.state()); } catch (IllegalArgumentException failure) { return null; }
            if (result.putIfAbsent(thread.id(), thread) != null) return null;
        }
        return result;
    }
    private static boolean hasLimitedStack(Map<Long, ThreadRecord> threads) {
        return threads.values().stream().anyMatch(thread -> thread.frames().size() >= JmxClient.MAX_STACK_DEPTH);
    }
    private static FrameChange firstFrameChange(List<StackTraceElement> before, List<StackTraceElement> after) {
        int length = Math.min(JmxClient.MAX_STACK_DEPTH, Math.max(before.size(), after.size()));
        for (int index = 0; index < length; index++) {
            StackTraceElement first = index < before.size() ? before.get(index) : null;
            StackTraceElement second = index < after.size() ? after.get(index) : null;
            // Version 1 snapshots retain these four fields, not module/classloader metadata.
            // Comparing StackTraceElement.equals would falsely report changes after save/reopen.
            if (!sameFrame(first, second)) return new FrameChange(index + 1, frameText(first), frameText(second));
        }
        return null;
    }
    private static boolean sameFrame(StackTraceElement before, StackTraceElement after) {
        if (before == null || after == null) return before == after;
        return before.getLineNumber() == after.getLineNumber() && before.getClassName().equals(after.getClassName())
                && before.getMethodName().equals(after.getMethodName()) && Objects.equals(before.getFileName(), after.getFileName());
    }
    private static ThreadView view(ThreadRecord thread) {
        return thread == null ? null : new ThreadView(thread.id(), bounded(thread.name()), bounded(thread.state()),
                thread.frames().isEmpty() ? "No frames captured" : frameText(thread.frames().getFirst()), bounded(empty(thread.lockName())), bounded(empty(thread.lockOwnerName())), thread.lockOwnerId());
    }
    private static String frameText(StackTraceElement frame) {
        if (frame == null) return "No captured frame at this depth";
        return bounded(bounded(frame.getClassName()) + "." + bounded(frame.getMethodName()) + "(" + bounded(frame.getFileName()) + ":" + frame.getLineNumber() + ")");
    }
    private static String lockLabel(ThreadView view) { return (view.lockName().isEmpty() ? "No lock reported" : view.lockName()) + " / " + (view.lockOwnerName().isEmpty() ? "No owner reported" : view.lockOwnerName()) + " / " + LockChains.ownerLabel(view.lockOwnerId()); }
    private static String empty(String value) { return value == null ? "" : value; }
    private static String bounded(String value) {
        if (value == null) return "—";
        StringBuilder result = new StringBuilder(Math.min(MAX_FIELD_TEXT, value.length()));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String token = switch (c) { case '\n' -> "\\n"; case '\r' -> "\\r"; case '\t' -> "\\t"; default -> Character.isISOControl(c) ? "?" : Character.toString(c); };
            if (result.length() + token.length() > MAX_FIELD_TEXT - 1) { result.append('…'); break; }
            result.append(token);
        }
        return result.toString();
    }
    private static String describeWindow(Window window) {
        if (window == null) return "Not captured";
        return Instant.ofEpochMilli(window.captureStart()) + " — " + Instant.ofEpochMilli(window.captureEnd()) + " (UTC), " + window.capturedThreads() + " records processed" + (window.truncated() ? ", list truncated" : "");
    }
    private static final class BoundedText {
        private static final String MARKER = "\n… Report text truncated; the summary counts above still cover the full processed scope.\n";
        private final StringBuilder value = new StringBuilder();
        private boolean full;
        private void append(String text) {
            if (full) return;
            int remaining = MAX_TEXT - MARKER.length() - value.length();
            if (text.length() <= remaining) value.append(text);
            else { value.append(text, 0, remaining).append(MARKER); full = true; }
        }
        @Override public String toString() { return value.toString(); }
    }
}
