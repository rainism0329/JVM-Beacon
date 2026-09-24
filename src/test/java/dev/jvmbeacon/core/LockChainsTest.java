package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class LockChainsTest {
    static JmxClient.ThreadRecord thread(long id, Long owner) {
        return new JmxClient.ThreadRecord(id, "same-name", "BLOCKED", 1, 0, "lock", "same-name", List.of(), owner);
    }
    static JmxClient.ThreadDump dump(List<JmxClient.ThreadRecord> threads) {
        return new JmxClient.ThreadDump(10, 20, threads, false, JmxClient.THREAD_COVERAGE, List.of(), "Permission denied for deadlock query");
    }
    @Test void followsIdsWithDuplicateNamesAndSeparatesDeadlockQuery() {
        LockChains chains = LockChains.from(dump(List.of(thread(1, 2L), thread(2, 3L), thread(3, -1L))));
        assertEquals(List.of(1L, 2L, 3L), chains.path(1).threads().stream().map(JmxClient.ThreadRecord::id).toList());
        assertFalse(chains.path(1).cycle());
        assertTrue(chains.evidence().contains("Permission denied"));
        assertTrue(chains.path(1).end().contains("No further owner reported"));
    }
    @Test void legacyUnknownAndMissingOwnerAreDifferentFromNoOwner() {
        LockChains unknown = LockChains.from(dump(List.of(thread(1, null), thread(2, -1L))));
        assertEquals(1, unknown.path(1).threads().size()); assertTrue(unknown.path(1).end().contains("not captured"));
        assertTrue(unknown.evidence().contains("Owner ID not captured for 1"));
        assertTrue(LockChains.from(dump(List.of(thread(1, 999L)))).path(1).end().contains("outside the captured records"));
        assertTrue(unknown.path(2).end().contains("No further owner reported"));
    }
    @Test void observedCyclesDoNotClaimSimultaneousDeadlockAndTraversalTerminates() {
        var path = LockChains.from(dump(List.of(thread(1, 2L), thread(2, 1L)))).path(1);
        assertEquals(2, path.threads().size()); assertTrue(path.cycle()); assertTrue(path.end().contains("do not establish"));
        assertTrue(LockChains.from(dump(List.of(thread(1, 1L)))).path(1).cycle());
    }
    @Test void validatesIdsWindowsAndBoundsWork() {
        for (var records : List.of(List.of(thread(1, 0L)), List.of(thread(0, 2L)), List.of(thread(1, -2L)), List.of(thread(1, 2L), thread(1, 3L))))
            assertTrue(LockChains.from(dump(records)).evidence().contains("Invalid"));
        assertTrue(LockChains.from(new JmxClient.ThreadDump(20, 10, List.of(thread(1, 2L)), false, "", List.of(), "")).waiters().isEmpty());
        var large = LockChains.from(dump(IntStream.rangeClosed(1, 700).mapToObj(id -> thread(id, (long) id + 1)).toList()));
        assertEquals(512, large.waiters().size()); assertEquals(64, large.path(1).threads().size());
        assertTrue(large.path(1).end().contains("truncated")); assertTrue(large.evidence().contains("Truncated list"));
        assertTrue(large.path(512).end().contains("outside"));
        assertTrue(LockChains.from(null).evidence().contains("No thread capture"));
        assertFalse(LockChains.from(null).available());
        assertTrue(LockChains.from(dump(List.of())).available(), "A valid empty capture differs from unavailable evidence.");
    }
    @Test void ownerIdChangesCountOnlyWhenBothSidesCaptured() {
        var id = new JmxClient.Identity("test", 1, "VM", "21");
        var before = dump(List.of(thread(1, 2L)));
        assertEquals(1, ThreadComparison.compare(id, before, id, dump(List.of(thread(1, 3L)))).counts().lockChanges());
        assertEquals(0, ThreadComparison.compare(id, before, id, dump(List.of(thread(1, null)))).counts().lockChanges());
        assertEquals(1, ThreadComparison.compare(id, before, id, dump(List.of(thread(1, -1L)))).counts().lockChanges());
    }
}
