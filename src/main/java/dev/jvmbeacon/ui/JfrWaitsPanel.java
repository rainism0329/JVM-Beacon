package dev.jvmbeacon.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.JfrStacks;
import dev.jvmbeacon.core.JfrWaits;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.time.Instant;

/** Filtering runs on LOCAL_IO; tables contain immutable local evidence and update in one batch. */
final class JfrWaitsPanel extends JPanel {
    private final JfrPanel.Jobs jobs;
    private final Consumer<JfrStacks.Frame> navigate;
    private final Consumer<String> status;
    private final BiConsumer<Instant, Instant> focusEvent;
    private JfrWaits.Kind activeKind;
    private String activeQuery = "";
    private final JComboBox<Object> kind = new JComboBox<>(new Object[]{"All event kinds", JfrWaits.Kind.ENTER, JfrWaits.Kind.WAIT, JfrWaits.Kind.PARK});
    private final JBTextField search = new JBTextField();
    private final JButton apply = new JButton("Apply filters"), drill = new JButton("Show hotspot events"), reset = new JButton("All filtered events");
    private final JButton inspect = new JButton("Inspect event…"), copy = new JButton("Copy report");
    private final JLabel summary = BeaconUi.label("Open a local .jfr to inspect recorded waits", true);
    private final JTextArea coverage = BeaconUi.text("No local recording inspected.", 12);
    private final Rows hotModel = new Rows(new String[]{"Kind", "Target class", "Recorded leaf", "Events", "Sum · ms", "Max · ms"},
            new Class<?>[]{String.class, String.class, String.class, Integer.class, BigDecimal.class, BigDecimal.class});
    private final Rows eventModel = new Rows(new String[]{"Kind", "Event thread", "Target class", "Duration · ms", "Start (UTC)"},
            new Class<?>[]{String.class, String.class, String.class, BigDecimal.class, String.class});
    private final JBTable hotTable = new JBTable(hotModel), eventTable = new JBTable(eventModel);
    private final JBTabbedPane tabs = new JBTabbedPane();
    private JfrWaits.Data data; private JfrWaits.View view; private List<JfrWaits.Event> events = List.of();
    private boolean busy; private long generation;

