package dev.jvmbeacon.ui;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionRunnerTest {
    @Test void rejectedCapacityRetainsConnectionAndDoesNotSendWork() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(4), rejected = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>(); AtomicInteger sent = new AtomicInteger();
        try (SessionRunner runner = new SessionRunner(runtime, 2000)) {
            for (int n = 0; n < 4; n++) runtime.calls.submit(() -> { started.countDown(); awaitIgnoringInterrupts(gate); });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            runner.submit("sample", sent::incrementAndGet, value -> fail("Must not run"), message -> { error.set(message); rejected.countDown(); });
            assertTrue(rejected.await(3, TimeUnit.SECONDS)); assertEquals(0, sent.get());
            assertTrue(error.get().contains("[CAPACITY]")); assertFalse(SessionRunner.invalidatesConnection(error.get()));
            assertTrue(SessionRunner.invalidatesConnection("[TIMEOUT] sample"));
            assertTrue(SessionRunner.invalidatesConnection("[CONNECTION] refused"));
            assertFalse(SessionRunner.invalidatesConnection("[TARGET] MBean failed"));
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void connectionDeadlineDoesNotExtendOrdinaryRequests() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch connected = new CountDownLatch(1), timedOut = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        try (SessionRunner runner = new SessionRunner(runtime, 40)) {
            runner.submit(SessionRunner.Lane.NETWORK, "connect", 2000, () -> { Thread.sleep(120); return 7; }, value -> connected.countDown(), error -> { failure.set(error); connected.countDown(); });
            assertTrue(connected.await(3, TimeUnit.SECONDS)); assertNull(failure.get());
            runner.submit("ordinary read", () -> { Thread.sleep(500); return 1; }, value -> fail("Must time out"), error -> { failure.set(error); timedOut.countDown(); });
            assertTrue(timedOut.await(3, TimeUnit.SECONDS)); assertTrue(failure.get().contains("[TIMEOUT]"));
        } finally { runtime.dispose(); }
    }
    @Test void closingOneSessionDiscardsItsLateResultWhileAnotherContinues() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        AtomicInteger wrongCallbacks = new AtomicInteger();
        AtomicReference<String> otherError = new AtomicReference<>();
        try (SessionRunner a = new SessionRunner(runtime, 3000); SessionRunner b = new SessionRunner(runtime, 3000)) {
            a.submit("connect A", () -> {
                var lease = runtime.reserveConnection();
                started.countDown(); awaitIgnoringInterrupts(gate); lease.attach(cleaned::countDown); return lease;
            }, value -> wrongCallbacks.incrementAndGet(), error -> wrongCallbacks.incrementAndGet());
            assertTrue(started.await(3, TimeUnit.SECONDS));
            a.close();
            assertTrue(b.submit("sample B", () -> 7, value -> otherDone.countDown(), error -> { otherError.set(error); otherDone.countDown(); }));
            assertTrue(otherDone.await(3, TimeUnit.SECONDS)); assertNull(otherError.get());
            gate.countDown(); assertTrue(cleaned.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(0, wrongCallbacks.get());
            awaitReturnedConnections(runtime);
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void callsRunOffEdtAndCallbacksOnEdt() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        try (SessionRunner runner = new SessionRunner(runtime, 2000)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean offEdt = new AtomicBoolean();
            AtomicBoolean onEdt = new AtomicBoolean();
            runner.submit("test", () -> { offEdt.set(!SwingUtilities.isEventDispatchThread()); return 42; },
                    value -> { onEdt.set(SwingUtilities.isEventDispatchThread() && value == 42); done.countDown(); },
                    failure -> done.countDown());
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(offEdt.get()); assertTrue(onEdt.get()); assertFalse(runner.isBusy());
        } finally { runtime.dispose(); }
    }

    @Test void timeoutClosesLateConnectionAndNeverPublishesIt() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), timedOut = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger published = new AtomicInteger();
        AtomicReference<String> error = new AtomicReference<>();
        try (SessionRunner runner = new SessionRunner(runtime, 80)) {
            runner.submit("连接", () -> {
                BeaconExecutors.ConnectionLease lease = runtime.reserveConnection();
                awaitIgnoringInterrupts(gate);
                lease.attach(closed::countDown);
                return lease;
            }, result -> published.incrementAndGet(), failure -> { error.set(failure); timedOut.countDown(); });
            assertTrue(timedOut.await(3, TimeUnit.SECONDS));
            assertTrue(error.get().contains("[TIMEOUT]"));
            gate.countDown();
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            awaitReturnedConnections(runtime);
            assertEquals(0, published.get());
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void cancelAndDisposeDiscardCallbacksAndRejectOverlap() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        SessionRunner runner = new SessionRunner(runtime, 1000);
        try {
            assertTrue(runner.submit("first", () -> {
                BeaconExecutors.ConnectionLease lease = runtime.reserveConnection();
                started.countDown(); awaitIgnoringInterrupts(gate); lease.attach(closed::countDown); return lease;
            },
                    value -> callbacks.incrementAndGet(), error -> callbacks.incrementAndGet()));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertFalse(runner.submit("overlap", () -> 2, value -> {}, error -> {}));
            assertFalse(runner.submit(SessionRunner.Lane.LOCAL_IO, "local overlap", () -> 3, value -> {}, error -> {}));
            runner.close(); gate.countDown();
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(0, callbacks.get());
            assertFalse(runner.submit("disposed", runtime::reserveConnection, value -> {}, error -> {}));
            awaitReturnedConnections(runtime);
        } finally { gate.countDown(); runner.close(); runtime.dispose(); }
    }

    @Test void hungCallsRemainGloballyBoundedAcrossNewSessions() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(4), timeouts = new CountDownLatch(4);
        SessionRunner[] runners = new SessionRunner[5];
        AtomicReference<String> rejected = new AtomicReference<>();
        CountDownLatch rejectedLatch = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                runners[i] = new SessionRunner(runtime, 100);
                runners[i].submit("blocked", () -> { started.countDown(); awaitIgnoringInterrupts(gate); return null; },
                        value -> {}, error -> timeouts.countDown());
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(timeouts.await(3, TimeUnit.SECONDS));
            runners[4] = new SessionRunner(runtime, 1000);
            runners[4].submit("new connection", runtime::reserveConnection, value -> rejectedLatch.countDown(),
                    error -> { rejected.set(error); rejectedLatch.countDown(); });
            assertTrue(rejectedLatch.await(3, TimeUnit.SECONDS));
            assertTrue(rejected.get().contains("[CAPACITY]"));
            assertEquals(4, runtime.calls.getLargestPoolSize());
            assertEquals(0, runtime.calls.getQueue().size());
            assertEquals(BeaconExecutors.MAX_CONNECTIONS, runtime.availableConnections(), "Rejected work never reserves a connection.");
        } finally {
            gate.countDown();
            for (SessionRunner runner : runners) if (runner != null) runner.close();
            runtime.dispose();
        }
    }

    @Test void exceptionsNeverExposeRawServerMessages() {
        String secret = "password=should-not-appear";
        assertFalse(SessionRunner.explain("请求", new SecurityException(secret)).contains(secret));
        assertTrue(SessionRunner.explain("请求", new SecurityException(secret)).contains("[PERMISSION]"));
        javax.net.ssl.SSLHandshakeException handshake = new javax.net.ssl.SSLHandshakeException(secret);
        handshake.initCause(new java.security.cert.CertificateException(secret));
        assertTrue(SessionRunner.explain("连接", new java.io.IOException(handshake)).contains("[TLS]"));
        assertTrue(SessionRunner.explain("连接", new dev.jvmbeacon.core.JmxClient.ConnectionException("Management agent", secret, null)).contains("[AGENT]"));
    }

    @Test void mbeanFailureWrappersTakePrecedenceOverNestedInputTransportAndPermissionErrors() {
        String secret = "password=must-not-appear; target-user-content";
        Throwable[] failures = {
                new javax.management.RuntimeMBeanException(new IllegalArgumentException(secret)),
                new javax.management.MBeanException(new IllegalArgumentException(secret)),
                new javax.management.MBeanException(new java.io.IOException(secret)),
                new javax.management.MBeanException(new javax.net.ssl.SSLHandshakeException(secret)),
                new javax.management.RuntimeMBeanException(new SecurityException(secret)),
                new javax.management.RuntimeErrorException(new AssertionError(secret)),
                new java.lang.reflect.UndeclaredThrowableException(new javax.management.RuntimeMBeanException(new IllegalArgumentException(secret)))
        };
        for (Throwable failure : failures) {
            String message = SessionRunner.explain("调用 fail", failure);
            assertTrue(message.contains("[TARGET]"), message);
            assertFalse(message.contains("[INPUT]"), message);
            assertFalse(message.contains("[IO]"), message);
            assertFalse(message.contains("[PERMISSION]"), message);
            assertFalse(message.contains("[TLS]"), message);
            assertFalse(message.contains(secret));
        }
        assertTrue(SessionRunner.explain("客户端类型校验", new IllegalArgumentException(secret)).contains("[INPUT]"));
        assertTrue(SessionRunner.explain("认证", new SecurityException(secret)).contains("[PERMISSION]"));
        assertTrue(SessionRunner.explain("网络", new java.net.ConnectException(secret)).contains("[CONNECTION]"));
    }

    @Test void offlineWorkStillSucceedsWhenAllNetworkWorkersIgnoreCancellation() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(4), timedOut = new CountDownLatch(4), localDone = new CountDownLatch(1);
        SessionRunner[] blocked = new SessionRunner[4];
        AtomicReference<String> localFailure = new AtomicReference<>();
        AtomicBoolean localLane = new AtomicBoolean(), onEdt = new AtomicBoolean();
        try (SessionRunner offline = new SessionRunner(runtime, 2_000)) {
            for (int i = 0; i < blocked.length; i++) {
                blocked[i] = new SessionRunner(runtime, 100);
                blocked[i].submit("hung JMX", () -> { started.countDown(); awaitIgnoringInterrupts(gate); return null; },
                        ignored -> {}, error -> timedOut.countDown());
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(timedOut.await(3, TimeUnit.SECONDS));
            assertEquals(4, runtime.calls.getActiveCount());
            assertTrue(offline.submit(SessionRunner.Lane.LOCAL_IO, "open offline capture", () -> {
                localLane.set(Thread.currentThread().getName().startsWith("jvm-beacon-local-") && !SwingUtilities.isEventDispatchThread());
                return 42;
            }, value -> { onEdt.set(SwingUtilities.isEventDispatchThread() && value == 42); localDone.countDown(); },
                    error -> { localFailure.set(error); localDone.countDown(); }));
            assertTrue(localDone.await(3, TimeUnit.SECONDS));
            assertNull(localFailure.get()); assertTrue(localLane.get()); assertTrue(onEdt.get());
            assertEquals(4, runtime.calls.getActiveCount(), "Offline success does not rely on releasing hung network workers.");
        } finally {
            gate.countDown();
            for (SessionRunner runner : blocked) if (runner != null) runner.close();
            runtime.dispose();
        }
    }

    @Test void localWorkersHaveAnIndependentBoundAndNeverQueue() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(2), timedOut = new CountDownLatch(2), rejectedDone = new CountDownLatch(1), networkDone = new CountDownLatch(1);
        SessionRunner[] local = new SessionRunner[3];
        AtomicReference<String> rejection = new AtomicReference<>(), networkFailure = new AtomicReference<>();
        AtomicInteger unexpectedCalls = new AtomicInteger();
        try (SessionRunner network = new SessionRunner(runtime, 2_000)) {
            for (int i = 0; i < 2; i++) {
                local[i] = new SessionRunner(runtime, 100);
                local[i].submit(SessionRunner.Lane.LOCAL_IO, "hung local storage", () -> { started.countDown(); awaitIgnoringInterrupts(gate); return null; },
                        ignored -> {}, error -> timedOut.countDown());
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(timedOut.await(3, TimeUnit.SECONDS));
            local[2] = new SessionRunner(runtime, 2_000);
            assertTrue(local[2].submit(SessionRunner.Lane.LOCAL_IO, "third local request", unexpectedCalls::incrementAndGet,
                    ignored -> rejectedDone.countDown(), error -> { rejection.set(error); rejectedDone.countDown(); }));
            assertTrue(rejectedDone.await(3, TimeUnit.SECONDS));
            assertTrue(rejection.get().contains("[CAPACITY]")); assertTrue(rejection.get().contains("Local"));
            assertEquals(0, unexpectedCalls.get());
            assertEquals(2, runtime.localCalls.getLargestPoolSize());
            assertEquals(0, runtime.localCalls.getQueue().size());
            network.submit("independent JMX lane", () -> true, ignored -> networkDone.countDown(), error -> { networkFailure.set(error); networkDone.countDown(); });
            assertTrue(networkDone.await(3, TimeUnit.SECONDS)); assertNull(networkFailure.get());
        } finally {
            gate.countDown();
            for (SessionRunner runner : local) if (runner != null) runner.close();
            runtime.dispose();
            assertTrue(runtime.localCalls.isShutdown());
        }
    }

    @Test void localWorkRejectsBothLanesOverlappingInTheSameView() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        try (SessionRunner runner = new SessionRunner(runtime, 2_000)) {
            assertTrue(runner.submit(SessionRunner.Lane.LOCAL_IO, "file read", () -> { started.countDown(); awaitIgnoringInterrupts(gate); return true; },
                    ignored -> { callbacks.incrementAndGet(); done.countDown(); }, error -> done.countDown()));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertFalse(runner.submit("network overlap", () -> 1, ignored -> {}, error -> {}));
            assertFalse(runner.submit(SessionRunner.Lane.LOCAL_IO, "local overlap", () -> 2, ignored -> {}, error -> {}));
            assertEquals(0, runtime.calls.getLargestPoolSize());
            gate.countDown();
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(1, callbacks.get());
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void localTimeoutDiscardsLateResultAndDescribesStorageRatherThanReconnect() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(1), timedOut = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger published = new AtomicInteger();
        try (SessionRunner runner = new SessionRunner(runtime, 100)) {
            runner.submit(SessionRunner.Lane.LOCAL_IO, "save capture", () -> {
                started.countDown(); awaitIgnoringInterrupts(gate); returned.countDown(); return 42;
            }, ignored -> published.incrementAndGet(), error -> { failure.set(error); timedOut.countDown(); });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(timedOut.await(3, TimeUnit.SECONDS));
            assertTrue(failure.get().contains("[TIMEOUT]")); assertTrue(failure.get().contains("Local"));
            assertFalse(failure.get().contains("reconnect")); assertFalse(runner.isBusy());
            gate.countDown(); assertTrue(returned.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(0, published.get());
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void allAdmittedConnectionsFitCleanupAndRepeatedCloseDoesNotConsumeSlots() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(2);
        CountDownLatch closed = new CountDownLatch(BeaconExecutors.MAX_CONNECTIONS);
        AtomicInteger closeCalls = new AtomicInteger();
        try {
            BeaconExecutors.ConnectionLease[] leases = new BeaconExecutors.ConnectionLease[BeaconExecutors.MAX_CONNECTIONS];
            for (int i = 0; i < leases.length; i++) {
                leases[i] = runtime.reserveConnection();
                leases[i].attach(() -> { started.countDown(); awaitIgnoringInterrupts(gate); closeCalls.incrementAndGet(); closed.countDown(); });
            }
            assertEquals(0, runtime.availableConnections());
            assertThrows(BeaconExecutors.ConnectionCapacityException.class, runtime::reserveConnection);
            for (BeaconExecutors.ConnectionLease lease : leases) { lease.close(); lease.close(); lease.close(); }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(BeaconExecutors.MAX_CONNECTIONS - 2, runtime.cleanup.getQueue().size());
            assertEquals(0, runtime.availableConnections(), "A queued or running close still owns its permit.");
            gate.countDown();
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            awaitReturnedConnections(runtime);
            assertEquals(BeaconExecutors.MAX_CONNECTIONS, closeCalls.get());
            runtime.reserveConnection().close();
            assertEquals(BeaconExecutors.MAX_CONNECTIONS, runtime.availableConnections(), "Capacity recovers after cleanup.");
        } finally { gate.countDown(); runtime.dispose(); }
    }

    @Test void failedCleanupAndUnusedReservationReturnExactlyOnePermit() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        AtomicInteger closed = new AtomicInteger();
        try {
            BeaconExecutors.ConnectionLease unused = runtime.reserveConnection();
            unused.close(); unused.close();
            assertEquals(BeaconExecutors.MAX_CONNECTIONS, runtime.availableConnections());
            BeaconExecutors.ConnectionLease failing = runtime.reserveConnection();
            failing.attach(() -> { closed.incrementAndGet(); throw new java.io.IOException("expected close failure"); });
            failing.close(); failing.close();
            awaitReturnedConnections(runtime);
            assertEquals(1, closed.get());
        } finally { runtime.dispose(); }
    }

    @Test void disposalRetainsQueuedCleanupAndAcceptsAnAlreadyReservedLateConnection() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(2);
        CountDownLatch closed = new CountDownLatch(BeaconExecutors.MAX_CONNECTIONS);
        BeaconExecutors.ConnectionLease late = runtime.reserveConnection();
        try {
            for (int i = 1; i < BeaconExecutors.MAX_CONNECTIONS; i++) {
                BeaconExecutors.ConnectionLease lease = runtime.reserveConnection();
                lease.attach(() -> { started.countDown(); awaitIgnoringInterrupts(gate); closed.countDown(); });
                lease.close();
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            runtime.dispose();
            assertFalse(runtime.cleanup.isShutdown(), "An admitted late connection still needs its cleanup executor.");
            assertThrows(BeaconExecutors.ConnectionCapacityException.class, runtime::reserveConnection);
            late.attach(closed::countDown); late.close();
            gate.countDown();
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            assertTrue(runtime.cleanup.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(BeaconExecutors.MAX_CONNECTIONS, runtime.availableConnections());
        } finally { late.close(); gate.countDown(); runtime.dispose(); }
    }

    @Test void failedSuccessCallbackAndFailureCallbackCanCloseSameConnectionSafely() throws Exception {
        BeaconExecutors runtime = new BeaconExecutors();
        AtomicInteger closed = new AtomicInteger();
        AtomicReference<BeaconExecutors.ConnectionLease> active = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        try (SessionRunner runner = new SessionRunner(runtime, 2_000)) {
            runner.submit("connection", () -> {
                BeaconExecutors.ConnectionLease lease = runtime.reserveConnection();
                lease.attach(closed::incrementAndGet); return lease;
            }, lease -> {
                active.set(lease); throw new IllegalStateException("UI failed after accepting connection");
            }, error -> { active.get().close(); failed.countDown(); });
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            awaitReturnedConnections(runtime);
            assertEquals(1, closed.get());
        } finally { runtime.dispose(); }
    }

    private static void awaitReturnedConnections(BeaconExecutors runtime) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (runtime.availableConnections() != BeaconExecutors.MAX_CONNECTIONS && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(BeaconExecutors.MAX_CONNECTIONS, runtime.availableConnections());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean done = false;
        while (!done) {
            try { latch.await(); done = true; } catch (InterruptedException ignored) { }
        }
    }
}
