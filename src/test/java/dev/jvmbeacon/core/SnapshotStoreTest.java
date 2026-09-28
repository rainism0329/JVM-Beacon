package dev.jvmbeacon.core;

import dev.jvmbeacon.core.JmxClient.*;
import dev.jvmbeacon.core.SnapshotStore.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreTest {
    @TempDir Path directory;
    private Snapshot capture() {
        return new Snapshot(new Identity("123@fixture", 100, "VM", "21"),
                new Sample(200, 210, List.of(new Metric("heap.used", "Heap used", 1024L, "bytes", null), new Metric("cpu.process", "CPU", null, "%", "unsupported"))),
                new ThreadDump(205, 220, List.of(new ThreadRecord(8,"等待线程", "WAITING", 0, 4, "lock", "owner", List.of(new StackTraceElement("dev.Test", "await", "Test.java", 42)))),
                        false, JmxClient.THREAD_COVERAGE, List.of(), "No cycle reported"), "中文备注");
    }
    @Test void savesAndReopensReadableVersionedEvidence() throws Exception {
        Path file = directory.resolve("sample.beacon");
        SnapshotStore.save(file, capture());
        Snapshot loaded = SnapshotStore.load(file);
        assertEquals(capture().identity(), loaded.identity());
        assertEquals("中文备注", loaded.notes());
        assertEquals(42, loaded.threads().threads().getFirst().frames().getFirst().getLineNumber());
        assertNull(loaded.sample().metrics().get(1).value());
        assertTrue(Files.readString(file).contains("format.version=3"));
        assertEquals(200, loaded.captureStart()); assertEquals(220, loaded.captureEnd());
    }
    @Test void missingCapturesRemainMissing() throws Exception {
        Path file = directory.resolve("missing.beacon");
        SnapshotStore.save(file, new Snapshot(capture().identity(), null, null, "notes only"));
        Snapshot loaded = SnapshotStore.load(file);
        assertNull(loaded.sample()); assertNull(loaded.threads());
        assertTrue(Files.readString(file).contains("sample.missing"));
    }
    @Test void ownerIdsRoundTripAndVersionOneRemainsExplicitlyUnknown() throws Exception {
        Path file = directory.resolve("owners.jvmb");
        Snapshot original = new Snapshot(capture().identity(), null, LockChainsTest.dump(List.of(
                LockChainsTest.thread(1, 2L), LockChainsTest.thread(2, -1L), LockChainsTest.thread(3, null))), "locks");
        SnapshotStore.save(file, original);
        assertEquals(original, SnapshotStore.load(file));
        String versionTwo = Files.readString(file).replace("format.version=3", "format.version=2");
        Files.writeString(file, versionTwo);
        assertEquals(original, SnapshotStore.load(file));
        Files.writeString(file, versionTwo.replace("format.version=2", "format.version=1").replaceAll("(?m)^threads\\.[0-9]+\\.ownerId[^\\r\\n]*\\r?\\n", ""));
        var legacy = SnapshotStore.load(file);
        assertTrue(legacy.threads().threads().stream().allMatch(t -> t.lockOwnerId() == null));
        assertEquals(1, LockChains.from(legacy.threads()).path(1).threads().size());
        SnapshotStore.save(file, legacy);
        assertEquals(legacy, SnapshotStore.load(file));
        for (String invalid : List.of("0", "-2", "9223372036854775808")) {
            Files.writeString(file, versionTwo.replace("threads.0.ownerId=2", "threads.0.ownerId=" + invalid));
            assertThrows(IOException.class, () -> SnapshotStore.load(file));
        }
        Files.writeString(file, versionTwo.replace("threads.0.ownerId.present=true", "threads.0.ownerId.present=missing"));
        assertThrows(IOException.class, () -> SnapshotStore.load(file));
    }
    @Test void rejectsUnknownVersionDuplicatesCountsAndOversizedFiles() throws Exception {
        Path file = directory.resolve("bad.beacon"); SnapshotStore.save(file, capture());
        String valid = Files.readString(file);
        Files.writeString(file, valid.replace("format.version=3", "format.version=999"));
        assertThrows(IOException.class, () -> SnapshotStore.load(file));
        Files.writeString(file, valid + "\nformat.version=1\n");
        assertThrows(IOException.class, () -> SnapshotStore.load(file));
        Files.writeString(file, valid.replace("threads.count=1", "threads.count=999999999"));
        assertThrows(IOException.class, () -> SnapshotStore.load(file));
        Files.write(file, new byte[SnapshotStore.MAX_BYTES + 1]);
        assertThrows(IOException.class, () -> SnapshotStore.load(file));
    }
    @Test void rejectsHostileDecimalScalesBeforeComparison() throws Exception {
        Path file = directory.resolve("number.beacon"); SnapshotStore.save(file, capture());
        Files.writeString(file, Files.readString(file).replace("sample.0.value=1024", "sample.0.value=1e-2147483647"));
        assertThrows(IOException.class, () -> SnapshotStore.load(file));
    }
    @Test void comparisonSeparatesRestartsAndPartialCoverage() {
        Snapshot first = capture();
        Snapshot restarted = new Snapshot(new Identity("123@fixture", 999, "VM", "21"), first.sample(), first.threads(), "");
        assertTrue(SnapshotStore.compare(first, restarted).contains("restart or PID reuse"));
        assertFalse(SnapshotStore.compare(first, restarted).contains("; Δ"));
        assertTrue(SnapshotStore.compare(first, first).contains("Captured scope"));
        assertTrue(SnapshotStore.compare(first, first).contains("not continuous history"));
    }

    @Test void incompleteIdentityNeverEnablesMetricDeltas() {
        Snapshot first = metricCapture(capture().identity(), 100, 110, "heap.used", 10L, "bytes");
        Snapshot second = metricCapture(capture().identity(), 200, 210, "heap.used", 20L, "bytes");
        for (Identity incomplete : List.of(new Identity("fixture", 0, "VM", "21"), new Identity("", 1, "VM", "21"),
                new Identity("fixture", 1, "—", "21"), new Identity("fixture", 1, "VM", ""))) {
            Snapshot a = new Snapshot(incomplete, first.sample(), null, ""), b = new Snapshot(incomplete, second.sample(), null, "");
            for (String report : List.of(SnapshotStore.compare(a, b), SnapshotStore.compare(a, second), SnapshotStore.compare(first, b))) {
                assertTrue(report.contains("identity is missing or incomplete"));
                assertFalse(report.contains("; Δ"));
            }
        }
    }

    @Test void metricDeltasRequireOrderedNonOverlappingWindows() {
        Snapshot first = metricCapture(capture().identity(), 100, 110, "heap.used", 10L, "bytes");
        for (long[] window : List.of(new long[]{50, 60}, new long[]{100, 110}, new long[]{105, 115}, new long[]{200, 190})) {
            String report = SnapshotStore.compare(first, metricCapture(first.identity(), window[0], window[1], "heap.used", 20L, "bytes"));
            assertFalse(report.contains("; Δ"));
            assertTrue(report.contains("reversed or overlapping capture windows"));
        }
        // Adjacent windows are non-overlapping and retain exact integer arithmetic above 2^53.
        String exact = SnapshotStore.compare(metricCapture(first.identity(), 100, 110, "heap.used", 9007199254740992L, "bytes"),
                metricCapture(first.identity(), 110, 120, "heap.used", 9007199254740993L, "bytes"));
        assertTrue(exact.contains("; Δ 1\n"));
    }

    @Test void cumulativeMetricDecreasesAreNotPresentedAsValidChanges() {
        for (String key : List.of("gc.time", "gc.count", "cpu.time", "runtime.uptime")) {
            Snapshot first = metricCapture(capture().identity(), 100, 110, key, 1000L, "unit");
            String reset = SnapshotStore.compare(first, metricCapture(first.identity(), 200, 210, key, 10L, "unit"));
            assertFalse(reset.contains("; Δ"));
            assertTrue(reset.contains("counter decreased"));
            String increasing = SnapshotStore.compare(first, metricCapture(first.identity(), 200, 210, key, 1001L, "unit"));
            assertTrue(increasing.contains("; Δ 1\n"));
            assertTrue(increasing.contains("cannot detect intervening resets"));
        }
        String gauge = SnapshotStore.compare(metricCapture(capture().identity(), 100, 110, "heap.used", 1000L, "bytes"),
                metricCapture(capture().identity(), 200, 210, "heap.used", 990L, "bytes"));
        assertTrue(gauge.contains("; Δ -1E+1\n"));
    }

    @Test void unavailableOrChangedUnitMetricsDoNotAcquireDeltas() {
        Snapshot first = metricCapture(capture().identity(), 100, 110, "heap.used", 10L, "bytes");
        for (Snapshot second : List.of(metricCapture(first.identity(), 200, 210, "heap.used", null, "bytes"),
                metricCapture(first.identity(), 200, 210, "heap.used", 20L, "MiB"),
                metricCapture(first.identity(), 200, 210, "heap.used", Double.NaN, "bytes"))) {
            String report = SnapshotStore.compare(first, second);
            assertFalse(report.contains("; Δ"));
            assertTrue(report.contains("unavailable") || report.contains("not comparable"));
        }
    }

    private Snapshot metricCapture(Identity identity, long start, long end, String key, Number value, String unit) {
        return new Snapshot(identity, new Sample(start, end, List.of(new Metric(key, key, value, unit, value == null ? "Not captured" : null))), null, "");
    }
}
