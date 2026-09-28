package dev.jvmbeacon.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.JfrMemory;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Pure local views of already copied data; no remote requests or file access. */
final class JfrMemoryPanel extends JPanel {
    private final JLabel summary = BeaconUi.label("Open a local .jfr to inspect GC and allocation evidence", true);
    private final JBTextField search = new JBTextField();
    private final DefaultTableModel gcModel = model(new String[]{"Kind", "GC ID", "Start (UTC)", "Duration · ms", "Name", "Cause"},
            new Class<?>[]{String.class, Long.class, String.class, Double.class, String.class, String.class});
    private final DefaultTableModel allocationModel = model(new String[]{"Allocated class", "Class ID", "Samples", "Weight · bytes", "Weight share · %"},
            new Class<?>[]{String.class, Long.class, Long.class, BigInteger.class, Double.class});
    private final JBTable gcTable = new JBTable(gcModel), allocationTable = new JBTable(allocationModel);
    private final TableRowSorter<DefaultTableModel> gcSorter = new TableRowSorter<>(gcModel), allocationSorter = new TableRowSorter<>(allocationModel);
    private final JTextArea detail = BeaconUi.text("Select an event or class to read exact evidence.", 2);
    private final JTextArea coverage = BeaconUi.text("No local recording inspected.", 12);
    private final JButton copy = new JButton("Copy selected evidence"), copyCoverage = new JButton("Copy coverage");
    private final JButton focus = new JButton("Focus event ±100 ms");
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final GcTimeline timeline = new GcTimeline(this::selectEvent);
    private JfrMemory.Data data;
    private JfrMemory.Gc selectedGc;
    private boolean busy;

