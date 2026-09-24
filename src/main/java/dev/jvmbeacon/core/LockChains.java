package dev.jvmbeacon.core;

import java.time.Instant;
import java.util.*;

/** A bounded view of observed owner IDs. No target calls and no name-based edges. */
public final class LockChains {
    public static final int MAX_PATH = 64;
    public static final String LIMITS = "Platform threads only · Non-atomic observation, not blocking duration. "
            + "An observed cycle is not a replacement for the separate JVM deadlock query. "
            + "A missing owner record may reflect truncation or collection timing; it does not prove thread exit.";
    public record Path(List<JmxClient.ThreadRecord> threads, String end, boolean cycle) {
        public Path { threads = List.copyOf(threads); }
    }
    private final Map<Long, JmxClient.ThreadRecord> records;
    private final List<JmxClient.ThreadRecord> waiters;
    private final String evidence;
    private final boolean available;

    private LockChains(Map<Long, JmxClient.ThreadRecord> records, List<JmxClient.ThreadRecord> waiters, String evidence, boolean available) {
        this.records = Map.copyOf(records); this.waiters = List.copyOf(waiters); this.evidence = evidence; this.available = available;
    }
    public static LockChains from(JmxClient.ThreadDump dump) {
        if (dump == null) return unavailable("No thread capture. Capture threads, or open a snapshot containing threads.");
        if (dump.captureStart() < 0 || dump.captureEnd() < dump.captureStart()) return unavailable("Invalid capture window; lock relationships are unavailable.");
        Map<Long, JmxClient.ThreadRecord> records = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(dump.threads().size(), JmxClient.MAX_THREADS); i++) {
            var t = dump.threads().get(i);
            if (t.id() <= 0 || t.name() == null || t.state() == null || t.lockOwnerId() != null && (t.lockOwnerId() == 0 || t.lockOwnerId() < -1)
                    || records.putIfAbsent(t.id(), t) != null) return unavailable("Invalid or duplicate thread / owner IDs; lock relationships are unavailable.");
            try { Thread.State.valueOf(t.state()); } catch (IllegalArgumentException e) { return unavailable("Invalid thread state; lock relationships are unavailable."); }
        }
        List<JmxClient.ThreadRecord> waiters = records.values().stream().filter(t -> t.lockOwnerId() != null && t.lockOwnerId() > 0
                || t.state().equals("BLOCKED") || Set.of("WAITING", "TIMED_WAITING").contains(t.state()) && hasLock(t.lockName())).toList();
        long unknown = records.values().stream().filter(t -> t.lockOwnerId() == null).count();
        String evidence = "Capture: " + Instant.ofEpochMilli(dump.captureStart()) + " → " + Instant.ofEpochMilli(dump.captureEnd())
                + " (UTC)\n" + waiters.size() + " captured waiters / " + records.size() + " processed threads"
                + (dump.truncated() || dump.threads().size() > JmxClient.MAX_THREADS ? " · Truncated list" : "")
                + (unknown > 0 ? " · Owner ID not captured for " + unknown + " threads (including legacy snapshots)" : "")
                + "\n" + LIMITS + "\nCoverage: " + dump.coverage() + "\nSeparate deadlock query: " + dump.deadlockStatus()
                + "\nReported IDs: " + dump.deadlockedIds();
        return new LockChains(records, waiters, evidence, true);
    }
    private static boolean hasLock(String name) { return name != null && !name.isBlank() && !name.equals("—"); }
    private static LockChains unavailable(String reason) { return new LockChains(Map.of(), List.of(), reason + "\n" + LIMITS, false); }
    public List<JmxClient.ThreadRecord> waiters() { return waiters; }
    public String evidence() { return evidence; }
    public boolean available() { return available; }
    public Path path(long start) {
        List<JmxClient.ThreadRecord> path = new ArrayList<>(); Set<Long> visited = new HashSet<>(); long id = start;
        while (path.size() < MAX_PATH) {
            if (!visited.add(id)) return new Path(path, "Observed owner-ID cycle returns to #" + id + ". Non-atomic observations do not establish a simultaneous deadlock.", true);
            var t = records.get(id);
            if (t == null) return new Path(path, "Owner #" + id + " is outside the captured records; its stack and further ownership are unknown.", false);
            path.add(t);
            if (t.lockOwnerId() == null) return new Path(path, "Owner ID was not captured for #" + id + "; names are not used to guess relationships.", false);
            if (t.lockOwnerId() == -1) return new Path(path, "No further owner reported for #" + id + ". This does not mean the thread holds no locks or that all waits are resolved.", false);
            id = t.lockOwnerId();
        }
        return new Path(path, "Path truncated at " + MAX_PATH + " threads; further relationships are unknown.", false);
    }
    public static String ownerLabel(Long id) { return id == null ? "Owner ID not captured" : id == -1 ? "No owner reported" : "Owner #" + id; }
}
