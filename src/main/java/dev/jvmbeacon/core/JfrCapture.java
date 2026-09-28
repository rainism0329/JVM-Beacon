package dev.jvmbeacon.core;

import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;
import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One session-owned recording. Remote/file methods require a worker; cancel() is non-blocking. */
public final class JfrCapture implements AutoCloseable {
    public static final long RETENTION_BYTES = 32L * 1024 * 1024;
    public static final long DOWNLOAD_BYTES = 64L * 1024 * 1024;
    private final MBeanServerConnection server;
    private final FlightRecorderMXBean recorder;
    private final OutputFactory outputs;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private long ownedId;

    JfrCapture(MBeanServerConnection server) {
        this(server, Files::newOutputStream);
    }

    @FunctionalInterface interface OutputFactory { OutputStream open(Path path) throws IOException; }

    JfrCapture(MBeanServerConnection server, OutputFactory outputs) {
        this.server = server;
        this.outputs = outputs;
        recorder = JMX.newMXBeanProxy(server, objectName(), FlightRecorderMXBean.class);
    }

    private static ObjectName objectName() {
        try { return new ObjectName(FlightRecorderMXBean.MXBEAN_NAME); }
        catch (Exception impossible) { throw new ExceptionInInitializerError(impossible); }
    }

    public record State(boolean supported, List<String> configurations, long id, String name,
                        String state, long startTime, long stopTime, long durationSeconds, long bytes,
                        long checkedAt) {
        public State { configurations = List.copyOf(configurations); }
    }

    public synchronized State inspect() throws IOException {
        check();
        if (!server.isRegistered(objectName()))
            return new State(false, List.of(), 0, "", "UNAVAILABLE", 0, 0, 0, 0, System.currentTimeMillis());
        check();
        List<String> configurations = recorder.getConfigurations().stream().map(c -> c.getName())
                .filter(name -> name.equals("default") || name.equals("profile")).distinct().toList();
        check();
        if (ownedId != 0) {
            for (RecordingInfo info : recorder.getRecordings()) {
                if (info.getId() == ownedId) return new State(true, configurations, ownedId, info.getName(),
                        info.getState(), info.getStartTime(), info.getStopTime(), info.getDuration(), info.getSize(), System.currentTimeMillis());
            }
            ownedId = 0; // Explicit inspection confirmed that the session's recording no longer exists.
        }
        return new State(true, configurations, 0, "", "READY", 0, 0, 0, 0, System.currentTimeMillis());
    }

    public synchronized State start(String configuration, int seconds) throws IOException {
        check();
        if (seconds < 5 || seconds > 120 || !List.of("default", "profile").contains(configuration))
            throw new IllegalArgumentException("Use default/profile and a duration between 5 and 120 seconds.");
        if (ownedId != 0) throw new IllegalStateException("Release the previous recording before starting another.");
        // Retain the ID after failures: never blindly create a second recording.
        ownedId = recorder.newRecording();
        check();
        recorder.setRecordingOptions(ownedId, Map.of("name", "JVM Beacon " + UUID.randomUUID(),
                "disk", "true", "duration", seconds + " s", "maxAge", seconds + " s",
                "maxSize", Long.toString(RETENTION_BYTES), "dumpOnExit", "false"));
        check();
        recorder.setPredefinedConfiguration(ownedId, configuration);
        check();
        recorder.startRecording(ownedId);
        check();
        return inspect();
    }

    public synchronized State stop() throws IOException {
        check();
        if (ownedId == 0) throw new IllegalStateException("No session-owned recording.");
        State current = inspect();
        if ("RUNNING".equals(current.state())) { check(); recorder.stopRecording(ownedId); }
        return inspect();
    }

    public synchronized State release() throws IOException {
        check();
        if (ownedId != 0) { recorder.closeRecording(ownedId); ownedId = 0; }
        return inspect();
    }

    /** A new local file, never the remote copyTo/destination options; existing files are never replaced. */
    public synchronized Path download(Path destination) throws IOException {
        State current = inspect();
        if (current.id() == 0 || !"STOPPED".equals(current.state()))
            throw new IllegalStateException("Refresh and stop this session's recording before downloading.");
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = localFile(() -> {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(target.toString());
            return Files.createTempFile(target.getParent(), ".jvm-beacon-", ".partial");
        });
        long stream = -1;
        long end = System.nanoTime() + 45_000_000_000L;
        Throwable failure = null;
        try {
            check();
            stream = recorder.openStream(ownedId, Map.of("blockSize", "65536"));
            long total = 0;
            try (var output = localOutput(temporary)) {
                while (true) {
                    check();
                    if (System.nanoTime() >= end) throw new InterruptedIOException("JFR transfer exceeded 45 seconds.");
                    byte[] block = recorder.readStream(stream);
                    check();
                    if (block == null) break;
                    if (block.length > 65536 || block.length == 0 || total + block.length > DOWNLOAD_BYTES)
                        throw new TransferLimitException();
                    output.write(block); total += block.length;
                }
            }
            if (total == 0) throw new TransferLimitException();
            long closing = stream; stream = -1; recorder.closeStream(closing);
            check();
            localFile(() -> Files.move(temporary, target)); // No REPLACE_EXISTING, including at the final publication step.
            return target;
        } catch (IOException | RuntimeException | Error error) {
            failure = error; throw error;
        } finally {
            Throwable streamFailure = null;
            try { if (stream >= 0) recorder.closeStream(stream); }
            catch (IOException | RuntimeException | Error error) { streamFailure = error; throw error; }
            finally {
                try { localFile(() -> Files.deleteIfExists(temporary)); }
                catch (LocalFileException cleanup) {
                    if (streamFailure != null) streamFailure.addSuppressed(cleanup);
                    else if (failure != null) failure.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
    }

    /** Only locally executed file operations use this marker; target read/close failures stay transport errors. */
    public static final class LocalFileException extends IOException {
        public LocalFileException(Throwable cause) { super("Local JFR file access failed.", cause); }
    }
    @FunctionalInterface private interface LocalFileCall<T> { T call() throws IOException; }
    private static <T> T localFile(LocalFileCall<T> call) throws LocalFileException {
        try { return call.call(); }
        catch (IOException | SecurityException error) { throw new LocalFileException(error); }
    }
    private OutputStream localOutput(Path path) throws LocalFileException {
        return new FilterOutputStream(localFile(() -> outputs.open(path))) {
            @Override public void write(int value) throws IOException { localFile(() -> { out.write(value); return null; }); }
            @Override public void write(byte[] value, int offset, int length) throws IOException { localFile(() -> { out.write(value, offset, length); return null; }); }
            @Override public void flush() throws IOException { localFile(() -> { out.flush(); return null; }); }
            @Override public void close() throws IOException { localFile(() -> { out.close(); return null; }); }
        };
    }

    /** Cancels future steps before queued cleanup runs. A remote call may still be executing. */
    public void cancel() { cancelled.set(true); }

    public static final class TransferLimitException extends IOException { }

    private void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("JFR session was cancelled. Target-side outcome may be unknown.");
    }

    @Override public void close() throws IOException {
        cancel();
        synchronized (this) {
            if (ownedId != 0) {
                long id = ownedId; ownedId = 0;
                recorder.closeRecording(id);
            }
        }
    }
}
