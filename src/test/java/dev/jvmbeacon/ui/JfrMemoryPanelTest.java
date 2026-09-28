package dev.jvmbeacon.ui;

import dev.jvmbeacon.core.JfrMemory;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JfrMemoryPanelTest {
    private static JfrMemory.Data data() {
        var time = Instant.parse("2026-09-28T00:00:00Z"); var counts = new JfrMemory.Counts(2, 0, 0);
        return new JfrMemory.Data(List.of(new JfrMemory.Gc(JfrMemory.Kind.CYCLE, 7, time, time.plusMillis(1), 1_000_000,
                "Test collector", null, null, 0L)), List.of(
                new JfrMemory.Allocation(1, "Large", 1, BigInteger.valueOf(75), time, time),
                new JfrMemory.Allocation(2, "Small", 1, BigInteger.valueOf(25), time, time)),
                counts, counts, counts, BigInteger.valueOf(100), time, time.plusSeconds(1), false, "Coverage evidence");
    }
    @Test void searchPreservesWeightDenominatorAndClearRemovesSelectionAndEvidence() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new JfrMemoryPanel(); panel.load(data()); var components = all(panel);
            var tabs = components.stream().filter(JTabbedPane.class::isInstance).map(JTabbedPane.class::cast).findFirst().orElseThrow();
            tabs.setSelectedIndex(1);
            JTable table = components.stream().filter(JTable.class::isInstance).map(JTable.class::cast).filter(t -> t.getColumnCount() == 5).findFirst().orElseThrow();
            JTextField search = components.stream().filter(JTextField.class::isInstance).map(JTextField.class::cast).findFirst().orElseThrow();
            search.setText("Small"); assertEquals(1, table.getRowCount()); assertEquals(25.0, table.getValueAt(0, 4));
            table.setRowSelectionInterval(0, 0);
            assertTrue(components.stream().filter(JTextArea.class::isInstance).map(JTextArea.class::cast).anyMatch(t -> t.getText().contains("25.000%")));
            search.setText("does-not-match"); assertEquals(0, table.getRowCount());
            assertFalse(button(components, "Copy selected evidence").isEnabled());
            panel.clear(); assertEquals(0, table.getModel().getRowCount()); assertFalse(button(components, "Copy coverage").isEnabled());
            assertTrue(components.stream().filter(JTextArea.class::isInstance).map(JTextArea.class::cast).noneMatch(t -> t.getText().contains("Small")));
        });
    }
    @Test void missingPauseIsDistinctFromZeroAndSortingKeepsExactEventDetails() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new JfrMemoryPanel(); panel.load(data()); var components = all(panel);
            JTable table = components.stream().filter(JTable.class::isInstance).map(JTable.class::cast).filter(t -> t.getColumnCount() == 6).findFirst().orElseThrow();
            table.getRowSorter().toggleSortOrder(3); table.setRowSelectionInterval(0, 0);
            assertTrue(components.stream().filter(JTextArea.class::isInstance).map(JTextArea.class::cast).anyMatch(t -> t.getText().contains("sumOfPauses: Not reported / unsupported · longestPause: 0 ns")));
        });
    }
    @Test void maximumRetainedTablesReplaceInBatchesAndKeepSortedEvidenceAndScopeSearch() throws Exception {
        var time = Instant.parse("2026-09-28T00:00:00Z");
        var gc = new ArrayList<JfrMemory.Gc>(); var allocations = new ArrayList<JfrMemory.Allocation>();
        BigInteger weight = BigInteger.ZERO;
        for (int i = 0; i < JfrMemory.GC_LIMIT; i++) gc.add(new JfrMemory.Gc(JfrMemory.Kind.CYCLE, i, time,
                time.plusNanos(i + 1), i + 1, "Collector", null, null, null));
        for (int i = 0; i < JfrMemory.CLASS_LIMIT; i++) {
            BigInteger amount = BigInteger.valueOf(i + 1); weight = weight.add(amount);
            allocations.add(new JfrMemory.Allocation(i, "Class-" + i, 1, amount, time, time));
        }
        var counts = new JfrMemory.Counts(0, 0, 0);
        var data = new JfrMemory.Data(gc, allocations, counts, counts, counts, weight, time, time.plusSeconds(1), false, "Coverage");
        SwingUtilities.invokeAndWait(() -> {
            var panel = new JfrMemoryPanel(); var components = all(panel);
            JTable gcTable = table(components, 6), allocationTable = table(components, 5);
            int[] changes = new int[2];
            gcTable.getModel().addTableModelListener(e -> changes[0]++);
            allocationTable.getModel().addTableModelListener(e -> changes[1]++);
            panel.load(data);
            assertArrayEquals(new int[]{2, 2}, changes, "Each table clears once and replaces once, independently of row count");
            assertEquals(JfrMemory.GC_LIMIT, gcTable.getRowCount()); assertEquals(JfrMemory.CLASS_LIMIT, allocationTable.getRowCount());
            assertEquals(BigInteger.valueOf(JfrMemory.CLASS_LIMIT), allocationTable.getValueAt(0, 3));
            gcTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
            gcTable.setRowSelectionInterval(0, 0);
            assertTrue(components.stream().filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                    .anyMatch(t -> t.getText().contains("GC #4095") && t.getText().contains("Duration: 4096 ns")));
            JTextField search = components.stream().filter(JTextField.class::isInstance).map(JTextField.class::cast).findFirst().orElseThrow();
            search.setText("Class-2047");
            changes[0] = 0; changes[1] = 0; panel.loadScoped(data);
            assertArrayEquals(new int[]{2, 2}, changes); assertEquals("Class-2047", search.getText());
            assertEquals(1, allocationTable.getRowCount()); assertEquals("Class-2047", allocationTable.getValueAt(0, 0));
            assertEquals(-1, gcTable.getSelectedRow()); assertEquals(-1, allocationTable.getSelectedRow());
            assertFalse(button(components, "Focus event ±100 ms").isEnabled());
        });
    }
    private static JTable table(List<Component> list, int columns) { return list.stream().filter(c -> c instanceof JTable t && t.getColumnCount() == columns).map(JTable.class::cast).findFirst().orElseThrow(); }
    private static JButton button(List<Component> list, String name) { return list.stream().filter(c -> c instanceof JButton b && b.getText().equals(name)).map(JButton.class::cast).findFirst().orElseThrow(); }
    private static List<Component> all(Container root) {
        var list = new ArrayList<Component>(); for (var c : root.getComponents()) { list.add(c); if (c instanceof Container child) list.addAll(all(child)); } return list;
    }
}
