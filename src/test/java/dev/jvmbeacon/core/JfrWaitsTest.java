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

class JfrWaitsTest {
    @TempDir static Path directory;
    private static Path recording;
    @BeforeAll static void recordOwnedChild() throws Exception {
        recording = directory.resolve("waits.jfr");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx64m",
                "-cp", System.getProperty("beacon.fixture.classes"), "dev.jvmbeacon.fixture.WaitRecordingFixture", recording.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start();
        try { assertTrue(child.waitFor(25, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), Files.readString(directory.resolve("child.log"))); }
        finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
    }
    @Test void realMonitorConditionAndPlatformVirtualParkEvidenceMatchesRawEvents() throws Exception {
        var data = JfrSummary.inspect(recording).waits(); assertFalse(data.partial());
        Map<JfrWaits.Kind, BigInteger> raw = new EnumMap<>(JfrWaits.Kind.class); long rawVirtualParks = 0;
        var probe = new JfrWaits.Builder();
        try (RecordingFile file = new RecordingFile(recording)) { while (file.hasMoreEvents()) {
            var event = file.readEvent();
            if (event.getEventType().getName().equals("jdk.ThreadPark") && event.getThread() != null && event.getThread().isVirtual()) rawVirtualParks++;
            if (event.getEventType().getName().equals("beacon.VirtualWaitProbe")) probe.accept(JfrWaits.Kind.PARK, event);
            for (var kind : JfrWaits.Kind.values()) if (kind.event.equals(event.getEventType().getName()))
                raw.merge(kind, BigInteger.valueOf(event.getDuration().toNanos()), BigInteger::add);
        }}
        for (var kind : JfrWaits.Kind.values()) {
            var view = JfrWaits.filter(data, kind, "");
            assertFalse(view.events().isEmpty()); assertEquals(raw.get(kind), view.hotspots().stream().map(JfrWaits.Hotspot::totalNanos).reduce(BigInteger.ZERO, BigInteger::add));
            assertEquals(0, data.counts().get(kind).invalid() + data.counts().get(kind).omitted());
        }
        var enter = JfrWaits.filter(data, JfrWaits.Kind.ENTER, "beacon-wait-entrant").events().getFirst();
        assertEquals("beacon-wait-owner", enter.relatedThread().name()); assertTrue(enter.nanos() > 0);
        assertTrue(enter.stack().stream().anyMatch(f -> f.method().equals("enterGate")));
        assertFalse(JfrWaits.filter(data, JfrWaits.Kind.WAIT, "beacon-wait-condition").events().isEmpty());
        // Coverage follows the actual file, not the fact that our workload parked a virtual thread.
        assertEquals(rawVirtualParks, data.events().stream().filter(e -> e.kind() == JfrWaits.Kind.PARK && Boolean.TRUE.equals(e.thread().virtual())).count());
        var virtual = probe.finish(false, null, null).events().getFirst();
        assertEquals(Boolean.TRUE, virtual.thread().virtual()); assertNull(virtual.relatedThread());
        assertTrue(data.events().stream().noneMatch(e -> e.start().equals(virtual.start()) && e.thread().equals(virtual.thread())), "Custom probe must never be presented as a production park");
        assertEquals(Boolean.FALSE, JfrWaits.filter(data, JfrWaits.Kind.PARK, "beacon-wait-platform-park").events().getFirst().thread().virtual());
        assertTrue(virtual.evidence().contains("not established"));
    }
    @Test void eventAndStackBudgetsAreSeparateAndCustomEventsAreNotProductionWaits() throws Exception {
        var limited = new JfrWaits.Builder(1, 0); var custom = new JfrWaits.Builder();
        try (RecordingFile file = new RecordingFile(recording)) { while (file.hasMoreEvents()) {
            var event = file.readEvent(); limited.accept(event);
            if (event.getEventType().getName().equals("beacon.WaitWithoutStack")) custom.accept(JfrWaits.Kind.WAIT, event);
            if (event.getEventType().getName().equals("beacon.WaitUnknownClass")) custom.accept(JfrWaits.Kind.PARK, event);
        }}
        var capped = limited.finish(true, null, null); assertEquals(1, capped.events().size());
        assertEquals(JfrWaits.StackState.OMITTED, capped.events().getFirst().stackState());
        assertEquals(capped.counts().values().stream().mapToLong(JfrWaits.Counts::observed).sum() - 1,
                capped.counts().values().stream().mapToLong(JfrWaits.Counts::omitted).sum());
        assertTrue(capped.text().contains("PARTIAL"));
        var missing = custom.finish(false, null, null); assertEquals(2, missing.events().size());
        assertTrue(missing.events().stream().allMatch(e -> e.stackState() == JfrWaits.StackState.NOT_RECORDED));
        assertTrue(missing.events().stream().anyMatch(e -> e.target().classId() == -1));
        assertTrue(missing.events().stream().allMatch(e -> e.relatedThread() == null));
    }
    @Test void concurrentDurationsOverflowSafelyAndKindsAndClassIdsStaySeparate() throws Exception {
        Instant now = Instant.now(); var thread = new JfrWaits.ThreadRef(1, 1, "worker", false);
        var target = new JfrWaits.Target(1, "Gate"); List<JfrWaits.Event> events = new ArrayList<>();
        for (int i = 0; i < 2; i++) events.add(new JfrWaits.Event(JfrWaits.Kind.ENTER, thread, target, null, now, now, Long.MAX_VALUE, List.of(), JfrWaits.StackState.NOT_RECORDED));
        events.add(new JfrWaits.Event(JfrWaits.Kind.PARK, thread, target, null, now, now, 0, List.of(), JfrWaits.StackState.NOT_RECORDED));
        events.add(new JfrWaits.Event(JfrWaits.Kind.ENTER, thread, new JfrWaits.Target(2, "Gate"), null, now, now, 0, List.of(), JfrWaits.StackState.NOT_RECORDED));
        var data = new JfrWaits.Data(events, Map.of(), false, "test"); var view = JfrWaits.filter(data, null, "");
        assertEquals(3, view.hotspots().size()); assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), view.hotspots().getFirst().totalNanos());
        assertEquals(3, JfrWaits.filter(data, JfrWaits.Kind.ENTER, "WORKER").events().size());
        assertTrue(JfrWaits.filter(data, null, "absent").events().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> view.events().clear());
    }
    @Test void cancellationAndOversizedFiltersDoNotSilentlyReturnEmptyResults() {
        var data = new JfrWaits.Data(List.of(new JfrWaits.Event(JfrWaits.Kind.PARK, null, null, null, null, null, 0, List.of(), JfrWaits.StackState.NOT_RECORDED)), Map.of(), false, "");
        assertThrows(IllegalArgumentException.class, () -> JfrWaits.filter(data, null, "x".repeat(513)));
        Thread.currentThread().interrupt(); try { assertThrows(java.io.InterruptedIOException.class, () -> JfrWaits.filter(data, null, "")); } finally { Thread.interrupted(); }
    }
}
