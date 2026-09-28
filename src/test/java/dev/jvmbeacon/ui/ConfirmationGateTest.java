package dev.jvmbeacon.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmationGateTest {
    @Test void nestedModalEventLoopSuppressesAutomaticTicksAndCancelRestoresThem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var gate = new ConfirmationGate();
            var paused = new AtomicInteger(); var automatic = new AtomicInteger();
            var loop = new AtomicReference<SecondaryLoop>();
            Timer timer = new Timer(10, event -> {
                if (gate.isPaused()) {
                    if (paused.incrementAndGet() == 3) loop.get().exit();
                } else if (automatic.incrementAndGet() == 3) loop.get().exit();
            });
            Timer watchdog = new Timer(2_000, event -> loop.get().exit());
            watchdog.setRepeats(false);
            try {
                loop.set(Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop());
                timer.start(); watchdog.start();
                boolean confirmed = gate.show(() -> {
                    assertTrue(loop.get().enter()); // DialogWrapper also pumps a nested EDT event loop.
                    return false;
                });
                assertFalse(confirmed); assertEquals(3, paused.get()); assertEquals(0, automatic.get());
                loop.set(Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop());
                watchdog.restart();
                assertTrue(loop.get().enter());
                assertEquals(3, automatic.get(), "Automatic polling resumes after the user cancels.");
            } finally { timer.stop(); watchdog.stop(); }
        });
    }

    @Test void nestedConfirmationFailureKeepsOuterGateAndRestoresItOnExit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var gate = new ConfirmationGate();
            assertThrows(IllegalStateException.class, () -> gate.show(() -> {
                assertTrue(gate.isPaused());
                assertThrows(IllegalArgumentException.class, () -> gate.show(() -> { throw new IllegalArgumentException(); }));
                assertTrue(gate.isPaused(), "An inner dialog must not resume polling in the outer dialog.");
                throw new IllegalStateException();
            }));
            assertFalse(gate.isPaused());
        });
    }
}
