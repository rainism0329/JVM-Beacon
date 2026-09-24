package dev.jvmbeacon.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocking JMX boundary. All methods, including close(), must run off the IDE event thread. */
public final class JmxClient implements AutoCloseable {
    public static final int MAX_NAMES = 10_000;
    public static final int MAX_THREADS = 512;
    public static final int MAX_STACK_DEPTH = 64;
    public static final int MAX_NOTIFICATIONS = 200;
    public static final String THREAD_COVERAGE = "ThreadMXBean: platform threads only; virtual threads are excluded. Up to 512 thread IDs and 64 stack frames per thread. The ID list and stacks are collected at different instants; exited threads may be absent.";

    public record LocalJvm(String pid, String displayName) { }
    public record Identity(String runtimeName, long startTime, String vmName, String vmVersion) { }
    public record AttributeValue(String name, String type, boolean readable, boolean writable, Object value, String error) {
        public boolean available() { return error == null; }
    }
    public record Metric(String key, String label, Number value, String unit, String error) {
        public boolean available() { return value != null && error == null; }
    }
    public record Sample(long captureStart, long captureEnd, List<Metric> metrics) {
        public Sample { metrics = List.copyOf(metrics); }
        public String source() { return "JMX platform MXBeans; process CPU is com.sun.management OperatingSystemMXBean when available."; }
    }
    public record ThreadRecord(long id, String name, String state, long blockedCount, long waitedCount,
                           String lockName, String lockOwnerName, List<StackTraceElement> frames, Long lockOwnerId) {
        public ThreadRecord { frames = List.copyOf(frames); }
        /** Legacy captures have no owner ID. -1 means queried but no owner reported. */
        public ThreadRecord(long id, String name, String state, long blockedCount, long waitedCount,
                            String lockName, String lockOwnerName, List<StackTraceElement> frames) {
            this(id, name, state, blockedCount, waitedCount, lockName, lockOwnerName, frames, null);
        }
    }
    public record ThreadDump(long captureStart, long captureEnd, List<ThreadRecord> threads, boolean truncated,
                             String coverage, List<Long> deadlockedIds, String deadlockStatus) {
        public ThreadDump { threads = List.copyOf(threads); deadlockedIds = List.copyOf(deadlockedIds); }
    }
    public record NotificationEvent(long timestamp, long sequence, String type, String message, String source, String userData) { }

    public static final class ConnectionException extends IOException {
        private final String stage;
        public ConnectionException(String stage, String message, Throwable cause) { super(stage + ": " + message, cause); this.stage = stage; }
        public String stage() { return stage; }
    }

    private final JMXConnector connector;
    private final MBeanServerConnection server;
    private final Identity identity;
    private final Map<String, NotificationListener> listeners = new ConcurrentHashMap<>();
    private final ArrayDeque<NotificationEvent> events = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean namesTruncated;

    private JmxClient(JMXConnector connector) throws Exception {
        this.connector = connector;
        this.server = connector.getMBeanServerConnection();
        ObjectName runtime = new ObjectName(ManagementFactory.RUNTIME_MXBEAN_NAME);
        this.identity = new Identity((String) server.getAttribute(runtime, "Name"),
                ((Number) server.getAttribute(runtime, "StartTime")).longValue(),
                (String) server.getAttribute(runtime, "VmName"), (String) server.getAttribute(runtime, "VmVersion"));
    }

