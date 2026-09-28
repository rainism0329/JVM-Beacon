package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CaptureTimelineTest {
    static JmxClient.Sample sample(long end, String key, Number value, String unit) {
        return new JmxClient.Sample(end - 10, end, List.of(new JmxClient.Metric(key, key, value, unit, value == null ? "Permission denied" : null)));
    }
    @Test void freezesRetainedEvidenceAndSelectsInAcquisitionOrder() {
        var source = new ArrayList<JmxClient.Sample>();
        for (int i = 1; i <= 130; i++) source.add(sample(i * 2000, "heap.used", i, "bytes"));
        var timeline = new CaptureTimeline(source); var selected = timeline.select(2, 4); source.clear();
        assertEquals(120, timeline.samples().size()); assertEquals(3, selected.samples().size());
        assertEquals(26000, selected.samples().getFirst().captureEnd());
        assertThrows(UnsupportedOperationException.class, () -> selected.samples().clear());
        assertThrows(IllegalArgumentException.class, () -> timeline.select(4, 2));
    }
    @Test void sharedAxisRetainsMissingPointsAndGaps() {
        var timeline = new CaptureTimeline(List.of(sample(100, "heap.used", 1, "bytes"), sample(2100, "heap.used", null, "bytes"), sample(10000, "heap.used", 3, "bytes")));
        var heap = timeline.series(CaptureTimeline.TRACKS.get(1)); var cpu = timeline.series(CaptureTimeline.TRACKS.getFirst());
        assertEquals(heap.from(), cpu.from()); assertEquals(heap.to(), cpu.to());
        assertEquals(3, cpu.points().size()); assertEquals(0, cpu.availableCount());
        assertNull(heap.points().get(1).value()); assertFalse(heap.points().getLast().joinPrevious());
        assertEquals(1, timeline.gaps()); assertEquals("—", timeline.change(CaptureTimeline.TRACKS.get(1)).delta());
    }
    @Test void unitMismatchIsNotPlottedOrCompared() {
        var timeline = new CaptureTimeline(List.of(sample(100, "heap.used", 1, "bytes"), sample(2100, "heap.used", 2, "MiB")));
        assertNull(timeline.series(CaptureTimeline.TRACKS.get(1)).points().getLast().value());
        assertEquals("—", timeline.change(CaptureTimeline.TRACKS.get(1)).delta());
    }
    @Test void counterResetAnywhereSuppressesMisleadingPositiveEndpointDelta() {
        var gc = CaptureTimeline.TRACKS.get(2);
        var timeline = new CaptureTimeline(List.of(sample(100, gc.key(), 50, "ms"), sample(2100, gc.key(), 10, "ms"), sample(4100, gc.key(), 80, "ms")));
        assertEquals("—", timeline.change(gc).delta()); assertTrue(timeline.change(gc).evidence().contains("counter decreased"));
    }
    @Test void reversedOrOverlappingWindowsBreakLinesAndSuppressDelta() {
        var heap = CaptureTimeline.TRACKS.get(1);
        for (long end : List.of(100L, 95L, 105L)) {
            var timeline = new CaptureTimeline(List.of(sample(100, heap.key(), 1, "bytes"), sample(end, heap.key(), 2, "bytes")));
            assertFalse(timeline.orderedWindows()); assertFalse(timeline.series(heap).points().getLast().joinPrevious());
            assertEquals("—", timeline.change(heap).delta());
        }
    }
    @Test void endpointChangeUsesExactValuesAndDoesNotClaimRatesAcrossGaps() {
        var heap = CaptureTimeline.TRACKS.get(1);
        var timeline = new CaptureTimeline(List.of(sample(100, heap.key(), 9007199254740992L, "bytes"), sample(10000, heap.key(), 9007199254740993L, "bytes")));
        assertEquals("1", timeline.change(heap).delta()); assertTrue(timeline.change(heap).evidence().contains("gaps present"));
        assertEquals("9007199254740993", timeline.change(heap).last());
    }
}
