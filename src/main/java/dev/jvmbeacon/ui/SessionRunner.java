package dev.jvmbeacon.ui;

import javax.swing.SwingUtilities;
import java.lang.ref.WeakReference;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One outstanding request per view. Connection results must implement ManagedConnection for late-result cleanup. */
public final class SessionRunner implements AutoCloseable {
    public enum Lane { NETWORK, LOCAL_IO }
    private final BeaconExecutors executors;
    private final long timeoutMillis;
    private Request<?> current;
    private boolean closed;

    public SessionRunner() { this(BeaconExecutors.getInstance(), 8_000); }
    SessionRunner(BeaconExecutors executors, long timeoutMillis) {
        this.executors = executors;
        this.timeoutMillis = timeoutMillis;
    }

    public synchronized boolean isBusy() { return current != null; }

    public <T> boolean submit(String label, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
        return submit(Lane.NETWORK, label, work, success, failure);
    }

    public synchronized <T> boolean submit(Lane lane, String label, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
        return submit(lane, label, timeoutMillis, work, success, failure);
    }

    /** Cold Attach/agent initialization has its own bounded deadline; ordinary requests keep 8 s. */
    public synchronized <T> boolean submit(Lane lane, String label, long deadlineMillis, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
        if (deadlineMillis <= 0 || deadlineMillis > 60_000) throw new IllegalArgumentException("Deadline must be between 1 and 60000 ms.");
        if (closed || current != null) return false;
        Request<T> request = new Request<>(this, lane, label, work, success, failure);
        current = request;
        try {
            request.deadline = executors.deadlines.schedule(request::timeout, deadlineMillis, TimeUnit.MILLISECONDS);
            request.future = (lane == Lane.LOCAL_IO ? executors.localCalls : executors.calls).submit(request::run);
        } catch (RejectedExecutionException rejected) {
            request.deliver(null, lane == Lane.LOCAL_IO
                    ? "[CAPACITY] Local file or credential workers are still busy, or the plugin is closing. Limit: 2 local tasks. No extra work is queued."
                    : "[CAPACITY] Target calls are still busy, or the plugin is closing. Limit: 4 calls. Local captures can still be opened, saved and compared. No extra work is queued.");
        }
        return true;
    }

    public synchronized void cancel() {
        Request<?> old = current;
        current = null;
        if (old != null) old.cancel();
    }

    static boolean invalidatesConnection(String error) {
        // A rejected task never reached the target. Capacity pressure is not a broken connection.
        return error.contains("[TIMEOUT]") || error.contains("[IO]") || error.contains("[CONNECTION]")
                || error.contains("[TLS]") || error.contains("[RUNTIME]");
    }

    @Override public synchronized void close() { closed = true; cancel(); }
    static void closeLater(BeaconExecutors.ManagedConnection resource) { if (resource != null) resource.close(); }

    private synchronized boolean release(Request<?> request) {
        if (current != request || closed) return false;
        current = null;
        return true;
    }

    private static final class Request<T> {
        private final WeakReference<SessionRunner> owner;
        private final String label;
        private final Lane lane;
        private final AtomicBoolean finished = new AtomicBoolean();
        private Callable<T> work;
        private volatile Consumer<T> success;
        private volatile Consumer<String> failure;
        private volatile Future<?> future;
        private volatile ScheduledFuture<?> deadline;

        Request(SessionRunner owner, Lane lane, String label, Callable<T> work,
                Consumer<T> success, Consumer<String> failure) {
            this.owner = new WeakReference<>(owner);
            this.label = label;
            this.lane = lane;
            this.work = work;
            this.success = success;
            this.failure = failure;
        }

        void run() {
            Callable<T> operation = work;
            work = null;
            if (finished.get()) return;
            try { deliver(operation.call(), null); }
            catch (Exception error) { deliver(null, explain(label, error, lane)); }
            catch (LinkageError error) { deliver(null, "[RUNTIME] The IDE runtime is missing a required module. Use a complete JBR/JDK 21 and check Attach support."); }
        }

        void timeout() {
            deliver(null, lane == Lane.LOCAL_IO
                    ? "[TIMEOUT] " + label + " timed out. Local file or credential work may still be running. Late results are discarded. Check the saved file or credential state before retrying."
                    : "[TIMEOUT] " + label + " timed out. The target may still be executing. Captured data is no longer updating. A mutation may have taken effect; check the target before reconnecting. Do not blindly retry mutations.");
            Future<?> running = future;
            if (running != null) running.cancel(true);
        }

        void cancel() {
            finished.set(true);
            success = null;
            failure = null;
            if (deadline != null) deadline.cancel(false);
            if (future != null) future.cancel(true);
            // Do not clear work here: a starting worker owns it. Late managed connections keep their cleanup permit.
        }

        void deliver(T value, String error) {
            if (!finished.compareAndSet(false, true)) {
                disposeResult(value);
                return;
            }
            if (deadline != null) deadline.cancel(false);
            SwingUtilities.invokeLater(() -> {
                SessionRunner runner = owner.get();
                Consumer<T> accepted = success;
                Consumer<String> rejected = failure;
                success = null;
                failure = null;
                if (runner == null || !runner.release(this)) { disposeResult(value); return; }
                if (error == null && accepted != null) {
                    try { accepted.accept(value); }
                    catch (RuntimeException uiFailure) {
                        disposeResult(value);
                        if (rejected != null) rejected.accept("[UI] The result could not be displayed. Any returned connection has been released. Reopen the workbench and retry.");
                    }
                }
                else if (error != null && rejected != null) rejected.accept(error);
                else disposeResult(value);
            });
        }

