package dev.jvmbeacon.ui;

import dev.jvmbeacon.core.StructuredValue;
import org.junit.jupiter.api.Test;

import javax.management.openmbean.*;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValueExplorerRowsTest {
    @Test void collidingTruncatedCompositeNamesCannotSilentlyMergeColumns() throws Exception {
        String left = "x".repeat(256) + "Left", right = "x".repeat(256) + "Right";
        var type = new CompositeType("row", "row", new String[]{"id", left, right},
                new String[]{"identity", "first field", "second field"},
                new OpenType[]{SimpleType.INTEGER, SimpleType.INTEGER, SimpleType.INTEGER});
        var table = new TabularDataSupport(new TabularType("rows", "rows", type, new String[]{"id"}));
        table.put(new CompositeDataSupport(type, Map.of("id", 1, left, 10, right, 20)));
        var captured = StructuredValue.capture("rows", table);
        var fields = captured.root().children().getFirst().children();
        assertEquals(List.of("1", "10", "20"), fields.stream().map(StructuredValue.Node::value).toList());
        assertEquals(fields.get(1).name(), fields.get(2).name(), "A display label must not become field identity");
        SwingUtilities.invokeAndWait(() -> {
            JComponent rows = ValueExplorerDialog.rows(captured, new JTextArea());
            assertTrue(all(rows).stream().noneMatch(JTable.class::isInstance));
            assertTrue(all(rows).stream().filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                    .anyMatch(text -> text.getText().contains("Rows unavailable") && text.getText().contains("Use Structure")));
        });
    }

    @Test void completeTableStillSupportsSortedRowsWithMatchingCellDetails() throws Exception {
        var type = new CompositeType("row", "row", new String[]{"id", "value"}, new String[]{"identity", "value"},
                new OpenType[]{SimpleType.INTEGER, SimpleType.STRING});
        var table = new TabularDataSupport(new TabularType("rows", "rows", type, new String[]{"id"}));
        table.put(new CompositeDataSupport(type, Map.of("id", 1, "value", "first")));
        table.put(new CompositeDataSupport(type, Map.of("id", 2, "value", "second")));
        var captured = StructuredValue.capture("rows", table);
        assertFalse(captured.incomplete());
        SwingUtilities.invokeAndWait(() -> {
            JTextArea detail = new JTextArea();
            JTable rows = all(ValueExplorerDialog.rows(captured, detail)).stream().filter(JTable.class::isInstance)
                    .map(JTable.class::cast).findFirst().orElseThrow();
            assertEquals(2, rows.getColumnCount()); assertEquals(2, rows.getRowCount());
            rows.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
            rows.changeSelection(0, 1, false, false);
            assertEquals("second", rows.getValueAt(0, 1));
            assertTrue(detail.getText().contains("Field: value") && detail.getText().endsWith("second"));
        });
    }
    private static List<Component> all(Container root) {
        var result = new ArrayList<Component>();
        for (Component child : root.getComponents()) {
            result.add(child);
            if (child instanceof Container container) result.addAll(all(container));
        }
        return result;
    }
}