    JfrMemoryPanel() {
        this((start, end) -> { });
    }
    JfrMemoryPanel(BiConsumer<Instant, Instant> focusEvent) {
        super(new BorderLayout(JBUI.scale(6), JBUI.scale(6)));
        JPanel top = BeaconUi.panel(2); top.add(summary, BorderLayout.NORTH);
        search.getEmptyText().setText("Filter by class, GC name, cause or ID");
        search.setPreferredSize(JBUI.size(300, 28)); search.getAccessibleContext().setAccessibleName("Filter GC and allocation tables");
        top.add(BeaconUi.row(BeaconUi.label("Search", true), search, copy, copyCoverage, focus), BorderLayout.CENTER); add(top, BorderLayout.NORTH);
        focus.addActionListener(e -> { if (!busy && selectedGc != null) focusEvent.accept(selectedGc.start(), selectedGc.end()); });
        focus.setToolTipText("Apply this event plus up to 100 ms on either side to all JFR views. Full event durations remain intact.");
        BeaconUi.table(gcTable, "No retained GC events. Events may be disabled, unsupported or absent.");
        BeaconUi.table(allocationTable, "No retained allocation samples. This does not mean zero allocations.");
        gcTable.setRowSorter(gcSorter); allocationTable.setRowSorter(allocationSorter);
        gcTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); allocationTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gcTable.putClientProperty("beacon.mono", true); allocationTable.putClientProperty("beacon.mono", true);
        gcTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(235));
        gcTable.getColumnModel().getColumn(4).setPreferredWidth(JBUI.scale(200));
        allocationTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(360));
        allocationTable.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            private double share; private boolean selected;
            { setHorizontalAlignment(SwingConstants.RIGHT); putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int col) {
                this.share = value instanceof Number n ? n.doubleValue() : 0; this.selected = selected;
                return super.getTableCellRendererComponent(table, value == null ? "Not available" : String.format(Locale.ROOT, "%.3f%%", share), selected, focus, row, col);
            }
            @Override protected void paintComponent(Graphics graphics) {
                if (!selected) {
                    graphics.setColor(getBackground()); graphics.fillRect(0, 0, getWidth(), getHeight());
                    Color color = BeaconUi.ACCENT; graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
                    graphics.fillRect(0, 0, (int) (getWidth() * Math.clamp(share / 100, 0, 1)), getHeight());
                    setOpaque(false); super.paintComponent(graphics); setOpaque(true);
                } else super.paintComponent(graphics);
            }
        });
        JPanel gc = BeaconUi.panel(4);
        JComponent gcSplit = BeaconUi.split(true, "jfr-memory-gc", timeline, BeaconUi.scroll(gcTable), .28f);
        timeline.setMinimumSize(JBUI.size(140, 64));
        gc.add(gcSplit, BorderLayout.CENTER);
        tabs.addTab("GC timeline", gc); tabs.addTab("Allocation pressure", BeaconUi.scroll(allocationTable));
        tabs.addTab("Coverage", BeaconUi.scroll(coverage)); add(tabs, BorderLayout.CENTER);
        detail.putClientProperty("beacon.mono", true); detail.setBorder(JBUI.Borders.empty(4, 8)); add(BeaconUi.scroll(detail), BorderLayout.SOUTH);
        gcTable.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelection(); });
        allocationTable.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelection(); });
        tabs.addChangeListener(e -> showSelection());
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); } public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        copy.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(detail.getText() + "\n\n" + coverage.getText())));
        copyCoverage.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(coverage.getText())));
        copy.setEnabled(false); copyCoverage.setEnabled(false);
    }
    void clear() {
        data = null; gcModel.setRowCount(0); allocationModel.setRowCount(0); search.setText("");
        timeline.show(null, List.of()); coverage.setText("No local recording inspected.");
        summary.setText("Open a local .jfr to inspect GC and allocation evidence"); copyCoverage.setEnabled(false); showSelection();
    }
    void load(JfrMemory.Data next) {
        clear(); data = next;
        for (var event : next.gc()) gcModel.addRow(new Object[]{event.kind().name(), event.id(), event.start().toString(), event.nanos() / 1_000_000.0, event.name(), event.cause()});
        for (var entry : next.allocations()) allocationModel.addRow(new Object[]{entry.className(), entry.classId(), entry.samples(), entry.weight(),
                next.totalWeight().signum() == 0 ? null : entry.weight().doubleValue() / next.totalWeight().doubleValue() * 100.0});
        allocationSorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
        coverage.setText(next.text()); coverage.setCaretPosition(0); copyCoverage.setEnabled(true);
        summary.setText(next.gc().size() + " GC events · " + next.allocations().size() + " allocation classes · "
                + (next.partial() ? "PARTIAL scan" : "End of file") + " · Check Coverage for omissions");
        summary.setToolTipText("Inspected event window: " + next.first() + " → " + next.last()); filter();
    }
    void loadScoped(JfrMemory.Data next) { String query = search.getText(); load(next); search.setText(query); }
    void setBusy(boolean busy) { this.busy = busy; focus.setEnabled(!busy && selectedGc != null); }
    private void filter() {
        String text = search.getText().strip();
        RowFilter<DefaultTableModel, Integer> filter = text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text));
        gcSorter.setRowFilter(filter); allocationSorter.setRowFilter(filter);
        List<JfrMemory.Gc> visible = new ArrayList<>();
        if (data != null) for (int i = 0; i < gcTable.getRowCount(); i++) visible.add(data.gc().get(gcTable.convertRowIndexToModel(i)));
        timeline.show(data, visible); showSelection();
        if (data != null) {
            gcTable.getEmptyText().setText(data.gc().isEmpty() ? "No retained GC events; check Coverage." : "No GC events match this search.");
            allocationTable.getEmptyText().setText(data.allocations().isEmpty() ? "No retained allocation samples; check Coverage." : "No classes match this search.");
        }
    }
    private void selectEvent(JfrMemory.Gc event) {
        if (data == null) return;
        int model = data.gc().indexOf(event), row = model < 0 ? -1 : gcTable.convertRowIndexToView(model);
        if (row >= 0) { gcTable.setRowSelectionInterval(row, row); gcTable.scrollRectToVisible(gcTable.getCellRect(row, 0, true)); }
    }
    private void showSelection() {
        String text = null; JfrMemory.Gc selected = null;
        if (data != null && tabs.getSelectedIndex() == 0 && gcTable.getSelectedRow() >= 0) {
            selected = data.gc().get(gcTable.convertRowIndexToModel(gcTable.getSelectedRow()));
            text = selected.kind() + " · GC #" + selected.id() + " · " + selected.name() + " · Cause: " + missing(selected.cause())
                    + "\nWindow: " + selected.start() + " → " + selected.end() + " · Duration: " + selected.nanos() + " ns"
                    + (selected.kind() == JfrMemory.Kind.CYCLE ? "\nReported sumOfPauses: " + nanos(selected.sumOfPauses()) + " · longestPause: " + nanos(selected.longestPause()) + " · Cycle duration is NOT pause time."
                    : "\nSource: jdk.GCPhasePause · Top-level pause event; not all application stalls.");
        } else if (data != null && tabs.getSelectedIndex() == 1 && allocationTable.getSelectedRow() >= 0) {
            var a = data.allocations().get(allocationTable.convertRowIndexToModel(allocationTable.getSelectedRow()));
            text = a.className() + " · Recorded class #" + a.classId() + " · Samples: " + a.samples()
                    + "\nWeight: " + a.weight() + " bytes · Share of ALL retained weight: " + (data.totalWeight().signum() == 0 ? "Not available (zero total)" : String.format(Locale.ROOT, "%.3f%%", a.weight().doubleValue() / data.totalWeight().doubleValue() * 100))
                    + "\nSample window: " + a.first() + " → " + a.last() + " · Statistical allocation pressure, NOT exact/live bytes.";
        }
        selectedGc = selected; focus.setEnabled(!busy && selected != null);
        timeline.selected = selected; timeline.repaint(); copy.setEnabled(text != null);
        detail.setText(text == null ? "Select an event or class to read exact evidence. Search does not change the allocation share denominator." : text); detail.setCaretPosition(0);
    }
    private static String missing(Object value) { return value == null ? "Not reported / unsupported" : value.toString(); }
    private static String nanos(Long value) { return value == null ? missing(null) : value + " ns"; }
    private static DefaultTableModel model(String[] columns, Class<?>[] types) {
        return new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return false; } public Class<?> getColumnClass(int c) { return types[c]; }
        };
    }

    private static final class GcTimeline extends JComponent {
        private static final Color CYCLE = JBColor.namedColor("JVMBeacon.gcCycle", 0x6761A8, 0xAEA0EF);
        private record Hit(Rectangle box, JfrMemory.Gc event) { }
        private final List<Hit> hits = new ArrayList<>();
        private final Consumer<JfrMemory.Gc> choose;
        private JfrMemory.Data data; private List<JfrMemory.Gc> events = List.of(); private JfrMemory.Gc selected;
        GcTimeline(Consumer<JfrMemory.Gc> choose) {
            this.choose = choose; setToolTipText("Select a GC event; use the table for keyboard navigation and exact duration.");
            getAccessibleContext().setAccessibleName("GC cycle and pause timeline. Use the GC table for keyboard navigation and exact values.");
            addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) {
                for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).box().contains(e.getPoint())) { choose.accept(hits.get(i).event()); return; }
            }});
        }
        @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleJComponent() { };
            return accessibleContext;
        }
        void show(JfrMemory.Data data, List<JfrMemory.Gc> events) { this.data = data; this.events = List.copyOf(events); hits.clear(); selected = null; repaint(); }
        @Override public Dimension getPreferredSize() { return JBUI.size(300, 64); }
        @Override public String getToolTipText(MouseEvent e) {
            for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).box().contains(e.getPoint())) {
                var event = hits.get(i).event(); return event.kind() + " · GC #" + event.id() + " · " + event.nanos() + " ns · " + event.name();
            }
            return "Marks are clipped to the applied range and at least 2 px. Overlapping events can hide each other. Table durations remain whole-event values.";
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); hits.clear(); Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(BeaconUi.CANVAS); g.fillRect(0, 0, getWidth(), getHeight());
                g.setFont(BeaconUi.font()); int left = JBUI.scale(95), width = Math.max(1, getWidth() - left - JBUI.scale(18));
                g.setColor(BeaconUi.MUTED); g.drawString("GC cycles", JBUI.scale(8), JBUI.scale(35)); g.drawString("GC pauses", JBUI.scale(8), JBUI.scale(57));
                if (data == null || data.first() == null && data.range() == null) { g.drawString("No inspected event window", left, JBUI.scale(23)); return; }
                Instant first = data.range() == null ? data.first() : data.range().from(), last = data.range() == null ? data.last() : data.range().until();
                double span = seconds(first, last);
                g.drawString((data.range() == null ? "Inspected window · " : "Applied range · ") + first + " → " + last, JBUI.scale(8), JBUI.scale(17));
                for (var event : events) {
                    double from = span <= 0 ? .5 : Math.clamp(seconds(first, event.start()) / span, 0, 1);
                    double to = span <= 0 ? .5 : Math.clamp(seconds(first, event.end()) / span, 0, 1);
                    int x = left + (int) (from * width), end = left + (int) (to * width);
                    Rectangle box = new Rectangle(Math.min(x, left + width - 2), JBUI.scale(event.kind() == JfrMemory.Kind.CYCLE ? 24 : 46), Math.max(2, end - x), JBUI.scale(12));
                    g.setColor(event.kind() == JfrMemory.Kind.CYCLE ? CYCLE : BeaconUi.ACCENT); g.fill(box);
                    if (event.equals(selected)) { g.setColor(JBColor.foreground()); g.drawRect(box.x - 1, box.y - 2, box.width + 1, box.height + 3); }
                    hits.add(new Hit(box, event));
                }
                if (events.isEmpty()) { g.setColor(BeaconUi.MUTED); g.drawString("No retained matching events · See Coverage", left, JBUI.scale(39)); }
            } finally { g.dispose(); }
        }
        private static double seconds(java.time.Instant from, java.time.Instant to) { var d = Duration.between(from, to); return d.getSeconds() + d.getNano() / 1e9; }
    }
}
