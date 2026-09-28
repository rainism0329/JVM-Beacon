package dev.jvmbeacon.core;

import dev.jvmbeacon.core.JmxClient.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Versioned, size-limited plain data. No Java object deserialization, class loading, or embedded paths. */
public final class SnapshotStore {
    public static final int MAX_BYTES = 5 * 1_024 * 1_024;
    public static final String REDACTION_NOTICE = "Export includes JVM identity, metric values, thread names/locks/class and source-file names, and your notes. It excludes connection URLs, credentials, system properties, command-line arguments, arbitrary MBean values and notifications. Thread names and notes can contain sensitive text and are not automatically redacted; review before sharing.";

    public record Snapshot(Identity identity, Sample sample, ThreadDump threads, String notes, List<Sample> history) {
        public Snapshot {
            Objects.requireNonNull(identity, "identity"); notes = notes == null ? "" : notes;
            history = List.copyOf(history);
            if (history.size() > CaptureTimeline.LIMIT) throw new IllegalArgumentException("At most 120 metric samples can be saved.");
            if (sample == null ? !history.isEmpty() : history.isEmpty() || !sample.equals(history.getLast()))
                throw new IllegalArgumentException("The current sample must be the last captured history entry.");
        }
        public Snapshot(Identity identity, Sample sample, ThreadDump threads, String notes) {
            this(identity, sample, threads, notes, sample == null ? List.of() : List.of(sample));
        }
        public long captureStart() {
            long start = history.stream().mapToLong(Sample::captureStart).min().orElse(Long.MAX_VALUE);
            if (threads != null) start = Math.min(start, threads.captureStart());
            return start == Long.MAX_VALUE ? 0 : start;
        }
        public long captureEnd() {
            long end = history.stream().mapToLong(Sample::captureEnd).max().orElse(0);
            return threads == null ? end : Math.max(end, threads.captureEnd());
        }
        public String redactionNotice() { return REDACTION_NOTICE; }
    }

    private SnapshotStore() { }

