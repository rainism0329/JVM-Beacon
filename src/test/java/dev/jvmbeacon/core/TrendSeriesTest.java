package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class TrendSeriesTest {
    private static JmxClient.Sample sample(long end, Number value) {
        return sample(end, value, "bytes");
    }
    private static JmxClient.Sample sample(long end, Number value, String unit) {
        return new JmxClient.Sample(end, end, List.of(new JmxClient.Metric("x", "x", value, unit, value == null ? "Denied" : null)));
    }
    private static TrendSeries series(JmxClient.Sample... samples) { return TrendSeries.of(List.of(samples), "x", "bytes"); }
    @Test void onePointAndConstantValuesAreCenteredInsteadOfPretendingToBeZero() {
        var one = series(sample(100, 4_194_304));
        assertEquals("MiB", one.unit()); assertEquals(4.0, one.points().getFirst().value());
        assertEquals(.5, one.x(one.points().getFirst())); assertEquals(.5, one.y(4));
        var flat = series(sample(100, 0), sample(2100, 0));
        assertTrue(flat.points().getLast().joinPrevious()); assertEquals(.5, flat.y(0));
    }
    @Test void failuresLongGapsAndClockReversalBreakLines() {
        var data = series(sample(100, 1), sample(2100, null), sample(4100, 3), sample(11000, 4), sample(10000, 5), sample(10000, 6));
        assertEquals(5, data.availableCount()); assertNull(data.points().get(1).value());
        assertTrue(data.points().stream().noneMatch(TrendSeries.Point::joinPrevious));
        assertEquals("Denied", data.points().get(1).detail());
        assertTrue(data.points().stream().allMatch(p -> data.x(p) >= 0 && data.x(p) <= 1));
    }
    @Test void nonFiniteAndInvalidWindowsStayMissingAndHistoryIsCapped() {
        var bad = series(sample(100, Double.NaN), new JmxClient.Sample(301, 300, sample(300, 1).metrics()));
        assertFalse(bad.available()); assertEquals(0, bad.availableCount());
        var capped = TrendSeries.of(IntStream.range(0, 130).mapToObj(i -> sample(i * 2000L, i)).toList(), "x", "bytes");
        assertEquals(120, capped.points().size()); assertEquals(20000, capped.from());
        assertThrows(UnsupportedOperationException.class, () -> capped.points().clear());
    }
    @Test void extremaAreVisibleAndFiniteWithoutOverflow() {
        var data = TrendSeries.of(List.of(sample(0, -Double.MAX_VALUE, "unit unspecified"), sample(2000, Double.MAX_VALUE, "unit unspecified")), "x", "unit unspecified");
        assertEquals(.06, data.y(-Double.MAX_VALUE), 1e-8); assertEquals(.94, data.y(Double.MAX_VALUE), 1e-8);
        var tiny = TrendSeries.of(List.of(sample(0, Double.MIN_VALUE, "unit unspecified"), sample(2000, Double.MIN_VALUE * 2, "unit unspecified")), "x", "unit unspecified");
        assertTrue(Double.isFinite(tiny.y(Double.MIN_VALUE)));
        assertTrue(data.points().getLast().joinPrevious());
    }
}
