package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class AttributeSeriesTest {
    @Test void retainsExactLongAndDecimalAndDistinguishesZeroFromMissing() {
        assertEquals("9223372036854775807", AttributeSeries.Reading.from(1, 2, Long.MAX_VALUE).exact());
        assertEquals("1.2300", AttributeSeries.Reading.from(1, 2, new BigDecimal("1.2300")).exact());
        assertEquals(0.0, AttributeSeries.Reading.from(1, 2, 0).plotted());
        assertNull(AttributeSeries.Reading.from(1, 2, null).plotted());
        assertNotNull(AttributeSeries.Reading.from(1, 2, Double.NaN).error());
        assertNotNull(AttributeSeries.Reading.from(1, 2, Double.POSITIVE_INFINITY).error());
    }
    @Test void rejectsHostileNumbersAndBoundsMagnitudeWithoutCallingOverrides() {
        Number hostile = new Number() {
            public int intValue() { throw new AssertionError(); }
            public long longValue() { throw new AssertionError(); }
            public float floatValue() { throw new AssertionError(); }
            public double doubleValue() { throw new AssertionError(); }
            public String toString() { throw new AssertionError(); }
        };
        assertNotNull(AttributeSeries.Reading.from(0, 1, hostile).error());
        assertNotNull(AttributeSeries.Reading.from(0, 1, BigInteger.ONE.shiftLeft(5000)).error());
        assertNotNull(AttributeSeries.Reading.from(0, 1, new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE)).error());
    }
    @Test void capsHistoryPreservesFailureGapsAndClearsBetweenTargets() {
        AttributeSeries series = new AttributeSeries();
        for (int i = 0; i < 200; i++) series.add(AttributeSeries.Reading.from(i, i + 1, i));
        series.add(AttributeSeries.Reading.missing(201, 202, "Denied"));
        assertEquals(120, series.readings().size());
        assertEquals(81, series.readings().getFirst().start());
        assertNull(series.readings().getLast().plotted());
        assertThrows(UnsupportedOperationException.class, () -> series.readings().clear());
        series.clear(); assertTrue(series.readings().isEmpty());
    }
    @Test void backwardsClockBecomesAnExplicitGapWithoutLosingOriginalTimestamps() {
        AttributeSeries series = new AttributeSeries();
        series.add(AttributeSeries.Reading.from(1, 2, 7));
        var backwards = AttributeSeries.Reading.from(4, 1, 8);
        series.add(backwards);
        assertEquals(4, backwards.start()); assertEquals(1, backwards.end());
        assertNull(backwards.exact()); assertNull(backwards.plotted());
        assertTrue(backwards.error().contains("clock moved backwards"));
        assertEquals(backwards, series.readings().getLast());
        assertNotNull(AttributeSeries.Reading.missing(4, 1, "Read failed").error());
    }
}
