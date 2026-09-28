package dev.jvmbeacon.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.Tree;
import dev.jvmbeacon.core.JfrStacks;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.*;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.function.Consumer;

final class JfrStacksPanel extends JPanel {
    private final JfrPanel.Jobs jobs;
    private final Consumer<String> status;
    private final JComboBox<JfrStacks.Kind> kind = new JComboBox<>(JfrStacks.Kind.values());
    private final JComboBox<Object> threads = new JComboBox<>();
    private final JButton apply = new JButton("Apply filters"), zoom = new JButton("Zoom selected"), reset = new JButton("Reset zoom");
    private final JButton source = new JButton("Find source candidate…"), copy = new JButton("Copy evidence");
    private final JBTextField highlight = new JBTextField();
    private final JLabel summary = BeaconUi.label("No recording inspected", true);
    private final JLabel losses = BeaconUi.label("Limits and missing evidence will appear here", true);
    private final JTextArea details = BeaconUi.text("Select a frame to inspect its sample counts and recorded location.", 3);
    private final JTextArea coverage = BeaconUi.text("Open a local JFR recording first. No target calls are made by this analysis.", 12);
    private final Tree tree = new Tree(new DefaultMutableTreeNode("No recording inspected"));
    private final JfrFlameGraph flame = new JfrFlameGraph(this::selectFromGraph);
    private final Map<JfrStacks.Node, DefaultMutableTreeNode> treeNodes = new IdentityHashMap<>();
    private JfrStacks.Data data;
    private JfrStacks.View view;
    private JfrStacks.Node selected;
    private boolean busy;
    private long generation;

