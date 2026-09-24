package dev.jvmbeacon.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/** Small shared presentation vocabulary. Never changes IDE-wide Swing defaults. */
final class BeaconUi {
    static final Color MUTED = JBColor.namedColor("JVMBeacon.secondaryForeground", 0x5C6066, 0xA8ADB7);
    static final Color SURFACE = JBColor.lazy(UIUtil::getPanelBackground);
    static final Color CANVAS = JBColor.namedColor("JVMBeacon.canvas", 0xFFFFFF, 0x232428);
    static final Color ACCENT = JBColor.namedColor("JVMBeacon.signal", 0x007E83, 0x48D5C5);
    private static final Color STALE = JBColor.namedColor("JVMBeacon.stale", 0x946100, 0xE6B665);
    private static final Color SIGNAL_SURFACE = JBColor.namedColor("JVMBeacon.signalSurface", 0xE5F3F1, 0x203B3A);

    private BeaconUi() { }

    static Font font() {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, JBUI.scale(13));
        return base.deriveFont(Font.PLAIN);
    }

    static void applyTypography(Component root) {
        Font base = font();
        applyTypography(root, base);
    }

    private static void applyTypography(Component component, Font base) {
        float multiplier = 1;
        boolean bold = false;
        boolean mono = false;
        if (component instanceof JComponent jc) {
            Object scale = jc.getClientProperty("beacon.fontScale");
            if (scale instanceof Number number) multiplier = number.floatValue();
            bold = Boolean.TRUE.equals(jc.getClientProperty("beacon.bold"));
            mono = Boolean.TRUE.equals(jc.getClientProperty("beacon.mono"));
        }
        // JBR bundles JetBrains Mono; logical Monospaced maps to a thin legacy face on Windows.
        Font face = mono ? new Font("JetBrains Mono", Font.PLAIN, base.getSize()) : base;
        if (mono && !"JetBrains Mono".equals(face.getFamily()))
            face = new Font(Font.MONOSPACED, Font.PLAIN, base.getSize());
        component.setFont(face.deriveFont(bold ? Font.BOLD : Font.PLAIN, base.getSize2D() * multiplier));
        if (component instanceof JTable table) {
            table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + JBUI.scale(12));
            if (table.getTableHeader() != null) table.getTableHeader().setFont(base.deriveFont(Font.PLAIN));
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents()) applyTypography(child, base);
    }

    static JPanel panel(int gap) { return new JPanel(new BorderLayout(JBUI.scale(gap), JBUI.scale(gap))); }

    static void metricCard(JPanel panel) {
        panel.setBackground(CANVAS);
        panel.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(ACCENT, 2, 0, 0, 0),
                JBUI.Borders.empty(12, 16)));
    }

    /** A connection indicator, never a health score or a promise of fresh data. */
    static void connectionBadge(JLabel label, boolean live, boolean offline, boolean hasCapture) {
        label.setText(live ? "LIVE" : offline ? "SNAPSHOT" : hasCapture ? "STALE" : "STANDBY");
        label.putClientProperty("beacon.mono", true);
        label.setOpaque(true);
        label.setForeground(live ? ACCENT : hasCapture && !offline ? STALE : MUTED);
        label.setBackground(live ? SIGNAL_SURFACE : CANVAS);
        label.setBorder(JBUI.Borders.empty(3, 8));
        label.setToolTipText(live ? "Connected. Check each capture window for data freshness."
                : offline ? "Reading a saved capture; no live connection."
                : hasCapture ? "Disconnected. Displayed data is from an earlier capture."
                : "Connect a JVM or open a saved capture.");
    }

    static Icon signalIcon() {
        return new Icon() {
            public int getIconWidth() { return JBUI.scale(24); }
            public int getIconHeight() { return JBUI.scale(24); }
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.translate(x, y); g.scale(getIconWidth() / 24.0, getIconHeight() / 24.0);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(ACCENT); g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawRoundRect(1, 1, 21, 21, 6, 6);
                    g.drawPolyline(new int[]{4, 8, 10, 13, 15, 19}, new int[]{13, 13, 6, 18, 11, 11}, 6);
                } finally { g.dispose(); }
            }
        };
    }

    static JLabel label(String text, boolean muted) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", Boolean.TRUE);
        if (muted) label.setForeground(MUTED);
        return label;
    }

    static JLabel title(String text) {
        JLabel label = label(text, false); label.putClientProperty("beacon.bold", true); return label;
    }

    static JBScrollPane scroll(Component view) {
        JBScrollPane pane = new JBScrollPane(view);
        pane.setBorder(JBUI.Borders.empty());
        pane.getViewport().setBackground(JBColor.lazy(view::getBackground));
        pane.setMinimumSize(JBUI.size(80, 48));
        return pane;
    }

    static JComponent split(boolean vertical, String key, JComponent first, JComponent second, float proportion) {
        OnePixelSplitter splitter = new OnePixelSplitter(vertical, "dev.jvmbeacon.ui2." + key, proportion);
        first.setMinimumSize(JBUI.size(140, 60)); second.setMinimumSize(JBUI.size(140, 60));
        splitter.setFirstComponent(first); splitter.setSecondComponent(second);
        return splitter;
    }

    static JPanel section(String title, JComponent content, JComponent actions) {
        JPanel panel = panel(0);
        JPanel heading = panel(8); heading.setBorder(JBUI.Borders.empty(8, 12));
        heading.add(title(title), BorderLayout.WEST);
        if (actions != null) heading.add(actions, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH); panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    static void table(JBTable table, String empty) {
        table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, col);
                setBorder(JBUI.Borders.empty(4, 10)); setFont(t.getFont());
                setToolTipText(value == null ? null : "Value: " + value); return this;
            }
        });
        table.getEmptyText().setText(empty);
        table.getTableHeader().setReorderingAllowed(false);
    }

    static JTextArea text(String text, int rows) {
        JTextArea area = new JTextArea(text, rows, 20);
        area.setEditable(false); area.setFont(font()); area.setOpaque(true);
        area.setBackground(CANVAS); area.setForeground(JBColor.foreground());
        area.setBorder(JBUI.Borders.empty(10, 12)); area.setMargin(JBUI.emptyInsets());
        return area;
    }

    /** FlowLayout's default preferred height ignores wrapping and clips a toolbar's second row. */
    static final class WrapLayout extends FlowLayout {
        WrapLayout() { super(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3)); }
        @Override public Dimension preferredLayoutSize(Container target) {
            synchronized (target.getTreeLock()) {
                int width = target.getWidth();
                Container parent = target.getParent();
                if (parent != null && parent.getLayout() instanceof BorderLayout layout) {
                    Object position = layout.getConstraints(target);
                    if (BorderLayout.NORTH.equals(position) || BorderLayout.SOUTH.equals(position)
                            || BorderLayout.PAGE_START.equals(position) || BorderLayout.PAGE_END.equals(position)) {
                        // BorderLayout measures nested NORTH/SOUTH panels before laying out their
                        // children. The parent already has its new width; this row can still have
                        // its previous width after a splitter drag. Use the width it will receive
                        // so the parent's preferred height includes every wrapped row on this pass.
                        Insets parentInsets = parent.getInsets();
                        int assignedWidth = parent.getWidth() - parentInsets.left - parentInsets.right;
                        if (assignedWidth > 0) width = assignedWidth;
                    }
                }
                if (width <= 0) return super.preferredLayoutSize(target);
                Insets insets = target.getInsets();
                int available = Math.max(1, width - insets.left - insets.right - getHgap() * 2);
                int rowWidth = 0, rowHeight = 0, totalHeight = getVgap() * 2, maxWidth = 0;
                for (Component child : target.getComponents()) {
                    if (!child.isVisible()) continue;
                    Dimension size = child.getPreferredSize();
                    int gap = rowWidth == 0 ? 0 : getHgap();
                    if (rowWidth > 0 && rowWidth + gap + size.width > available) {
                        maxWidth = Math.max(maxWidth, rowWidth); totalHeight += rowHeight + getVgap(); rowWidth = 0; rowHeight = 0; gap = 0;
                    }
                    rowWidth += gap + size.width; rowHeight = Math.max(rowHeight, size.height);
                }
                return new Dimension(Math.max(maxWidth, rowWidth) + insets.left + insets.right + getHgap() * 2,
                        totalHeight + rowHeight + insets.top + insets.bottom);
            }
        }
    }
}