        private void disposeResult(Object result) {
            if (result instanceof BeaconExecutors.ManagedConnection resource) resource.close();
        }
    }

    static String explain(String label, Throwable error) {
        return explain(label, error, Lane.NETWORK);
    }

    private static String explain(String label, Throwable error, Lane lane) {
        Throwable cause = error;
        java.util.List<Throwable> chain = new java.util.ArrayList<>();
        for (int i = 0; i < 12 && cause != null && !chain.contains(cause); i++, cause = cause.getCause()) chain.add(cause);
        cause = chain.getLast();
        String help;
        // JMX wrappers identify an exception returned by the MBean itself. Its nested cause may
        // look like an input, permission, TLS or transport failure, but that is the target's
        // execution stage, not evidence that client validation or the connection failed.
        if (chain.stream().anyMatch(e -> e instanceof javax.management.MBeanException
                || e instanceof javax.management.RuntimeMBeanException || e instanceof javax.management.RuntimeErrorException))
            help = "[TARGET] The MBean returned an exception. Check its constraints and server logs. The request may have had partial effects; it will not be retried automatically.";
        else if (chain.stream().anyMatch(e -> e instanceof dev.jvmbeacon.core.JfrCapture.TransferLimitException))
            help = "[LIMIT] The JFR stream was empty, returned an invalid block, or exceeded 64 MiB. No completed file was saved. The recording is retained; use a shorter capture or another analysis tool.";
        else if (chain.stream().anyMatch(e -> e instanceof java.nio.file.FileSystemException))
            help = "[FILE] Local file access failed. Choose a writable directory and a new file name. JFR downloads never replace existing files; the connection is retained.";
        else if (lane == Lane.LOCAL_IO && chain.stream().anyMatch(e -> e instanceof SecurityException))
            help = "[PERMISSION] Local file or credential access was denied. Check user permissions and unlock the credential store if needed.";
        else if (lane == Lane.LOCAL_IO && chain.stream().anyMatch(e -> e instanceof java.io.IOException))
            help = "[IO] Local file or credential I/O failed. Check the path, permissions, file format and storage availability.";
        else if (chain.stream().anyMatch(e -> e instanceof BeaconExecutors.ConnectionCapacityException))
            help = "[CAPACITY] The 16-connection limit is reached (including opening and closing connections), or the plugin is closing. Wait for cleanup. Restart the IDE if underlying calls remain unresponsive.";
        else if (chain.stream().anyMatch(e -> e instanceof dev.jvmbeacon.core.JmxClient.ConnectionException connection
                && "Management agent".equals(connection.stage())))
            help = "[AGENT] This JVM has no local management address yet. Allow the local management agent in the connection dialog before retrying. Starting it changes the target process state.";
        else if (chain.stream().anyMatch(e -> e instanceof SecurityException || e instanceof javax.security.auth.login.LoginException))
            help = "[PERMISSION] Authentication or server authorization was denied. Check credentials, roles and allowed operations. Client read-only mode does not replace server authorization.";
        else if (chain.stream().anyMatch(e -> e instanceof javax.net.ssl.SSLException || e instanceof java.security.cert.CertificateException))
            help = "[TLS] The TLS handshake failed. Check registry TLS settings, server certificates and the IDEA JBR trust store. Do not disable verification to bypass this failure.";
        else if (chain.stream().anyMatch(e -> e instanceof SocketTimeoutException))
            help = "[TIMEOUT] The network read timed out. A mutation may have executed. Check target state; the request will not be retried automatically.";
        else if (chain.stream().anyMatch(e -> e instanceof ConnectException || e instanceof java.rmi.ConnectException
                || e instanceof java.rmi.ConnectIOException || e instanceof java.net.UnknownHostException))
            help = "[CONNECTION] JMX/RMI is unreachable. For a local JVM, check that the process is still running and the PID matches; a restarted fixture has a new PID. For remote JMX, check registry/server ports, advertised host, DNS and routing.";
        else if (chain.stream().anyMatch(e -> e instanceof com.sun.tools.attach.AttachNotSupportedException || e instanceof com.sun.tools.attach.AttachOperationFailedException
                || e instanceof dev.jvmbeacon.core.JmxClient.ConnectionException connection && "Attach".equals(connection.stage())))
            help = "[ATTACH] Attach failed. Check the PID, OS user, whether the target disables Attach, and container or permission boundaries.";
        else if (chain.stream().anyMatch(e -> e instanceof dev.jvmbeacon.core.JmxClient.ConnectionException connection && "Configuration".equals(connection.stage())))
            help = "[INPUT] Check the full service:jmx:rmi: URL. A username is required when supplying a password.";
        else if (cause instanceof java.io.IOException)
            help = "[IO] Read or connection failed. Check whether the target exited, the file is readable and the JMX/RMI endpoint is reachable.";
        else if (cause instanceof IllegalArgumentException)
            help = "[INPUT] Invalid parameter, type or capture format. Check the input guidance. Arbitrary Java object conversion is not supported.";
        else help = "[TARGET] The target rejected or could not complete this request. Check MBean capabilities and server logs.";
        // Throwable messages can contain passwords, arbitrary server data and complete URLs.
        return label + ": " + help + " (" + cause.getClass().getSimpleName() + ")";
    }
}
