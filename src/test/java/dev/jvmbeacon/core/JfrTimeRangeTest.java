package dev.jvmbeacon.core;

import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigInteger;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class JfrTimeRangeTest {
    @TempDir static Path directory;
    private static Path recording;
    @BeforeAll static void capture() throws Exception {
        recording = directory.resolve("range.jfr");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx64m", "-XX:+UseSerialGC",
                "-cp", System.getProperty("beacon.fixture.classes"), "dev.jvmbeacon.fixture.RangeRecordingFixture", recording.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start();
        try { assertTrue(child.waitFor(25, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), Files.readString(directory.resolve("child.log"))); }
        finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
    }
    @Test void nanosecondBoundariesDistinguishPointsFromOverlappingDurations() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z"); var r = new JfrTimeRange(t, t.plusNanos(10));
        assertTrue(r.includes(t, t)); assertFalse(r.includes(t.plusNanos(10), t.plusNanos(10)));
        assertFalse(r.includes(t.minusNanos(5), t)); assertFalse(r.includes(t.plusNanos(10), t.plusNanos(20)));
        assertTrue(r.includes(t.minusNanos(5), t.plusNanos(1))); assertTrue(r.includes(t.minusSeconds(1), t.plusSeconds(1)));
        assertFalse(r.includes(t.plusNanos(2), t.plusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new JfrTimeRange(t, t));
    }
    @Test void offsetsAreExactAndInvalidOrOutOfWindowInputsAreRejected() {
        Instant t = Instant.parse("2026-01-01T00:00:00.123456789Z");
        var r = JfrTimeRange.offsets(t, t.plusSeconds(2), "0.000000001", "2.000000001");
        assertEquals(t.plusNanos(1), r.from()); assertEquals("2.000000001", JfrTimeRange.offset(t, r.until()));
        for (String value : List.of("-1", "NaN", "1e2", "0.0000000001", "9".repeat(41)))
            assertThrows(IllegalArgumentException.class, () -> JfrTimeRange.offsets(t, t.plusSeconds(2), value, "1"));
        assertThrows(IllegalArgumentException.class, () -> JfrTimeRange.offsets(t, t.plusSeconds(2), "0", "2.1"));
        var focus = JfrTimeRange.around(t, t, t, t);
        assertEquals(t, focus.from()); assertEquals(t.plusNanos(1), focus.until());
    }
    @Test void allViewsUseRawEventsInTheSamePhaseAndFullRecordingRestoresTotals() throws Exception {
        var full = JfrSummary.inspect(recording); Map<String, Instant> phases = new HashMap<>();
        try (var file = new RecordingFile(recording)) { while (file.hasMoreEvents()) {
            var e = file.readEvent(); if (e.getEventType().getName().equals("beacon.RangePhase")) phases.put(e.getString("phase"), e.getStartTime());
        }}
        var range = new JfrTimeRange(phases.get("B start"), phases.get("B end"));
        var scoped = JfrSummary.inspect(recording, range, full.stamp());
        Map<String, Long> counts = new HashMap<>(); BigInteger weight = BigInteger.ZERO, waits = BigInteger.ZERO;
        try (var file = new RecordingFile(recording)) { while (file.hasMoreEvents()) {
            var e = file.readEvent(); var start = e.getStartTime(); var end = e.getEndTime();
            boolean match = start.equals(end) ? start.compareTo(range.from()) >= 0 && start.compareTo(range.until()) < 0
                    : start.compareTo(range.until()) < 0 && end.compareTo(range.from()) > 0;
            if (!match) continue;
            String name = e.getEventType().getName(); counts.merge(name, 1L, Long::sum);
            if (name.equals("jdk.ObjectAllocationSample")) weight = weight.add(BigInteger.valueOf(e.getLong("weight")));
            if (Set.of("jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark").contains(name)) waits = waits.add(BigInteger.valueOf(e.getDuration().toNanos()));
        }}
        assertFalse(scoped.partial()); assertTrue(scoped.events() < full.events()); assertEquals(full.events(), scoped.inspected());
        assertEquals(counts, scoped.types().stream().collect(java.util.stream.Collectors.toMap(JfrSummary.EventCount::name, JfrSummary.EventCount::count)));
        assertEquals(weight, scoped.memory().totalWeight()); assertTrue(weight.signum() > 0);
        assertEquals(waits, scoped.waits().events().stream().map(e -> BigInteger.valueOf(e.nanos())).reduce(BigInteger.ZERO, BigInteger::add));
        assertEquals(counts.get("jdk.GarbageCollection"), scoped.memory().cycles().observed());
        assertEquals(counts.getOrDefault("jdk.ExecutionSample", 0L).longValue(), scoped.stacks().counts().get(JfrStacks.Kind.JAVA).observed());
        assertTrue(scoped.memory().text().contains(range.describe())); assertTrue(scoped.waits().text().contains(range.describe()));
        assertTrue(JfrStacks.aggregate(scoped.stacks(), JfrStacks.Kind.JAVA, null).text().contains(range.describe()));
        assertEquals(full.memory().totalWeight(), JfrSummary.inspect(recording, null, full.stamp()).memory().totalWeight());
    }
    @Test void partialTraversalBudgetCountsExcludedEventsAndEmptyRangeStaysExplicit() throws Exception {
        var full = JfrSummary.inspect(recording); var range = new JfrTimeRange(full.last().plusSeconds(1), full.last().plusSeconds(2));
        var partial = JfrSummary.inspect(recording, 1, range, full.stamp());
        assertTrue(partial.partial()); assertEquals(1, partial.inspected()); assertEquals(0, partial.events());
        var empty = JfrSummary.inspect(recording, range, full.stamp());
        assertFalse(empty.partial()); assertTrue(empty.memory().gc().isEmpty()); assertTrue(empty.waits().events().isEmpty()); assertTrue(empty.stacks().samples().isEmpty());
        assertEquals(BigInteger.ZERO, empty.memory().totalWeight()); assertTrue(empty.text().contains("Matching range: 0"));
    }
    @Test void changedFileAndCancellationNeverReturnMixedResults() throws Exception {
        Path copy = directory.resolve("changed.jfr"); Files.copy(recording, copy);
        var report = JfrSummary.inspect(copy); Files.write(copy, new byte[]{1}, StandardOpenOption.APPEND);
        var error = assertThrows(java.io.IOException.class, () -> JfrSummary.inspect(copy, null, report.stamp()));
        assertTrue(error.getMessage().contains("changed"));
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.InterruptedIOException.class, () -> JfrSummary.inspect(recording)); } finally { Thread.interrupted(); }
    }
}