    public static List<LocalJvm> listLocal() {
        List<LocalJvm> result = new ArrayList<>();
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            // Attach descriptors can contain the complete application command line, including secrets.
            // Only a syntactically valid main-class token is used, never arguments or executable paths.
            String token = descriptor.displayName().split("\\s+", 2)[0];
            String label = token.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
                    ? token : "Java process";
            result.add(new LocalJvm(descriptor.id(), label + " · PID " + descriptor.id()));
        }
        result.sort(Comparator.comparing(LocalJvm::pid));
        return List.copyOf(result);
    }

    public static JmxClient connectLocal(String pid, boolean allowStartAgent) throws ConnectionException {
        return connectLocal(pid, allowStartAgent, stage -> {});
    }

    public static JmxClient connectLocal(String pid, boolean allowStartAgent, java.util.function.Consumer<String> progress) throws ConnectionException {
        if (pid == null || !pid.matches("[0-9]{1,12}")) throw new ConnectionException("Attach", "Choose a numeric local process ID.", null);
        VirtualMachine machine = null;
        String address;
        try {
            progress.accept("Attach to the selected local PID");
            machine = VirtualMachine.attach(pid);
            progress.accept("Read the local management address");
            address = machine.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            if (address == null || address.isBlank()) {
                if (!allowStartAgent) throw new ConnectionException("Management agent", "This process has no local management agent. Explicitly allow starting its local management agent, then reconnect. Starting the agent changes the target process.", null);
                progress.accept("Start the local management agent (explicitly allowed)");
                address = machine.startLocalManagementAgent();
                if (address == null) throw new ConnectionException("Management agent", "The target did not provide a local JMX address. Verify that its JDK supports the management agent.", null);
            }
        } catch (ConnectionException e) { throw e; }
        catch (Exception e) {
            throw new ConnectionException("Attach", "Cannot attach. Verify that the process still runs, uses a supported HotSpot JDK, belongs to the same OS user, and has not disabled Attach. " + safeError(e), e);
        } finally {
            if (machine != null) try { machine.detach(); } catch (IOException ignored) { /* Best effort; no credentials or target command line logged. */ }
        }
        return connect(address, Map.of(), progress);
    }

    public static JmxClient connectRemote(String url, String username, char[] password, boolean tlsRegistry) throws ConnectionException {
        return connectRemote(url, username, password, tlsRegistry, stage -> {});
    }

    public static JmxClient connectRemote(String url, String username, char[] password, boolean tlsRegistry, java.util.function.Consumer<String> progress) throws ConnectionException {
        try { url = RemoteEndpoint.normalize(url); }
        catch (IllegalArgumentException e) { throw new ConnectionException("Configuration", e.getMessage(), e); }
        Map<String, Object> environment = new HashMap<>();
        if (username != null && !username.isBlank()) {
            environment.put(JMXConnector.CREDENTIALS, new String[]{username, password == null ? "" : new String(password)});
        } else if (password != null && password.length > 0) {
            throw new ConnectionException("Configuration", "A username is required when a password is supplied.", null);
        }
        if (tlsRegistry) environment.put("com.sun.jndi.rmi.factory.socket", new SslRMIClientSocketFactory());
        try { return connect(url, environment, progress); }
        finally {
            Object credentials = environment.remove(JMXConnector.CREDENTIALS);
            if (credentials instanceof String[] array) Arrays.fill(array, null);
        }
    }

    private static JmxClient connect(String address, Map<String, ?> environment, java.util.function.Consumer<String> progress) throws ConnectionException {
        JMXServiceURL url;
        try {
            url = new JMXServiceURL(address);
            if (!url.getProtocol().equals("rmi")) throw new IOException("Unsupported protocol");
        } catch (Exception e) { throw new ConnectionException("Configuration", "Enter a JMX RMI service URL, for example service:jmx:rmi:///jndi/rmi://host:port/jmxrmi.", e); }
        JMXConnector connector = null;
        try {
            Map<String, Object> boundedEnvironment = new HashMap<>(environment);
            // OpenJDK-specific: disable the provider's background heartbeat thread.
            // This is not a transport timeout and does not disable all provider recovery.
            boundedEnvironment.put("jmx.remote.x.client.connection.check.period", 0L);
            boundedEnvironment.put("jmx.remote.x.notification.fetch.max", 200);
            boundedEnvironment.put("jmx.remote.x.notification.fetch.timeout", 1_000L);
            connector = JMXConnectorFactory.newJMXConnector(url, boundedEnvironment);
            progress.accept("Connect JMX/RMI and negotiate authentication / TLS");
            connector.connect();
            progress.accept("Read and verify JVM identity");
            return new JmxClient(connector);
        } catch (Exception e) {
            if (connector != null) try { connector.close(); } catch (IOException ignored) { }
            throw new ConnectionException("JMX connection / identity", "Unable to establish and identify the JMX connection. " + safeError(e)
                    + " Check both the RMI registry port and the advertised server host/port; TLS trust and registry TLS must match the server configuration.", e);
        }
    }

    public Identity identity() { return identity; }

    public List<String> queryNames() throws Exception {
        ensureOpen();
        Set<ObjectName> names = server.queryNames(null, null);
        namesTruncated = names.size() > MAX_NAMES;
        return names.stream().map(ObjectName::getCanonicalName).sorted().limit(MAX_NAMES).toList();
    }

    public boolean namesTruncated() { return namesTruncated; }

    public MBeanInfo info(String name) throws Exception {
        ensureOpen();
        return server.getMBeanInfo(new ObjectName(name));
    }

    public List<AttributeValue> readAttributes(String name) throws Exception {
        ensureOpen();
        ObjectName objectName = new ObjectName(name);
        MBeanAttributeInfo[] attributes = server.getMBeanInfo(objectName).getAttributes();
        List<AttributeValue> result = new ArrayList<>();
        for (int i = 0; i < Math.min(attributes.length, 1_000); i++) {
            ensureOpen();
            if (Thread.currentThread().isInterrupted()) throw new IOException("Attribute read cancelled between remote calls; any in-flight server call may still run.");
            MBeanAttributeInfo attribute = attributes[i];
            Object value = null;
            String error = null;
            if (!attribute.isReadable()) error = "Not readable (write-only attribute).";
            else {
                try { value = server.getAttribute(objectName, attribute.getName()); }
                catch (Exception e) { throwConnectionFailure(e); error = safeError(e); }
            }
            result.add(new AttributeValue(attribute.getName(), attribute.getType(), attribute.isReadable(), attribute.isWritable(), value, error));
        }
        if (attributes.length > 1_000) result.add(new AttributeValue("[attributes truncated]", "", false, false, null, "Only the first 1000 attributes are shown."));
        return List.copyOf(result);
    }

    public AttributeSeries.Reading readNumericAttribute(String name, String attribute) throws Exception {
        ensureOpen();
        long start = System.currentTimeMillis();
        try {
            Object value = server.getAttribute(new ObjectName(name), attribute);
            return AttributeSeries.Reading.from(start, System.currentTimeMillis(), value);
        } catch (Exception e) {
            throwConnectionFailure(e);
            return AttributeSeries.Reading.missing(start, System.currentTimeMillis(), safeError(e));
        }
    }

    public void setAttribute(String name, String attribute, String type, String text) throws Exception {
        ensureOpen();
        Object value = TypeCodec.parse(type, text);
        server.setAttribute(new ObjectName(name), new Attribute(attribute, value));
    }

    public Object invoke(String name, MBeanOperationInfo operation, List<String> inputs) throws Exception {
        ensureOpen();
        MBeanParameterInfo[] parameters = operation.getSignature();
        if (inputs.size() != parameters.length) throw new IllegalArgumentException("Expected " + parameters.length + " parameters.");
        Object[] values = new Object[parameters.length];
        String[] signature = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            signature[i] = parameters[i].getType();
            values[i] = TypeCodec.parse(signature[i], inputs.get(i));
        }
        // Deliberately no retry: a response failure cannot prove that the operation did not execute.
        return server.invoke(new ObjectName(name), operation.getName(), values, signature);
    }

    public void subscribe(String name) throws Exception {
        ensureOpen();
        NotificationListener listener = (notification, handback) -> {
            if (closed.get()) return;
            NotificationEvent event = new NotificationEvent(notification.getTimeStamp(), notification.getSequenceNumber(),
                    bounded(notification.getType(), 512), bounded(notification.getMessage(), 2_048), bounded(name, 1_024),
                    bounded(ValueFormatter.format(notification.getUserData()), 4_096));
            synchronized (events) {
                if (closed.get()) return;
                if (events.size() == MAX_NOTIFICATIONS) events.removeFirst();
                events.addLast(event);
            }
        };
        synchronized (listeners) {
            if (listeners.containsKey(name)) return;
            if (listeners.size() >= 32) throw new IllegalStateException("At most 32 notification subscriptions are supported per connection.");
            listeners.put(name, listener);
        }
        try {
            server.addNotificationListener(new ObjectName(name), listener, null, null);
            // A late subscription response must not survive close(). Closing the connector removes its registrations.
            if (closed.get()) server.removeNotificationListener(new ObjectName(name), listener);
        } catch (Exception e) { listeners.remove(name, listener); throw e; }
    }

    public void unsubscribe(String name) throws Exception {
        ensureOpen();
        NotificationListener listener = listeners.get(name);
        if (listener != null) {
            server.removeNotificationListener(new ObjectName(name), listener);
            listeners.remove(name, listener);
        }
    }

    public List<NotificationEvent> notifications() { synchronized (events) { return List.copyOf(events); } }

    public Sample sample() throws IOException {
        ensureOpen();
        long started = System.currentTimeMillis();
        List<Metric> result = new ArrayList<>();
        memory(result, "HeapMemoryUsage", "heap", "Heap");
        memory(result, "NonHeapMemoryUsage", "nonheap", "Non-heap");
        metric(result, "threads.live", "Live platform threads", "threads", ManagementFactory.THREAD_MXBEAN_NAME, "ThreadCount", 1);
        metric(result, "threads.daemon", "Daemon platform threads", "threads", ManagementFactory.THREAD_MXBEAN_NAME, "DaemonThreadCount", 1);
        metric(result, "classes.loaded", "Loaded classes", "classes", ManagementFactory.CLASS_LOADING_MXBEAN_NAME, "LoadedClassCount", 1);
        metric(result, "runtime.uptime", "JVM uptime", "ms", ManagementFactory.RUNTIME_MXBEAN_NAME, "Uptime", 1);
        metric(result, "cpu.process", "Process CPU load", "%", ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, "ProcessCpuLoad", 100);
        metric(result, "cpu.time", "Process CPU time", "ns", ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, "ProcessCpuTime", 1);
        try {
            Set<ObjectName> collectors = server.queryNames(new ObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*"), null);
            long count = 0, millis = 0;
            boolean unsupported = collectors.isEmpty();
            for (ObjectName collector : collectors) {
                long c = ((Number) server.getAttribute(collector, "CollectionCount")).longValue();
                long m = ((Number) server.getAttribute(collector, "CollectionTime")).longValue();
                if (c < 0 || m < 0) unsupported = true;
                else { count += c; millis += m; }
            }
            String error = unsupported ? "At least one collector does not expose cumulative collection statistics." : null;
            result.add(new Metric("gc.count", "GC collection count (sum, cumulative)", unsupported ? null : count, "collections", error));
            result.add(new Metric("gc.time", "GC collection time (sum, cumulative)", unsupported ? null : millis, "ms", error));
        } catch (Exception e) {
            throwConnectionFailure(e);
            result.add(new Metric("gc.count", "GC collection count (sum, cumulative)", null, "collections", safeError(e)));
            result.add(new Metric("gc.time", "GC collection time (sum, cumulative)", null, "ms", safeError(e)));
        }
        return new Sample(started, System.currentTimeMillis(), result);
    }

    private void memory(List<Metric> result, String attribute, String prefix, String label) throws IOException {
        try {
            CompositeData data = (CompositeData) server.getAttribute(new ObjectName(ManagementFactory.MEMORY_MXBEAN_NAME), attribute);
            for (String property : List.of("used", "committed", "max")) {
                long value = ((Number) data.get(property)).longValue();
                result.add(new Metric(prefix + "." + property, label + " " + property, value < 0 ? null : value, "bytes", value < 0 ? "Not defined by this JVM." : null));
            }
        } catch (Exception e) {
            throwConnectionFailure(e);
            for (String property : List.of("used", "committed", "max")) result.add(new Metric(prefix + "." + property, label + " " + property, null, "bytes", safeError(e)));
        }
    }

    private void metric(List<Metric> result, String key, String label, String unit, String bean, String attribute, int scale) throws IOException {
        try {
            Number number = (Number) server.getAttribute(new ObjectName(bean), attribute);
            if (number.doubleValue() < 0 || !Double.isFinite(number.doubleValue())) result.add(new Metric(key, label, null, unit, "Not supported or not available from this JVM yet."));
            else {
                Number scaled = number;
                if (scale != 1) scaled = number.doubleValue() * scale;
                result.add(new Metric(key, label, scaled, unit, null));
            }
        } catch (Exception e) { throwConnectionFailure(e); result.add(new Metric(key, label, null, unit, safeError(e))); }
    }

    public ThreadActivity.Report hotThreads() throws Exception {
        ensureOpen();
        MBeanInfo info = server.getMBeanInfo(new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME));
        boolean bulk = Arrays.stream(info.getOperations()).anyMatch(op -> op.getName().equals("getThreadCpuTime")
                && op.getSignature().length == 1 && op.getSignature()[0].getType().equals("[J") && op.getReturnType().equals("[J"));
        if (!bulk) return ThreadActivity.unavailable(identity, "This target does not expose bulk platform-thread CPU counters. No per-thread network polling fallback is used.");
        var bean = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.THREAD_MXBEAN_NAME, com.sun.management.ThreadMXBean.class);
        return ThreadActivity.capture(bean, identity);
    }

    public ThreadDump threads() throws IOException {
        ensureOpen();
        long started = System.currentTimeMillis();
        ThreadMXBean threads = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        long[] allIds = threads.getAllThreadIds();
        Arrays.sort(allIds);
        long[] ids = Arrays.copyOf(allIds, Math.min(MAX_THREADS, allIds.length));
        ThreadInfo[] infos = threads.getThreadInfo(ids, MAX_STACK_DEPTH);
        List<ThreadRecord> result = new ArrayList<>();
        int exited = 0;
        for (ThreadInfo info : infos) {
            if (info == null) { exited++; continue; }
            result.add(new ThreadRecord(info.getThreadId(), bounded(info.getThreadName(), 1_024), info.getThreadState().name(),
                    info.getBlockedCount(), info.getWaitedCount(), bounded(info.getLockName(), 1_024), bounded(info.getLockOwnerName(), 1_024),
                    Arrays.asList(info.getStackTrace()), info.getLockOwnerId()));
        }
        List<Long> deadlocked = new ArrayList<>();
        String deadlockStatus;
        try {
            boolean synchronizers = threads.isSynchronizerUsageSupported();
            long[] found = synchronizers ? threads.findDeadlockedThreads() : threads.findMonitorDeadlockedThreads();
            if (found != null) for (long id : found) { if (deadlocked.size() == MAX_THREADS) break; deadlocked.add(id); }
            deadlockStatus = (synchronizers ? "Monitor and ownable-synchronizer cycle detection" : "Monitor-only cycle detection; ownable synchronizers unsupported")
                    + (deadlocked.isEmpty() ? ": no cycle reported at capture time. This does not exclude other blocking or virtual-thread problems." : ": " + deadlocked.size() + " platform thread IDs reported.")
                    + (found != null && found.length > MAX_THREADS ? " Deadlock IDs truncated to 512." : "");
        } catch (RuntimeException e) { throwConnectionFailure(e); deadlockStatus = "Deadlock detection unavailable. " + safeError(e); }
        String coverage = THREAD_COVERAGE + (exited > 0 ? " " + exited + " selected thread(s) exited before stack capture." : "");
        return new ThreadDump(started, System.currentTimeMillis(), result, allIds.length > MAX_THREADS, coverage, deadlocked, deadlockStatus);
    }

    public static String safeError(Throwable failure) {
        Throwable current = failure;
        for (int i = 0; i < 12 && current != null; i++, current = current.getCause()) {
            if (current instanceof SecurityException) return "Permission or authentication denied. Check server-side credentials and access rules.";
            if (current instanceof javax.net.ssl.SSLException) return "TLS handshake failed. Check the certificate trust chain and server TLS requirements.";
            if (current instanceof java.net.ConnectException) return "Connection refused. Check that the process and JMX endpoint are running and reachable.";
            if (current instanceof java.net.UnknownHostException) return "Host resolution failed. Check the hostname advertised by the JMX/RMI server.";
            if (current instanceof java.net.SocketTimeoutException) return "Network request timed out. The target may still be processing the request.";
            if (current instanceof InstanceNotFoundException) return "MBean no longer exists. Refresh the object list.";
            if (current instanceof AttributeNotFoundException) return "Attribute is not exposed by this JVM or MBean.";
            if (current instanceof UnsupportedOperationException) return "This capability is not supported by the target.";
        }
        // Raw exception messages may contain user data, credentials or endpoint URLs.
        return "Request failed (" + failure.getClass().getSimpleName() + "). Check target availability and its server-side logs.";
    }

    private static String bounded(String value, int max) { return value == null ? "" : value.substring(0, Math.min(value.length(), max)); }
    private static void throwConnectionFailure(Throwable failure) throws IOException {
        // An MBean may itself throw an IOException; MBeanException keeps that as an attribute/operation error.
        if (failure instanceof MBeanException || failure instanceof RuntimeMBeanException) return;
        Throwable current = failure;
        for (int i = 0; i < 12 && current != null; i++, current = current.getCause()) {
            if (current instanceof IOException io) throw io;
        }
    }
    private void ensureOpen() { if (closed.get()) throw new IllegalStateException("The JMX connection has been closed."); }

    @Override public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            listeners.clear();
            synchronized (events) { events.clear(); }
            connector.close();
        }
    }
}