    JfrWaitsPanel(JfrPanel.Jobs jobs, Consumer<JfrStacks.Frame> navigate, Consumer<String> status) {
        this(jobs, navigate, status, (start, end) -> { });
    }
    JfrWaitsPanel(JfrPanel.Jobs jobs, Consumer<JfrStacks.Frame> navigate, Consumer<String> status, BiConsumer<Instant, Instant> focusEvent) {
        super(new BorderLayout(JBUI.scale(6), JBUI.scale(6))); this.jobs = jobs; this.navigate = navigate; this.status = status; this.focusEvent = focusEvent;
        kind.setRenderer(new DefaultListCellRenderer() { { putClientProperty("html.disable", true); } });
        kind.getAccessibleContext().setAccessibleName("Wait event kind filter");
        search.setPreferredSize(JBUI.size(280, 28)); search.getEmptyText().setText("Thread / class / recorded method…");
        search.getAccessibleContext().setAccessibleName("Search retained wait events");
        JPanel top = BeaconUi.panel(3); top.add(BeaconUi.row(kind, search, apply), BorderLayout.NORTH);
        top.add(summary, BorderLayout.SOUTH); add(top, BorderLayout.NORTH);
        for (JBTable table : List.of(hotTable, eventTable)) {
            BeaconUi.table(table, "No retained matching events. Missing events do not rule out a problem.");
            table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.putClientProperty("beacon.mono", true);
            table.getSelectionModel().addListSelectionListener(e -> actions());
            table.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) { if (table == hotTable) drill(); else inspect(); } } });
            table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "beacon.inspectWait");
            table.getActionMap().put("beacon.inspectWait", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (table == hotTable) drill(); else inspect(); } });
        }
        columnWidths(hotTable, 140, 220, 265, 80, 120, 120);
        columnWidths(eventTable, 140, 250, 215, 120, 230);
        hotTable.getTableHeader().setToolTipText("Sum / Max: completed event durations in milliseconds. Sums can overlap across threads; they are not CPU time.");
        tabs.addTab("Wait hotspots", BeaconUi.scroll(hotTable)); tabs.addTab("Events", BeaconUi.scroll(eventTable));
        tabs.addTab("Coverage", BeaconUi.scroll(coverage)); tabs.addChangeListener(e -> actions()); add(tabs, BorderLayout.CENTER);
        add(BeaconUi.row(drill, reset, inspect, copy), BorderLayout.SOUTH);
        apply.addActionListener(e -> apply()); search.addActionListener(e -> apply()); drill.addActionListener(e -> drill());
        reset.addActionListener(e -> { if (view != null) { showEvents(view.events()); tabs.setSelectedIndex(1); } });
        inspect.addActionListener(e -> inspect()); copy.addActionListener(e -> {
            if (view != null) CopyPasteManager.getInstance().setContents(new StringSelection(report()));
        });
        drill.setToolTipText("Show all retained events belonging to the selected class + recorded leaf group. Enter also opens it.");
        actions();
    }
    void clear() {
        generation++; data = null; view = null; events = List.of(); hotModel.replace(List.of()); eventModel.replace(List.of());
        activeKind = null; activeQuery = "";
        search.setText(""); kind.setSelectedIndex(0); coverage.setText("No local recording inspected.");
        summary.setText("Open a local .jfr to inspect recorded waits"); actions();
    }
    void load(JfrWaits.Data data, JfrWaits.View initial) { clear(); this.data = data; show(initial); }
    JfrWaits.Kind activeKind() { return activeKind; }
    String activeQuery() { return activeQuery; }
    void loadScoped(JfrWaits.Data data, JfrWaits.View initial, JfrWaits.Kind selectedKind, String query) {
        load(data, initial); activeKind = selectedKind; activeQuery = query;
        kind.setSelectedItem(selectedKind == null ? "All event kinds" : selectedKind); search.setText(query);
    }
    void setBusy(boolean busy) { this.busy = busy; actions(); }
    private void actions() {
        apply.setEnabled(data != null && !busy); kind.setEnabled(!busy); search.setEnabled(!busy);
        drill.setEnabled(view != null && tabs.getSelectedIndex() == 0 && hotTable.getSelectedRow() >= 0);
        reset.setEnabled(view != null); inspect.setEnabled(selectedEvent() != null); copy.setEnabled(view != null);
    }
    private void apply() {
        if (data == null || busy) return;
        var current = data; long expected = generation; var filterKind = kind.getSelectedItem() instanceof JfrWaits.Kind k ? k : null;
        String query = search.getText();
        jobs.run("Filter retained JFR waits", false, 8_000, () -> JfrWaits.filter(current, filterKind, query),
                result -> { if (generation == expected) { activeKind = filterKind; activeQuery = query; show(result); } }, error -> { if (generation == expected) status.accept(error + " · Previous wait report retained."); });
    }
    private void show(JfrWaits.View next) {
        view = next;
        hotModel.replace(next.hotspots().stream().map(h -> new Object[]{h.key().kind().label, h.key().target().name(),
                h.key().leaf() == null ? "Stack unavailable" : h.key().leaf().label(), h.events().size(), ms(h.totalNanos()), ms(BigInteger.valueOf(h.maxNanos()))}).toList());
        hotTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(4, SortOrder.DESCENDING)));
        showEvents(next.events()); coverage.setText(next.text()); coverage.setCaretPosition(0); actions();
        status.accept("Wait analysis ready. Duration sums can overlap across threads; inspect events before drawing conclusions.");
    }
    private void showEvents(List<JfrWaits.Event> next) {
        events = next;
        eventModel.replace(next.stream().map(e -> new Object[]{e.kind().label, e.thread().toString(), e.target().name(), ms(BigInteger.valueOf(e.nanos())), e.start().toString()}).toList());
        summary.setText(next.size() + " shown / " + view.events().size() + " filtered events · " + view.hotspots().size()
                + " hotspots · " + (data.partial() ? "PARTIAL scan" : "See Coverage for omissions"));
        summary.setToolTipText("Hotspots sum completed event durations, not CPU time or wall-time percentages. Active filter is recorded in Coverage.");
    }
    private void drill() {
        int row = hotTable.getSelectedRow(); if (view == null || row < 0) return;
        showEvents(view.hotspots().get(hotTable.convertRowIndexToModel(row)).events()); tabs.setSelectedIndex(1);
    }
    private JfrWaits.Event selectedEvent() {
        int row = eventTable.getSelectedRow(); if (view == null || tabs.getSelectedIndex() != 1 || row < 0 || row >= eventTable.getRowCount()) return null;
        int model = eventTable.convertRowIndexToModel(row); return model < events.size() ? events.get(model) : null;
    }
    private String report() {
        var event = selectedEvent();
        String prefix = event == null ? "" : event.evidence() + "\n";
        int row = hotTable.getSelectedRow();
        if (tabs.getSelectedIndex() == 0 && row >= 0 && row < hotTable.getRowCount()) {
            var hotspot = view.hotspots().get(hotTable.convertRowIndexToModel(row));
            prefix = "Selected hotspot: " + hotspot.key() + "\nEvents: " + hotspot.events().size()
                    + "\nDuration sum: " + hotspot.totalNanos() + " ns · Max: " + hotspot.maxNanos() + " ns\n\n";
        }
        return prefix + view.text();
    }
    private void inspect() {
        var event = selectedEvent(); if (event == null) return;
        long expected = generation;
        new EventDialog(event, navigate, view.text(), (start, end) -> { if (generation == expected && !busy) focusEvent.accept(start, end); }).show();
    }
    private static BigDecimal ms(BigInteger nanos) { return new BigDecimal(nanos, 6); }
    private static void columnWidths(JBTable table, int... widths) {
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(JBUI.scale(widths[i]));
    }
    private static final class Rows extends AbstractTableModel {
        private final String[] columns; private final Class<?>[] types; private List<Object[]> rows = List.of();
        Rows(String[] columns, Class<?>[] types) { this.columns = columns; this.types = types; }
        void replace(List<Object[]> next) { rows = List.copyOf(next); fireTableDataChanged(); }
        public int getRowCount() { return rows.size(); } public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; } public Class<?> getColumnClass(int c) { return types[c]; }
        public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
    }
    private static final class EventDialog extends DialogWrapper {
        private final JfrWaits.Event event; private final Consumer<JfrStacks.Frame> navigate; private final String report;
        private final BiConsumer<Instant, Instant> focusEvent;
        EventDialog(JfrWaits.Event event, Consumer<JfrStacks.Frame> navigate, String report, BiConsumer<Instant, Instant> focusEvent) {
            super(true); this.event = event; this.navigate = navigate; this.report = report; this.focusEvent = focusEvent; setTitle("JFR wait event · " + event.kind().label); setOKButtonText("Close"); init();
        }
        @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
        @Override protected JComponent createCenterPanel() {
            JPanel panel = BeaconUi.panel(6); panel.setPreferredSize(JBUI.size(880, 490));
            JTextArea evidence = BeaconUi.text(event.evidence(), 9); evidence.putClientProperty("beacon.mono", true);
            JBList<JfrStacks.Frame> stack = new JBList<>(event.stack().toArray(JfrStacks.Frame[]::new));
            stack.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); stack.putClientProperty("beacon.mono", true);
            stack.getEmptyText().setText("Stack " + event.stackState() + "; event duration remains available.");
            stack.setCellRenderer(new DefaultListCellRenderer() {
                { putClientProperty("html.disable", true); }
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    return super.getListCellRendererComponent(list, value instanceof JfrStacks.Frame f ? f.label() + "  " + f.descriptor() : value, index, selected, focus);
                }
            });
            JButton source = new JButton("Find source candidate…"), copy = new JButton("Copy event & coverage"); source.setEnabled(false);
            stack.addListSelectionListener(e -> source.setEnabled(stack.getSelectedValue() != null && stack.getSelectedValue().line() > 0));
            source.addActionListener(e -> { var selected = stack.getSelectedValue(); if (selected != null) { close(OK_EXIT_CODE); navigate.accept(selected); } });
            copy.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(event.evidence()
                    + event.stack().stream().map(f -> f.label() + " " + f.descriptor()).collect(java.util.stream.Collectors.joining("\n")) + "\n\n" + report)));
            panel.add(BeaconUi.split(true, "jfr-wait-event", BeaconUi.scroll(evidence), BeaconUi.scroll(stack), .48f), BorderLayout.CENTER);
            JButton focus = new JButton("Focus event ±100 ms");
            focus.addActionListener(e -> { close(OK_EXIT_CODE); focusEvent.accept(event.start(), event.end()); });
            panel.add(BeaconUi.row(BeaconUi.label("Recorded stack · leaf first", true), source, copy, focus), BorderLayout.SOUTH);
            BeaconUi.applyTypography(panel); return panel;
        }
    }
}
