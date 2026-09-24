package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ThreadActivityTest {
    private static final JmxClient.Identity ID = new JmxClient.Identity("123@test", 100, "Test VM", "21");
    private static final ThreadActivity.Window START = new ThreadActivity.Window(100, 101, 1_000_000, 3_000_000);
    private static final ThreadActivity.Window END = new ThreadActivity.Window(1100, 1102, 1_001_000_000, 1_003_000_000);
    private static ThreadActivity.Point point(long id, long cpu, String name) {
        return new ThreadActivity.Point(id, cpu, name == null ? null : new JmxClient.ThreadRecord(id, name, "RUNNABLE", 0, 0, "—", "—", List.of()));
    }
    private static ThreadActivity.Report compare(List<ThreadActivity.Point> a, List<ThreadActivity.Point> b) {
        return ThreadActivity.compare(ID, START, END, a, b, a.size());
    }

    @Test void realZeroRanksBelowActivityAndMissingIsNotZero() {
        var result = compare(List.of(point(1, 20, "idle"), point(2, 10, "busy"), point(3, -1, "missing")),
                List.of(point(1, 20, "idle"), point(2, 100_000_010, "busy"), point(3, -1, "missing")));
        assertEquals(List.of(2L, 1L, 3L), result.rows().stream().map(ThreadActivity.Row::id).toList());
        assertEquals(100_000_000L, result.rows().getFirst().cpuDeltaNanos()); assertEquals(10.0, result.rows().getFirst().oneCorePercent(), 1e-8);
        assertEquals(0L, result.rows().get(1).cpuDeltaNanos()); assertNull(result.rows().get(2).cpuDeltaNanos());
        assertEquals(2, result.measuredCount()); assertEquals(ID, result.identity());
    }

    @Test void missingMetadataResetAndRenamedThreadNeverCreateDeltas() {
        var result = compare(List.of(point(1, 20, "a"), point(2, 20, "b"), point(3, 20, "c"), point(4, 20, null)),
                List.of(point(1, 10, "a"), point(2, 30, "renamed"), point(4, 30, "new identity"), point(5, 99, "new after baseline")));
        assertEquals(4, result.rows().size()); assertEquals(0, result.measuredCount());
        assertTrue(result.rows().stream().allMatch(r -> r.cpuDeltaNanos() == null && r.oneCorePercent() == null));
        assertTrue(result.rows().get(2).evidence().contains("does not prove thread exit"));
    }

    @Test void latencyChangesMidpointEstimateWithoutClampingUncertainValues() {
        var delayedEnd = new ThreadActivity.Window(1100, 1300, 1_001_000_000, 1_203_000_000);
        var result = ThreadActivity.compare(ID, START, delayedEnd, List.of(point(1, 0, "busy")), List.of(point(1, 2_200_000_000L, "busy")), 900);
        assertEquals(1_100_000_000L, result.intervalNanos()); assertEquals(200.0, result.rows().getFirst().oneCorePercent(), 1e-8);
        assertTrue(result.rows().getFirst().evidence().contains("uncertainty")); assertTrue(result.truncated());
    }

    @Test void invalidTimingAndDuplicateIdentitiesRejectMeasurement() {
        var one = List.of(point(1, 10, "a"));
        assertNotNull(ThreadActivity.compare(ID, START, START, one, one, 1).unavailable());
        assertNotNull(compare(List.of(one.getFirst(), one.getFirst()), one).unavailable());
        assertNotNull(compare(one, List.of(one.getFirst(), one.getFirst())).unavailable());
        assertNotNull(compare(List.of(point(-1, 0, "bad")), one).unavailable());
    }

    @Test void candidateBudgetAndReportAreBoundedAndImmutable() {
        var full = IntStream.rangeClosed(1, 512).mapToObj(i -> point(i, 0, "idle-" + i)).toList();
        var result = ThreadActivity.compare(ID, START, END, full, full, 600);
        assertEquals(512, result.rows().size()); assertTrue(result.truncated());
        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
        var oversized = IntStream.rangeClosed(1, 513).mapToObj(i -> point(i, 0, "idle")).toList();
        assertThrows(IllegalArgumentException.class, () -> compare(oversized, full));
    }
}
