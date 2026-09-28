package dev.jvmbeacon.ui;

import dev.jvmbeacon.core.JmxClient;
import dev.jvmbeacon.core.MBeanMetadata.Attribute;
import dev.jvmbeacon.core.StructuredValue;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class MBeanAttributesPanelTest {
    @Test void selectionFilterAndSortAreLocalAndEnterReadsOnlyTheSelectedDefinition() throws Exception {
        Harness h = harness();
        edt(() -> {
            h.select("A0"); assertEquals(0, h.submissions);
            h.search.setText("A1"); assertEquals(1, h.table.getRowCount()); assertEquals(0, h.submissions);
            h.table.setRowSelectionInterval(0, 0); assertEquals("A1", h.panel.selected().name());
            h.table.getActionMap().get("beacon.readAttribute").actionPerformed(null);
            assertEquals(1, h.submissions); assertTrue(h.text().contains("Reading…"));
        });
        h.complete();
        edt(() -> {
            assertEquals(List.of("A1"), h.reads); assertTrue(h.text().contains("Read window:")); assertTrue(h.text().contains("value-A1"));
            h.search.setText(""); h.table.getRowSorter().toggleSortOrder(0); h.table.getRowSorter().toggleSortOrder(0);
            h.select("A0"); assertTrue(h.text().contains("Not read")); assertFalse(h.button("Explore…").isEnabled());
            h.select("A1"); assertTrue(h.text().contains("value-A1")); assertEquals(1, h.submissions);
            h.search.setText("no match"); assertNull(h.panel.selected()); assertFalse(h.button("Copy").isEnabled());
            assertFalse(h.text().contains("value-A1"));
        });
    }

    @Test void eightReadCacheEvictsWithoutAutomaticallyReadingAndNullRemainsCaptured() throws Exception {
        Harness h = harness();
        for (int i = 0; i < 9; i++) { int n = i; edt(() -> { h.select("A" + n); h.button("Read value").doClick(); }); h.complete(); }
        edt(() -> {
            h.select("A0"); assertTrue(h.text().contains("Evicted")); assertEquals(9, h.submissions);
            assertFalse(h.button("Explore…").isEnabled()); h.select("A8"); assertTrue(h.text().contains("value-A8"));
            h.panel.load(List.of(attribute("Null", true)), "new target", name -> new JmxClient.AttributeReading(1, 2, "null", StructuredValue.capture(name, null), null));
            h.select("Null"); h.button("Read value").doClick();
        });
        h.complete(); edt(() -> { assertTrue(h.text().contains("\n\nnull")); assertTrue(h.button("Explore…").isEnabled()); });
    }

    @Test void lateReadIsDiscardedAfterReloadAndDisconnectEndsPendingWithoutRetry() throws Exception {
        Harness h = harness();
        edt(() -> { h.select("A0"); h.button("Read value").doClick(); h.load(); h.select("A0"); });
        h.complete();
        edt(() -> {
            assertTrue(h.text().contains("Not read")); assertFalse(h.text().contains("value-A0"));
            h.button("Read value").doClick(); h.panel.setSession(false, false, false);
            assertTrue(h.text().contains("Stopped waiting")); assertFalse(h.button("Read value").isEnabled());
        });
        h.complete(); edt(() -> { assertTrue(h.text().contains("Stopped waiting")); assertFalse(h.text().contains("value-A0")); assertEquals(2, h.submissions); });
    }

    @Test void rejectedReadNeverShowsPendingAndWriteReadBackUsesOriginalAttributeNotCurrentRow() throws Exception {
        Harness h = harness();
        edt(() -> {
            h.select("A0"); h.accept = false; h.button("Read value").doClick(); assertTrue(h.text().contains("Not read"));
            h.accept = true; Attribute written = h.panel.selected(); long generation = h.panel.generation();
            h.select("A1"); h.panel.readBack(written, generation);
        });
        h.complete();
        edt(() -> {
            assertEquals(List.of("A0"), h.reads); assertTrue(h.text().contains("Not read"));
            h.select("A0"); assertTrue(h.text().contains("value-A0"));
            int before = h.submissions; h.panel.readBack(attribute("WriteOnly", false), h.panel.generation());
            assertEquals(before, h.submissions); assertTrue(h.status.contains("write-only"));
            long old = h.panel.generation(); h.load(); h.panel.readBack(attribute("A0", true), old);
            assertEquals(before, h.submissions); assertTrue(h.status.contains("view changed"));
        });
    }

    @Test void failedReadAndUnknownWriteNeverRetainSuccessfulValueOrPermanentPending() throws Exception {
        Harness h = harness();
        edt(() -> { h.select("A0"); h.button("Read value").doClick(); }); h.complete();
        edt(() -> {
            h.button("Read value").doClick(); h.failure.accept("[TARGET] Denied");
            assertTrue(h.text().contains("[TARGET] Denied")); assertFalse(h.button("Explore…").isEnabled());
            h.panel.invalidate(h.panel.selected(), h.panel.generation(), "Write pending · Outcome unknown");
            h.panel.setSession(false, false, false);
            assertTrue(h.text().contains("Stopped waiting for write; outcome unknown")); assertFalse(h.text().contains("Write pending"));
        });
    }

    private static Attribute attribute(String name, boolean readable) { return new Attribute(name, "int", readable, true, "Test definition"); }
    private static void edt(Runnable work) throws Exception { SwingUtilities.invokeAndWait(work); }
    private static Harness harness() throws Exception { Harness[] result = new Harness[1]; edt(() -> result[0] = new Harness()); return result[0]; }
    private static final class Harness {
        int submissions; boolean accept = true; String status = "";
        Callable<JmxClient.AttributeReading> work; Consumer<JmxClient.AttributeReading> success; Consumer<String> failure;
        final List<String> reads = new ArrayList<>();
        final MBeanAttributesPanel panel = new MBeanAttributesPanel((label, callable, ok, error) -> {
            submissions++; if (!accept) return false; work = callable; success = ok; failure = error; return true;
        }, message -> status = message, () -> {}, () -> {}, (context, value) -> {});
        final List<Component> components = all(panel);
        final JTable table = components.stream().filter(JTable.class::isInstance).map(JTable.class::cast).findFirst().orElseThrow();
        final JTextField search = components.stream().filter(JTextField.class::isInstance).map(JTextField.class::cast).findFirst().orElseThrow();
        Harness() { load(); panel.setSession(true, false, true); }
        void load() {
            List<Attribute> definitions = new ArrayList<>(); for (int i = 0; i < 10; i++) definitions.add(attribute("A" + i, true));
            panel.load(definitions, "Target: controlled test", name -> {
                assertFalse(SwingUtilities.isEventDispatchThread()); reads.add(name);
                return new JmxClient.AttributeReading(100, 120, "value-" + name, StructuredValue.capture(name, "value-" + name), null);
            });
        }
        void complete() throws Exception { var callback = success; var result = work.call(); edt(() -> callback.accept(result)); }
        void select(String name) {
            for (int i = 0; i < table.getRowCount(); i++) if (name.equals(table.getValueAt(i, 0))) { table.setRowSelectionInterval(i, i); return; }
            fail("Missing row " + name);
        }
        JButton button(String name) { return components.stream().filter(c -> c instanceof JButton b && b.getText().equals(name)).map(JButton.class::cast).findFirst().orElseThrow(); }
        String text() { return components.stream().filter(JTextArea.class::isInstance).map(JTextArea.class::cast).findFirst().orElseThrow().getText(); }
    }
    private static List<Component> all(Container root) {
        var list = new ArrayList<Component>(); for (var c : root.getComponents()) { list.add(c); if (c instanceof Container child) list.addAll(all(child)); } return list;
    }
}
