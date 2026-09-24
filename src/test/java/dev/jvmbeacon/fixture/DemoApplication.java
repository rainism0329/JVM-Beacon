package dev.jvmbeacon.fixture;

import javax.management.*;
import javax.management.openmbean.*;
import javax.management.remote.*;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.net.ssl.SSLServerSocketFactory;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.security.auth.Subject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.security.AccessController;
import java.security.Principal;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled child JVM. Optional CPU pulse is capped at 120 s, with no unbounded allocations or deadlocks. */
public final class DemoApplication {
    public static final String BEAN_NAME = "dev.jvmbeacon.demo:type=Probe,name=Workbench";
    private static volatile long cpuSink;

    public interface ProbeMBean {
        int getCounter();
        long getElapsedMillis();
        Double getMissingNumber();
        double getNonFinite();
        int getDeniedNumber();
        void setCounter(int value);
        String getLabel();
        void setLabel(String value);
        boolean isEnabled();
        void setEnabled(boolean value);
        String getForbidden();
        String getBroken();
        CompositeData getSummary();
        TabularData getRows();
        int add(int left, int right);
        String echo(String text);
        int[] twice(int[] values);
        TabularData inspectRows();
        void emit(String message);
        void burst(int count);
        void fail();
        String slow(long millis) throws InterruptedException;
        String startLockContention(int seconds) throws InterruptedException;
        void releaseLockContention();
    }