    public static void save(Path path, Snapshot snapshot) throws IOException {
        Properties data = encode(snapshot);
        StringWriter text = new StringWriter();
        data.store(text, "JVM Beacon snapshot version 3; UTF-8; bounded observations, not continuous recording");
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Snapshot exceeds 5 MiB. Shorten notes or collect a smaller capture.");
        Path destination = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(destination)) throw new IOException("Refusing to replace a symbolic link with a snapshot.");
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Choose an existing snapshot directory.");
        Path temporary = Files.createTempFile(parent, ".jvm-beacon-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static Snapshot load(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Choose a regular snapshot file; symbolic links and directories are not accepted.");
        if (Files.size(path) > MAX_BYTES) throw new IOException("Snapshot exceeds the 5 MiB read limit.");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Snapshot exceeds the 5 MiB read limit.");
        Properties data = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate snapshot field.");
                return super.put(key, value);
            }
        };
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            data.load(reader);
            String version = required(data, "format.version", 16);
            if (!Set.of("1", "2", "3").contains(version)) throw new IOException("Unsupported snapshot version. Versions 1, 2 and 3 can be opened.");
            if (!"jvm-beacon".equals(required(data, "format.kind", 32))) throw new IOException("This file is not a JVM Beacon snapshot.");
            Identity identity = new Identity(required(data, "identity.runtime", 1_024), nonnegative(data, "identity.start"),
                    required(data, "identity.vm", 1_024), required(data, "identity.version", 1_024));
            Sample sample = bool(data, "sample.present") ? readSample(data, "sample") : null;
            List<Sample> history = new ArrayList<>();
            if (version.equals("3")) {
                int count = readCount(data, "history.count", CaptureTimeline.LIMIT - 1);
                if (sample == null && count != 0) throw new IOException("History requires a current metric sample.");
                for (int i = 0; i < count; i++) history.add(readSample(data, "history." + i));
            }
            if (sample != null) history.add(sample);
            ThreadDump threads = bool(data, "threads.present") ? readThreads(data, version) : null;
            return new Snapshot(identity, sample, threads, required(data, "notes", 32_768), history);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid snapshot: " + e.getMessage(), e); }
    }

    private static Properties encode(Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Properties data = new Properties();
        put(data, "format.version", "3", 16); put(data, "format.kind", "jvm-beacon", 32);
        put(data, "identity.runtime", snapshot.identity.runtimeName(), 1_024);
        number(data, "identity.start", snapshot.identity.startTime());
        put(data, "identity.vm", snapshot.identity.vmName(), 1_024);
        put(data, "identity.version", snapshot.identity.vmVersion(), 1_024);
        put(data, "notes", snapshot.notes, 32_768);
        put(data, "export.redaction", REDACTION_NOTICE, 2_048);
        put(data, "capture.kind", "Separate on-demand captures. No uncaptured history is recoverable.", 512);
        data.setProperty("sample.present", String.valueOf(snapshot.sample != null));
        data.setProperty("threads.present", String.valueOf(snapshot.threads != null));
        if (snapshot.sample == null) data.setProperty("sample.missing", "No metric sample was captured.");
        if (snapshot.threads == null) data.setProperty("threads.missing", "No thread capture was collected.");
        if (snapshot.sample != null) writeSample(data, "sample", snapshot.sample);
        int previous = Math.max(0, snapshot.history.size() - 1);
        count(data, "history.count", previous, CaptureTimeline.LIMIT - 1);
        for (int i = 0; i < previous; i++) writeSample(data, "history." + i, snapshot.history.get(i));
        if (snapshot.threads != null) {
            ThreadDump dump = snapshot.threads;
            window(data, "threads", dump.captureStart(), dump.captureEnd());
            count(data, "threads.count", dump.threads().size(), JmxClient.MAX_THREADS);
            put(data, "threads.coverage", dump.coverage(), 2_048);
            data.setProperty("threads.truncated", String.valueOf(dump.truncated()));
            put(data, "threads.deadlock.status", dump.deadlockStatus(), 2_048);
            count(data, "threads.deadlock.count", dump.deadlockedIds().size(), JmxClient.MAX_THREADS);
            for (int i = 0; i < dump.deadlockedIds().size(); i++) number(data, "threads.deadlock." + i, dump.deadlockedIds().get(i));
            for (int i = 0; i < dump.threads().size(); i++) {
                ThreadRecord thread = dump.threads().get(i);
                String prefix = "threads." + i + ".";
                number(data, prefix + "id", thread.id()); put(data, prefix + "name", thread.name(), 1_024); put(data, prefix + "state", thread.state(), 64);
                number(data, prefix + "blocked", thread.blockedCount()); number(data, prefix + "waited", thread.waitedCount());
                put(data, prefix + "lock", thread.lockName(), 1_024); put(data, prefix + "owner", thread.lockOwnerName(), 1_024);
                Long ownerId = thread.lockOwnerId();
                if (ownerId != null && (ownerId == 0 || ownerId < -1)) throw new IOException("Invalid lock owner ID.");
                data.setProperty(prefix + "ownerId.present", String.valueOf(ownerId != null));
                if (ownerId != null) data.setProperty(prefix + "ownerId", ownerId.toString());
                count(data, prefix + "frames", thread.frames().size(), JmxClient.MAX_STACK_DEPTH);
                for (int j = 0; j < thread.frames().size(); j++) {
                    StackTraceElement frame = thread.frames().get(j);
                    String stack = prefix + "frame." + j + ".";
                    put(data, stack + "class", frame.getClassName(), 2_048); put(data, stack + "method", frame.getMethodName(), 1_024);
                    put(data, stack + "file", frame.getFileName(), 1_024); data.setProperty(stack + "line", String.valueOf(frame.getLineNumber()));
                }
            }
        }
        return data;
    }

    private static void writeSample(Properties data, String base, Sample sample) throws IOException {
        window(data, base, sample.captureStart(), sample.captureEnd());
        put(data, base + ".source", sample.source(), 1_024);
        count(data, base + ".count", sample.metrics().size(), 64);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < sample.metrics().size(); i++) {
            Metric metric = sample.metrics().get(i);
            if (!keys.add(metric.key())) throw new IOException("Duplicate metric key.");
            String prefix = base + "." + i + ".";
            put(data, prefix + "key", metric.key(), 128); put(data, prefix + "label", metric.label(), 512); put(data, prefix + "unit", metric.unit(), 64);
            data.setProperty(prefix + "present", String.valueOf(metric.available()));
            if (metric.available()) {
                if (!Double.isFinite(metric.value().doubleValue())) throw new IOException("A metric contains a non-finite value.");
                checkedDecimal(metric.value().toString());
                put(data, prefix + "value", metric.value().toString(), 128);
            } else put(data, prefix + "error", metric.error() == null ? "Unavailable; no value was captured." : metric.error(), 2_048);
        }
    }

    private static Sample readSample(Properties data, String base) throws IOException {
        long[] window = readWindow(data, base);
        int count = readCount(data, base + ".count", 64);
        List<Metric> metrics = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String prefix = base + "." + i + ".";
            String key = required(data, prefix + "key", 128);
            if (!keys.add(key)) throw new IOException("Duplicate metric key.");
            String label = required(data, prefix + "label", 512), unit = required(data, prefix + "unit", 64);
            Number value = null; String error = null;
            if (bool(data, prefix + "present")) {
                String text = required(data, prefix + "value", 128);
                BigDecimal decimal = checkedDecimal(text);
                value = decimal;
            } else error = required(data, prefix + "error", 2_048);
            metrics.add(new Metric(key, label, value, unit, error));
        }
        return new Sample(window[0], window[1], metrics);
    }

    private static ThreadDump readThreads(Properties data, String version) throws IOException {
        long[] window = readWindow(data, "threads");
        int count = readCount(data, "threads.count", JmxClient.MAX_THREADS);
        List<ThreadRecord> threads = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String prefix = "threads." + i + ".";
            long id = nonnegative(data, prefix + "id");
            if (id == 0 || !ids.add(id)) throw new IOException("Invalid or duplicate thread ID.");
            String name = required(data, prefix + "name", 1_024), state = required(data, prefix + "state", 64);
            Thread.State.valueOf(state);
            List<StackTraceElement> frames = new ArrayList<>();
            int depth = readCount(data, prefix + "frames", JmxClient.MAX_STACK_DEPTH);
            for (int j = 0; j < depth; j++) {
                String stack = prefix + "frame." + j + ".";
                String file = required(data, stack + "file", 1_024);
                int line = Integer.parseInt(required(data, stack + "line", 16));
                if (line < -2) throw new IOException("Invalid stack frame line number.");
                frames.add(new StackTraceElement(required(data, stack + "class", 2_048), required(data, stack + "method", 1_024), file.isEmpty() ? null : file, line));
            }
            Long ownerId = null;
            if (!version.equals("1") && bool(data, prefix + "ownerId.present")) {
                ownerId = Long.parseLong(required(data, prefix + "ownerId", 24));
                if (ownerId == 0 || ownerId < -1) throw new IOException("Invalid lock owner ID.");
            }
            threads.add(new ThreadRecord(id, name, state, nonnegative(data, prefix + "blocked"), nonnegative(data, prefix + "waited"),
                    required(data, prefix + "lock", 1_024), required(data, prefix + "owner", 1_024), frames, ownerId));
        }
        List<Long> deadlocked = new ArrayList<>();
        int deadlockCount = readCount(data, "threads.deadlock.count", JmxClient.MAX_THREADS);
        for (int i = 0; i < deadlockCount; i++) deadlocked.add(nonnegative(data, "threads.deadlock." + i));
        return new ThreadDump(window[0], window[1], threads, bool(data, "threads.truncated"), required(data, "threads.coverage", 2_048),
                deadlocked, required(data, "threads.deadlock.status", 2_048));
    }

    public static String compare(Snapshot before, Snapshot after) {
        boolean sameRuntime = before.identity.runtimeName().equals(after.identity.runtimeName());
        boolean sameInstance = before.identity.equals(after.identity);
        StringBuilder result = new StringBuilder();
        if (sameInstance) result.append("Same observed runtime name and JVM start time. This is a best-effort identity match.\n");
        else if (sameRuntime && before.identity.startTime() != after.identity.startTime()) result.append("Same runtime label but different JVM start times: restart or PID reuse. Thread IDs and cumulative deltas are not comparable.\n");
        else if (sameRuntime) result.append("Same runtime label but different VM metadata: target identity is uncertain. Thread IDs and cumulative deltas are not comparable.\n");
        else result.append("Different runtime labels: captures may be from different targets. Thread IDs and cumulative deltas are not compared.\n");
        result.append("Before capture: ").append(before.captureStart()).append(" – ").append(before.captureEnd()).append(" epoch ms\n");
        result.append("After capture: ").append(after.captureStart()).append(" – ").append(after.captureEnd()).append(" epoch ms\n");
        result.append("Metric comparison uses each capture's last acquired sample. Inspect earlier observations in Timeline.\n");
        if (before.sample == null || after.sample == null) result.append("Metrics: missing in at least one snapshot.\n");
        else {
            Map<String, Metric> baseline = before.sample.metrics().stream().collect(Collectors.toMap(Metric::key, metric -> metric));
            for (Metric current : after.sample.metrics()) {
                Metric previous = baseline.get(current.key());
                result.append(current.label()).append(": ");
                if (previous == null || !previous.available() || !current.available() || !previous.unit().equals(current.unit())) result.append("not comparable (missing, unavailable or different units)");
                else {
                    result.append(previous.value()).append(" → ").append(current.value()).append(' ').append(current.unit());
                    if (sameInstance) result.append("; Δ ").append(new BigDecimal(current.value().toString()).subtract(new BigDecimal(previous.value().toString())).stripTrailingZeros().toString());
                }
                result.append('\n');
            }
        }
        result.append(ThreadComparison.describeComparison(before, after));
        result.append("Captures are separate observations, not continuous history or proof of a root cause.");
        return result.toString();
    }

    private static void put(Properties data, String key, String value, int maximum) throws IOException {
        String safe = value == null ? "" : value;
        if (safe.length() > maximum) throw new IOException("Snapshot field exceeds its limit: " + key);
        data.setProperty(key, safe);
    }
    private static BigDecimal checkedDecimal(String value) throws IOException {
        if (value.length() > 128) throw new IOException("Metric numeric text exceeds 128 characters.");
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.scale() < -100 || decimal.scale() > 100 || decimal.precision() > 100 || !Double.isFinite(decimal.doubleValue()))
            throw new IOException("Metric exceeds the supported precision or exponent range.");
        return decimal;
    }
    private static void number(Properties data, String key, long value) throws IOException {
        if (value < 0) throw new IOException("Negative snapshot field: " + key);
        data.setProperty(key, Long.toString(value));
    }
    private static void count(Properties data, String key, int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Snapshot collection exceeds its limit: " + key);
        data.setProperty(key, Integer.toString(value));
    }
    private static String required(Properties data, String key, int maximum) throws IOException {
        String value = data.getProperty(key);
        if (value == null || value.length() > maximum) throw new IOException("Missing or oversized snapshot field: " + key);
        return value;
    }
    private static boolean bool(Properties data, String key) throws IOException {
        String value = required(data, key, 5);
        if (!value.equals("true") && !value.equals("false")) throw new IOException("Invalid boolean snapshot field: " + key);
        return Boolean.parseBoolean(value);
    }
    private static long nonnegative(Properties data, String key) throws IOException {
        long value = Long.parseLong(required(data, key, 20));
        if (value < 0) throw new IOException("Negative snapshot field: " + key);
        return value;
    }
    private static int readCount(Properties data, String key, int maximum) throws IOException {
        long value = nonnegative(data, key);
        if (value > maximum) throw new IOException("Snapshot collection exceeds its limit: " + key);
        return (int) value;
    }
    private static void window(Properties data, String prefix, long start, long end) throws IOException {
        if (end < start) throw new IOException("Capture ends before it begins.");
        number(data, prefix + ".start", start); number(data, prefix + ".end", end);
    }
    private static long[] readWindow(Properties data, String prefix) throws IOException {
        long start = nonnegative(data, prefix + ".start"), end = nonnegative(data, prefix + ".end");
        if (end < start) throw new IOException("Capture ends before it begins.");
        return new long[]{start, end};
    }
}
