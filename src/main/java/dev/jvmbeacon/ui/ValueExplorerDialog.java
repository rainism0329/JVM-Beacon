package dev.jvmbeacon.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.StructuredValue;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;

/** Browses only immutable, bounded capture data. No remote reads and no remote objects on the EDT. */
final class ValueExplorerDialog extends DialogWrapper {
    private final StructuredValue capture;
    private final String context;
    private final Tree tree = new Tree();
    private final JBTextField search = new JBTextField();
    private final JTextArea detail = BeaconUi.text("", 6);
    private final JLabel matches = BeaconUi.label("", true);
    private final JTextArea rowDetail = BeaconUi.text("Select a cell to inspect its captured value.", 4);

    ValueExplorerDialog(Project project, String context, StructuredValue capture) {
        super(project, true);
        this.context = context; this.capture = capture;
        setTitle("Value explorer · JVM Beacon");
        setCancelButtonText("Close");
        init();
        BeaconUi.applyTypography(getContentPane());
        // Wrapped text and an unselected table tab can otherwise inflate the initial minimum.
        // Keep the complete dialog inside this monitor's work area, including at 125% zoom.
        Rectangle screen = getWindow().getGraphicsConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(getWindow().getGraphicsConfiguration());
        setSize(Math.min(JBUI.scale(880), screen.width - insets.left - insets.right - JBUI.scale(32)),
                Math.min(JBUI.scale(620), screen.height - insets.top - insets.bottom - JBUI.scale(32)));
        getWindow().setLocation(screen.x + insets.left + (screen.width - insets.left - insets.right - getSize().width) / 2,
                screen.y + insets.top + (screen.height - insets.top - insets.bottom - getSize().height) / 2);
    }

    @Override protected Action[] createActions() { return new Action[]{getCancelAction()}; }
    @Override public JComponent getPreferredFocusedComponent() { return search; }

