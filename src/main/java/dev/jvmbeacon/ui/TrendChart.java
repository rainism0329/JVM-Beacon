package dev.jvmbeacon.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.TrendSeries;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/** Paints captured points only. Hover inspects a sample; it never starts a remote request. */
final class TrendChart extends JComponent {
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter AXIS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Supplier<TrendSeries> data;
    private int hoverX = -1;
    @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public javax.accessibility.AccessibleRole getAccessibleRole() { return javax.accessibility.AccessibleRole.CANVAS; }
        };
        return accessibleContext;
    }
    TrendChart(Supplier<TrendSeries> data) {
        this.data = data; setToolTipText("Hover to inspect a captured sample.");
        getAccessibleContext().setAccessibleName("Captured metric trend; hover for exact sample values and times");
        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { hoverX = e.getX(); repaint(); }
            @Override public void mouseExited(MouseEvent e) { hoverX = -1; repaint(); }
        };
        addMouseListener(hover); addMouseMotionListener(hover);
    }
    private int left() { return JBUI.scale(16); }
    private int plotWidth() { return Math.max(1, getWidth() - left() * 2); }
    private TrendSeries.Point nearest(TrendSeries series, int x) {
        return series.points().stream().min(java.util.Comparator.comparingDouble(p -> Math.abs(left() + series.x(p) * plotWidth() - x))).orElse(null);
    }
    @Override public String getToolTipText(MouseEvent event) {
        TrendSeries series = data.get(); TrendSeries.Point point = nearest(series, event.getX());
        return point == null ? "No captured samples. Enable Auto · 2 s or choose Sample now."
                : "<html><body style='width:360px'>Window start: " + FULL.format(Instant.ofEpochMilli(point.start()))
                + "<br>Window end: " + FULL.format(Instant.ofEpochMilli(point.end())) + "<br>"
                + point.detail().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>";
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics); Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setFont(getFont() == null ? BeaconUi.font() : getFont());
            g.setColor(BeaconUi.CANVAS); g.fillRect(0, 0, getWidth(), getHeight());
            int left = left(), width = plotWidth();
            int textHeight = g.getFontMetrics().getHeight(), baseline = JBUI.scale(8) + g.getFontMetrics().getAscent();
            boolean compact = getHeight() < JBUI.scale(160);
            // A connection notice or a short tool window must not hide an otherwise usable plot.
            // Exact times remain in the capture caption and hover when the axis cannot fit.
            boolean tight = getHeight() < JBUI.scale(110);
            int top = baseline + JBUI.scale(tight ? 4 : 12) + (compact ? 0 : textHeight);
            int height = getHeight() - top - (tight ? JBUI.scale(8) : textHeight + JBUI.scale(12));
            TrendSeries series = data.get(); g.setColor(BeaconUi.MUTED);
            if (series.points().isEmpty()) { line(g, "No samples · Enable Auto or choose Sample now", left, JBUI.scale(24), width); return; }
            if (height < JBUI.scale(16)) { line(g, "Expand this panel to see the trend", left, baseline, width); return; }
            var latest = series.points().getLast();
            String latestText = "Latest: " + (latest.value() == null ? "Unavailable" : number(latest.value()) + " " + series.unit());
            String description = !series.available() ? "Unavailable samples are not zero"
                    : series.availableCount() == 1 ? "One valid point · More samples needed for a line"
                    : series.min() == series.max() ? "Unchanged value · " + series.availableCount() + " valid samples"
                    : "Range " + number(series.min()) + " – " + number(series.max()) + " " + series.unit() + " · Auto scale";
            String brief = !series.available() ? "Missing ≠ zero" : series.availableCount() == 1 ? "One point"
                    : series.min() == series.max() ? "Unchanged" : "Auto scale";
            line(g, latestText + (compact ? " · " + brief : ""), left, baseline, width);
            if (!compact) line(g, description, left, baseline + textHeight, width);
            g.setColor(JBColor.border()); for (int i = 0; i < 3; i++) g.drawLine(left, top + height * i / 2, left + width, top + height * i / 2);
            TrendSeries.Point previous = null; int px = 0, py = 0;
            for (TrendSeries.Point point : series.points()) {
                int x = left + (int) (series.x(point) * width);
                if (point.value() == null) {
                    g.setColor(BeaconUi.MUTED); g.drawLine(x, top + height - JBUI.scale(4), x, top + height); previous = null; continue;
                }
                int y = top + height - (int) (series.y(point.value()) * height);
                g.setColor(BeaconUi.ACCENT); g.setStroke(new BasicStroke(JBUI.scale(2)));
                if (previous != null && point.joinPrevious()) g.drawLine(px, py, x, y);
                int radius = JBUI.scale(series.availableCount() == 1 ? 4 : 2);
                g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
                previous = point; px = x; py = y;
            }
            if (hoverX >= left && hoverX <= left + width) {
                var point = nearest(series, hoverX);
                if (point != null) { int x = left + (int) (series.x(point) * width); g.setColor(BeaconUi.MUTED); g.setStroke(new BasicStroke(1)); g.drawLine(x, top, x, top + height); }
            }
            g.setColor(BeaconUi.MUTED);
            if (tight) return;
            String start = AXIS.format(Instant.ofEpochMilli(series.from())), end = AXIS.format(Instant.ofEpochMilli(series.to()));
            int axisBaseline = top + height + JBUI.scale(6) + g.getFontMetrics().getAscent();
            if (series.from() == series.to()) {
                g.drawString(end, left + Math.max(0, (width - g.getFontMetrics().stringWidth(end)) / 2), axisBaseline);
            } else if (g.getFontMetrics().stringWidth(start + end) + JBUI.scale(12) <= width) {
                g.drawString(start, left, axisBaseline); g.drawString(end, left + width - g.getFontMetrics().stringWidth(end), axisBaseline);
            } else line(g, "Time →", left, axisBaseline, width);
        } finally { g.dispose(); }
    }
    private static String number(double n) { return String.format(Locale.ROOT, Math.abs(n) >= 1e6 || (n != 0 && Math.abs(n) < .001) ? "%.4g" : "%.3f", n); }
    private static void line(Graphics2D g, String text, int x, int y, int width) {
        String visible = text;
        if (g.getFontMetrics().stringWidth(visible) > width) {
            while (!visible.isEmpty() && g.getFontMetrics().stringWidth(visible + "…") > width) visible = visible.substring(0, visible.length() - 1);
            visible += "…";
        }
        g.drawString(visible, x, y);
    }
}
