package dev.jvmbeacon.ui;

import dev.jvmbeacon.core.JmxClient;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReconnectSafetyTest {
    @Test void reconnectDoesNotCarryAgentConsentOrCredentialPersistenceAndTransfersSecretOwnership() {
        var identity = new JmxClient.Identity("1@fixture", 1000, "VM", "21");
        var target = new ConnectionDialog.Target(true, "42", "", false, null, "", identity);
        char[] password = "test-only".toCharArray();
        var secret = new CredentialSecret(password);
        var request = BeaconPanel.reconnectRequest(target, secret.take());
        secret.close();
        assertArrayEquals("test-only".toCharArray(), request.password());
        assertFalse(request.allowAgent()); assertFalse(request.remember());
        assertEquals(identity, request.previous()); assertEquals("42", request.address());
        request.erasePassword(); assertArrayEquals(new char[password.length], password);
    }

    @Test void credentialResultArrivingAfterTimeoutIsErasedAndNeverPublished() throws Exception {
        var runtime = new BeaconExecutors();
        var gate = new CountDownLatch(1); var started = new CountDownLatch(1);
        var timedOut = new CountDownLatch(1); var erased = new CountDownLatch(1);
        var published = new AtomicInteger(); char[] password = "test-only".toCharArray();
        try (var runner = new SessionRunner(runtime, 500)) {
            assertTrue(runner.submit(SessionRunner.Lane.LOCAL_IO, "Read reconnect credential", () -> {
                started.countDown();
                boolean waiting = true;
                while (waiting) try { gate.await(); waiting = false; } catch (InterruptedException ignored) { }
                var secret = new CredentialSecret(password);
                return (BeaconExecutors.ManagedConnection) () -> { secret.close(); erased.countDown(); };
            }, value -> published.incrementAndGet(), error -> timedOut.countDown()));
            assertTrue(started.await(3, TimeUnit.SECONDS)); assertTrue(timedOut.await(3, TimeUnit.SECONDS));
            gate.countDown(); assertTrue(erased.await(3, TimeUnit.SECONDS));
            assertArrayEquals(new char[password.length], password); assertEquals(0, published.get());
        } finally { gate.countDown(); runtime.dispose(); }
    }
}
