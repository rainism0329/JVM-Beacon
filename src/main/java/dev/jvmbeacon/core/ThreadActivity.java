package dev.jvmbeacon.core;

import com.sun.management.ThreadMXBean;
import java.lang.management.ThreadInfo;
import java.util.*;

/** Two bounded counter reads, not a stack-sampling profiler. Never enables target monitoring. */
public final class ThreadActivity {
    public static final int MAX_THREADS = 512;
    public static final String COVERAGE = "Platform threads only · Up to 512 baseline IDs / 64 end-stack frames. "
            + "Threads starting after the baseline are excluded. IDs may be reused; matches are candidates. "
            + "End stacks are separate observations, not attribution of CPU time to methods. Not saved in .jvmb files.";

    public record Window(long startMillis, long endMillis, long counterStartNanos, long counterEndNanos) {
        public long counterSpanNanos() { return counterEndNanos - counterStartNanos; }
    }
    record Point(long id, long cpuNanos, JmxClient.ThreadRecord thread) { }
    public record Row(long id, String name, Long cpuDeltaNanos, Double oneCorePercent,
                      String evidence, JmxClient.ThreadRecord endThread) { }
    public record Report(JmxClient.Identity identity, Window baseline, Window end, long intervalNanos,
                         int discovered, int selected, boolean truncated, List<Row> rows, String unavailable) {
        public Report { rows = List.copyOf(rows); }
        public long measuredCount() { return rows.stream().filter(r -> r.cpuDeltaNanos() != null).count(); }
        public String source() { return "JMX com.sun.management.ThreadMXBean.getThreadCpuTime(long[]) · Two bulk reads; no monitoring settings changed."; }
    }
    private record Reading(Window window, List<Point> points) { }

    private ThreadActivity() { }

    public static Report unavailable(JmxClient.Identity identity, String reason) {
        long now = System.currentTimeMillis();
        return new Report(identity, new Window(now, now, 0, 0), null, 0, 0, 0, false, List.of(), reason);
    }

