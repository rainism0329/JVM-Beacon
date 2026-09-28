package dev.jvmbeacon.ui;

import dev.jvmbeacon.core.JfrStacks;
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

class JfrStacksPanelTest {
    @Test void zoomAndResetStartAtTheNewRootInsteadOfKeepingAnOldScrollOffset() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var child = new JfrStacks.Node(null, "child", 1, 1, List.of());
            var root = new JfrStacks.Node(null, "root", 1, 0, List.of(child));
            var flame = new JfrFlameGraph(n -> { }); flame.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            var viewport = new JViewport(); viewport.setView(flame); viewport.setSize(300, 40);
            flame.show(root); viewport.setViewPosition(new Point(0, 25)); flame.show(child);
            assertEquals(new Point(), viewport.getViewPosition());
            viewport.setViewPosition(new Point(0, 10)); flame.show(root); assertEquals(new Point(), viewport.getViewPosition());
        });
    }
    @Test void clearingFileDiscardsPendingAnalysisAndBusyFiltersDoNotSubmit() throws Exception {
        var frame = new JfrStacks.Frame(1, "fixture.Work", "run", "()V", 1);
        var data = new JfrStacks.Data(List.of(new JfrStacks.Sample(JfrStacks.Kind.JAVA,
                new JfrStacks.SampledThread(1, 1, "worker"), Instant.now(), List.of(frame), false)),
                Map.of(JfrStacks.Kind.JAVA, new JfrStacks.Counts(1, 0, 0, 0)), false);
        var view = JfrStacks.aggregate(data, JfrStacks.Kind.JAVA, null);
        SwingUtilities.invokeAndWait(() -> {
            class Jobs implements JfrPanel.Jobs {
                Runnable pending; int requests;
                public <T> void run(String label, boolean network, long deadline, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
                    assertFalse(network); requests++;
                    pending = () -> { try { success.accept(work.call()); } catch (Exception e) { throw new AssertionError(e); } };
                }
            }
            Jobs jobs = new Jobs();
            var panel = new JfrStacksPanel(jobs, f -> fail("No implicit source navigation"), s -> { }); panel.load(data, view);
            JButton apply = button(panel, "Apply filters");
            panel.setBusy(true); assertFalse(apply.isEnabled()); apply.doClick(); assertEquals(0, jobs.requests);
            panel.setBusy(false); apply.doClick(); assertEquals(1, jobs.requests);
            panel.clear(); jobs.pending.run();
            assertFalse(button(panel, "Copy evidence").isEnabled()); assertFalse(button(panel, "Reset zoom").isEnabled());
            assertFalse(button(panel, "Apply filters").isEnabled());
        });
    }
    private static List<Component> all(Container root) {
        var list = new ArrayList<Component>();
        for (var c : root.getComponents()) { list.add(c); if (c instanceof Container child) list.addAll(all(child)); } return list;
    }
    private static JButton button(Container root, String name) {
        return all(root).stream().filter(c -> c instanceof JButton b && name.equals(b.getText())).map(JButton.class::cast).findFirst().orElseThrow();
    }
}
