package dev.jvmbeacon.core;

import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class JfrMemoryTest {
    @TempDir static Path directory;
    private static Path recording;
    @BeforeAll static void captureInOwnedChild() throws Exception {
        recording = directory.resolve("memory.jfr");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Xmx64m", "-XX:+UseSerialGC", "-cp", System.getProperty("beacon.fixture.classes"),
                "dev.jvmbeacon.fixture.MemoryRecordingFixture", recording.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start();
        try { assertTrue(child.waitFor(25, TimeUnit.SECONDS), "Owned recording child exceeded deadline");
            assertEquals(0, child.exitValue(), () -> { try { return Files.readString(directory.resolve("child.log")); } catch (Exception e) { return e.toString(); } });
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
    }
    @Test void realEventsMatchIndependentFieldTotalsWithoutMixingCustomOrNestedEvents() throws Exception {
        var report = JfrSummary.inspect(recording); var data = report.memory();
        assertFalse(report.partial()); assertFalse(data.gc().isEmpty()); assertFalse(data.allocations().isEmpty());
        long cycles = 0, pauses = 0, samples = 0; BigInteger weight = BigInteger.ZERO;
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                var event = file.readEvent();
                switch (event.getEventType().getName()) {
                    case "jdk.GarbageCollection" -> {
                        cycles++;
                        var match = data.gc().stream().filter(g -> g.kind() == JfrMemory.Kind.CYCLE && g.id() == event.getLong("gcId")).findFirst().orElseThrow();
                        assertEquals(event.getDuration().toNanos(), match.nanos());
                        assertEquals(event.getDuration("sumOfPauses").toNanos(), match.sumOfPauses());
                        assertEquals(event.getDuration("longestPause").toNanos(), match.longestPause());
                    }
                    case "jdk.GCPhasePause" -> pauses++;
                    case "jdk.ObjectAllocationSample" -> { samples++; weight = weight.add(BigInteger.valueOf(event.getLong("weight"))); }
                }
            }
        }
        assertTrue(cycles > 0); assertTrue(pauses > 0); assertTrue(samples > 0);
        assertEquals(cycles, data.cycles().observed()); assertEquals(pauses, data.pauses().observed()); assertEquals(samples, data.samples().observed());
        assertEquals(weight, data.totalWeight()); assertEquals(samples, data.allocations().stream().mapToLong(JfrMemory.Allocation::samples).sum());
        assertEquals(0, data.cycles().invalid() + data.pauses().invalid() + data.samples().invalid());
        assertEquals(0, data.cycles().omitted() + data.pauses().omitted() + data.samples().omitted());
        assertThrows(UnsupportedOperationException.class, () -> data.gc().clear());
        assertTrue(data.text().contains("NOT exact allocated/live bytes"));
    }
    @Test void exactWeightsDoNotOverflowAndWrongUnitsNegativeValuesAreNotZero() throws Exception {
        var builder = new JfrMemory.Builder();
        try (RecordingFile file = new RecordingFile(recording)) { while (file.hasMoreEvents()) {
            var event = file.readEvent(); String name = event.getEventType().getName();
            if (name.startsWith("beacon.Allocation")) builder.acceptAllocation(event);
            if (name.equals("beacon.InvalidGc")) builder.acceptGc(event, JfrMemory.Kind.CYCLE);
        }}
        var data = builder.finish(false, null, null);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), data.totalWeight());
        assertEquals(5, data.samples().observed()); assertEquals(2, data.samples().invalid());
        assertEquals(3, data.allocations().getFirst().samples()); assertEquals(1, data.cycles().invalid()); assertTrue(data.gc().isEmpty());
    }
    @Test void budgetsCountOmissionsAndDoNotStopAccumulatingRetainedClasses() throws Exception {
        var builder = new JfrMemory.Builder(1, 1);
        try (RecordingFile file = new RecordingFile(recording)) { while (file.hasMoreEvents()) builder.accept(file.readEvent()); }
        var data = builder.finish(true, null, null);
        assertEquals(1, data.gc().size()); assertEquals(data.cycles().observed() + data.pauses().observed() - 1,
                data.cycles().omitted() + data.pauses().omitted());
        assertTrue(data.text().contains("PARTIAL scan"));
        var classes = new JfrMemory.Builder(1, 1); var now = java.time.Instant.now();
        classes.addAllocation(1, "same.Name", 10, now); classes.addAllocation(2, "same.Name", 30, now);
        classes.addAllocation(1, "same.Name", 20, now.minusSeconds(1));
        var limited = classes.finish(false, now, now);
        assertEquals(1, limited.samples().omitted()); assertEquals(BigInteger.valueOf(30), limited.totalWeight());
        assertEquals(2, limited.allocations().getFirst().samples()); assertEquals(now.minusSeconds(1), limited.allocations().getFirst().first());
    }
    @Test void emptyEvidenceDoesNotDiagnoseZeroActivity() {
        var data = new JfrMemory.Builder().finish(false, null, null);
        assertTrue(data.gc().isEmpty()); assertTrue(data.allocations().isEmpty());
        assertTrue(data.text().contains("does not mean zero pauses or zero allocations"));
    }
}