    JfrStacksPanel(JfrPanel.Jobs jobs, Consumer<JfrStacks.Frame> navigate, Consumer<String> status) {
        super(new BorderLayout(0, 6)); this.jobs = jobs; this.status = status;
        threads.setPrototypeDisplayValue("All retained sampled threads · Java #00000");
        threads.setRenderer(new DefaultListCellRenderer() { { putClientProperty("html.disable", true); } });
        threads.getAccessibleContext().setAccessibleName("Retained sampled thread filter");
        kind.getAccessibleContext().setAccessibleName("JFR sample event kind");
        highlight.setColumns(18); highlight.getEmptyText().setText("Highlight method / class…");
        highlight.getAccessibleContext().setAccessibleName("Highlight frames without changing counts");
        JPanel top = BeaconUi.panel(4);
        top.add(BeaconUi.row(kind, threads, apply, highlight), BorderLayout.NORTH);
        top.add(summary, BorderLayout.CENTER); top.add(losses, BorderLayout.SOUTH); add(top, BorderLayout.NORTH);
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Flame graph", BeaconUi.scroll(flame)); tabs.addTab("Call tree", BeaconUi.scroll(tree));
        tabs.addTab("Coverage", BeaconUi.scroll(coverage)); add(tabs, BorderLayout.CENTER);
        tree.putClientProperty("beacon.mono", true);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            { putClientProperty("html.disable", true); }
            @Override public java.awt.Component getTreeCellRendererComponent(JTree t, Object value, boolean s, boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, s, expanded, leaf, row, focus);
                setFont(t.getFont()); setIcon(null); return this;
            }
        });
        tree.getAccessibleContext().setAccessibleName("Sampled call paths with inclusive and self counts");
        tree.addTreeSelectionListener(e -> {
            Object node = tree.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof JfrStacks.Node value) select(value);
        });
        JPanel bottom = BeaconUi.panel(4);
        details.putClientProperty("beacon.mono", true);
        details.setRows(2); details.setBorder(com.intellij.util.ui.JBUI.Borders.empty(4, 8));
        bottom.add(BeaconUi.scroll(details), BorderLayout.CENTER);
        bottom.add(BeaconUi.row(zoom, reset, source, copy), BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        kind.addActionListener(e -> populateThreads());
        apply.addActionListener(e -> apply());
        zoom.addActionListener(e -> { if (selected != null) flame.show(selected); });
        reset.addActionListener(e -> { if (view != null) flame.show(view.root()); });
        source.addActionListener(e -> { if (selected != null && selected.frame() != null) navigate.accept(selected.frame()); });
        copy.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(coverage.getText() + "\nSelected frame evidence:\n" + details.getText())));
        highlight.getDocument().addDocumentListener(new DocumentListener() {
            private void update() { flame.highlight(highlight.getText()); }
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        });
        zoom.setToolTipText("Re-scale the selected call path. Global sample shares in the details remain unchanged.");
        populateThreads(); updateActions();
    }
    void clear() {
        generation++; data = null; view = null; selected = null; treeNodes.clear();
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("No recording inspected"))); flame.show(null);
        summary.setText("No recording inspected"); coverage.setText("Open a local JFR recording first.");
        losses.setText("Limits and missing evidence will appear here");
        details.setText("Select a frame to inspect its sample counts and recorded location."); highlight.setText("");
        populateThreads(); updateActions();
    }
    void load(JfrStacks.Data next, JfrStacks.View initial) {
        generation++; data = next; highlight.setText(""); kind.setSelectedItem(JfrStacks.Kind.JAVA); populateThreads(); show(initial);
    }
    JfrStacks.Kind activeKind() { return view == null ? JfrStacks.Kind.JAVA : view.kind(); }
    JfrStacks.SampledThread activeThread() { return view == null ? null : view.thread(); }
    void loadScoped(JfrStacks.Data next, JfrStacks.View initial) {
        generation++; data = next; kind.setSelectedItem(initial.kind()); populateThreads();
        if (initial.thread() != null) {
            if (!next.threads(initial.kind()).contains(initial.thread())) threads.addItem(initial.thread());
            threads.setSelectedItem(initial.thread());
        }
        show(initial);
    }
    void setBusy(boolean busy) { this.busy = busy; updateActions(); }
    private void populateThreads() {
        threads.removeAllItems(); threads.addItem("All retained sampled threads");
        if (data != null) for (var thread : data.threads((JfrStacks.Kind) kind.getSelectedItem())) threads.addItem(thread);
    }
    private void apply() {
        if (data == null || busy) return;
        JfrStacks.Data captured = data; long expected = generation;
        JfrStacks.Kind selectedKind = (JfrStacks.Kind) kind.getSelectedItem();
        JfrStacks.SampledThread thread = threads.getSelectedItem() instanceof JfrStacks.SampledThread t ? t : null;
        jobs.run("Aggregate retained JFR samples", false, 8_000, () -> JfrStacks.aggregate(captured, selectedKind, thread),
                result -> { if (expected == generation) show(result); }, error -> {
                    if (expected == generation) { summary.setText("Analysis failed · Previous results remain below"); coverage.setText(error + "\n\n" + (view == null ? "" : view.text())); }
                });
    }
    private void show(JfrStacks.View next) {
        view = next; selected = null; treeNodes.clear();
        tree.setModel(new DefaultTreeModel(treeNode(next.root()))); tree.expandRow(0);
        flame.show(next.root()); coverage.setText(next.text()); coverage.setCaretPosition(0);
        summary.setText(next.kind().event + " · " + next.root().inclusive() + " represented samples · Roots at top · Width ≠ CPU time");
        summary.setToolTipText("Displayed thread filter: " + (next.thread() == null ? "All retained sampled threads" : next.thread())
                + "; window: " + next.first() + " → " + next.last() + ". Change selectors then Apply filters.");
        losses.setText((next.partialScan() ? "PARTIAL scan" : "End of file") + " · Scoped missing: " + next.counts().missing()
                + " / omitted: " + next.counts().omitted() + " / truncated: " + next.counts().truncated() + " · Tree omissions: " + next.omitted() + " · See Coverage");
        select(next.root()); updateActions();
        status.accept("Local JFR sampled stacks ready · " + next.root().inclusive() + " represented samples. Coverage lists the active filters and limits.");
    }
    private DefaultMutableTreeNode treeNode(JfrStacks.Node value) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(value); treeNodes.put(value, node);
        for (var child : value.children()) node.add(treeNode(child)); return node;
    }
    private void selectFromGraph(JfrStacks.Node value) {
        var node = treeNodes.get(value); if (node != null) { TreePath path = new TreePath(node.getPath()); tree.setSelectionPath(path); }
        select(value);
    }
    private void select(JfrStacks.Node value) {
        selected = value; flame.select(value);
        String share = view == null || view.root().inclusive() == 0 ? "n/a" : String.format(Locale.ROOT, "%.2f%%", 100.0 * value.inclusive() / view.root().inclusive());
        details.setText(value.label() + "\n" + value.inclusive() + " inclusive · " + value.self() + " self · " + share + " of represented samples (not CPU time)"
                + (value.frame() == null ? "" : "\nDescriptor: " + value.frame().descriptor() + " · Recorded class #" + value.frame().classId() + " · Line: " + value.frame().line()));
        details.setCaretPosition(0); updateActions();
    }
    private void updateActions() {
        kind.setEnabled(!busy && data != null); threads.setEnabled(!busy && data != null); apply.setEnabled(!busy && data != null);
        zoom.setEnabled(selected != null && selected.inclusive() > 0); reset.setEnabled(view != null); copy.setEnabled(view != null);
        source.setEnabled(selected != null && selected.frame() != null && selected.frame().line() > 0);
    }
}
