package dev.jvmbeacon.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.ThreadActivity;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** One retained, explicit measurement per connection. Filtering and sorting never contact the target. */
final class HotThreadsPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").withZone(ZoneId.systemDefault());
    private final JButton measure = new JButton("Measure CPU · 1 s");
    private final JButton details = new JButton("Capture details…");
    private final JButton copy = new JButton("Copy report");
    private final JLabel summary = BeaconUi.label("Connect a JVM, then measure platform-thread activity.", true);
    private final JLabel count = BeaconUi.label("No measurement", true);
    private final JBTextField search = new JBTextField();
    private final Rows model = new Rows();
    private final JBTable table = new JBTable(model);
    private final DefaultListModel<StackTraceElement> frames = new DefaultListModel<>();
    private final JBList<StackTraceElement> stack = new JBList<>(frames);
    private final JTextArea selection = BeaconUi.text("Select a measured thread to inspect its end stack.\nCPU time is not attributed to individual methods.", 5);
    private ThreadActivity.Report report;
    private boolean connected;

    HotThreadsPanel(Project project, Disposable owner, Runnable capture, Consumer<String> status) {
        super(new BorderLayout(JBUI.scale(12), JBUI.scale(12)));
        setBorder(JBUI.Borders.empty(12, 16));
        JPanel header = BeaconUi.panel(6);
        JPanel actions = new JPanel(new BeaconUi.WrapLayout());
        actions.add(measure); actions.add(details); actions.add(copy);
        header.add(BeaconUi.title("Hot threads"), BorderLayout.WEST); header.add(actions, BorderLayout.CENTER);
        header.add(summary, BorderLayout.SOUTH); add(header, BorderLayout.NORTH);
        measure.setToolTipText("Read CPU counters twice with a 1 s wait. The actual measured interval includes remote call timing. Does not enable monitoring.");
        measure.addActionListener(e -> capture.run());
        details.addActionListener(e -> new DialogWrapper(project, false) {
            { setTitle("Hot Threads · Capture Details"); setOKButtonText("Close"); init(); }
            @Override protected JComponent createCenterPanel() {
                JTextArea text = BeaconUi.text(reportText(false), 18); text.setLineWrap(true); text.setWrapStyleWord(true);
                JComponent content = BeaconUi.scroll(text); content.setPreferredSize(JBUI.size(680, 380)); return content;
            }
            @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
        }.show());
        copy.addActionListener(e -> { CopyPasteManager.getInstance().setContents(new StringSelection(reportText(true))); status.accept("Copied CPU measurement, identity, timing and end stacks. Names and stacks are not redacted."); });
        copy.setToolTipText("Copy all captured candidates, including filtered-out rows. Names and stacks are not redacted.");
        search.getEmptyText().setText("Filter by name, ID or state…"); search.getAccessibleContext().setAccessibleName("Filter measured platform threads");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        BeaconUi.table(table, "Measure CPU to discover active platform threads");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true);
        table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.DESCENDING)));
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(260));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(58));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(102));
        table.getColumnModel().getColumn(2).setMinWidth(JBUI.scale(90));
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(100));
        table.getColumnModel().getColumn(3).setMinWidth(JBUI.scale(95));
        table.getColumnModel().getColumn(4).setPreferredWidth(JBUI.scale(125));
        table.setDefaultRenderer(Long.class, new NumericCell(false));
        table.setDefaultRenderer(Double.class, new NumericCell(false));
        table.getColumnModel().getColumn(3).setCellRenderer(new NumericCell(true));
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) select(); });
        selection.setLineWrap(true); selection.setWrapStyleWord(true);
        stack.putClientProperty("beacon.mono", true); stack.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stack.setCellRenderer(new DefaultListCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setBorder(JBUI.Borders.empty(5, 10)); setFont(list.getFont()); return this;
            }
        });
        Runnable navigate = () -> {
            StackTraceElement frame = stack.getSelectedValue();
            if (frame == null) status.accept("Select an end-stack frame first.");
            else SourceNavigator.navigate(project, owner, frame, status);
        };
        JButton source = new JButton("Go to source"); source.addActionListener(e -> navigate.run());
        stack.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) navigate.run(); } });
        stack.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "beacon.hot.source");
        stack.getActionMap().put("beacon.hot.source", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { navigate.run(); } });
        JPanel left = BeaconUi.panel(8); left.add(search, BorderLayout.NORTH); left.add(BeaconUi.scroll(table), BorderLayout.CENTER); left.add(count, BorderLayout.SOUTH);
        JPanel right = BeaconUi.panel(8);
        right.add(BeaconUi.scroll(selection), BorderLayout.NORTH);
        right.add(BeaconUi.section("End stack · Separate observation", BeaconUi.scroll(stack), source), BorderLayout.CENTER);
        add(BeaconUi.split(false, "hotThreads", left, right, .58f), BorderLayout.CENTER);
        JTextArea scope = BeaconUi.text("Platform threads only · CPU % is relative to one core, not the whole machine. End stacks do not identify the CPU-consuming method. Monitoring settings are never changed.", 2);
        scope.setLineWrap(true); scope.setWrapStyleWord(true); scope.setForeground(BeaconUi.MUTED);
        add(BeaconUi.scroll(scope), BorderLayout.SOUTH);
        setConnectionState(false, false);
    }

    void showReport(ThreadActivity.Report value) { report = value; filter(); updateSummary(); }
    void clear() { report = null; search.setText(""); filter(); updateSummary(); }
    void setConnectionState(boolean live, boolean busy) {
        connected = live; measure.setEnabled(live && !busy); updateSummary();
    }

    private void updateSummary() {
        details.setEnabled(report != null); copy.setEnabled(report != null);
        String text = report == null ? (connected ? "Ready · Explicit measurement; no background CPU polling." : "Connect a JVM to measure CPU activity.")
                : (connected ? "Captured · " : "Stale · ") + (report.unavailable() != null ? report.unavailable()
                : String.format(Locale.ROOT, "%.3f s interval · %d / %d candidates measured · Ended %s", report.intervalNanos() / 1e9,
                report.measuredCount(), report.selected(), TIME.format(Instant.ofEpochMilli(report.end().endMillis()))));
        summary.setText(text); summary.setToolTipText(text);
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        table.clearSelection();
        model.rows = report == null ? List.of() : report.rows().stream().filter(r ->
                (r.name() + " " + r.id() + " " + (r.endThread() == null ? "unavailable" : r.endThread().state())).toLowerCase(Locale.ROOT).contains(query)).toList();
        model.fireTableDataChanged(); select();
        table.getEmptyText().setText(report == null ? "Measure CPU to discover active platform threads" : report.unavailable() != null ? "CPU measurement unavailable · See capture details" : "No matching threads");
        count.setText(report == null ? "No measurement" : model.rows.size() + " shown / " + report.selected() + " baseline candidates / " + report.discovered() + " discovered" + (report.truncated() ? " · Truncated" : ""));
    }

    private void select() {
        frames.clear(); int view = table.getSelectedRow();
        if (view < 0 || view >= table.getRowCount()) { selection.setText("Select a measured thread to inspect its end stack.\nCPU time is not attributed to individual methods."); return; }
        ThreadActivity.Row row = model.rows.get(table.convertRowIndexToModel(view));
        selection.setText("#" + row.id() + " " + row.name() + "\nCPU delta: " + (row.cpuDeltaNanos() == null ? "Unavailable" : row.cpuDeltaNanos() + " ns")
                + "\n" + row.evidence() + (row.endThread() == null ? "" : "\nEnd state: " + row.endThread().state() + " · Lock: " + row.endThread().lockName() + " · Owner: " + row.endThread().lockOwnerName()));
        selection.setCaretPosition(0);
        if (row.endThread() != null) row.endThread().frames().forEach(frames::addElement);
    }

    private String reportText(boolean includeRows) {
        if (report == null) return "No CPU measurement.";
        StringBuilder text = new StringBuilder("JVM Beacon · Hot threads\nTarget: ").append(report.identity().runtimeName())
                .append("\nJVM: ").append(report.identity().vmName()).append(' ').append(report.identity().vmVersion())
                .append("\nProcess started: ").append(TIME.format(Instant.ofEpochMilli(report.identity().startTime())))
                .append("\n").append(report.source()).append("\n\n").append(ThreadActivity.COVERAGE)
                .append("\n\n").append(report.unavailable() == null ? "Counters use nanosecond units, not nanosecond accuracy. Interval uses client monotonic midpoints of the two counter reads. Reads and end stacks are not atomic. One core = 100%; estimates can exceed 100% with timing uncertainty." : report.unavailable());
        if (report.end() != null) text.append("\nBaseline: ").append(window(report.baseline())).append("\nEnd: ").append(window(report.end()))
                .append(String.format(Locale.ROOT, "\nCounter midpoint interval: %.3f ms\nDiscovered: %d · Baseline candidates: %d · Measured: %d · Truncated: %s",
                        report.intervalNanos() / 1e6, report.discovered(), report.selected(), report.measuredCount(), report.truncated()));
        else text.append("\nReport created: ").append(TIME.format(Instant.ofEpochMilli(report.baseline().endMillis()))).append(" · No complete counter measurement.");
        text.append("\n\nIdentity matches remain candidates: IDs can be reused and equal names do not prove the same thread. Counter reset/re-enable cycles between reads cannot be ruled out.\nThis capture is not included in .jvmb snapshots. Copied names and stacks are not redacted.\n");
        if (includeRows) for (ThreadActivity.Row row : report.rows()) {
            text.append("\n#").append(row.id()).append(' ').append(row.name()).append(" · CPU delta ns: ").append(row.cpuDeltaNanos() == null ? "Unavailable" : row.cpuDeltaNanos())
                    .append(" · Approx. one-core %: ").append(row.oneCorePercent() == null ? "Unavailable" : String.format(Locale.ROOT, "%.3f", row.oneCorePercent()))
                    .append("\n").append(row.evidence()).append('\n');
            if (row.endThread() != null) { text.append("End state: ").append(row.endThread().state()).append('\n'); row.endThread().frames().forEach(f -> text.append("  at ").append(f).append('\n')); }
        }
        return text.toString();
    }

    private static String window(ThreadActivity.Window w) {
        return TIME.format(Instant.ofEpochMilli(w.startMillis())) + " → " + TIME.format(Instant.ofEpochMilli(w.endMillis()))
                + String.format(Locale.ROOT, " · Counter read: %.3f ms", w.counterSpanNanos() / 1e6);
    }

    private static final class Rows extends AbstractTableModel {
        private List<ThreadActivity.Row> rows = List.of();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 5; }
        public String getColumnName(int c) { return new String[]{"Thread", "ID", "CPU · ms", "≈ 1 core · %", "End state"}[c]; }
        public Class<?> getColumnClass(int c) { return c == 1 ? Long.class : c == 2 || c == 3 ? Double.class : String.class; }
        public Object getValueAt(int r, int c) {
            var row = rows.get(r);
            return switch (c) { case 0 -> row.name(); case 1 -> row.id(); case 2 -> row.cpuDeltaNanos() == null ? null : row.cpuDeltaNanos() / 1e6;
                case 3 -> row.oneCorePercent(); default -> row.endThread() == null ? "Unavailable" : row.endThread().state(); };
        }
    }

    private static final class NumericCell extends DefaultTableCellRenderer {
        private final boolean bar;
        private double fraction;
        NumericCell(boolean bar) { this.bar = bar; setHorizontalAlignment(SwingConstants.RIGHT); putClientProperty("html.disable", true); }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            setFont(table.getFont()); setBorder(JBUI.Borders.empty(4, 10));
            setText(value == null ? "—" : value instanceof Double d ? String.format(Locale.ROOT, "%.3f", d) : value.toString());
            setToolTipText(value == null ? "Unavailable, not zero. Select the row for evidence." : "Captured value: " + value);
            fraction = bar && value instanceof Number n ? Math.max(0, Math.min(1, n.doubleValue() / 100)) : 0;
            return this;
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (fraction > 0) { g.setColor(BeaconUi.ACCENT); g.fillRect(JBUI.scale(8), getHeight() - JBUI.scale(3), (int) ((getWidth() - JBUI.scale(16)) * fraction), JBUI.scale(2)); }
        }
    }
}
