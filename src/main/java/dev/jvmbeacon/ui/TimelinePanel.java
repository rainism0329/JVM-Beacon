package dev.jvmbeacon.ui;

import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.CaptureTimeline;
import dev.jvmbeacon.core.JmxClient.Sample;
import dev.jvmbeacon.core.TrendSeries;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Reuses the connection's sampler. Freezing copies evidence, never pauses or invokes the target. */
final class TimelinePanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private CaptureTimeline latest = new CaptureTimeline(List.of());
    private CaptureTimeline frozen;
    private CaptureTimeline selected = latest;
    private boolean offline, changing;
    private final JToggleButton freeze = new JToggleButton("Freeze & select");
    private final JButton save = new JButton("Save interval…");
    private final JSpinner first = spinner("First captured sample"), last = spinner("Last captured sample"), inspect = spinner("Inspect captured sample");
    private final JTextArea caption = text(1), cursor = text(2);
    private String sampling = "No samples · Connect a JVM or open a capture.";
    private final JLabel range = BeaconUi.label("", true);
    private final List<TrendSeries> series = new ArrayList<>();
    private final List<TrendChart> charts = new ArrayList<>();
    private final DefaultTableModel changes = new DefaultTableModel(new String[]{"Metric / source unit", "First", "Last", "Change", "Evidence"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };

    TimelinePanel(JButton sampleNow, JButton startLive, Runnable open, Consumer<List<Sample>> saveInterval) {
        super(new BorderLayout(JBUI.scale(8), JBUI.scale(8)));
        setBorder(JBUI.Borders.empty(12, 16));
        JButton openFile = new JButton("Open capture…"); openFile.addActionListener(e -> open.run());
        JPanel header = BeaconUi.panel(4);
        header.add(BeaconUi.row(BeaconUi.title("Signal timeline"), sampleNow, startLive, freeze, openFile), BorderLayout.NORTH);
        header.add(BeaconUi.row(new JLabel("From #"), first, new JLabel("to #"), last, save,
                new JLabel("Inspect #"), inspect, range), BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JPanel tracks = new TrackGrid();
        for (int i = 0; i < CaptureTimeline.TRACKS.size(); i++) {
            var track = CaptureTimeline.TRACKS.get(i); int index = i;
            series.add(selected.series(track));
            TrendChart chart = new TrendChart(() -> series.get(index));
            chart.setCursorListener(point -> { if (!changing) inspect.setValue(point + (int) first.getValue()); });
            charts.add(chart);
            JPanel strip = BeaconUi.panel(8); strip.setBackground(BeaconUi.CANVAS);
            JPanel label = BeaconUi.panel(4); label.setOpaque(false); label.setBorder(JBUI.Borders.empty(4, 10));
            label.add(BeaconUi.title(String.format("%02d  %s", i + 1, track.title())), BorderLayout.NORTH);
            JLabel unit = BeaconUi.label(track.unit().equals("bytes") ? "MiB · original: bytes" : track.unit() + (track.counter() ? " · cumulative" : " · observed"), true);
            unit.putClientProperty("beacon.mono", true); label.add(unit, BorderLayout.SOUTH);
            label.setPreferredSize(JBUI.size(225, 80)); label.setToolTipText(track.meaning());
            strip.add(label, BorderLayout.WEST); strip.add(chart, BorderLayout.CENTER);
            strip.setPreferredSize(JBUI.size(740, 80)); tracks.add(strip);
        }
        JBTabbedPane views = new JBTabbedPane();
        var scroll = BeaconUi.scroll(tracks); scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(24));
        views.addTab("Signals", scroll);
        JBTable table = new JBTable(changes); BeaconUi.table(table, "Capture samples to compare their endpoints");
        table.getColumnModel().getColumn(0).setPreferredWidth(230);
        table.getColumnModel().getColumn(4).setPreferredWidth(360);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        JPanel comparison = BeaconUi.panel(8); comparison.add(BeaconUi.scroll(table), BorderLayout.CENTER);
        JTextArea meaning = text(4);
        meaning.setText("Change compares the first and last observed values in source units. CPU change is percentage points, not CPU time.\n"
                + "GC time sums approximate cumulative collection times; it is not a pause duration. Counter decreases suppress the delta.\n"
                + "Missing values, invalid windows or different units suppress comparison. No interpolation, averages, root-cause claims or virtual-thread coverage.");
        comparison.add(meaning, BorderLayout.SOUTH); views.addTab("Interval comparison", comparison);
        add(views, BorderLayout.CENTER);
        JPanel footer = BeaconUi.panel(6); footer.add(caption, BorderLayout.NORTH); footer.add(cursor, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        freeze.setToolTipText("Freeze this view to select a stable interval. Existing live sampling may continue.");
        freeze.addActionListener(e -> { frozen = freeze.isSelected() ? latest : null; setRange(); });
        first.addChangeListener(e -> rangeChanged(true)); last.addChangeListener(e -> rangeChanged(false));
        inspect.addChangeListener(e -> { if (!changing) showCursor(); });
        save.addActionListener(e -> {
            try { first.commitEdit(); last.commitEdit(); }
            catch (java.text.ParseException invalid) { caption.setText("Enter sample numbers within the captured range, then save again."); return; }
            if (!selected.samples().isEmpty() && frozen != null) saveInterval.accept(selected.samples());
        });
        setRange();
    }
    void reset() {
        latest = new CaptureTimeline(List.of()); frozen = null; offline = false; freeze.setSelected(false); setRange();
    }
    void update(List<Sample> samples, boolean offline) {
        latest = new CaptureTimeline(samples); this.offline = offline;
        if (offline) { frozen = latest; freeze.setSelected(true); setRange(); }
        else if (frozen == null) setRange();
    }
    void sampling(String text) { sampling = text; showCaption(); }
    private void setRange() {
        changing = true;
        int size = Math.max(1, (frozen == null ? latest : frozen).samples().size());
        first.setModel(new SpinnerNumberModel(1, 1, size, 1)); last.setModel(new SpinnerNumberModel(size, 1, size, 1));
        changing = false; render();
    }
    private void rangeChanged(boolean from) {
        if (changing) return;
        changing = true;
        if ((int) first.getValue() > (int) last.getValue()) {
            if (from) last.setValue(first.getValue()); else first.setValue(last.getValue());
        }
        changing = false; render();
    }
    private void render() {
        CaptureTimeline source = frozen == null ? latest : frozen;
        boolean present = !source.samples().isEmpty();
        selected = present ? source.select((int) first.getValue() - 1, (int) last.getValue() - 1) : source;
        first.setEnabled(present && frozen != null); last.setEnabled(present && frozen != null);
        freeze.setEnabled(present && !offline); save.setEnabled(present && frozen != null); inspect.setEnabled(present);
        freeze.setText(frozen == null ? "Freeze & select" : "Follow latest");
        changing = true;
        inspect.setModel(new SpinnerNumberModel((int) last.getValue(), (int) first.getValue(), (int) last.getValue(), 1));
        changing = false;
        changes.setRowCount(0);
        for (int i = 0; i < charts.size(); i++) {
            var track = CaptureTimeline.TRACKS.get(i); series.set(i, selected.series(track));
            var change = selected.change(track);
            changes.addRow(new Object[]{track.title() + " · " + track.unit(), change.first(), change.last(), change.delta(), change.evidence()});
        }
        range.setText(!present ? "" : "Time " + shortTime(series.getFirst().from()) + " → " + shortTime(series.getFirst().to()));
        range.setToolTipText("Shared horizontal axis: capture end times, using the client clock. Values within a sample are read separately.");
        showCaption();
        showCursor();
    }
    private void showCaption() {
        caption.setText(selected.samples().isEmpty() ? "No captured history. Sample a connected JVM or open a .jvmb capture."
                : (offline ? "OFFLINE" : frozen == null ? "FOLLOWING" : "FROZEN VIEW") + " · " + selected.samples().size() + " samples · "
                + selected.gaps() + " gaps > 5 s" + (selected.orderedWindows() ? "" : " · Clock order / overlap warning")
                + (offline ? " · Saved observations only" : " · " + sampling));
        caption.setToolTipText("Latest 120 retained. Freeze fixes the view while live sampling may continue. Select by acquisition order; save interval includes only these metrics, identity and notes. Gaps are not recovered.");
    }
    private void showCursor() {
        int index = (int) inspect.getValue() - (int) first.getValue();
        for (TrendChart chart : charts) chart.setCursorIndex(index);
        if (selected.samples().isEmpty()) { cursor.setText("Hover a signal or use Inspect # to read exact captured values across all four tracks."); return; }
        Sample sample = selected.samples().get(index);
        StringBuilder text = new StringBuilder("#" + ((int) first.getValue() + index) + " · " + TIME.format(Instant.ofEpochMilli(sample.captureStart()))
                + " → " + TIME.format(Instant.ofEpochMilli(sample.captureEnd())) + " · Client clock · JMX MXBeans\n");
        for (var track : CaptureTimeline.TRACKS) text.append(track.title()).append(": ").append(CaptureTimeline.exact(sample, track)).append(' ').append(track.unit()).append("    ");
        cursor.setText(text.toString()); cursor.setCaretPosition(0);
    }
    private static JSpinner spinner(String name) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
        spinner.getAccessibleContext().setAccessibleName(name); spinner.setPreferredSize(JBUI.size(74, 28)); return spinner;
    }
    private static String shortTime(long epoch) { return TIME.format(Instant.ofEpochMilli(epoch)).substring(11); }
    private static JTextArea text(int rows) {
        JTextArea area = new JTextArea(rows, 20); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setOpaque(false); area.setForeground(BeaconUi.MUTED); return area;
    }
    /** Fit all tracks when there is room for legible compact plots; otherwise scroll intact rows. */
    private static final class TrackGrid extends JPanel implements Scrollable {
        TrackGrid() { super(new GridLayout(4, 1, 0, JBUI.scale(6))); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return JBUI.scale(24); }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(JBUI.scale(24), r.height - JBUI.scale(24)); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            int rowMinimum = getFontMetrics(BeaconUi.font()).getHeight() + JBUI.scale(32);
            return getParent() != null && getParent().getHeight() >= 4 * rowMinimum + JBUI.scale(3 * 6);
        }
    }
}
