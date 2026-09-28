package dev.jvmbeacon.ui;

import dev.jvmbeacon.core.JmxClient;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TimelinePanelTest {
    private static List<JmxClient.Sample> samples(int offset) {
        return java.util.stream.IntStream.range(1, 7).mapToObj(i -> new JmxClient.Sample((offset + i) * 2000L, (offset + i) * 2000L + 10,
                List.of(new JmxClient.Metric("heap.used", "heap", i, "bytes", null)))).toList();
    }
    @Test void frozenRangeSurvivesNewSamplesAndResetPreventsOldTargetExport() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<List<JmxClient.Sample>> saved = new AtomicReference<>();
            var panel = new TimelinePanel(new JButton("Sample"), new JButton("Start"), () -> {}, saved::set);
            panel.update(samples(0), false);
            button(panel, "Freeze & select").doClick();
            spinner(panel, "First captured sample").setValue(2); spinner(panel, "Last captured sample").setValue(4);
            assertEquals(4, spinner(panel, "Inspect captured sample").getValue());
            assertEquals(2, ((SpinnerNumberModel) spinner(panel, "Inspect captured sample").getModel()).getMinimum());
            panel.update(samples(100), false);
            button(panel, "Save interval…").doClick();
            assertEquals(samples(0).subList(1, 4), saved.get());
            button(panel, "Follow latest").doClick(); button(panel, "Freeze & select").doClick();
            button(panel, "Save interval…").doClick(); assertEquals(samples(100), saved.get());
            panel.reset(); assertFalse(button(panel, "Save interval…").isEnabled());
            panel.update(samples(200), false); assertFalse(button(panel, "Save interval…").isEnabled());
        });
    }
    @Test void offlineRangeCanBeSelectedAndSavedWithoutAnyTargetActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<List<JmxClient.Sample>> saved = new AtomicReference<>();
            var panel = new TimelinePanel(new JButton("Sample"), new JButton("Start"), () -> {}, saved::set);
            panel.update(samples(0), true);
            assertFalse(button(panel, "Follow latest").isEnabled());
            spinner(panel, "Last captured sample").setValue(3);
            spinner(panel, "First captured sample").setValue(5); // Moving start past end gives a one-point interval.
            button(panel, "Save interval…").doClick(); assertEquals(List.of(samples(0).get(4)), saved.get());
        });
    }
    private static List<Component> descendants(Container root) {
        var list = new ArrayList<Component>();
        for (var component : root.getComponents()) { list.add(component); if (component instanceof Container child) list.addAll(descendants(child)); }
        return list;
    }
    private static AbstractButton button(Container root, String name) {
        return descendants(root).stream().filter(c -> c instanceof AbstractButton b && name.equals(b.getText())).map(AbstractButton.class::cast).findFirst().orElseThrow();
    }
    private static JSpinner spinner(Container root, String name) {
        return descendants(root).stream().filter(c -> c instanceof JSpinner s && name.equals(s.getAccessibleContext().getAccessibleName())).map(JSpinner.class::cast).findFirst().orElseThrow();
    }
}
