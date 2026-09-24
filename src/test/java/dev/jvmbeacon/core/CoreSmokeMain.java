package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Standalone fallback smoke/measurement harness; no IDE or JUnit runtime needed. */
public final class CoreSmokeMain {
    public static void main(String[] args) throws Exception {
        System.out.println("Environment: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "; " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        try (FixtureProcess fixture = new FixtureProcess(false)) {
            System.out.println("Fixture discoverable via Attach.list: " + JmxClient.listLocal().stream().anyMatch(process -> process.pid().equals(fixture.pid)));
            boolean denied = false;
            try (JmxClient ignored = JmxClient.connectLocal(fixture.pid, false)) { }
            catch (JmxClient.ConnectionException expected) { denied = expected.stage().equals("Management agent"); }
            require(denied, "Agent startup consent required");
            try (JmxClient local = JmxClient.connectLocal(fixture.pid, true)) {
                for (int i = 0; i < 10; i++) local.sample();
                long cpuStart = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                double[] millis = new double[100];
                for (int i = 0; i < millis.length; i++) {
                    long start = System.nanoTime();
                    local.sample();
                    millis[i] = (System.nanoTime() - start) / 1_000_000d;
                }
                long cpuEnd = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                Arrays.sort(millis);
                System.out.printf(Locale.ROOT, "Local samples=100, warmup=10, median=%.3f ms, p95=%.3f ms, max=%.3f ms, calling-thread CPU=%.3f ms%n", millis[49], millis[94], millis[99], (cpuEnd-cpuStart)/1_000_000d);
                var dump = local.threads();
                require(dump.threads().stream().anyMatch(thread -> thread.name().equals("beacon-fixture-platform-wait")), "Platform thread visible");
                require(dump.threads().stream().noneMatch(thread -> thread.name().equals("beacon-fixture-virtual-wait")), "Virtual thread excluded");
                System.out.println("Captured platform threads=" + dump.threads().size() + "; virtual fixture thread correctly excluded");
                Path output = Path.of("build", "core-check", "real-fixture.beacon");
                Files.createDirectories(output.getParent());
                SnapshotStore.save(output, new SnapshotStore.Snapshot(local.identity(), local.sample(), dump, "Controlled local fixture; no target command line exported."));
                require(SnapshotStore.load(output).identity().equals(local.identity()), "Snapshot round trip");
                System.out.println("Snapshot round trip: " + output.toAbsolutePath() + " (" + Files.size(output) + " bytes)");
            }
        }
        try (FixtureProcess fixture = new FixtureProcess(true)) {
            try (JmxClient client = fixture.remote("operator")) {
                client.setAttribute(DemoApplication.BEAN_NAME, "Counter", "int", "24");
                var operations = client.info(DemoApplication.BEAN_NAME).getOperations();
                var attributes = client.readAttributes(DemoApplication.BEAN_NAME);
                require(attributes.stream().anyMatch(attribute -> attribute.value() instanceof javax.management.openmbean.CompositeData), "CompositeData result");
                require(attributes.stream().anyMatch(attribute -> attribute.value() instanceof javax.management.openmbean.TabularData), "TabularData result");
                var add = Arrays.stream(operations).filter(operation -> operation.getName().equals("add")).findFirst().orElseThrow();
                require(client.invoke(DemoApplication.BEAN_NAME, add, List.of("2", "5")).equals(7), "Real MBean operation");
                client.subscribe(DemoApplication.BEAN_NAME);
                var burst = Arrays.stream(operations).filter(operation -> operation.getName().equals("burst")).findFirst().orElseThrow();
                client.invoke(DemoApplication.BEAN_NAME, burst, List.of("260"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (client.notifications().size() < 200 && System.nanoTime() < deadline) Thread.sleep(25);
                require(client.notifications().size() == 200, "Notification cap");
                require(client.notifications().getLast().sequence() == 260, "Newest notification retained");
                System.out.println("Remote authenticated workflow: write/invoke/complex values/260 notifications → buffer 200");
                client.unsubscribe(DemoApplication.BEAN_NAME);
            }
            try (JmxClient observer = fixture.remote("observer")) {
                boolean denied = false;
                try { observer.setAttribute(DemoApplication.BEAN_NAME, "Counter", "int", "25"); }
                catch (SecurityException expected) { denied = true; }
                require(denied, "Server-side observer write denied");
            }
            for (int i = 0; i < 3; i++) try (JmxClient client = fixture.remote("operator")) { client.subscribe(DemoApplication.BEAN_NAME); client.sample(); }
            Thread.sleep(300);
            Map<String, Long> baseline = jmxThreads();
            for (int i = 0; i < 20; i++) try (JmxClient client = fixture.remote("operator")) { client.subscribe(DemoApplication.BEAN_NAME); client.sample(); client.unsubscribe(DemoApplication.BEAN_NAME); }
            Thread.sleep(500);
            System.out.println("Client JMX/RMI threads after warmup: " + baseline);
            System.out.println("Client JMX/RMI threads after 20 connect/subscribe/sample/unsubscribe/close cycles: " + jmxThreads());
        }
        System.out.println("CORE_SMOKE_PASS");
    }

    private static Map<String, Long> jmxThreads() {
        Map<String, Long> counts = new TreeMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (name.startsWith("JMX") || name.startsWith("RMI")) {
                String group = name.replaceAll("\\d+", "#");
                counts.merge(group, 1L, Long::sum);
            }
        }
        return counts;
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
