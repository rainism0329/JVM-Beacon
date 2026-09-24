package dev.jvmbeacon.core;

import java.util.ArrayList;
import java.util.List;

/** Geometry derived only from bounded captured evidence; never interpolates missing observations. */
public record TrendSeries(List<Point> points, String unit, long from, long to, double min, double max) {
    public record Point(long start, long end, Double value, String detail, boolean joinPrevious) { }
    public TrendSeries { points = List.copyOf(points); }
    public static TrendSeries of(List<JmxClient.Sample> samples, String key, String sourceUnit) {
        double divisor = "bytes".equals(sourceUnit) ? 1_048_576d : "ns".equals(sourceUnit) ? 1_000_000d : 1;
        String unit = "bytes".equals(sourceUnit) ? "MiB" : "ns".equals(sourceUnit) ? "ms" : sourceUnit;
        List<Point> points = new ArrayList<>();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        long from = Long.MAX_VALUE, to = Long.MIN_VALUE;
        Point previous = null;
        for (JmxClient.Sample sample : samples.subList(Math.max(0, samples.size() - 120), samples.size())) {
            var metric = sample.metrics().stream().filter(m -> m.key().equals(key)).findFirst().orElse(null);
            String error = sample.captureEnd() < sample.captureStart() ? "Invalid capture window"
                    : metric == null ? "Not captured" : !metric.available() ? metric.error() == null ? "Unavailable" : metric.error() : null;
            double value = error == null ? metric.value().doubleValue() / divisor : Double.NaN;
            if (!Double.isFinite(value) && error == null) error = "Non-finite value; not zero";
            boolean join = previous != null && previous.value() != null && error == null
                    && sample.captureEnd() > previous.end() && (double) sample.captureEnd() - previous.end() <= 5_000;
            Point point = new Point(sample.captureStart(), sample.captureEnd(), error == null ? value : null,
                    error == null ? "Captured · " + metric.value() + " " + sourceUnit : error, join);
            points.add(point); previous = point;
            from = Math.min(from, point.end()); to = Math.max(to, point.end());
            if (point.value() != null) { min = Math.min(min, value); max = Math.max(max, value); }
        }
        return new TrendSeries(points, unit, points.isEmpty() ? 0 : from, points.isEmpty() ? 0 : to, min, max);
    }
    public boolean available() { return Double.isFinite(min); }
    public long availableCount() { return points.stream().filter(p -> p.value() != null).count(); }
    public double x(Point point) { double span = (double) to - from; return span <= 0 ? .5 : Math.max(0, Math.min(1, ((double) point.end() - from) / span)); }
    /** A constant (including zero) is centered. Padding keeps extrema clear of axes. */
    public double y(double value) {
        if (max == min) return .5;
        double scale = Math.max(1, Math.max(Math.abs(min), Math.abs(max)));
        double range = max / scale - min / scale;
        if (range == 0) return .5;
        return .06 + .88 * Math.max(0, Math.min(1, (value / scale - min / scale) / range));
    }
}
