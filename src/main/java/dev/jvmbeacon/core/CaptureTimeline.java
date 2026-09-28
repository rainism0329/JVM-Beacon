package dev.jvmbeacon.core;

import java.math.BigDecimal;
import java.util.List;

/** Immutable, bounded observations in acquisition order. Time is never sorted or interpolated. */
public record CaptureTimeline(List<JmxClient.Sample> samples) {
    public static final int LIMIT = 120;
    public record Track(String key, String title, String unit, String meaning, boolean counter) { }
    public static final List<Track> TRACKS = List.of(
            new Track("cpu.process", "Process CPU", "%", "Recent JVM-reported load across all CPUs; its averaging window is unspecified.", false),
            new Track("heap.used", "Heap used", "bytes", "Observed used heap; changes alone do not identify allocation or leaks.", false),
            new Track("gc.time", "GC collection time", "ms", "Sum of approximate cumulative collector times; not a pause timeline.", true),
            new Track("threads.live", "Live platform threads", "threads", "ThreadMXBean count; excludes virtual threads.", false));
    public CaptureTimeline {
        samples = List.copyOf(samples.subList(Math.max(0, samples.size() - LIMIT), samples.size()));
    }
    public CaptureTimeline select(int first, int last) {
        if (first < 0 || last < first || last >= samples.size()) throw new IllegalArgumentException("Select a valid captured sample interval.");
        return new CaptureTimeline(samples.subList(first, last + 1));
    }
    public long gaps() {
        long count = 0;
        for (int i = 1; i < samples.size(); i++)
            if ((double) samples.get(i).captureEnd() - samples.get(i - 1).captureEnd() > 5_000) count++;
        return count;
    }
    public boolean orderedWindows() {
        for (int i = 0; i < samples.size(); i++) {
            var s = samples.get(i);
            if (s.captureStart() < 0 || s.captureEnd() < s.captureStart()) return false;
            if (i > 0 && (s.captureEnd() <= samples.get(i - 1).captureEnd() || s.captureStart() < samples.get(i - 1).captureEnd())) return false;
        }
        return true;
    }
    public TrendSeries series(Track track) { return TrendSeries.of(samples, track.key(), track.unit()); }
    public record Change(String first, String last, String delta, String evidence) { }
    public Change change(Track track) {
        TrendSeries series = series(track);
        String first = exact(samples.isEmpty() ? null : samples.getFirst(), track);
        String last = exact(samples.isEmpty() ? null : samples.getLast(), track);
        String evidence = series.availableCount() + " / " + samples.size() + " available";
        if (samples.size() < 2) return new Change(first, last, "—", evidence + "; need two samples");
        if (!orderedWindows()) return new Change(first, last, "—", evidence + "; reversed or overlapping capture windows");
        if (series.availableCount() != samples.size()) return new Change(first, last, "—", evidence + "; missing values or units differ");
        BigDecimal a = null, previous = null, b = null;
        for (var s : samples) {
            BigDecimal value = new BigDecimal(metric(s, track).value().toString());
            if (track.counter() && previous != null && value.compareTo(previous) < 0)
                return new Change(first, last, "—", evidence + "; counter decreased; reset or collector change possible");
            if (a == null) a = value;
            b = value; previous = value;
        }
        return new Change(first, last, b.subtract(a).stripTrailingZeros().toString(), evidence
                + (gaps() > 0 ? "; gaps present; endpoint change only" : "; endpoint change, not a rate or cause"));
    }
    private static JmxClient.Metric metric(JmxClient.Sample s, Track track) {
        return s.metrics().stream().filter(m -> m.key().equals(track.key())).findFirst().orElse(null);
    }
    public static String exact(JmxClient.Sample s, Track track) {
        var m = s == null ? null : metric(s, track);
        if (m == null) return "Not captured";
        if (!m.unit().equals(track.unit())) return "Different unit: " + m.unit();
        return m.available() && Double.isFinite(m.value().doubleValue()) ? m.value().toString()
                : m.error() == null ? "Unavailable" : m.error();
    }
}
