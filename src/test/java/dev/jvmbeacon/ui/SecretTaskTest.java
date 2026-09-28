package dev.jvmbeacon.ui;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SecretTaskTest {
    @Test void workerErasesOnSuccessAndFailure() throws Exception {
        char[] password = "fixture-only".toCharArray();
        assertEquals(12, new SecretTask<>(password, value -> value.length).call());
        assertErased(password);
        char[] failing = "fixture-only".toCharArray();
        assertThrows(IOException.class, () -> new SecretTask<>(failing, value -> { throw new IOException(); }).call());
        assertErased(failing);
    }

    @Test void closedRunnerErasesWithoutSending() {
        var runtime = new BeaconExecutors();
        try (var runner = new SessionRunner(runtime, 2000)) {
            runner.close();
            char[] password = "fixture-only".toCharArray();
            assertFalse(runner.submit("credential", new SecretTask<>(password, value -> fail("Must not run")), value -> {}, error -> {}));
            assertErased(password);
        } finally { runtime.dispose(); }
    }

    @Test void capacityRejectionErasesWithoutSending() throws Exception {
        var runtime = new BeaconExecutors();
        var started = new CountDownLatch(2); var gate = new CountDownLatch(1); var rejected = new CountDownLatch(1);
        try (var runner = new SessionRunner(runtime, 2000)) {
            for (int i = 0; i < 2; i++) runtime.localCalls.submit(() -> { started.countDown(); await(gate); });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            char[] password = "fixture-only".toCharArray();
            assertTrue(runner.submit(SessionRunner.Lane.LOCAL_IO, "credential", new SecretTask<>(password, value -> fail("Must not run")), value -> fail("Must not succeed"), error -> rejected.countDown()));
            assertTrue(rejected.await(3, TimeUnit.SECONDS)); assertErased(password);
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void cancelBeforeExecutorStartsErasesWithoutDependingOnWorkerFinally() throws Exception {
        var runtime = new BeaconExecutors();
        var ready = new CountDownLatch(1); var gate = new CountDownLatch(1); var ended = new CountDownLatch(1);
        runtime.localCalls.setThreadFactory(work -> {
            Thread thread = new Thread(() -> { ready.countDown(); await(gate); try { work.run(); } finally { ended.countDown(); } });
            thread.setDaemon(true); return thread;
        });
        var calls = new AtomicInteger();
        try (var runner = new SessionRunner(runtime, 2000)) {
            char[] password = "fixture-only".toCharArray();
            runner.submit(SessionRunner.Lane.LOCAL_IO, "credential", new SecretTask<>(password, value -> calls.incrementAndGet()), value -> calls.incrementAndGet(), error -> calls.incrementAndGet());
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            char[] overlapping = "overlap-only".toCharArray();
            assertFalse(runner.submit("overlap", new SecretTask<>(overlapping, value -> calls.incrementAndGet()), value -> {}, error -> {}));
            assertErased(overlapping);
            runner.cancel(); assertErased(password);
            gate.countDown(); runtime.dispose(); assertTrue(ended.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {}); assertEquals(0, calls.get());
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void timeoutBeforeWorkerStartsErasesAndReportsExactlyOnce() throws Exception {
        var runtime = new BeaconExecutors(); var gate = new CountDownLatch(1); var ended = new CountDownLatch(1);
        runtime.localCalls.setThreadFactory(work -> {
            Thread thread = new Thread(() -> { await(gate); try { work.run(); } finally { ended.countDown(); } });
            thread.setDaemon(true); return thread;
        });
        var done = new CountDownLatch(1); var callbacks = new AtomicInteger();
        try (var runner = new SessionRunner(runtime, 100)) {
            char[] password = "fixture-only".toCharArray();
            runner.submit(SessionRunner.Lane.LOCAL_IO, "credential", new SecretTask<>(password, value -> fail("Must not run")), value -> fail("Must not succeed"), error -> { callbacks.incrementAndGet(); done.countDown(); });
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertErased(password);
            gate.countDown(); runtime.dispose(); assertTrue(ended.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {}); assertEquals(1, callbacks.get());
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void runningTaskKeepsInputUntilItReturnsAndLateResultIsErased() throws Exception {
        var runtime = new BeaconExecutors(); var gate = new CountDownLatch(1); var started = new CountDownLatch(1);
        var timedOut = new CountDownLatch(1); var erased = new CountDownLatch(1);
        char[] password = "fixture-only".toCharArray(); char[] result = "saved-result".toCharArray();
        try (var runner = new SessionRunner(runtime, 150)) {
            runner.submit(SessionRunner.Lane.LOCAL_IO, "credential", new SecretTask<>(password, value -> {
                started.countDown(); await(gate);
                assertArrayEquals("fixture-only".toCharArray(), value);
                return (BeaconExecutors.ManagedConnection) () -> { new CredentialSecret(result).close(); erased.countDown(); };
            }), value -> fail("Late result must not publish"), error -> timedOut.countDown());
            assertTrue(started.await(3, TimeUnit.SECONDS)); assertTrue(timedOut.await(3, TimeUnit.SECONDS));
            assertArrayEquals("fixture-only".toCharArray(), password, "Do not change an in-use input from the EDT.");
            gate.countDown(); assertTrue(erased.await(3, TimeUnit.SECONDS)); assertErased(password); assertErased(result);
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void discardedSecretCannotBeUsedAgain() {
        char[] password = "fixture-only".toCharArray();
        var task = new SecretTask<>(password, value -> fail("Must not run"));
        task.discard(); task.discard(); assertErased(password);
        assertThrows(CancellationException.class, task::call);
    }

    private static void assertErased(char[] value) { assertArrayEquals(new char[value.length], value); }
    private static void await(CountDownLatch gate) {
        boolean done = false;
        while (!done) try { gate.await(); done = true; } catch (InterruptedException ignored) { }
    }
}