    static Report capture(ThreadMXBean bean, JmxClient.Identity identity) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!bean.isThreadCpuTimeSupported()) return unavailable(identity, "Per-thread CPU counters are not supported by this target.");
        if (!bean.isThreadCpuTimeEnabled()) return unavailable(identity, "Thread CPU monitoring is disabled on the target. No settings were changed.");
        long[] all = bean.getAllThreadIds();
        Arrays.sort(all);
        long[] ids = Arrays.copyOf(all, Math.min(MAX_THREADS, all.length));
        for (int i = 0; i < ids.length; i++) if (ids[i] <= 0 || (i > 0 && ids[i] == ids[i - 1]))
            return unavailable(identity, "The target returned invalid or duplicate thread IDs.");
        try {
            Reading baseline = read(bean, ids, 0);
            Thread.sleep(1_000);
            if (!bean.isThreadCpuTimeEnabled()) return unavailable(identity, "Thread CPU monitoring became disabled during capture. No delta was calculated.");
            Reading end = read(bean, ids, JmxClient.MAX_STACK_DEPTH);
            if (!bean.isThreadCpuTimeEnabled()) return unavailable(identity, "Thread CPU monitoring became disabled during capture. No delta was calculated.");
            return compare(identity, baseline.window(), end.window(), baseline.points(), end.points(), all.length);
        } catch (UnsupportedOperationException unavailable) {
            return unavailable(identity, "Bulk platform-thread CPU counters are unavailable on this target. No settings were changed.");
        } catch (InconsistentResponse malformed) {
            return unavailable(identity, "The target returned inconsistent thread counter or metadata arrays. No delta was calculated.");
        }
    }

    private static Reading read(ThreadMXBean bean, long[] ids, int depth) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long wallStart = System.currentTimeMillis();
        ThreadInfo[] infos = bean.getThreadInfo(ids, depth);
        long before = System.nanoTime();
        long[] counters = bean.getThreadCpuTime(ids);
        long after = System.nanoTime(), wallEnd = System.currentTimeMillis();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (infos == null || counters == null || infos.length != ids.length || counters.length != ids.length) throw new InconsistentResponse();
        List<Point> points = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            ThreadInfo info = infos[i];
            if (info != null && info.getThreadId() != ids[i]) throw new InconsistentResponse();
            JmxClient.ThreadRecord thread = info == null ? null : new JmxClient.ThreadRecord(info.getThreadId(), text(info.getThreadName()),
                    info.getThreadState().name(), info.getBlockedCount(), info.getWaitedCount(), text(info.getLockName()), text(info.getLockOwnerName()),
                    Arrays.asList(info.getStackTrace()).stream().limit(JmxClient.MAX_STACK_DEPTH).toList());
            points.add(new Point(ids[i], counters[i], thread));
        }
        return new Reading(new Window(wallStart, wallEnd, before, after), List.copyOf(points));
    }

    /** Pure evidence calculation, also used for adverse counter/identity/timing tests. */
    static Report compare(JmxClient.Identity identity, Window first, Window last, List<Point> before, List<Point> after, int discovered) {
        if (before.size() > MAX_THREADS || after.size() > MAX_THREADS) throw new IllegalArgumentException("Thread sample exceeds 512 candidates.");
        long interval = (last.counterStartNanos() - first.counterStartNanos())
                + (last.counterSpanNanos() - first.counterSpanNanos()) / 2;
        if (first.counterSpanNanos() < 0 || last.counterSpanNanos() < 0 || interval <= 0)
            return new Report(identity, first, last, 0, discovered, before.size(), discovered > before.size(), List.of(), "Invalid monotonic counter windows. No CPU delta was calculated.");
        Map<Long, Point> end = new HashMap<>();
        for (Point point : after) if (point.id() <= 0 || end.put(point.id(), point) != null)
            return unavailable(identity, "Duplicate or invalid thread identity in the end sample.");
        Set<Long> seen = new HashSet<>(); List<Row> rows = new ArrayList<>();
        for (Point a : before) {
            if (a.id() <= 0 || !seen.add(a.id())) return unavailable(identity, "Duplicate or invalid thread identity in the baseline.");
            Point b = end.get(a.id());
            JmxClient.ThreadRecord ending = b == null ? null : b.thread();
            String name = ending != null ? ending.name() : a.thread() != null ? a.thread().name() : "Identity unavailable";
            String reason = a.thread() == null ? "No baseline identity; no delta"
                    : ending == null ? "Not observed at end; this does not prove thread exit"
                    : !a.thread().name().equals(ending.name()) ? "Thread name changed; identity uncertain"
                    : a.cpuNanos() < 0 || b.cpuNanos() < 0 ? "CPU counter unavailable; not zero"
                    : b.cpuNanos() < a.cpuNanos() ? "CPU counter decreased; no delta"
                    : null;
            Long delta = reason == null ? b.cpuNanos() - a.cpuNanos() : null;
            Double percent = delta == null ? null : delta.doubleValue() / interval * 100.0;
            String evidence = reason != null ? reason : percent > 100.0 ? "Estimate exceeds one core; inspect counter-read timing uncertainty"
                    : "Same ID/name candidate; end stack does not identify the CPU-consuming method";
            rows.add(new Row(a.id(), text(name), delta, percent, evidence, ending));
        }
        rows.sort(Comparator.comparing(Row::cpuDeltaNanos, Comparator.nullsLast(Comparator.reverseOrder())).thenComparingLong(Row::id));
        return new Report(identity, first, last, interval, discovered, before.size(), discovered > before.size(), rows, null);
    }

    private static String text(String value) { return value == null ? "—" : value.substring(0, Math.min(value.length(), 1024)); }
    private static final class InconsistentResponse extends RuntimeException { }
}
