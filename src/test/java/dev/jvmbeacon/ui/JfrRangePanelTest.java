package dev.jvmbeacon.ui;

import com.intellij.openapi.util.Disposer;
import dev.jvmbeacon.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class JfrRangePanelTest {
    @TempDir static Path directory;
    private static Path recording;
    private static JfrSummary.Report report;
    @BeforeAll static void capture() throws Exception {
        recording = directory.resolve("range.jfr");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx64m", "-XX:+UseSerialGC",
                "-cp", System.getProperty("beacon.fixture.classes"), "dev.jvmbeacon.fixture.RangeRecordingFixture", recording.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start();
        try { assertTrue(child.waitFor(25, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), Files.readString(directory.resolve("child.log"))); }
        finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
        report = JfrSummary.inspect(recording);
    }
    private record Pending<T>(Callable<T> work, Consumer<T> success, Consumer<String> failure) {
        Runnable prepare() {
            assertFalse(SwingUtilities.isEventDispatchThread());
            try { T result = work.call(); return () -> success.accept(result); }
            catch (Exception e) { return () -> failure.accept(e.getMessage()); }
        }
    }
    private static final class Jobs implements JfrPanel.Jobs {
        final List<Pending<?>> pending = new ArrayList<>(); int requests;
        public <T> void run(String label, boolean network, long deadline, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
            assertFalse(network); assertEquals(8000, deadline); requests++; pending.add(new Pending<>(work, success, failure));
        }
        void complete() throws Exception { SwingUtilities.invokeAndWait(pending.removeFirst().prepare()); }
    }
    @Test void applyingRangeIsAtomicPreservesActiveWaitFiltersAndBusySuppressesWork() throws Exception {
        var owner = Disposer.newDisposable(); Jobs jobs = new Jobs(); JfrPanel[] panel = new JfrPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> { panel[0] = new JfrPanel(null, owner, jobs, s -> { }); panel[0].analyze(recording); }); jobs.complete();
            SwingUtilities.invokeAndWait(() -> {
                var waits = all(panel[0]).stream().filter(JfrWaitsPanel.class::isInstance).map(JfrWaitsPanel.class::cast).findFirst().orElseThrow();
                var parts = all(waits);
                var kind = (JComboBox<?>) named(parts, "Wait event kind filter"); kind.setSelectedItem(JfrWaits.Kind.PARK);
                ((JTextField) named(parts, "Search retained wait events")).setText("main"); button(parts, "Apply filters").doClick();
            }); jobs.complete();
            var range = new JfrTimeRange(report.first(), report.first().plus(Duration.between(report.first(), report.last()).dividedBy(2)));
            SwingUtilities.invokeAndWait(() -> {
                panel[0].setSession(null, true, true); int requests = jobs.requests; panel[0].applyRange(range); assertEquals(requests, jobs.requests);
                panel[0].setSession(null, false, true); panel[0].applyRange(range);
                assertTrue(labels(panel[0]).stream().anyMatch(s -> s.startsWith("FULL ·")));
            }); jobs.complete();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(labels(panel[0]).stream().anyMatch(s -> s.startsWith("RANGE ·")));
                assertEquals(4, all(panel[0]).stream().filter(c -> c instanceof JTextArea a && a.getText().contains(range.describe())).count());
                var waits = all(panel[0]).stream().filter(JfrWaitsPanel.class::isInstance).map(JfrWaitsPanel.class::cast).findFirst().orElseThrow();
                assertEquals(JfrWaits.Kind.PARK, waits.activeKind()); assertEquals("main", waits.activeQuery());
                panel[0].applyRange(null);
            }); jobs.complete();
            SwingUtilities.invokeAndWait(() -> assertTrue(labels(panel[0]).stream().anyMatch(s -> s.startsWith("FULL · " + report.events()))));
        } finally { Disposer.dispose(owner); }
    }
    @Test void changedFileFailureKeepsPreviousScopeAndEvidence() throws Exception {
        Path copy = directory.resolve("ui-changed.jfr"); Files.copy(recording, copy);
        var owner = Disposer.newDisposable(); Jobs jobs = new Jobs(); JfrPanel[] panel = new JfrPanel[1]; List<String> status = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> { panel[0] = new JfrPanel(null, owner, jobs, status::add); panel[0].analyze(copy); }); jobs.complete();
            Files.write(copy, new byte[]{1}, StandardOpenOption.APPEND);
            SwingUtilities.invokeAndWait(() -> panel[0].applyRange(new JfrTimeRange(report.first(), report.last()))); jobs.complete();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(labels(panel[0]).stream().anyMatch(s -> s.startsWith("FULL ·") && s.contains("Update failed")));
                assertEquals(4, all(panel[0]).stream().filter(c -> c instanceof JTextArea a && a.getText().contains("Applied time range: Full recording")).count());
                assertTrue(status.getLast().contains("Previous range and all views retained"));
            });
        } finally { Disposer.dispose(owner); }
    }
    @Test void lateSuccessCannotResurrectAReplacedFile() throws Exception {
        var owner = Disposer.newDisposable(); Jobs jobs = new Jobs(); JfrPanel[] panel = new JfrPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> { panel[0] = new JfrPanel(null, owner, jobs, s -> { }); panel[0].analyze(recording); });
            Runnable late = jobs.pending.removeFirst().prepare();
            SwingUtilities.invokeAndWait(() -> panel[0].analyze(directory.resolve("missing.jfr"))); jobs.complete();
            SwingUtilities.invokeAndWait(late);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(button(all(panel[0]), "Time range…").isEnabled());
                assertFalse(labels(panel[0]).stream().anyMatch(s -> s.startsWith("FULL ·") || s.startsWith("RANGE ·")));
            });
        } finally { Disposer.dispose(owner); }
    }
    private static Component named(List<Component> list, String name) { return list.stream().filter(c -> c.getAccessibleContext() != null && name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow(); }
    private static JButton button(List<Component> list, String name) { return list.stream().filter(c -> c instanceof JButton b && name.equals(b.getText())).map(JButton.class::cast).findFirst().orElseThrow(); }
    private static List<String> labels(Container root) { return all(root).stream().filter(JLabel.class::isInstance).map(c -> ((JLabel)c).getText()).filter(java.util.Objects::nonNull).toList(); }
    private static List<Component> all(Container root) { var result = new ArrayList<Component>(); for (var c : root.getComponents()) { result.add(c); if (c instanceof Container child) result.addAll(all(child)); } return result; }
}
