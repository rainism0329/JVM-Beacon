package dev.jvmbeacon.core;

import jdk.jfr.DataAmount;
import jdk.jfr.consumer.RecordedEvent;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Same-pass, bounded GC evidence and allocation weights. No retained JDK event objects. */
public final class JfrMemory {
    private JfrMemory() { }
    public static final int GC_LIMIT = 4096, CLASS_LIMIT = 2048, TEXT_LIMIT = 512;
    public enum Kind { CYCLE, PAUSE }
    public record Gc(Kind kind, long id, Instant start, Instant end, long nanos, String name,
                     String cause, Long sumOfPauses, Long longestPause) { }
    public record Allocation(long classId, String className, long samples, BigInteger weight,
                             Instant first, Instant last) { }
    public record Counts(long observed, long invalid, long omitted) { }
    public record Data(List<Gc> gc, List<Allocation> allocations, Counts cycles, Counts pauses,
                       Counts samples, BigInteger totalWeight, Instant first, Instant last,
                       boolean partial, String text) {
        public Data { gc = List.copyOf(gc); allocations = List.copyOf(allocations); }
    }
    static final class Builder {
        private final List<Gc> gc = new ArrayList<>();
        private record ClassKey(long id, String name) { }
        private final Map<ClassKey, Allocation> allocations = new LinkedHashMap<>();
        private final long[] cycles = new long[3], pauses = new long[3], samples = new long[3];
        private final int gcLimit, classLimit;
        Builder() { this(GC_LIMIT, CLASS_LIMIT); }
        Builder(int gcLimit, int classLimit) { this.gcLimit = gcLimit; this.classLimit = classLimit; }
        void accept(RecordedEvent event) {
            switch (event.getEventType().getName()) {
                case "jdk.GarbageCollection" -> acceptGc(event, Kind.CYCLE);
                case "jdk.GCPhasePause" -> acceptGc(event, Kind.PAUSE);
                case "jdk.ObjectAllocationSample" -> acceptAllocation(event);
                default -> { } // Never combine nested pauses, TLAB events or old-object samples.
            }
        }
        void acceptGc(RecordedEvent event, Kind kind) {
            long[] counts = kind == Kind.CYCLE ? cycles : pauses;
            counts[0]++;
            if (gc.size() >= gcLimit) { counts[2]++; return; }
            try {
                long id = event.getLong("gcId"), nanos = event.getDuration().toNanos();
                String name = text(event.getString("name"));
                String cause = kind == Kind.CYCLE ? optionalText(event, "cause") : null;
                if (id < 0 || nanos < 0 || name == null) throw new IllegalArgumentException();
                gc.add(new Gc(kind, id, event.getStartTime(), event.getEndTime(), nanos, name, cause,
                        kind == Kind.CYCLE ? duration(event, "sumOfPauses") : null,
                        kind == Kind.CYCLE ? duration(event, "longestPause") : null));
            } catch (IllegalArgumentException | ArithmeticException e) { counts[1]++; }
        }
        void acceptAllocation(RecordedEvent event) {
            samples[0]++;
            try {
                var field = event.getEventType().getField("weight");
                var amount = field == null ? null : field.getAnnotation(DataAmount.class);
                if (amount == null || !DataAmount.BYTES.equals(amount.value())) throw new IllegalArgumentException();
                var clazz = event.getClass("objectClass");
                String name = clazz == null ? null : text(clazz.getName());
                long weight = event.getLong("weight");
                if (name == null || weight < 0) throw new IllegalArgumentException();
                addAllocation(clazz.getId(), name, weight, event.getStartTime());
            } catch (IllegalArgumentException e) { samples[1]++; }
        }
        // Called only after schema validation. Kept separate for deterministic budget/overflow tests.
        void addAllocation(long id, String name, long weight, Instant time) {
            var key = new ClassKey(id, name);
            Allocation previous = allocations.get(key);
            if (previous == null && allocations.size() >= classLimit) { samples[2]++; return; }
            allocations.put(key, previous == null ? new Allocation(id, name, 1, BigInteger.valueOf(weight), time, time)
                    : new Allocation(id, name, previous.samples() + 1, previous.weight().add(BigInteger.valueOf(weight)),
                    time.isBefore(previous.first()) ? time : previous.first(), time.isAfter(previous.last()) ? time : previous.last()));
        }
        Data finish(boolean partial, Instant first, Instant last) {
            var sortedGc = gc.stream().sorted(Comparator.comparing(Gc::start).thenComparing(Gc::kind)).toList();
            var sortedAllocations = allocations.values().stream().sorted(Comparator.comparing(Allocation::weight).reversed()
                    .thenComparing(Allocation::className).thenComparingLong(Allocation::classId)).toList();
            BigInteger total = sortedAllocations.stream().map(Allocation::weight).reduce(BigInteger.ZERO, BigInteger::add);
            String report = "JFR / GC & ALLOCATION EVIDENCE\n\nSource: JDK RecordingFile · "
                    + (partial ? "PARTIAL scan" : "Reached end of file") + "\nInspected event window: "
                    + (first == null ? "No events" : first + " → " + last)
                    + "\n\nGarbageCollection: " + counts(cycles) + "\nGCPhasePause: " + counts(pauses)
                    + "\nObjectAllocationSample: " + counts(samples)
                    + "\nRetained GC events: " + sortedGc.size() + "; allocation classes: " + sortedAllocations.size()
                    + "\nRetained allocation weight: " + total + " bytes (statistical weight, NOT exact allocated/live bytes)"
                    + "\n\nGC: cycle duration can include concurrent work; it is NOT pause duration."
                    + "\nOnly top-level GCPhasePause events form the pause lane. Nested levels and parallel phases are excluded."
                    + "\nReported sumOfPauses / longestPause are per-cycle fields; unavailable fields remain missing."
                    + "\nLanes may overlap. No summation into a pause ratio, no cross-event deduplication or root-cause inference."
                    + "\n\nALLOCATION: only ObjectAllocationSample with a non-negative, byte-annotated weight is aggregated."
                    + "\nWeight estimates relative allocation pressure across many samples; it is not object size, live heap or a leak verdict."
                    + "\nShares use retained weight across ALL displayed classes, including classes hidden by search. Zero total has no share."
                    + "\nClass IDs distinguish classes within this recording, not across files or IDE class loaders."
                    + "\nNo allocation rate is inferred from first/last samples. TLAB / outside-TLAB / old-object events are not mixed in."
                    + "\n\nCOVERAGE: event settings, thresholds, sampling and collector/JDK support can hide activity."
                    + "\nNo matching events does not mean zero pauses or zero allocations. Empty lanes cover inspected events only."
                    + "\nLimits: 4,096 GC events, 2,048 allocation classes, 512 characters per name. Omission follows file order."
                    + "\nGC budget is shared across both kinds; retained classes continue accumulating after the class budget is reached."
                    + "\nFile scan: 64 MiB / 200k events / 5 s soft budget. Partial/omitted results are not a representative sample."
                    + "\nOriginal file is NOT redacted; nothing is uploaded. Recorded target identity is not authenticated.\n";
            return new Data(sortedGc, sortedAllocations, copy(cycles), copy(pauses), copy(samples), total, first, last, partial, report);
        }
        private static Counts copy(long[] values) { return new Counts(values[0], values[1], values[2]); }
        private static String counts(long[] values) { return values[0] + " observed / " + values[1] + " invalid or unsupported / " + values[2] + " omitted"; }
        private static String text(String value) { return value != null && value.length() <= TEXT_LIMIT ? value : null; }
        private static String optionalText(RecordedEvent event, String field) {
            try { return text(event.getString(field)); } catch (IllegalArgumentException e) { return null; }
        }
        private static Long duration(RecordedEvent event, String field) {
            try { long value = event.getDuration(field).toNanos(); return value < 0 ? null : value; }
            catch (IllegalArgumentException | ArithmeticException e) { return null; }
        }
    }
}
