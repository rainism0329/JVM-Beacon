package dev.jvmbeacon.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** App-wide limits stay in force across reconnects, projects and timed-out requests. */
@Service(Service.Level.APP)
public final class BeaconExecutors implements Disposable {
    static final int MAX_CONNECTIONS = 16;
    final ThreadPoolExecutor calls = pool(4, "jvm-beacon-call");
    // Offline captures and PasswordSafe remain available when remote calls cannot be interrupted.
    final ThreadPoolExecutor localCalls = pool(2, "jvm-beacon-local");
    final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_CONNECTIONS), factory("jvm-beacon-close"), new ThreadPoolExecutor.AbortPolicy());
    final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, factory("jvm-beacon-deadline"));
    private int connections;
    private boolean disposed;

    public BeaconExecutors() {
        cleanup.allowCoreThreadTimeOut(true);
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    static BeaconExecutors getInstance() {
        return ApplicationManager.getApplication().getService(BeaconExecutors.class);
    }

    private static ThreadPoolExecutor pool(int max, String name) {
        return new ThreadPoolExecutor(0, max, 20, TimeUnit.SECONDS,
                new SynchronousQueue<>(), factory(name), new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory factory(String prefix) {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Implementations only schedule their reserved cleanup; close itself must never block the EDT. */
    interface ManagedConnection extends AutoCloseable {
        @Override void close();
    }

    static final class ConnectionCapacityException extends RejectedExecutionException { }

    /** Called inside the worker, before attempting Attach or JMX. No waiting or unbounded admission. */
    synchronized ConnectionLease reserveConnection() {
        if (disposed || connections == MAX_CONNECTIONS) throw new ConnectionCapacityException();
        connections++;
        return new ConnectionLease(this);
    }

    synchronized int availableConnections() { return MAX_CONNECTIONS - connections; }

    private synchronized void connectionClosed() {
        connections--;
        if (disposed && connections == 0) cleanup.shutdown();
    }

    /** A permit covers connecting, active and closing states, including results returned after cancellation. */
    static final class ConnectionLease implements ManagedConnection {
        private final BeaconExecutors owner;
        private AutoCloseable resource;
        private boolean closing;

        private ConnectionLease(BeaconExecutors owner) { this.owner = owner; }

        synchronized void attach(AutoCloseable connection) {
            if (closing || resource != null) throw new IllegalStateException("Connection lease is already used.");
            resource = java.util.Objects.requireNonNull(connection);
        }

        @Override public void close() {
            AutoCloseable connection;
            synchronized (this) {
                if (closing) return;
                closing = true; // Claim before enqueueing: repeated UI/error/late-result paths consume no extra slot.
                connection = resource;
                resource = null;
            }
            if (connection == null) { owner.connectionClosed(); return; }
            // There are at most 16 outstanding leases and each schedules once. The 16-place queue
            // therefore has room even when both cleanup workers are stuck. It stays open until
            // every lease has returned, so disposal cannot reject a late connection's cleanup.
            owner.cleanup.execute(() -> {
                try { connection.close(); }
                catch (Exception ignored) { /* Never log target content or credentials. */ }
                finally { owner.connectionClosed(); }
            });
        }
    }

    @Override public synchronized void dispose() {
        disposed = true;
        calls.shutdownNow();
        localCalls.shutdownNow();
        deadlines.shutdownNow();
        // Never discard queued closes, wait on EDT, or reject cleanup from an already admitted worker.
        // An uninterruptible RMI call/close can retain this bounded service after dynamic unload;
        // only exiting the IDE guarantees termination of those underlying daemon threads.
        if (connections == 0) cleanup.shutdown();
    }
}
