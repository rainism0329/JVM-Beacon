package dev.jvmbeacon.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.ThreadComparison;
import dev.jvmbeacon.core.LockChains;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Locale;

/** Fixed A/B evidence; subsequent live captures do not change this report. */
final class ThreadComparisonPanel extends JPanel {
    private final Rows rows = new Rows();
    private final JBTable table = new JBTable(rows);
    private final JBTextField search = new JBTextField();
    private final JTextArea summary = BeaconUi.text("", 3);
    private final JTextArea beforeDetail = BeaconUi.text("", 6);
    private final JTextArea afterDetail = BeaconUi.text("", 6);
    private final JButton copy = new JButton("Copy comparison");
    private final JButton evidence = new JButton("Comparison details…");
    private ThreadComparison.Report report;
    private String context = "";

    ThreadComparisonPanel(Project project, JComponent actions) {
        super(new BorderLayout(JBUI.scale(8), JBUI.scale(8))); setBorder(JBUI.Borders.empty(12, 16));
        JPanel top = BeaconUi.panel(6); top.add(actions, BorderLayout.NORTH);
        summary.setLineWrap(true); summary.setWrapStyleWord(true); top.add(BeaconUi.scroll(summary), BorderLayout.CENTER); add(top, BorderLayout.NORTH);
        for (JTextArea text : List.of(beforeDetail, afterDetail)) { text.setLineWrap(true); text.setWrapStyleWord(true); }
        search.getEmptyText().setText("Filter differences by name, ID or changed field…");
        search.getAccessibleContext().setAccessibleName("Filter fixed thread comparison");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        BeaconUi.table(table, "Pin a thread baseline, capture again, then compare"); table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] widths = {55, 260, 230, 140, 140};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(JBUI.scale(widths[i]));
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(80));
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) select(); });
        copy.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(context + "\n\n" + ThreadComparison.describe(report))));
        copy.setToolTipText("Copy retained differences and coverage. Names and stack frames are not redacted.");
        evidence.addActionListener(e -> new DialogWrapper(project, false) {
            { setTitle("Thread Comparison · Evidence and Coverage"); setOKButtonText("Close"); init(); }
            @Override protected JComponent createCenterPanel() {
                JTextArea text = BeaconUi.text(context + "\n\n" + ThreadComparison.describe(report), 18); text.setLineWrap(true); text.setWrapStyleWord(true);
                JComponent content = BeaconUi.scroll(text); content.setPreferredSize(JBUI.size(720, 420)); return content;
            }
            @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
        }.show());
        JPanel reportActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)); reportActions.add(evidence); reportActions.add(copy);
        JPanel differences = BeaconUi.panel(6); differences.add(search, BorderLayout.NORTH); differences.add(BeaconUi.scroll(table), BorderLayout.CENTER);
        JComponent observations = BeaconUi.split(false, "thread-before-after", BeaconUi.scroll(beforeDetail), BeaconUi.scroll(afterDetail), .5f);
        add(BeaconUi.split(true, "thread-comparison", differences, BeaconUi.section("A → B · Selected thread", observations, reportActions), .5f), BorderLayout.CENTER);
        clear();
    }
    void clear() { report = null; context = ""; summary.setText("Pin a captured thread snapshot as A. Capture again, then compare A → current B.\nOr use Snapshots → Compare with file; the selected file becomes A.\nComparisons are fixed; later samples do not overwrite them."); copy.setEnabled(false); evidence.setEnabled(false); filter(); table.getEmptyText().setText("No comparison yet; pin a baseline or compare with a file"); }
    void showReport(ThreadComparison.Report value, String labels) {
        report = value; context = labels;
        String counts = value.comparable() ? "Changes: state " + value.counts().stateChanges() + " · stack " + value.counts().stackChanges() + " · lock " + value.counts().lockChanges()
                + " · newly observed " + value.counts().newlyObserved() + " · no longer observed " + value.counts().noLongerObserved()
                + (value.inputTruncated() || value.differencesTruncated() ? " · Limited scope / details" : "") : value.explanation();
        summary.setText("A " + window(value.before()) + "\nB " + window(value.after()) + "\n" + counts
                + "\nPlatform threads · Matching IDs are candidates only. See Comparison details for identity, capture order and coverage."); summary.setCaretPosition(0);
        copy.setEnabled(true); evidence.setEnabled(true); filter();
        table.getEmptyText().setText(!value.comparable() ? "Comparison unavailable; see the capture details above" : "No matching differences in the compared fields");
    }
    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        rows.values = report == null ? List.of() : report.differences().stream().filter(d -> (d.id() + " " + name(d) + " " + label(d)).toLowerCase(Locale.ROOT).contains(query)).toList();
        table.clearSelection(); rows.fireTableDataChanged(); select();
    }
    private void select() {
        int row = table.getSelectedRow();
        if (row < 0) { beforeDetail.setText("A · Select a difference to inspect the earlier selection."); afterDetail.setText("B · Matching IDs are candidates; missing observations do not prove thread creation or exit."); return; }
        var d = rows.values.get(table.convertRowIndexToModel(row));
        beforeDetail.setText("A · #" + d.id() + "\n" + describe(d.before()) + (d.frameChange() == null ? "" : "\nFirst different frame · Depth " + d.frameChange().depth() + "\n" + d.frameChange().before()));
        afterDetail.setText("B · #" + d.id() + "\n" + describe(d.after()) + (d.frameChange() == null ? "" : "\nFirst different frame · Depth " + d.frameChange().depth() + "\n" + d.frameChange().after()));
        beforeDetail.setCaretPosition(0); afterDetail.setCaretPosition(0);
    }
    private static String describe(ThreadComparison.ThreadView t) { return t == null ? "Not observed in this capture" : t.name() + " · " + t.state() + "\n" + LockChains.ownerLabel(t.lockOwnerId()) + " · " + t.lockOwnerName() + "\nLock: " + t.lockName() + "\nTop frame: " + t.topFrame(); }
    private static String window(ThreadComparison.Window w) { return w == null ? "Not captured" : java.time.Instant.ofEpochMilli(w.captureStart()) + " → " + java.time.Instant.ofEpochMilli(w.captureEnd()) + " (UTC)"; }
    private static String name(ThreadComparison.Difference d) { return d.before() == null ? d.after().name() : d.after() == null || d.before().name().equals(d.after().name()) ? d.before().name() : d.before().name() + " → " + d.after().name(); }
    private static String label(ThreadComparison.Difference d) { return switch (d.kind()) { case NEWLY_OBSERVED -> "Newly observed"; case NO_LONGER_OBSERVED -> "No longer observed"; case CHANGED -> d.fields().stream().sorted().map(Enum::name).collect(java.util.stream.Collectors.joining(" · ")); }; }
    private static final class Rows extends AbstractTableModel {
        List<ThreadComparison.Difference> values = List.of();
        public int getRowCount() { return values.size(); }
        public int getColumnCount() { return 5; }
        public String getColumnName(int col) { return new String[]{"ID", "Thread", "Observation", "State A", "State B"}[col]; }
        public Class<?> getColumnClass(int col) { return col == 0 ? Long.class : String.class; }
        public Object getValueAt(int row, int col) { var d = values.get(row); return switch (col) { case 0 -> d.id(); case 1 -> name(d); case 2 -> label(d); case 3 -> d.before() == null ? "Not observed" : d.before().state(); default -> d.after() == null ? "Not observed" : d.after().state(); }; }
    }
}
