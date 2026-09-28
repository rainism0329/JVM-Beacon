package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TimelineStoreTest {
    @TempDir Path directory;
    private SnapshotStore.Snapshot capture(List<JmxClient.Sample> samples) {
        return new SnapshotStore.Snapshot(new JmxClient.Identity("owned fixture", 1, "VM", "21"), samples.getLast(), null, "Selected interval", samples);
    }
    @Test void fullRetentionRoundTripsWindowsUnitsMissingValuesAndAcquisitionOrder() throws Exception {
        var samples = new ArrayList<JmxClient.Sample>();
        for (int i = 0; i < 120; i++) samples.add(CaptureTimelineTest.sample(i * 2000 + 100, "heap.used", i == 10 ? null : 100L + i, "bytes"));
        // Preserve a backward clock step explicitly, do not sort it into a plausible continuous line.
        samples.set(50, CaptureTimelineTest.sample(8000, "heap.used", 150L, "bytes"));
        Path file = directory.resolve("history.jvmb"); SnapshotStore.save(file, capture(samples));
        var loaded = SnapshotStore.load(file);
        assertEquals(120, loaded.history().size()); assertEquals(8000, loaded.history().get(50).captureEnd());
        assertEquals("Permission denied", loaded.history().get(10).metrics().getFirst().error());
        assertEquals("bytes", loaded.history().getFirst().metrics().getFirst().unit());
        assertEquals(loaded.sample(), loaded.history().getLast()); assertEquals(90, loaded.captureStart());
        assertFalse(new CaptureTimeline(loaded.history()).orderedWindows()); assertNull(loaded.threads());
    }
    @Test void legacySnapshotsContainOnlyTheirActuallyCapturedSample() throws Exception {
        Path file = directory.resolve("legacy.jvmb"); SnapshotStore.save(file, capture(List.of(CaptureTimelineTest.sample(100, "heap.used", 100, "bytes"))));
        String modern = Files.readString(file);
        for (String version : List.of("1", "2")) {
            Files.writeString(file, modern.replace("format.version=3", "format.version=" + version).replaceAll("(?m)^history[^\\r\\n]*\\r?\\n", ""));
            assertEquals(1, SnapshotStore.load(file).history().size());
        }
    }
    @Test void corruptHistoryFailsWithoutReplacingExistingEvidence() throws Exception {
        Path file = directory.resolve("bounds.jvmb");
        var s = CaptureTimelineTest.sample(100, "heap.used", 100, "bytes");
        SnapshotStore.save(file, capture(List.of(s, s)));
        String valid = Files.readString(file);
        for (String invalid : List.of(valid.replace("history.count=1", "history.count=120"),
                valid.replace("history.0.end=100", "history.0.end=1"),
                valid.replace("history.0.0.value=100", "history.0.0.value=1e2147483647"),
                valid + "\nhistory.count=1\n")) {
            Files.writeString(file, invalid); assertThrows(IOException.class, () -> SnapshotStore.load(file));
        }
        Files.writeString(file, valid);
        var excessive = new ArrayList<JmxClient.Sample>(); for (int i = 0; i < 121; i++) excessive.add(s);
        assertThrows(IllegalArgumentException.class, () -> capture(excessive));
        var bad = CaptureTimelineTest.sample(100, "heap.used", Double.NaN, "bytes");
        assertThrows(IOException.class, () -> SnapshotStore.save(file, capture(List.of(bad))));
        assertEquals(valid, Files.readString(file));
    }
}