    public static final class Probe extends NotificationBroadcasterSupport implements ProbeMBean {
        private final AtomicLong sequence = new AtomicLong();
        private final long created = System.nanoTime();
        private volatile int counter = 7;
        private volatile String label = "JVM Beacon controlled fixture";
        private volatile boolean enabled = true;
        private LockDemo lockDemo;
        @Override public synchronized String startLockContention(int seconds) throws InterruptedException {
            if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("Lock demo must last 1–120 seconds.");
            closeLocks();
            lockDemo = new LockDemo(seconds);
            return "Started bounded waiter → bridge → owner chain for up to " + seconds + " s. Invoke releaseLockContention to release early.";
        }
        @Override public synchronized void releaseLockContention() { if (lockDemo != null) lockDemo.release.countDown(); }
        synchronized void closeLocks() throws InterruptedException { if (lockDemo != null) { lockDemo.close(); lockDemo = null; } }
        @Override public int getCounter() { return counter; }
        @Override public long getElapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - created); }
        @Override public Double getMissingNumber() { return null; }
        @Override public double getNonFinite() { return Double.NaN; }
        @Override public int getDeniedNumber() { throw new SecurityException("Fixture numeric read denied."); }
        @Override public void setCounter(int value) { if (value < 0 || value > 1_000_000) throw new IllegalArgumentException("Counter must be between 0 and 1000000."); counter = value; }
        @Override public String getLabel() { return label; }
        @Override public void setLabel(String value) { if (value.length() > 256) throw new IllegalArgumentException("Label exceeds 256 characters."); label = value; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void setEnabled(boolean value) { enabled = value; }
        @Override public String getForbidden() { throw new SecurityException("Fixture refuses this attribute intentionally."); }
        @Override public String getBroken() { throw new IllegalStateException("Fixture getter failed intentionally."); }
        @Override public CompositeData getSummary() {
            try {
                CompositeType type = new CompositeType("Summary", "Fixture summary", new String[]{"label", "counter", "enabled"},
                        new String[]{"Label", "Counter", "Enabled"}, new OpenType<?>[]{SimpleType.STRING, SimpleType.INTEGER, SimpleType.BOOLEAN});
                return new CompositeDataSupport(type, Map.of("label", label, "counter", counter, "enabled", enabled));
            } catch (OpenDataException e) { throw new IllegalStateException(e); }
        }
        @Override public TabularData getRows() {
            try {
                CompositeType row = new CompositeType("Row", "Counter row", new String[]{"name", "value"},
                        new String[]{"Name", "Value"}, new OpenType<?>[]{SimpleType.STRING, SimpleType.INTEGER});
                TabularDataSupport result = new TabularDataSupport(new TabularType("Rows", "Fixture rows", row, new String[]{"name"}));
                result.put(new CompositeDataSupport(row, Map.of("name", "current", "value", counter)));
                result.put(new CompositeDataSupport(row, Map.of("name", "next", "value", counter + 1)));
                return result;
            } catch (OpenDataException e) { throw new IllegalStateException(e); }
        }
        @Override public int add(int left, int right) { return Math.addExact(left, right); }
        @Override public String echo(String text) { return text; }
        @Override public int[] twice(int[] values) { return java.util.Arrays.stream(values).map(value -> value * 2).toArray(); }
        @Override public TabularData inspectRows() { return getRows(); }
        @Override public void emit(String message) {
            if (message.length() > 2_048) throw new IllegalArgumentException("Message exceeds fixture limit.");
            Notification event = new Notification("dev.jvmbeacon.demo.changed", BEAN_NAME, sequence.incrementAndGet(), System.currentTimeMillis(), message);
            event.setUserData(getSummary());
            sendNotification(event);
        }
        @Override public void burst(int count) { if (count < 0 || count > 400) throw new IllegalArgumentException("Burst is limited to 400 notifications."); for (int i = 0; i < count; i++) emit("Fixture notification " + i); }
        @Override public void fail() { throw new IllegalArgumentException("Fixture operation failed intentionally."); }
        @Override public String slow(long millis) throws InterruptedException { if (millis < 0 || millis > 3_000) throw new IllegalArgumentException("Slow operation is limited to 3000 ms."); Thread.sleep(millis); return "Finished once after " + millis + " ms"; }
        @Override public MBeanNotificationInfo[] getNotificationInfo() { return new MBeanNotificationInfo[]{new MBeanNotificationInfo(new String[]{"dev.jvmbeacon.demo.changed"}, Notification.class.getName(), "Controlled fixture change")}; }
    }

    /** Three daemon threads; a single retained run, bounded lifetime and early release. No actual deadlock. */
    private static final class LockDemo {
        private final Object first = new Object(), second = new Object();
        private final CountDownLatch release = new CountDownLatch(1), closed = new CountDownLatch(1);
        private final CountDownLatch ownerReady = new CountDownLatch(1), bridgeReady = new CountDownLatch(1);
        private final long deadline;
        private final Thread owner, bridge, waiter;
        LockDemo(int seconds) throws InterruptedException {
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            owner = Thread.ofPlatform().daemon().name("beacon-lock-owner").unstarted(() -> {
                synchronized (second) { ownerReady.countDown(); await(release); }
                await(closed);
            });
            bridge = Thread.ofPlatform().daemon().name("beacon-lock-bridge").unstarted(() -> {
                await(ownerReady);
                synchronized (first) { bridgeReady.countDown(); synchronized (second) { /* observed contention */ } }
                await(closed);
            });
            waiter = Thread.ofPlatform().daemon().name("beacon-lock-waiter").unstarted(() -> {
                await(bridgeReady); synchronized (first) { /* observed contention */ } await(closed);
            });
            owner.start(); bridge.start(); waiter.start();
            try { if (!bridgeReady.await(2, TimeUnit.SECONDS)) { close(); throw new IllegalStateException("Fixture lock demo did not become ready."); } }
            catch (InterruptedException e) { release.countDown(); closed.countDown(); throw e; }
        }
        private void await(CountDownLatch latch) {
            try { latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        void close() throws InterruptedException {
            release.countDown(); closed.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (Thread thread : new Thread[]{owner, bridge, waiter}) thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())));
        }
    }

    public static void main(String[] args) throws Exception {
        boolean remote = args.length > 0 && args[0].equals("--remote");
        boolean tls = java.util.Arrays.asList(args).contains("--tls");
        if (tls && !remote) throw new IllegalArgumentException("--tls requires --remote as the first argument.");
        if (tls) configureTestTls();
        boolean timed = java.util.Arrays.asList(args).contains("--timed");
        boolean interactive = java.util.Arrays.asList(args).contains("--interactive");
        int durationSeconds = 600;
        for (String argument : args) {
            if (argument.startsWith("--duration=")) {
                durationSeconds = Integer.parseInt(argument.substring("--duration=".length()));
                if (durationSeconds < 1 || durationSeconds > 600) throw new IllegalArgumentException("Duration must be between 1 and 600 seconds.");
                timed = true;
            }
        }
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        if (java.util.Arrays.asList(args).contains("--disable-thread-cpu")) ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(false);
        Probe probe = new Probe();
        server.registerMBean(probe, new ObjectName(BEAN_NAME));
        CountDownLatch stop = new CountDownLatch(1);
        Thread parked = Thread.ofPlatform().daemon(true).name("beacon-fixture-platform-wait").start(() -> await(stop));
        Thread virtual = Thread.ofVirtual().name("beacon-fixture-virtual-wait").start(() -> await(stop));
        Thread cpu = java.util.Arrays.asList(args).contains("--cpu-demo")
                ? Thread.ofPlatform().daemon(true).name("beacon-fixture-cpu-pulse").start(() -> cpuPulse(stop)) : null;
        JMXConnectorServer connector = null;
        Registry registry = null;
        try {
            if (remote) {
                System.out.println("PASSWORD_REQUIRED (operator/observer users; password read from stdin, never arguments)");
                System.out.flush();
                String password = input.readLine();
                if (password == null || password.length() < 8) throw new IllegalArgumentException("Provide a fixture password of at least 8 characters on stdin.");
                System.setProperty("java.rmi.server.hostname", "127.0.0.1");
                LoopbackSockets registrySockets = new LoopbackSockets(tls);
                registry = LocateRegistry.createRegistry(0, tls ? new SslRMIClientSocketFactory() : null, registrySockets);
                int registryPort = registrySockets.port;
                Map<String, Object> environment = new HashMap<>(Map.of(
                        JMXConnectorServer.AUTHENTICATOR, (JMXAuthenticator) credentials -> authenticate(credentials, password),
                        RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, new LoopbackSockets(tls),
                        "jmx.remote.x.notification.buffer.size", 512));
                if (tls) {
                    environment.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, new SslRMIClientSocketFactory());
                    // The connector binds through its own registry and must trust the fixture certificate, too.
                    environment.put("com.sun.jndi.rmi.factory.socket", new SslRMIClientSocketFactory());
                }
                JMXServiceURL address = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + registryPort + "/jmxrmi");
                connector = JMXConnectorServerFactory.newJMXConnectorServer(address, environment, server);
                connector.setMBeanServerForwarder(readOnlyGuard());
                connector.start();
                System.out.println("JMX_URL=" + address);
            }
            System.out.println("PID=" + ProcessHandle.current().pid());
            System.out.println("READY " + BEAN_NAME + (timed ? "; exits after " + durationSeconds + " s" : interactive ? "; type quit then Enter to exit (blank lines are ignored)" : "; send a newline on stdin to exit"));
            System.out.flush();
            if (timed) { stop.await(durationSeconds, TimeUnit.SECONDS); System.out.println("STOPPING: configured duration elapsed."); }
            else if (interactive) {
                String command;
                while ((command = input.readLine()) != null && !command.strip().equalsIgnoreCase("quit")) {
                    System.out.println("Still running; type quit then Enter to stop this fixture."); System.out.flush();
                }
                System.out.println(command == null ? "STOPPING: stdin was closed. Keep an interactive terminal open, or use -DurationSeconds 600."
                        : "STOPPING: quit requested.");
            } else { input.readLine(); System.out.println("STOPPING: test harness input received or closed."); }
        } finally {
            stop.countDown();
            probe.closeLocks();
            parked.join(2_000); virtual.join(2_000);
            if (cpu != null) cpu.join(2_000);
            if (connector != null) connector.stop();
            if (registry != null) UnicastRemoteObject.unexportObject(registry, true);
            server.unregisterMBean(new ObjectName(BEAN_NAME));
        }
    }

    private static void await(CountDownLatch stop) { try { stop.await(10, TimeUnit.MINUTES); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static void cpuPulse(CountDownLatch stop) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        try {
            while (stop.getCount() != 0 && System.nanoTime() < deadline) {
                // Exceed a Windows scheduler/accounting tick; 5 ms bursts can alias to zero CPU time.
                long slice = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40), value = cpuSink;
                while (System.nanoTime() < slice) value = value * 1664525 + 1013904223;
                cpuSink = value;
                if (stop.await(160, TimeUnit.MILLISECONDS)) break;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static Subject authenticate(Object credentials, String password) {
        if (!(credentials instanceof String[] values) || values.length != 2 || !password.equals(values[1])
                || (!"operator".equals(values[0]) && !"observer".equals(values[0]))) throw new SecurityException("Fixture authentication failed.");
        return new Subject(true, Set.of((Principal) () -> values[0]), Set.of(), Set.of());
    }

    @SuppressWarnings("removal")
    private static MBeanServerForwarder readOnlyGuard() {
        AtomicReference<MBeanServer> delegate = new AtomicReference<>();
        return (MBeanServerForwarder) Proxy.newProxyInstance(DemoApplication.class.getClassLoader(), new Class<?>[]{MBeanServerForwarder.class}, (proxy, method, args) -> {
            if (method.getName().equals("setMBeanServer")) { if (!delegate.compareAndSet(null, (MBeanServer) args[0])) throw new IllegalArgumentException("Already initialized."); return null; }
            if (method.getName().equals("getMBeanServer")) return delegate.get();
            Subject subject = Subject.getSubject(AccessController.getContext());
            boolean observer = subject != null && subject.getPrincipals().stream().anyMatch(principal -> principal.getName().equals("observer"));
            if (observer && Set.of("setAttribute", "setAttributes", "invoke", "createMBean", "unregisterMBean").contains(method.getName())) throw new SecurityException("Observer has server-enforced read-only access.");
            try { return method.invoke(delegate.get(), args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    private static final class LoopbackSockets implements RMIServerSocketFactory {
        private volatile int port;
        private final boolean tls;
        private LoopbackSockets(boolean tls) { this.tls = tls; }
        @Override public ServerSocket createServerSocket(int requested) throws java.io.IOException {
            ServerSocket socket = tls
                    ? SSLServerSocketFactory.getDefault().createServerSocket(requested, 50, InetAddress.getByName("127.0.0.1"))
                    : new ServerSocket(requested, 50, InetAddress.getByName("127.0.0.1"));
            port = socket.getLocalPort();
            return socket;
        }
    }

    private static void configureTestTls() {
        String store = System.getenv("BEACON_TEST_KEYSTORE");
        String trust = System.getenv("BEACON_TEST_TRUSTSTORE");
        String password = System.getenv("BEACON_TEST_STORE_PASSWORD");
        if (store == null || trust == null || password == null) throw new IllegalArgumentException("TLS fixture requires dedicated test store environment variables.");
        // This main runs only in an owned child JVM. No parent/IDE TLS properties or user stores are modified.
        System.setProperty("javax.net.ssl.keyStore", store);
        System.setProperty("javax.net.ssl.keyStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.keyStorePassword", password);
        System.setProperty("javax.net.ssl.trustStore", trust);
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.trustStorePassword", password);
    }
}
