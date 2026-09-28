package dev.jvmbeacon.core;

import jdk.jfr.consumer.RecordingFile;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Bounded inventory and sampled-stack copies; never retains JDK recorded objects. */
public final class JfrSummary {
    private JfrSummary() { }
    public record EventCount(String name, long count) { }
    public record Report(String text, List<EventCount> types, long events, long bytes, Instant first, Instant last, boolean partial, JfrStacks.Data stacks, JfrMemory.Data memory, JfrWaits.Data waits) {
        public Report { types = List.copyOf(types); }
    }
    public static String read(Path path) throws IOException { return inspect(path).text(); }
    public static Report inspect(Path path) throws IOException { return inspect(path, 200_000); }

    static String read(Path path, int eventLimit) throws IOException {
        return inspect(path, eventLimit).text();
    }

    private static Report inspect(Path path, int eventLimit) throws IOException {
        if (eventLimit < 1 || eventLimit > 200_000) throw new IllegalArgumentException("Invalid event limit.");
        long size = Files.size(path);
        if (size > JfrCapture.DOWNLOAD_BYTES) throw new IOException("Local JFR inventory is limited to 64 MiB files.");
        Map<String, Long> counts = new LinkedHashMap<>();
        JfrStacks.Builder stacks = new JfrStacks.Builder();
        JfrMemory.Builder memory = new JfrMemory.Builder();
        JfrWaits.Builder waits = new JfrWaits.Builder();
        long count = 0, otherTypes = 0;
        Instant first = null, last = null;
        boolean truncated;
        long deadline = System.nanoTime() + 5_000_000_000L;
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents() && count < eventLimit && System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Local JFR inventory cancelled.");
                var event = file.readEvent();
                stacks.accept(event);
                memory.accept(event);
                waits.accept(event);
                String type = event.getEventType().getName();
                if (counts.containsKey(type) || counts.size() < 256) counts.merge(type, 1L, Long::sum);
                else otherTypes++;
                if (first == null || event.getStartTime().isBefore(first)) first = event.getStartTime();
                if (last == null || event.getEndTime().isAfter(last)) last = event.getEndTime();
                count++;
            }
            truncated = file.hasMoreEvents();
        }
        StringBuilder result = new StringBuilder("JFR / LOCAL EVENT INVENTORY\n\nFile: ").append(path.toAbsolutePath())
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
                count, size, first, last, truncated, stacks.finish(truncated), memory.finish(truncated, first, last), waits.finish(truncated, first, last));
    }
}