    @Override protected JComponent createCenterPanel() {
        JPanel panel = BeaconUi.panel(10);
        panel.setPreferredSize(JBUI.size(880, 550));
        panel.setMinimumSize(JBUI.size(560, 360));
        JTextArea source = BeaconUi.text(context, 4);
        source.setLineWrap(true); source.setWrapStyleWord(true); source.setForeground(BeaconUi.MUTED);
        source.setBackground(BeaconUi.SURFACE); source.setBorder(JBUI.Borders.empty(0, 4));
        source.getAccessibleContext().setAccessibleName("Captured target and collection window");
        panel.add(BeaconUi.scroll(source), BorderLayout.NORTH);

        search.getEmptyText().setText("Search captured fields, types and values");
        search.getAccessibleContext().setAccessibleName("Search captured structure");
        tree.setRootVisible(true); tree.setShowsRootHandles(true); tree.setRowHeight(0);
        tree.putClientProperty("beacon.mono", true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.getAccessibleContext().setAccessibleName("Captured value structure");
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                setFont(t.getFont());
                if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof StructuredValue.Node data) {
                    setText(data.toString() + (data.limited() ? "  [truncated]" : ""));
                    setToolTipText(null);
                }
                return this;
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof StructuredValue.Node value) {
                detail.setText(describe(value)); detail.setCaretPosition(0);
            } else detail.setText("No matching captured fields. Adjust the search.");
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        JPanel left = BeaconUi.panel(8); left.add(search, BorderLayout.NORTH);
        left.add(BeaconUi.scroll(tree), BorderLayout.CENTER); left.add(matches, BorderLayout.SOUTH);
        JButton copy = new JButton("Copy value");
        copy.addActionListener(e -> {
            if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof StructuredValue.Node value) copy(value.value());
        });
        detail.putClientProperty("beacon.mono", true);
        detail.setLineWrap(true); detail.setWrapStyleWord(true);
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Structure", BeaconUi.split(false, "valueExplorer", left,
                BeaconUi.section("Selected node", BeaconUi.scroll(detail), copy), .52f));
        if (capture.root().kind() == StructuredValue.Kind.TABLE) tabs.addTab("Rows", rows());
        panel.add(tabs, BorderLayout.CENTER);
        JTextArea limits = BeaconUi.text((capture.incomplete() ? "PARTIAL CAPTURE" : "CAPTURED VALUE") + " · " + capture.nodeCount()
                + " nodes · Limits: 512 nodes, 100 children per node, depth 6, 32768 characters; shared attribute budget also applies.\n"
                + "Browse and search this capture without another remote read. Values are not automatically redacted or included in snapshot files.", 2);
        limits.setLineWrap(true); limits.setWrapStyleWord(true); limits.setForeground(BeaconUi.MUTED);
        limits.setBackground(BeaconUi.SURFACE); limits.setBorder(JBUI.Borders.empty(4));
        panel.add(limits, BorderLayout.SOUTH);
        filter();
        return panel;
    }

    private void filter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        int[] count = {0};
        DefaultMutableTreeNode root = filter(capture.root(), query, count);
        tree.setModel(new DefaultTreeModel(root));
        if (root != null) {
            if (!query.isEmpty()) for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
            else tree.expandRow(0);
            tree.setSelectionRow(0);
        } else detail.setText("No matching captured fields. Adjust the search.");
        matches.setText(query.isEmpty() ? capture.nodeCount() + " captured nodes · Arrow keys to explore"
                : count[0] + " matching nodes · Ancestors shown for context");
    }

    private static DefaultMutableTreeNode filter(StructuredValue.Node value, String query, int[] count) {
        boolean match = query.isEmpty() || (value.name() + "\n" + value.type() + "\n" + value.value()).toLowerCase(Locale.ROOT).contains(query);
        if (match) count[0]++;
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(value);
        for (StructuredValue.Node child : value.children()) {
            var found = filter(child, query, count);
            if (found != null) node.add(found);
        }
        return match || !node.isLeaf() ? node : null;
    }

    private JComponent rows() {
        // Columns come from captured row fields only, never from another metadata or value request.
        List<String> columns = capture.root().children().stream().flatMap(row -> row.children().stream())
                .map(StructuredValue.Node::name).distinct().limit(100).toList();
        DefaultTableModel model = new DefaultTableModel(columns.toArray(), 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        List<List<StructuredValue.Node>> cells = new ArrayList<>();
        for (var row : capture.root().children()) {
            List<StructuredValue.Node> values = new ArrayList<>();
            for (String name : columns) values.add(row.children().stream().filter(n -> n.name().equals(name)).findFirst().orElse(null));
            cells.add(values);
            model.addRow(values.stream().map(n -> n == null ? "[not captured]" : n.limited() ? "[truncated] " + n.value() : n.value()).toArray());
        }
        JBTable table = new JBTable(model); BeaconUi.table(table, "No rows captured; inspect Structure for details");
        table.setCellSelectionEnabled(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); table.setAutoCreateRowSorter(true);
        for (int i = 0; i < table.getColumnCount(); i++) table.getColumnModel().getColumn(i).setPreferredWidth(JBUI.scale(210));
        Runnable inspect = () -> {
            int row = table.getSelectedRow(), col = table.getSelectedColumn();
            if (row < 0 || col < 0) return;
            var cell = cells.get(table.convertRowIndexToModel(row)).get(table.convertColumnIndexToModel(col));
            rowDetail.setText(cell == null ? "This field was not captured. See the capture limits." : describe(cell));
            rowDetail.setCaretPosition(0);
        };
        table.getSelectionModel().addListSelectionListener(e -> inspect.run());
        table.getColumnModel().getSelectionModel().addListSelectionListener(e -> inspect.run());
        JPanel panel = BeaconUi.panel(6);
        JTextArea header = BeaconUi.text(capture.root().value() + "\nColumns and rows reflect the bounded capture. Nested cells can be expanded in Structure.", 2);
        header.setLineWrap(true); header.setWrapStyleWord(true); header.setForeground(BeaconUi.MUTED);
        panel.add(header, BorderLayout.NORTH);
        panel.add(BeaconUi.split(true, "valueRows", BeaconUi.scroll(table), BeaconUi.scroll(rowDetail), .70f), BorderLayout.CENTER);
        return panel;
    }

    private static String describe(StructuredValue.Node value) {
        return "Field: " + value.name() + "\nType: " + value.type() + "\nKind: " + value.kind()
                + (value.limited() ? "\nTRUNCATED · More data exists beyond this display limit." : "") + "\n\n" + value.value();
    }
    private static void copy(String value) { CopyPasteManager.getInstance().setContents(new StringSelection(value)); }
}
