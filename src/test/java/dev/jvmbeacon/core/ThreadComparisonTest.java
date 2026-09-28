package dev.jvmbeacon.core;

import dev.jvmbeacon.core.JmxClient.Identity;
import dev.jvmbeacon.core.JmxClient.ThreadDump;
import dev.jvmbeacon.core.JmxClient.ThreadRecord;
import dev.jvmbeacon.core.ThreadComparison.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ThreadComparisonTest {
    private static final Identity IDENTITY = new Identity("123@fixture", 1_000, "Fixture VM", "21.0.9");
    private static final StackTraceElement TOP = frame("await", 12);

    @Test void reportsAddedAbsentAndIndependentChangesInStableIdOrder() {
        ThreadRecord unchanged = thread(2, "worker-2", "WAITING", "lock", "owner", List.of(TOP));
        ThreadDump before = dump(2_000, 2_010, false, List.of(thread(3), unchanged,
                thread(1, "old-name", "WAITING", "lock-A", "owner-A", List.of(TOP, frame("oldCall", 20)))));
        ThreadDump after = dump(3_000, 3_010, false, List.of(thread(4),
                thread(1, "new-name", "BLOCKED", "lock-B", "owner-B", List.of(TOP, frame("newCall", 21))), unchanged));

        Report report = ThreadComparison.compare(IDENTITY, before, IDENTITY, after);
        assertEquals(Status.COMPARABLE, report.status()); assertTrue(report.chronological());
        assertEquals(new Counts(1, 1, 2, 1, 1, 1, 1, 1), report.counts());
        assertEquals(List.of(1L, 3L, 4L), report.differences().stream().map(Difference::id).toList());
        Difference changed = report.differences().getFirst();
        assertEquals(Kind.CHANGED, changed.kind());
        assertEquals(Set.of(Field.NAME, Field.STATE, Field.STACK, Field.LOCK), changed.fields());
        assertEquals(2, changed.frameChange().depth());
        assertTrue(changed.frameChange().before().contains("oldCall"));
        assertTrue(changed.frameChange().after().contains("newCall"));
        assertEquals(Kind.NO_LONGER_OBSERVED, report.differences().get(1).kind());
        assertEquals(Kind.NEWLY_OBSERVED, report.differences().get(2).kind());
        assertEquals(new Window(2_000, 2_010, 3, false), report.before());
        assertThrows(UnsupportedOperationException.class, () -> report.differences().clear());
        assertThrows(UnsupportedOperationException.class, () -> changed.fields().clear());
    }

    @Test void neverMatchesIdsAcrossRestartOtherRuntimeOrDifferentVmMetadata() {
        List<Identity> alternatives = List.of(new Identity("123@fixture", 9_000, "Fixture VM", "21.0.9"),
                new Identity("124@fixture", 1_000, "Fixture VM", "21.0.9"),
                new Identity("123@fixture", 1_000, "Other VM", "21.0.9"),
                new Identity("123@fixture", 1_000, "Fixture VM", "25"));
        for (Identity alternative : alternatives) {
            Report report = ThreadComparison.compare(IDENTITY, sample(), alternative, sample());
            assertEquals(Status.DIFFERENT_JVM, report.status()); assertNull(report.counts());
            assertTrue(report.differences().isEmpty());
            assertTrue(ThreadComparison.describe(report).contains("cannot be determined"));
        }
    }

    @Test void incompleteIdentityAndMissingCaptureAreNotZeroChangeResults() {
        List<Identity> incomplete = Arrays.asList(null, new Identity("", 1_000, "VM", "21"),
                new Identity("123@fixture", 0, "VM", "21"), new Identity("123@fixture", 1_000, null, "21"));
        for (Identity identity : incomplete) {
            Report report = ThreadComparison.compare(identity, sample(), IDENTITY, sample());
            assertEquals(Status.MISSING_IDENTITY, report.status()); assertNull(report.counts());
        }
        Report missing = ThreadComparison.compare(IDENTITY, null, IDENTITY, sample());
        assertEquals(Status.MISSING_CAPTURE, missing.status()); assertNull(missing.before()); assertNull(missing.counts());
        assertTrue(ThreadComparison.describe(missing).contains("not captured does not mean zero threads"));
        assertTrue(ThreadComparison.describeComparison(null, null).contains("identity is missing"));
        Report empty = ThreadComparison.compare(IDENTITY, dump(2_000, 2_001, false, List.of()), IDENTITY, dump(3_000, 3_001, false, List.of()));
        assertTrue(empty.comparable()); assertEquals(new Counts(0, 0, 0, 0, 0, 0, 0, 0), empty.counts());
    }

    @Test void reusedOrRenamedIdIsOnlyACandidateAndUnchangedStackIsNotADiagnosis() {
        ThreadRecord sameIdRenamed = thread(1, "renamed", "WAITING", "", "", List.of(TOP));
        Report report = ThreadComparison.compare(IDENTITY, sample(), IDENTITY, dump(3_000, 3_010, false, List.of(sameIdRenamed)));
        assertEquals(1, report.counts().matchedIds()); assertEquals(1, report.counts().nameChanges());
        assertEquals(0, report.counts().newlyObserved()); assertEquals(0, report.counts().stackChanges());
        String text = ThreadComparison.describe(report);
        assertTrue(text.contains("ID reuse")); assertTrue(text.contains("Identity continuity is unconfirmed"));
        assertTrue(text.contains("Identical states or stacks do not prove continuous blocking or deadlock"));
        assertTrue(text.contains("virtual threads are not covered"));
    }

    @Test void placeholderIdentityHasTheSameUnavailableMeaningForMetricsAndThreads() {
        var metricA = new JmxClient.Sample(2_000, 2_010, List.of(new JmxClient.Metric("heap.used", "Heap used", 10L, "bytes", null)));
        var metricB = new JmxClient.Sample(3_000, 3_010, List.of(new JmxClient.Metric("heap.used", "Heap used", 20L, "bytes", null)));
        for (Identity placeholder : List.of(new Identity("—", 1_000, "VM", "21"),
                new Identity("fixture", 1_000, "—", "21"), new Identity("fixture", 1_000, "VM", "—"))) {
            for (Identity[] pair : List.of(new Identity[]{placeholder, placeholder},
                    new Identity[]{placeholder, IDENTITY}, new Identity[]{IDENTITY, placeholder})) {
                Report threads = ThreadComparison.compare(pair[0], sample(), pair[1], sample());
                assertEquals(Status.MISSING_IDENTITY, threads.status()); assertNull(threads.counts());
                assertTrue(threads.differences().isEmpty());
                String snapshot = SnapshotStore.compare(new SnapshotStore.Snapshot(pair[0], metricA, sample(), ""),
                        new SnapshotStore.Snapshot(pair[1], metricB, sample(), ""));
                assertTrue(snapshot.contains("Metric deltas are unavailable"));
                assertTrue(snapshot.contains("thread IDs cannot be compared"));
                assertFalse(snapshot.contains("; Δ"));
                assertFalse(snapshot.contains("Captured scope:"));
            }
        }
    }

    @Test void truncatedAbsenceRemainsAnObservationRatherThanThreadTermination() {
        Report report = ThreadComparison.compare(IDENTITY, sample(), IDENTITY, dump(3_000, 3_010, true, List.of()));
        assertTrue(report.inputTruncated()); assertEquals(1, report.counts().noLongerObserved());
        assertEquals(Kind.NO_LONGER_OBSERVED, report.differences().getFirst().kind());
        String text = ThreadComparison.describe(report);
        assertTrue(text.contains("no longer observed does not mean terminated")); assertTrue(text.contains("not captured atomically"));
        assertTrue(text.contains("list truncated"));
    }

    @Test void frameComparisonUsesPersistedFieldsInsteadOfModuleAndClassloaderMetadata() {
        StackTraceElement live = new StackTraceElement("app-loader", "example.module", "1.0", "example.Worker", "await", "Worker.java", 12);
        assertNotEquals(live, TOP, "JDK equals includes metadata not present in snapshot version 1.");
        Report report = ThreadComparison.compare(IDENTITY, dump(2_000, 2_001, false, List.of(thread(1, "worker-1", "WAITING", "", "", List.of(live)))),
                IDENTITY, sample());
        assertEquals(0, report.counts().stackChanges()); assertEquals(1, report.counts().unchanged());
        assertTrue(report.differences().isEmpty());
    }

    @Test void onlyComparesSupportedStackDepthAndLabelsDeeperFramesUnknown() {
        List<StackTraceElement> before = new ArrayList<>(Collections.nCopies(65, TOP));
        List<StackTraceElement> after = new ArrayList<>(before); after.set(64, frame("outsideLimit", 99));
        Report report = ThreadComparison.compare(IDENTITY, dump(2_000, 2_001, false, List.of(thread(1, "worker", "WAITING", "", "", before))),
                IDENTITY, dump(3_000, 3_001, false, List.of(thread(1, "worker", "WAITING", "", "", after))));
        assertTrue(report.stackDepthLimited()); assertEquals(0, report.counts().stackChanges());
        assertTrue(ThreadComparison.describe(report).contains("deeper calls are unknown"));
        after.set(3, frame("insideLimit", 77));
        Report changed = ThreadComparison.compare(IDENTITY, dump(2_000, 2_001, false, List.of(thread(1, "worker", "WAITING", "", "", before))),
                IDENTITY, dump(3_000, 3_001, false, List.of(thread(1, "worker", "WAITING", "", "", after))));
        assertEquals(4, changed.differences().getFirst().frameChange().depth());
    }

    @Test void missingFramesAndLockOwnerChangesAreComparedWithoutInventingOwnerIds() {
        Report owner = ThreadComparison.compare(IDENTITY, dump(2_000, 2_001, false, List.of(thread(1, "worker", "WAITING", "lock", "owner-A", List.of(TOP)))),
                IDENTITY, dump(3_000, 3_001, false, List.of(thread(1, "worker", "WAITING", "lock", "owner-B", List.of()))));
        assertEquals(1, owner.counts().lockChanges()); assertEquals(1, owner.counts().stackChanges());
        assertTrue(owner.differences().getFirst().frameChange().after().contains("No captured frame at this depth"));
        Report nullLock = ThreadComparison.compare(IDENTITY, dump(2_000, 2_001, false, List.of(thread(1, "worker", "WAITING", null, null, List.of()))),
                IDENTITY, dump(3_000, 3_001, false, List.of(thread(1, "worker", "WAITING", "", "", List.of()))));
        assertEquals(0, nullLock.counts().lockChanges());
    }

    @Test void capsInputRowsDetailsFieldLengthsAndRenderedText() {
        String hostileName = "forged\nline\t" + "名".repeat(4_000);
        List<ThreadRecord> oversized = IntStream.rangeClosed(1, 700).mapToObj(id -> thread(id, hostileName, "WAITING", "", "", List.of(TOP))).toList();
        Report report = ThreadComparison.compare(IDENTITY, dump(2_000, 2_001, false, oversized), IDENTITY, dump(3_000, 3_001, false, List.of()));
        assertTrue(report.inputTruncated()); assertEquals(512, report.before().capturedThreads());
        assertEquals(512, report.counts().noLongerObserved());
        assertEquals(ThreadComparison.MAX_DIFFERENCES, report.differences().size()); assertTrue(report.differencesTruncated());
        assertTrue(report.differences().getFirst().before().name().length() <= ThreadComparison.MAX_FIELD_TEXT);
        assertFalse(report.differences().getFirst().before().name().contains("\n"));
        assertTrue(report.differences().getFirst().before().name().contains("\\n"));
        String text = ThreadComparison.describe(report);
        assertTrue(text.length() <= ThreadComparison.MAX_TEXT); assertTrue(text.contains("Report text truncated"));
        assertTrue(text.contains("counts above include all processed records"));
    }

    @Test void duplicateIdsInvalidStatesAndReversedWindowsMakeCountsUnavailable() {
        List<ThreadDump> invalid = List.of(dump(2_000, 2_001, false, List.of(thread(1), thread(1))),
                dump(2_000, 2_001, false, List.of(thread(0))), dump(2_000, 1_000, false, List.of(thread(1))),
                dump(-1, 2_001, false, List.of(thread(1))),
                dump(2_000, 2_001, false, List.of(thread(1, "worker", "NOT_A_STATE", "", "", List.of()))));
        for (ThreadDump dump : invalid) {
            Report report = ThreadComparison.compare(IDENTITY, dump, IDENTITY, sample());
            assertEquals(Status.INVALID_CAPTURE, report.status()); assertNull(report.counts()); assertTrue(report.differences().isEmpty());
        }
    }

    @Test void overlappingOrReverseCaptureOrderIsExplicitAndNeverUsedAsTimeTrend() {
        Report overlap = ThreadComparison.compare(IDENTITY, sample(), IDENTITY, dump(2_005, 2_020, false, List.of(thread(1))));
        assertTrue(overlap.comparable()); assertFalse(overlap.chronological());
        String text = ThreadComparison.describe(overlap);
        assertTrue(text.contains("selection order, not a time trend"));
        assertTrue(text.contains("1970-01-01T00:00:02Z"));
        Report reverse = ThreadComparison.compare(IDENTITY, sample(), IDENTITY, dump(1_800, 1_900, false, List.of(thread(1))));
        assertFalse(reverse.chronological());
    }

    private static ThreadDump sample() { return dump(2_000, 2_010, false, List.of(thread(1))); }
    private static StackTraceElement frame(String method, int line) { return new StackTraceElement("example.Worker", method, "Worker.java", line); }
    private static ThreadRecord thread(long id) { return thread(id, "worker-" + id, "WAITING", "", "", List.of(TOP)); }
    private static ThreadRecord thread(long id, String name, String state, String lock, String owner, List<StackTraceElement> frames) {
        return new ThreadRecord(id, name, state, 0, 0, lock, owner, frames);
    }
    private static ThreadDump dump(long start, long end, boolean truncated, List<ThreadRecord> threads) {
        return new ThreadDump(start, end, threads, truncated, JmxClient.THREAD_COVERAGE, List.of(), "Not inferred by comparison");
    }
}
