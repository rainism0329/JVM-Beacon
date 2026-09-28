package dev.jvmbeacon.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.JfrStacks.Node;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Sample-count flame graph (roots at top). The adjacent native tree is the keyboard equivalent. */
final class JfrFlameGraph extends JComponent {
    private record Hit(Rectangle bounds, Node node) { }
    private final List<Hit> hits = new ArrayList<>();
    private final Consumer<Node> selection;
    private Node root, selected;
    private String highlight = "";
    private int depth;
    private static final Color[] COLORS = {
            new JBColor(0xCFEEE9, 0x234E49), new JBColor(0xD5E8F8, 0x293F59),
            new JBColor(0xE6DFF7, 0x473657), new JBColor(0xF4E6C8, 0x55462A)};

    JfrFlameGraph(Consumer<Node> selection) {
        this.selection = selection;
        putClientProperty("beacon.mono", true); setOpaque(true); setBackground(BeaconUi.CANVAS);
        getAccessibleContext().setAccessibleName("Sample-count flame graph. Use Call tree for keyboard navigation and exact values.");
        setToolTipText("");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                for (Hit hit : hits) if (hit.bounds.contains(e.getPoint())) { selection.accept(hit.node); break; }
            }
        });
    }
    @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() { };
        return accessibleContext;
    }
    void show(Node node) {
        root = node; depth = node == null ? 0 : depth(node); hits.clear();
        if (node == null) selected = null;
        if (getParent() instanceof JViewport viewport) viewport.setViewPosition(new Point());
        revalidate(); repaint();
    }
    void select(Node node) { selected = node; repaint(); }
    void highlight(String text) { highlight = text.toLowerCase(Locale.ROOT); repaint(); }
    private static int depth(Node n) { return 1 + n.children().stream().mapToInt(JfrFlameGraph::depth).max().orElse(0); }
    private int rowHeight() { return getFontMetrics(getFont() == null ? BeaconUi.font() : getFont()).getHeight() + JBUI.scale(12); }
    @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(600), Math.max(JBUI.scale(130), depth * rowHeight() + JBUI.scale(8))); }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(BeaconUi.CANVAS); g.fillRect(0, 0, getWidth(), getHeight()); hits.clear();
            g.setFont(getFont() == null ? BeaconUi.font() : getFont());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (root == null || root.inclusive() == 0) {
                g.setColor(BeaconUi.MUTED); g.drawString(root == null ? "Open a local recording to inspect sampled stacks."
                        : "No represented stacks for this selection. Check Coverage for missing or omitted samples.", JBUI.scale(12), JBUI.scale(30));
            } else paintNode(g, root, 0, getWidth(), 0);
        } finally { g.dispose(); }
    }
    private void paintNode(Graphics2D g, Node node, double left, double width, int level) {
        if (width < 1) return;
        int x = (int) left, right = (int) (left + width), y = level * rowHeight();
        Rectangle rect = new Rectangle(x, y, Math.max(1, right - x - 1), rowHeight() - 1);
        hits.add(new Hit(rect, node));
        if (g.getClipBounds().intersects(rect)) {
            boolean match = !highlight.isEmpty() && node.label().toLowerCase(Locale.ROOT).contains(highlight);
            g.setColor(COLORS[Math.floorMod(node.frame() == null ? 0 : node.frame().className().hashCode(), COLORS.length)]);
            g.fillRect(rect.x, rect.y, rect.width, rect.height);
            if (node == selected || match) { g.setColor(BeaconUi.ACCENT); g.drawRect(rect.x + 1, rect.y + 1, Math.max(0, rect.width - 3), rect.height - 3); }
            Graphics2D label = (Graphics2D) g.create();
            try {
                label.clipRect(rect.x + JBUI.scale(5), rect.y, Math.max(0, rect.width - JBUI.scale(10)), rect.height);
                label.setColor(JBColor.foreground());
                label.drawString(node.inclusive() + "  " + node.label(), rect.x + JBUI.scale(6), rect.y + (rect.height + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2);
            } finally { label.dispose(); }
        }
        double offset = left;
        for (Node child : node.children()) {
            double childWidth = width * child.inclusive() / node.inclusive();
            paintNode(g, child, offset, childWidth, level + 1); offset += childWidth;
        }
    }
    @Override public String getToolTipText(MouseEvent e) {
        for (Hit hit : hits) if (hit.bounds.contains(e.getPoint())) return "Frame: " + hit.node.label() + " · " + hit.node.inclusive()
                + " inclusive / " + hit.node.self() + " self samples. Click for exact details; Zoom selected to expand narrow frames.";
        return "Width = represented samples, not time. Gaps below a frame are self samples. Subpixel frames are available in Call tree.";
    }
}
