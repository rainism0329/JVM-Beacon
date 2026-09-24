package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import javax.management.MBeanOperationInfo;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JmxClientIntegrationTest {
    @TempDir Path directory;
    private static final String BEAN = DemoApplication.BEAN_NAME;

    @Test void explicitReconnectKeepsIdentityForSameJvmAndDetectsAReplacement() throws Exception {
        JmxClient.Identity first;
        try (FixtureProcess fixture = new FixtureProcess(true)) {
            try (JmxClient initial = fixture.remote("observer")) { first = initial.identity(); }
            for (int i = 0; i < 3; i++) try (JmxClient reconnected = fixture.remote("observer")) {
                assertEquals(ConnectionIdentity.Match.SAME_REPORTED_IDENTITY, ConnectionIdentity.compare(first, reconnected.identity()));
                assertFalse(reconnected.sample().metrics().isEmpty());
            }
        }
        try (FixtureProcess replacement = new FixtureProcess(true); JmxClient connected = replacement.remote("observer")) {
            assertEquals(ConnectionIdentity.Match.CHANGED, ConnectionIdentity.compare(first, connected.identity()));
        }
    }

    @Test void realLockChainReleaseComparisonAndOfflineReopen() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true); JmxClient client = fixture.remote("operator")) {
            var start = operation(client, "startLockContention");
            assertThrows(javax.management.RuntimeMBeanException.class, () -> client.invoke(BEAN, start, List.of("121")));
            for (int run = 0; run < 2; run++) {
                client.invoke(BEAN, start, List.of("30"));
                JmxClient.ThreadDump before = awaitLockState(client, "BLOCKED");
                var waiter = before.threads().stream().filter(t -> t.name().equals("beacon-lock-waiter")).findFirst().orElseThrow();
                var chain = LockChains.from(before).path(waiter.id());
                assertEquals(List.of("beacon-lock-waiter", "beacon-lock-bridge", "beacon-lock-owner"), chain.threads().stream().map(JmxClient.ThreadRecord::name).toList());
                assertFalse(chain.cycle()); assertTrue(before.deadlockedIds().isEmpty());
                var a = new SnapshotStore.Snapshot(client.identity(), null, before, "Controlled contention");
                Path saved = directory.resolve("locks.jvmb"); SnapshotStore.save(saved, a);
                var reopened = SnapshotStore.load(saved);
                var reopenedChain = LockChains.from(reopened.threads()).path(waiter.id());
                assertEquals(chain.threads().stream().map(JmxClient.ThreadRecord::id).toList(), reopenedChain.threads().stream().map(JmxClient.ThreadRecord::id).toList());
                assertEquals(chain.threads().stream().map(JmxClient.ThreadRecord::lockOwnerId).toList(), reopenedChain.threads().stream().map(JmxClient.ThreadRecord::lockOwnerId).toList());
                assertEquals(chain.end(), reopenedChain.end());
                // Snapshot frames persist class/method/file/line, not module or class-loader metadata.
                assertEquals(0, ThreadComparison.compare(a.identity(), before, reopened.identity(), reopened.threads()).differences().size());
                client.invoke(BEAN, operation(client, "releaseLockContention"), List.of());
                var after = awaitLockState(client, "TIMED_WAITING");
                var report = ThreadComparison.compare(a.identity(), before, a.identity(), after);
                var difference = report.differences().stream().filter(d -> d.id() == waiter.id()).findFirst().orElseThrow();
                assertTrue(difference.fields().containsAll(List.of(ThreadComparison.Field.STATE, ThreadComparison.Field.LOCK)));
                assertEquals(-1L, difference.after().lockOwnerId());
                assertEquals(3, after.threads().stream().filter(t -> t.name().startsWith("beacon-lock-")).count(), "Replacing the demo cleans the prior three threads.");
            }
            try (JmxClient observer = fixture.remote("observer")) {
                assertThrows(SecurityException.class, () -> observer.invoke(BEAN, operation(observer, "startLockContention"), List.of("1")));
            }
        }
    }

    private static JmxClient.ThreadDump awaitLockState(JmxClient client, String state) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            var dump = client.threads();
            if (dump.threads().stream().anyMatch(t -> t.name().equals("beacon-lock-waiter") && t.state().equals(state))) return dump;
            Thread.sleep(20);
        } while (System.nanoTime() < end);
        throw new AssertionError("Fixture lock waiter did not reach " + state);
    }

    @Test void remoteAuthenticatedWorkflowComplexValuesFailuresNotificationsAndSnapshot() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true); JmxClient client = fixture.remote("operator")) {
            assertTrue(client.identity().startTime() > 0);
            assertTrue(client.queryNames().stream().anyMatch(name -> name.contains("dev.jvmbeacon.demo")));
            assertFalse(client.namesTruncated());
            var attributes = client.readAttributes(BEAN);
            assertTrue(attributes.stream().anyMatch(value -> value.name().equals("Forbidden") && value.error().contains("Permission")));
            assertTrue(attributes.stream().anyMatch(value -> value.name().equals("Broken") && value.error() != null));
            assertTrue(attributes.stream().anyMatch(value -> value.value() instanceof CompositeData && ValueFormatter.format(value.value()).contains("counter")));
            assertTrue(attributes.stream().anyMatch(value -> value.value() instanceof TabularData && ValueFormatter.format(value.value()).contains("current")));
            var structuredSummary = StructuredValue.capture("Summary", attributes.stream().filter(a -> a.name().equals("Summary")).findFirst().orElseThrow().value());
            assertEquals(StructuredValue.Kind.COMPOSITE, structuredSummary.root().kind());
            assertFalse(structuredSummary.incomplete());
            var structuredRows = StructuredValue.capture("Return value", client.invoke(BEAN, operation(client, "inspectRows"), List.of()));
            assertEquals(StructuredValue.Kind.TABLE, structuredRows.root().kind());
            assertEquals(2, structuredRows.root().children().size()); assertFalse(structuredRows.incomplete());
            client.setAttribute(BEAN, "Counter", "int", "18");
            assertEquals(18, client.readAttributes(BEAN).stream().filter(value -> value.name().equals("Counter")).findFirst().orElseThrow().value());
            assertEquals(5, client.invoke(BEAN, operation(client, "add"), List.of("2", "3")));
            assertArrayEquals(new int[]{2,4}, (int[]) client.invoke(BEAN, operation(client, "twice"), List.of("[1,2]")));
            var operationFailure = assertThrows(javax.management.RuntimeMBeanException.class,
                    () -> client.invoke(BEAN, operation(client, "fail"), List.of()));
            assertInstanceOf(IllegalArgumentException.class, operationFailure.getTargetException(), "Keep the target-stage JMX wrapper for the UI classifier.");
            var setterFailure = assertThrows(javax.management.RuntimeMBeanException.class,
                    () -> client.setAttribute(BEAN, "Counter", "int", "-1"));
            assertInstanceOf(IllegalArgumentException.class, setterFailure.getTargetException());
            assertThrows(IllegalArgumentException.class,
                    () -> client.invoke(BEAN, operation(client, "add"), List.of("not-an-int", "3")), "Client parsing errors remain input failures.");
            client.subscribe(BEAN); client.subscribe(BEAN);
            client.invoke(BEAN, operation(client, "burst"), List.of("260"));
            awaitNotifications(client, 200);
            assertEquals(200, client.notifications().size());
            assertEquals(260, client.notifications().getLast().sequence());
            client.unsubscribe(BEAN); client.unsubscribe(BEAN);
            var sample = client.sample();
            assertTrue(sample.metrics().stream().filter(metric -> metric.key().equals("heap.used")).findFirst().orElseThrow().available());
            var dump = client.threads();
            assertTrue(dump.coverage().contains("virtual threads are excluded"));
            assertTrue(dump.threads().stream().anyMatch(thread -> thread.name().equals("beacon-fixture-platform-wait")));
            assertFalse(dump.threads().stream().anyMatch(thread -> thread.name().equals("beacon-fixture-virtual-wait")));
            Path saved = directory.resolve("real.beacon");
            SnapshotStore.save(saved, new SnapshotStore.Snapshot(client.identity(), sample, dump, "Real fixture capture"));
            assertEquals(client.identity(), SnapshotStore.load(saved).identity());
            assertFalse(java.nio.file.Files.readString(saved).contains(fixture.password));
        }
    }

    @Test void serverAuthorizationWrongPasswordAndRepeatedConnectionCleanup() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true)) {
            assertThrows(JmxClient.ConnectionException.class, () -> JmxClient.connectRemote(fixture.url, "operator", "wrong-password".toCharArray(), false));
            try (JmxClient observer = fixture.remote("observer")) {
                assertTrue(observer.sample().metrics().stream().anyMatch(JmxClient.Metric::available));
                assertThrows(SecurityException.class, () -> observer.setAttribute(BEAN, "Counter", "int", "99"));
                assertThrows(SecurityException.class, () -> observer.invoke(BEAN, operation(observer, "emit"), List.of("not allowed")));
            }
            for (int i = 0; i < 8; i++) try (JmxClient client = fixture.remote("operator")) { client.subscribe(BEAN); client.sample(); client.unsubscribe(BEAN); }
        }
    }

    @Test void localAttachRequiresExplicitAgentStartAndTargetExitPropagates() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(false)) {
            // Windows sandbox/process namespaces can hide hsperfdata enumeration even when explicit-PID Attach works.
            // Discovery availability is separately measured by CoreSmokeMain; it is not a precondition for Attach.
            assertThrows(JmxClient.ConnectionException.class, () -> JmxClient.connectLocal(fixture.pid, false));
            try (JmxClient local = JmxClient.connectLocal(fixture.pid, true)) {
                assertTrue(local.sample().metrics().stream().anyMatch(JmxClient.Metric::available));
                assertEquals(fixture.pid, local.identity().runtimeName().split("@")[0]);
            }
            JmxClient existing = JmxClient.connectLocal(fixture.pid, false);
            try {
                assertTrue(existing.queryNames().size() > 10);
                fixture.close();
                assertThrows(IOException.class, existing::sample);
            } finally {
                try { existing.close(); }
                catch (IOException closeFailure) { if (fixture.process.isAlive()) throw closeFailure; }
            }
        }
    }

    @Test void slowOperationRunsOnceAndCancellationDoesNotPromiseRemoteCancellation() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true); JmxClient client = fixture.remote("operator")) {
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "bounded-test-request"); t.setDaemon(true); return t; });
            try {
                MBeanOperationInfo slow = operation(client, "slow");
                Future<Object> request = executor.submit(() -> client.invoke(BEAN, slow, List.of("700")));
                assertThrows(TimeoutException.class, () -> request.get(50, TimeUnit.MILLISECONDS));
                assertEquals("Finished once after 700 ms", request.get(3, TimeUnit.SECONDS));
                assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("long", "not-a-number"));
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); }
        }
    }

    @Test void shorthandAuthenticatedConnectionAndNumericTrackingBoundaries() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true)) {
            String shorthand = fixture.url.substring(fixture.url.indexOf("/jndi/rmi://") + 12).replace("/jmxrmi", "");
            JmxClient client = JmxClient.connectRemote(shorthand, "observer", fixture.password.toCharArray(), false);
            try {
                var first = client.readNumericAttribute(BEAN, "ElapsedMillis");
                assertNull(first.error()); assertTrue(first.start() <= first.end());
                assertTrue(client.readNumericAttribute(BEAN, "ElapsedMillis").plotted() >= first.plotted());
                assertEquals("7", client.readNumericAttribute(BEAN, "Counter").exact());
                for (String attribute : List.of("MissingNumber", "NonFinite", "DeniedNumber", "Label", "Summary", "NoSuchAttribute")) {
                    var reading = client.readNumericAttribute(BEAN, attribute);
                    assertNull(reading.plotted(), attribute); assertNotNull(reading.error(), attribute);
                }
                assertTrue(client.sample().metrics().stream().anyMatch(JmxClient.Metric::available));
                fixture.close();
                assertThrows(IOException.class, () -> client.readNumericAttribute(BEAN, "Counter"));
            } finally {
                // Only cleanup after deliberate target exit may ignore IOException; connection/read failures must fail the test.
                try { client.close(); }
                catch (IOException closeFailure) { if (fixture.process.isAlive()) throw closeFailure; }
            }
        }
    }

    @Test void simultaneousTargetsKeepValuesNotificationsAndDisconnectIndependent() throws Exception {
        try (FixtureProcess fixtureA = new FixtureProcess(true); FixtureProcess fixtureB = new FixtureProcess(true);
             JmxClient a = fixtureA.remote("operator"); JmxClient b = fixtureB.remote("operator")) {
            assertNotEquals(a.identity().runtimeName(), b.identity().runtimeName());
            a.subscribe(BEAN); b.subscribe(BEAN);
            a.setAttribute(BEAN, "Counter", "int", "12");
            assertEquals("12", a.readNumericAttribute(BEAN, "Counter").exact());
            assertEquals("7", b.readNumericAttribute(BEAN, "Counter").exact());
            a.invoke(BEAN, operation(a, "emit"), List.of("from-a"));
            awaitNotifications(a, 1);
            assertEquals(1, a.notifications().size()); assertTrue(b.notifications().isEmpty());
            a.close();
            b.invoke(BEAN, operation(b, "emit"), List.of("from-b"));
            awaitNotifications(b, 1);
            assertEquals(1, b.notifications().size());
            assertTrue(b.sample().metrics().stream().anyMatch(JmxClient.Metric::available));
            fixtureA.close();
            assertEquals("7", b.readNumericAttribute(BEAN, "Counter").exact());
            assertTrue(b.threads().threads().stream().anyMatch(t -> t.name().equals("beacon-fixture-platform-wait")));
        }
    }

    private static MBeanOperationInfo operation(JmxClient client, String name) throws Exception {
        return Arrays.stream(client.info(BEAN).getOperations()).filter(operation -> operation.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test void hotThreadsUsesRealBulkCountersAndServerAuthorization() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true, java.util.Map.of(), List.of("--cpu-demo"));
             JmxClient client = fixture.remote("operator"); JmxClient observer = fixture.remote("observer")) {
            var result = client.hotThreads();
            assertNull(result.unavailable()); assertEquals(client.identity(), result.identity());
            assertTrue(result.intervalNanos() >= 1_000_000_000L); assertTrue(result.rows().size() <= 512);
            var busy = result.rows().stream().filter(r -> r.name().equals("beacon-fixture-cpu-pulse")).findFirst().orElseThrow();
            assertNotNull(busy.cpuDeltaNanos()); assertTrue(busy.cpuDeltaNanos() > 0, () -> "Expected CPU activity from bounded pulse: " + busy + " interval=" + result.intervalNanos()); assertTrue(busy.oneCorePercent() > 0);
            assertNotNull(busy.endThread()); assertFalse(busy.endThread().frames().isEmpty()); assertTrue(busy.endThread().frames().size() <= 64);
            assertFalse(result.rows().stream().anyMatch(r -> r.name().equals("beacon-fixture-virtual-wait")));
            assertThrows(SecurityException.class, observer::hotThreads);
            assertTrue(observer.sample().metrics().stream().anyMatch(JmxClient.Metric::available));
        }
    }

    @Test void hotThreadsNeverEnablesDisabledTargetMonitoring() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true, java.util.Map.of(), List.of("--disable-thread-cpu"));
             JmxClient client = fixture.remote("operator")) {
            var result = client.hotThreads(); assertNotNull(result.unavailable());
            assertTrue(result.unavailable().contains("disabled")); assertTrue(result.rows().isEmpty());
            var attribute = client.readAttributes("java.lang:type=Threading").stream().filter(a -> a.name().equals("ThreadCpuTimeEnabled")).findFirst().orElseThrow();
            assertEquals(Boolean.FALSE, attribute.value());
        }
    }

    @Test void interactiveFixtureIgnoresBlankLinesAndLocalReconnectsKeepSampling() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(false, java.util.Map.of(), List.of("--interactive"))) {
            fixture.input.newLine(); fixture.input.newLine(); fixture.input.flush();
            var stages = new java.util.ArrayList<String>();
            for (int cycle = 0; cycle < 3; cycle++) try (JmxClient client = JmxClient.connectLocal(fixture.pid, cycle == 0, stages::add)) {
                for (int n = 0; n < 4; n++) {
                    var sample = client.sample();
                    assertTrue(sample.metrics().stream().anyMatch(JmxClient.Metric::available));
                    assertTrue(sample.captureEnd() >= sample.captureStart());
                    Thread.sleep(30);
                }
            }
            assertTrue(fixture.process.isAlive());
            assertTrue(stages.contains("Read and verify JVM identity"));
            assertTrue(stages.contains("Start the local management agent (explicitly allowed)"));
            fixture.close(); assertEquals(0, fixture.process.exitValue());
        }
    }
    private static void awaitNotifications(JmxClient client, int count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.notifications().size() < count && System.nanoTime() < until) Thread.sleep(25);
    }
}
