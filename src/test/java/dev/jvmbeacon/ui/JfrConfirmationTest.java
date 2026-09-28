package dev.jvmbeacon.ui;

import com.intellij.openapi.util.Disposer;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class JfrConfirmationTest {
    private static final class Jobs implements JfrPanel.Jobs {
        int submitted;
        public <T> void run(String label, boolean network, long deadline, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
            submitted++;
            assertFalse(network, "Opening local evidence must not contact a target.");
        }
    }

    @Test void localChooserHoldsSharedGateUntilCancelAndDoesNotSubmitWork() throws Exception {
        var owner = Disposer.newDisposable();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var gate = new ConfirmationGate(); var jobs = new Jobs();
                var panel = new JfrPanel(null, owner, jobs, s -> {}, gate);
                gate.show(() -> {
                    panel.openLocal(() -> {
                        assertTrue(gate.isPaused());
                        return null;
                    });
                    assertTrue(gate.isPaused(), "A nested chooser must not resume its enclosing confirmation.");
                    return null;
                });
                assertFalse(gate.isPaused()); assertEquals(0, jobs.submitted);
                assertThrows(IllegalStateException.class, () -> panel.openLocal(() -> { throw new IllegalStateException(); }));
                assertFalse(gate.isPaused(), "Chooser failure must restore automatic polling.");
            });
        } finally { Disposer.dispose(owner); }
    }

    @Test void busyAfterChooserReportsNotSubmittedWithoutRetryOrLosingNextExplicitOpen() throws Exception {
        var owner = Disposer.newDisposable();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var gate = new ConfirmationGate(); var jobs = new Jobs(); var messages = new ArrayList<String>();
                var panel = new JfrPanel(null, owner, jobs, messages::add, gate);
                panel.openLocal(() -> {
                    assertTrue(gate.isPaused());
                    panel.setSession(null, true, true);
                    return Path.of("selected.jfr");
                });
                assertFalse(gate.isPaused()); assertEquals(0, jobs.submitted);
                assertTrue(messages.getLast().contains("not submitted"));
                panel.setSession(null, false, true);
                assertEquals(0, jobs.submitted, "Becoming idle never retries the user's previous choice.");
                panel.openLocal(() -> Path.of("selected.jfr"));
                assertEquals(1, jobs.submitted);
            });
        } finally { Disposer.dispose(owner); }
    }
}
