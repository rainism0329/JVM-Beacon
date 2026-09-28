package dev.jvmbeacon.ui;

import dev.jvmbeacon.core.JfrWaits;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class JfrWaitsPanelTest {
    private static JfrWaits.Data data() {
        var thread = new JfrWaits.ThreadRef(2, 3, "worker", false); var target = new JfrWaits.Target(1, "Gate");
        var now = Instant.now();
        return new JfrWaits.Data(List.of(
                new JfrWaits.Event(JfrWaits.Kind.PARK, thread, target, null, now, now, 100, List.of(), JfrWaits.StackState.NOT_RECORDED),
                new JfrWaits.Event(JfrWaits.Kind.ENTER, thread, target, null, now, now, 200, List.of(), JfrWaits.StackState.NOT_RECORDED)), Map.of(), false, "Coverage");
    }
    private static final class Jobs implements JfrPanel.Jobs {
        Runnable pending; int requests;
        public <T> void run(String label, boolean network, long deadline, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
            assertFalse(network); requests++;
            pending = () -> { try { success.accept(work.call()); } catch (Exception e) { throw new AssertionError(e); } };
        }
    }
    @Test void sortedHotspotDrilldownAndClearKeepSelectionSafe() throws Exception {
        var data = data(); var view = JfrWaits.filter(data, null, "");
        SwingUtilities.invokeAndWait(() -> {
            var panel = new JfrWaitsPanel(new Jobs(), f -> fail("No implicit navigation"), s -> { }); panel.load(data, view);
            var components = all(panel);
            JTable hotspots = table(components, 6), events = table(components, 5);
            hotspots.getRowSorter().toggleSortOrder(4); // Ascending: park first, even though model is duration-descending.
            hotspots.setRowSelectionInterval(0, 0); button(components, "Show hotspot events").doClick();
            assertEquals(1, events.getRowCount()); assertEquals("Park", events.getValueAt(0, 0));
            events.setRowSelectionInterval(0, 0); assertTrue(button(components, "Inspect event…").isEnabled());
            panel.clear(); assertEquals(0, events.getRowCount()); assertFalse(button(components, "Inspect event…").isEnabled());
            assertFalse(button(components, "Copy report").isEnabled());
        });
    }
    @Test void busySuppressesWorkAndFileReplacementDiscardsLateFilter() throws Exception {
        var data = data(); var view = JfrWaits.filter(data, null, "");
        SwingUtilities.invokeAndWait(() -> {
            Jobs jobs = new Jobs(); var panel = new JfrWaitsPanel(jobs, f -> fail("No implicit navigation"), s -> { }); panel.load(data, view);
            var components = all(panel); var apply = button(components, "Apply filters");
            panel.setBusy(true); apply.doClick(); assertEquals(0, jobs.requests);
            panel.setBusy(false); apply.doClick(); assertEquals(1, jobs.requests);
            panel.clear(); jobs.pending.run(); assertEquals(0, table(components, 6).getRowCount()); assertFalse(apply.isEnabled());
        });
    }
    private static JTable table(List<Component> list, int columns) { return list.stream().filter(c -> c instanceof JTable t && t.getColumnCount() == columns).map(JTable.class::cast).findFirst().orElseThrow(); }
    private static JButton button(List<Component> list, String name) { return list.stream().filter(c -> c instanceof JButton b && name.equals(b.getText())).map(JButton.class::cast).findFirst().orElseThrow(); }
    private static List<Component> all(Container root) { var result = new ArrayList<Component>(); for (var c : root.getComponents()) { result.add(c); if (c instanceof Container child) result.addAll(all(child)); } return result; }
}
