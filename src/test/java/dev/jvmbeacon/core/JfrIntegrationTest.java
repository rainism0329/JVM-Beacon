package dev.jvmbeacon.core;

import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.management.JMX;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import jdk.management.jfr.FlightRecorderMXBean;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServerConnection;
import static org.junit.jupiter.api.Assertions.*;

class JfrIntegrationTest {
    @TempDir Path directory;

    @Test void actualRemoteProfileFindsOwnedCpuPulseAndThread() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true, Map.of(), java.util.List.of("--cpu-demo")); JmxClient client = fixture.remote("operator")) {
            client.jfr().start("profile", 8);
            long end = System.nanoTime() + 18_000_000_000L;
            while (!"STOPPED".equals(client.jfr().inspect().state()) && System.nanoTime() < end) Thread.sleep(250);
            assertEquals("STOPPED", client.jfr().inspect().state());
            var report = JfrSummary.inspect(client.jfr().download(directory.resolve("cpu-profile.jfr")));
            var thread = report.stacks().threads(JfrStacks.Kind.JAVA).stream().filter(t -> t.name().equals("beacon-fixture-cpu-pulse")).findFirst().orElseThrow();
            var view = JfrStacks.aggregate(report.stacks(), JfrStacks.Kind.JAVA, thread);
            assertTrue(view.root().inclusive() > 0);
            assertTrue(report.stacks().samples().stream().filter(s -> s.thread().equals(thread)).anyMatch(s -> s.leafFirst().stream().anyMatch(f -> f.method().equals("cpuPulse"))));
            assertEquals(0, client.jfr().release().id());
        }
    }

    @Test void remoteRecordingStopsByItselfDownloadsAndReleases() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true);
             JmxClient client = JmxClient.connectRemote(fixture.url, "operator", fixture.password.toCharArray(), false)) {
            JfrCapture capture = client.jfr();
            assertTrue(capture.inspect().supported());
            assertTrue(capture.inspect().configurations().contains("default"));
            var started = capture.start("default", 5);
            assertEquals("RUNNING", started.state());
            assertEquals(5, started.durationSeconds());
            assertThrows(IllegalStateException.class, () -> capture.start("default", 5));
            assertThrows(IllegalStateException.class, () -> capture.download(directory.resolve("too-early.jfr")));
            long end = System.nanoTime() + 12_000_000_000L;
            JfrCapture.State stopped;
            do { Thread.sleep(250); stopped = capture.inspect(); }
            while (!"STOPPED".equals(stopped.state()) && System.nanoTime() < end);
            assertEquals("STOPPED", stopped.state());
            Path file = capture.download(directory.resolve("capture.jfr"));
            assertTrue(Files.size(file) > 0);
            try (RecordingFile recording = new RecordingFile(file)) { assertTrue(recording.hasMoreEvents()); }
            String report = JfrSummary.read(file);
            assertTrue(report.contains("jdk."));
            assertTrue(report.contains("Reached end of file"));
            assertTrue(JfrSummary.read(file, 1).contains("PARTIAL"));
            assertThrows(java.io.IOException.class, () -> capture.download(file));
            assertEquals(0, capture.release().id());
            assertEquals(0, capture.inspect().id());
        }
    }

    @Test void serverReadOnlyDeniesStartAndInvalidInputDoesNotCreateRecording() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true);
             JmxClient client = JmxClient.connectRemote(fixture.url, "observer", fixture.password.toCharArray(), false)) {
            assertTrue(client.jfr().inspect().supported());
            assertThrows(IllegalArgumentException.class, () -> client.jfr().start("default", 0));
            assertThrows(SecurityException.class, () -> client.jfr().start("default", 5));
            assertEquals(0, client.jfr().inspect().id());
        }
    }

    @Test void earlyStopAndDisconnectOnlyReleaseOwnedRecordingAcrossRepeatedSessions() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true);
             var independent = JMXConnectorFactory.connect(new JMXServiceURL(fixture.url),
                     Map.of("jmx.remote.credentials", new String[]{"operator", fixture.password}))) {
            var control = JMX.newMXBeanProxy(independent.getMBeanServerConnection(),
                    new ObjectName(FlightRecorderMXBean.MXBEAN_NAME), FlightRecorderMXBean.class);
            long unrelated = control.newRecording();
            try {
                for (int i = 0; i < 3; i++) {
                    long own;
                    try (JmxClient client = fixture.remote("operator")) {
                        var started = client.jfr().start("profile", 30); own = started.id();
                        var options = control.getRecordingOptions(own);
                        assertEquals("true", options.get("disk"));
                        assertEquals("false", options.get("dumpOnExit"));
                        assertEquals(JfrCapture.RETENTION_BYTES, Long.parseLong(options.get("maxSize")));
                        assertTrue(options.get("destination") == null || options.get("destination").isEmpty());
                        assertEquals("STOPPED", client.jfr().stop().state());
                    }
                    assertTrue(control.getRecordings().stream().noneMatch(r -> r.getId() == own));
                    assertTrue(control.getRecordings().stream().anyMatch(r -> r.getId() == unrelated));
                }
            } finally { control.closeRecording(unrelated); }
        }
    }

    @Test void transferCapAndLateCancellationCloseStreamsAndLeaveNoPartialFile() throws Exception {
        try (FixtureProcess fixture = new FixtureProcess(true);
             var connector = JMXConnectorFactory.connect(new JMXServiceURL(fixture.url),
                     Map.of("jmx.remote.credentials", new String[]{"operator", fixture.password}))) {
            for (boolean cancel : new boolean[]{false, true}) {
                var closes = new AtomicInteger(); var reads = new AtomicInteger();
                var active = new AtomicReference<JfrCapture>();
                var delegate = connector.getMBeanServerConnection();
                byte[] block = new byte[65536];
                var intercepted = (MBeanServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{MBeanServerConnection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("invoke")) {
                                if ("readStream".equals(args[1])) {
                                    reads.incrementAndGet();
                                    if (cancel) active.get().cancel();
                                    return block; // Simulates an endless stream, or a read returning after cancellation.
                                }
                                if ("closeStream".equals(args[1])) closes.incrementAndGet();
                            }
                            try { return method.invoke(delegate, args); }
                            catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                try (var capture = new JfrCapture(intercepted)) {
                    active.set(capture); capture.start("default", 30); capture.stop();
                    if (cancel) assertThrows(java.io.InterruptedIOException.class, () -> capture.download(directory.resolve("cancelled.jfr")));
                    else assertThrows(JfrCapture.TransferLimitException.class, () -> capture.download(directory.resolve("oversized.jfr")));
                    assertEquals(1, closes.get());
                    assertEquals(cancel ? 1 : 1025, reads.get());
                    try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
                }
            }
        }
    }
}
