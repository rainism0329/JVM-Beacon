package dev.jvmbeacon.core;

import jdk.jfr.consumer.RecordingFile;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Bounded inventory and sampled-stack copies; never retains JDK recorded objects. */
public final class JfrSummary {
    private JfrSummary() { }
    public record EventCount(String name, long count) { }
    public record FileStamp(long bytes, FileTime modified, String key) {
        static FileStamp read(Path path) throws IOException {
            var a = Files.readAttributes(path, BasicFileAttributes.class);
            if (!a.isRegularFile()) throw new IOException("Choose a regular local recording file.");
            return new FileStamp(a.size(), a.lastModifiedTime(), String.valueOf(a.fileKey()));
        }
    }
    public record Report(String text, List<EventCount> types, long events, long bytes, Instant first, Instant last, boolean partial, JfrStacks.Data stacks, JfrMemory.Data memory, JfrWaits.Data waits,
                         JfrTimeRange range, long inspected, FileStamp stamp) {
        public Report { types = List.copyOf(types); }
    }
    public static String read(Path path) throws IOException { return inspect(path).text(); }
    public static Report inspect(Path path) throws IOException { return inspect(path, null, null); }
    public static Report inspect(Path path, JfrTimeRange range, FileStamp expected) throws IOException { return inspect(path, 200_000, range, expected); }

    static String read(Path path, int eventLimit) throws IOException {
        return inspect(path, eventLimit, null, null).text();
    }

    static Report inspect(Path path, int eventLimit, JfrTimeRange range, FileStamp expected) throws IOException {
        if (eventLimit < 1 || eventLimit > 200_000) throw new IllegalArgumentException("Invalid event limit.");
        FileStamp stamp = FileStamp.read(path);
        if (expected != null && !expected.equals(stamp)) throw new IOException("Local recording changed. Reopen it before selecting a range.");
        long size = stamp.bytes();
        if (size > JfrCapture.DOWNLOAD_BYTES) throw new IOException("Local JFR inventory is limited to 64 MiB files.");
        Map<String, Long> counts = new LinkedHashMap<>();
        JfrStacks.Builder stacks = new JfrStacks.Builder();
        JfrMemory.Builder memory = new JfrMemory.Builder();
        JfrWaits.Builder waits = new JfrWaits.Builder();
        long count = 0, matched = 0, otherTypes = 0;
        Instant first = null, last = null, selectedFirst = null, selectedLast = null;
        boolean truncated;
        long deadline = System.nanoTime() + 5_000_000_000L;
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents() && count < eventLimit && System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Local JFR inventory cancelled.");
                var event = file.readEvent();
                if (first == null || event.getStartTime().isBefore(first)) first = event.getStartTime();
                if (last == null || event.getEndTime().isAfter(last)) last = event.getEndTime();
                count++;
                if (range != null && !range.includes(event.getStartTime(), event.getEndTime())) continue;
                matched++;
                stacks.accept(event);
                memory.accept(event);
                waits.accept(event);
                String type = event.getEventType().getName();
                if (counts.containsKey(type) || counts.size() < 256) counts.merge(type, 1L, Long::sum);
                else otherTypes++;
                if (selectedFirst == null || event.getStartTime().isBefore(selectedFirst)) selectedFirst = event.getStartTime();
                if (selectedLast == null || event.getEndTime().isAfter(selectedLast)) selectedLast = event.getEndTime();
            }
            truncated = file.hasMoreEvents();
        }
        if (!stamp.equals(FileStamp.read(path))) throw new IOException("Local recording changed while reading. Reopen it.");
        String scope = (range == null ? "Applied time range: Full recording (inspected events only)." : range.describe())
                + "\nFile events inspected: " + count + " · Matching range: " + matched
                + "\nInspected file window: " + first + " → " + last
                + "\nAnalysis counters and retention budgets apply AFTER the time filter; file/time scan limits apply to ALL traversed events."
                + "\nEvery range change rereads the local file; no remote calls. A partial scan may miss events inside the range.\n"
                + "File stability is checked by size, modification time and file key; this is not content authentication.";
        StringBuilder result = new StringBuilder(scope).append("\n\nJFR / LOCAL EVENT INVENTORY\n\nFile: ").append(path.toAbsolutePath())
                .append("\nFile bytes: ").append(size).append("\nEvents inspected: ").append(count)
                .append(truncated ? " · PARTIAL (event/time limit)" : " · Reached end of file")
                .append("\nObserved event window: ").append(first == null ? "No events" : first + " → " + last)
                .append("\nSource: JDK RecordingFile; event names and counts only.\n")
                .append("Limits: 64 MiB file / 200,000 events / 256 named types / 5 s scan budget.\n")
                .append("Counts describe inspected events, not execution time or all JVM activity.\n")
                .append("Events may be disabled, sampled, thresholded or absent; missing events do not rule out a problem.\n")
                .append("No field values or stacks are displayed here. The original file is NOT redacted.\n\n")
                .append(String.format("%12s  %s%n", "EVENTS", "EVENT TYPE"));
        counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> result.append(String.format("%12d  %s%n", entry.getValue(), entry.getKey())));
        if (otherTypes != 0) result.append("Events from additional types: ").append(otherTypes).append('\n');
        result.append("\nUse Sampled stacks, GC & allocations and Wait analysis for bounded local analysis; JDK Mission Control offers deeper analysis.\n")
                .append("No upload or cloud account is required. This inventory does not authenticate the file's target identity.\n");
        return new Report(result.toString(), counts.entrySet().stream().map(e -> new EventCount(e.getKey(), e.getValue())).toList(),
                matched, size, first, last, truncated, stacks.finish(truncated).withScope(scope),
                memory.finish(truncated, selectedFirst, selectedLast).withScope(scope, range), waits.finish(truncated, selectedFirst, selectedLast).withScope(scope), range, count, stamp);
    }
}
