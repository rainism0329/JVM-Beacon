package dev.jvmbeacon.ui;

import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import dev.jvmbeacon.core.JmxClient;
import dev.jvmbeacon.core.LockChains;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Selection, filtering and owner traversal only inspect the captured, bounded thread dump. */
final class LockChainsPanel extends JPanel {
    private LockChains capture = LockChains.from(null);
    private final Rows rows = new Rows();
    private final JBTable table = new JBTable(rows);
    private final JBTextField search = new JBTextField();
    private final JLabel count = BeaconUi.label("No thread capture", true);
    private final JTextArea evidence = BeaconUi.text("", 2);
    private final JTextArea ending = BeaconUi.text("Select a captured waiter to follow owner IDs.", 1);
    private final DefaultListModel<JmxClient.ThreadRecord> chainModel = new DefaultListModel<>();
    private final JBList<JmxClient.ThreadRecord> chain = new JBList<>(chainModel);
    private final DefaultListModel<StackTraceElement> frameModel = new DefaultListModel<>();
    private final JBList<StackTraceElement> frames = new JBList<>(frameModel);
    private final JLabel stackTitle = BeaconUi.label("Select a chain member to inspect its stack", true);

    LockChainsPanel(Project project, JButton captureButton, Consumer<StackTraceElement> navigate) {
        super(new BorderLayout(JBUI.scale(8), JBUI.scale(8))); setBorder(JBUI.Borders.empty(12, 16));
        JPanel top = BeaconUi.panel(6);
        JButton details = new JButton("Capture details…");
        details.addActionListener(e -> new DialogWrapper(project, false) {
            { setTitle("Lock Chains · Capture Details"); setOKButtonText("Close"); init(); }
            @Override protected JComponent createCenterPanel() {
                JTextArea text = BeaconUi.text(capture.evidence(), 15); text.setLineWrap(true); text.setWrapStyleWord(true);
                JComponent content = BeaconUi.scroll(text); content.setPreferredSize(JBUI.size(680, 360)); return content;
            }
            @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
        }.show());
        JPanel actions = new JPanel(new BeaconUi.WrapLayout()); actions.add(captureButton); actions.add(details); actions.add(count);
        top.add(actions, BorderLayout.NORTH);
        for (JTextArea text : List.of(evidence, ending)) { text.setLineWrap(true); text.setWrapStyleWord(true); }
        top.add(BeaconUi.scroll(evidence), BorderLayout.CENTER); add(top, BorderLayout.NORTH);
        search.getEmptyText().setText("Filter waiters by name, ID, state or owner…");
        search.getAccessibleContext().setAccessibleName("Filter captured lock waiters");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        BeaconUi.table(table, "Capture threads to inspect lock waits");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true);
        int[] widths = {200, 45, 115, 150};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(JBUI.scale(widths[i]));
            table.getColumnModel().getColumn(i).setMinWidth(JBUI.scale(i == 0 ? 130 : widths[i]));
        }
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectWaiter(); });
        JPanel waiters = BeaconUi.panel(6); waiters.add(search, BorderLayout.NORTH); waiters.add(BeaconUi.scroll(table), BorderLayout.CENTER);
        chain.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chain.setCellRenderer(new DefaultListCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int i, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, i, selected, focus);
                if (value instanceof JmxClient.ThreadRecord t) setText((i == 0 ? "WAIT " : "  →  ") + "#" + t.id() + " " + t.name() + " · " + t.state());
                setBorder(JBUI.Borders.empty(5, 8)); return this;
            }
        });
        chain.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            frameModel.clear(); var thread = chain.getSelectedValue();
            if (thread == null) { stackTitle.setText("Select a chain member to inspect its stack"); return; }
            thread.frames().forEach(frameModel::addElement);
            stackTitle.setText("#" + thread.id() + " " + thread.name() + " · " + LockChains.ownerLabel(thread.lockOwnerId()));
            frames.getEmptyText().setText("No stack frames captured for this thread");
        });
        JPanel path = BeaconUi.panel(6); path.add(BeaconUi.scroll(chain), BorderLayout.CENTER); path.add(BeaconUi.scroll(ending), BorderLayout.SOUTH);
        frames.putClientProperty("beacon.mono", true); frames.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        frames.setCellRenderer(new DefaultListCellRenderer() {{ putClientProperty("html.disable", Boolean.TRUE); }});
        JButton source = new JButton("Go to source"); source.setEnabled(false);
        frames.addListSelectionListener(e -> source.setEnabled(frames.getSelectedValue() != null));
        Runnable go = () -> { if (frames.getSelectedValue() != null) navigate.accept(frames.getSelectedValue()); };
        source.addActionListener(e -> go.run());
        frames.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) go.run(); } });
        frames.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "source");
        frames.getActionMap().put("source", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { go.run(); } });
        JPanel stack = BeaconUi.panel(6); stack.add(stackTitle, BorderLayout.NORTH); stack.add(BeaconUi.scroll(frames), BorderLayout.CENTER);
        JComponent detail = BeaconUi.split(true, "lock-chain-members", BeaconUi.section("Waiter → observed owners", path, null),
                BeaconUi.section("Captured stack · Up to 64 frames", stack, source), .55f);
        add(BeaconUi.split(false, "lock-waiter-table", waiters, detail, .46f), BorderLayout.CENTER);
        showCapture(null);
    }
    void showCapture(JmxClient.ThreadDump dump) {
        capture = LockChains.from(dump);
        evidence.setText(capture.evidence().lines().findFirst().orElse("") + "\nPlatform threads · Non-atomic observation. Open Capture details for coverage and the separate deadlock query.");
        evidence.setCaretPosition(0); filter();
        table.getEmptyText().setText(dump == null ? "Capture threads or open a snapshot" : "No matching waits in captured records; this does not rule out contention");
    }
    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        rows.values = capture.waiters().stream().filter(t -> (t.name() + " " + t.id() + " " + t.state() + " " + t.lockOwnerName() + " " + LockChains.ownerLabel(t.lockOwnerId())).toLowerCase(Locale.ROOT).contains(query)).toList();
        table.clearSelection(); rows.fireTableDataChanged(); selectWaiter();
        count.setText(capture.available() ? rows.values.size() + " / " + capture.waiters().size() + " captured waiters" : "Capture unavailable");
    }
    private void selectWaiter() {
        chainModel.clear(); frameModel.clear(); int row = table.getSelectedRow();
        if (row < 0) { ending.setText("Select a captured waiter to follow owner IDs. No selection triggers a new target read."); return; }
        var path = capture.path(rows.values.get(table.convertRowIndexToModel(row)).id());
        path.threads().forEach(chainModel::addElement); ending.setText(path.end()); ending.setCaretPosition(0);
        if (!chainModel.isEmpty()) chain.setSelectedIndex(0);
    }
    private static final class Rows extends AbstractTableModel {
        List<JmxClient.ThreadRecord> values = List.of();
        public int getRowCount() { return values.size(); }
        public int getColumnCount() { return 4; }
        public String getColumnName(int col) { return new String[]{"Waiter", "ID", "State", "Observed owner"}[col]; }
        public Class<?> getColumnClass(int col) { return col == 1 ? Long.class : String.class; }
        public Object getValueAt(int row, int col) { var t = values.get(row); return switch (col) { case 0 -> t.name(); case 1 -> t.id(); case 2 -> t.state(); default -> LockChains.ownerLabel(t.lockOwnerId()); }; }
    }
}
