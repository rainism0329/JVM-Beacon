package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;

import javax.management.MBeanOperationInfo;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded standalone resource observation. This measures the harness process, never the whole IDE. */
public final class ResourceSoakMain {
    private static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int REQUEST_SECONDS = 10;
    private static final String BEAN = DemoApplication.BEAN_NAME;
    private static final ExecutorService CALLS = new ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> daemon(runnable, "beacon-soak-call"), new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicReference<FixtureProcess> OWNED_FIXTURE = new AtomicReference<>();
    private static final AtomicReference<JmxClient> OWNED_CLIENT = new AtomicReference<>();
    private static final List<Double> LATENCIES = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static com.sun.management.OperatingSystemMXBean processMxBean;
    private static Path reportDirectory;
    private static int subscriptions, removals, snapshotRoundTrips, threadCaptures, samples;
    private static long maximumHeap, initialCpu, finalCpu, measuredNanos;
    private static boolean targetExitRejected, targetStopped, cleaned, closeConfirmed = true;

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 180;
        if (seconds < 20 || seconds > 600) throw new IllegalArgumentException("Duration must be between 20 and 600 seconds.");
        reportDirectory = args.length > 1 ? Path.of(args[1]).toAbsolutePath().normalize()
                : Path.of("build", "reports", "resource-soak", Long.toString(System.currentTimeMillis())).toAbsolutePath();
        Files.createDirectories(reportDirectory);
        var os = ManagementFactory.getOperatingSystemMXBean();
        processMxBean = os instanceof com.sun.management.OperatingSystemMXBean extended ? extended : null;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            FixtureProcess fixture = OWNED_FIXTURE.get();
            if (fixture != null && fixture.process.isAlive()) fixture.process.destroyForcibly();
        }, "beacon-soak-owned-fixture-cleanup"));
        Thread watchdog = daemon(() -> {
            try { Thread.sleep(TimeUnit.SECONDS.toMillis(seconds + 100L)); }
            catch (InterruptedException e) { return; }
            FixtureProcess fixture = OWNED_FIXTURE.get();
            if (fixture != null && fixture.process.isAlive()) fixture.process.destroyForcibly();
            System.err.println("RESOURCE_SOAK_WATCHDOG: duration + 100 seconds reached; owned fixture stopped.");
            System.exit(2);
        }, "beacon-soak-watchdog");
        watchdog.start();

        line("# JVM Beacon standalone collection resource observation");
        line("");
        line("- Start time: " + Instant.now());
        line("- Environment: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " / "
                + System.getProperty("os.arch") + "; " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        line("- Available logical processors: " + Runtime.getRuntime().availableProcessors());
        line("- Planned sampling window: " + seconds + " seconds; serial collection every 2 seconds, with no catch-up for missed cycles. Warmup and cleanup waits are additional.");
        line("- Measurement scope: the standalone ResourceSoakMain collector process. It excludes the whole IDEA process, Swing UI and SessionRunner, and does not represent overall plugin overhead.");
        line("- Target: a separate JDK 21 fixture started by this run; authenticated JMX/RMI listens only on loopback. Passwords and connection URLs are not recorded.");
        line("- Each request has a " + REQUEST_SECONDS + "-second deadline; the deadline only stops waiting. Collection stops on failure, cleans up its owned target in finally, and does not add worker threads.");
        line("- Heap values are current used/committed values without forced GC; CPU values are deltas of collector process CPU time. Neither directly establishes a leak or steady-state retained memory.");
        Throwable failure = null;
        try {
            run(seconds);
        } catch (Throwable problem) {
            failure = problem;
            line("- Run failed: " + exceptionTypes(problem) + ". Messages are omitted to avoid exposing target-returned content.");
        } finally {
            cleanup();
            watchdog.interrupt();
            writeSummary(failure);
        }
        System.out.println(failure == null && targetExitRejected && targetStopped && cleaned && closeConfirmed ? "RESOURCE_SOAK_PASS" : "RESOURCE_SOAK_INCOMPLETE");
        System.out.println("Report: " + reportDirectory.resolve("summary.md"));
        if (failure != null || !targetExitRejected || !targetStopped || !cleaned || !closeConfirmed) System.exit(1);
    }

    private static void run(int seconds) throws Exception {
        FixtureProcess fixture = new FixtureProcess(true);
        OWNED_FIXTURE.set(fixture);
        JmxClient client = call("connect", () -> fixture.remote("operator"));
        OWNED_CLIENT.set(client);
        MBeanOperationInfo emit = call("metadata", () -> Arrays.stream(client.info(BEAN).getOperations())
                .filter(operation -> operation.getName().equals("emit")).findFirst().orElseThrow());
        for (int i = 0; i < 3; i++) {
            int cycle = i;
            call("warmup", () -> {
                client.sample(); client.threads(); client.subscribe(BEAN);
                client.invoke(BEAN, emit, List.of("warmup " + cycle));
                client.unsubscribe(BEAN);
                return null;
            });
            Thread.sleep(300);
        }
        line(""); line("## Related thread observations");
        line("Threads are selected by names starting with JMX or RMI, or containing beacon; digits are normalized. beacon-soak-* threads belong to this harness.");
        observeThreads("After warmup, connection still open", 5, 1000);
        initialCpu = cpuNanos();
        long started = System.nanoTime(), next = started, deadline = started + TimeUnit.SECONDS.toNanos(seconds);
        Path capture = reportDirectory.resolve("last-capture.jvmb");
        try (BufferedWriter csv = Files.newBufferedWriter(reportDirectory.resolve("samples.csv"), StandardCharsets.UTF_8)) {
            csv.write("timestamp_utc,elapsed_ms,sample_latency_ms,process_cpu_ms,process_cpu_delta_ms,cpu_percent_one_core,heap_used_bytes,heap_committed_bytes,platform_threads,jmx_rmi_beacon_threads,notification_count,snapshot_bytes\n");
            long previousCpu = cpuNanos(), previousWall = started;
            while (System.nanoTime() < deadline) {
                long pause = next - System.nanoTime();
                if (pause > 0) TimeUnit.NANOSECONDS.sleep(Math.min(pause, Math.max(0, deadline - System.nanoTime())));
                if (System.nanoTime() >= deadline) break;
                int index = samples;
                Tick tick = call("sample cycle", () -> sampleCycle(client, emit, index, capture));
                samples++; LATENCIES.add(tick.latencyMs());
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                maximumHeap = Math.max(maximumHeap, heap.getUsed());
                long now = System.nanoTime(), cpu = cpuNanos();
                double cpuDeltaMs = cpu < 0 || previousCpu < 0 ? -1 : (cpu - previousCpu) / 1_000_000d;
                double cpuPercent = cpuDeltaMs < 0 ? -1 : (cpu - previousCpu) * 100d / Math.max(1, now - previousWall);
                csv.write(String.format(Locale.ROOT, "%s,%.3f,%.3f,%.3f,%.3f,%.5f,%d,%d,%d,%d,%d,%d%n", Instant.now(),
                        (now - started) / 1_000_000d, tick.latencyMs(), cpu < 0 ? -1 : cpu / 1_000_000d, cpuDeltaMs, cpuPercent,
                        heap.getUsed(), heap.getCommitted(), ManagementFactory.getThreadMXBean().getThreadCount(),
                        relatedThreads().values().stream().mapToInt(Integer::intValue).sum(), tick.notifications(), tick.snapshotBytes()));
                csv.flush(); previousCpu = cpu; previousWall = now;
                if (samples % 15 == 0) System.out.printf(Locale.ROOT, "SOAK_PROGRESS samples=%d elapsed=%.1fs heap_used=%.1fMiB%n", samples, (now - started) / 1_000_000_000d, heap.getUsed() / 1_048_576d);
                next += INTERVAL_NANOS;
                if (next < now) next = now + INTERVAL_NANOS;
            }
        }
        measuredNanos = System.nanoTime() - started; finalCpu = cpuNanos();
        call("final unsubscribe", () -> { client.unsubscribe(BEAN); return null; });
        observeThreads("After sampling, connection still open, unsubscribed", 5, 1000);

        fixture.close(); targetStopped = !fixture.process.isAlive();
        long exitCheckStarted = System.nanoTime();
        try { call("read after target exit", client::sample); }
        catch (Exception expected) {
            targetExitRejected = true;
            line("- Sampling after the real target exited: failed; exception type chain " + exceptionTypes(expected) + "; waited "
                    + String.format(Locale.ROOT, "%.3f", (System.nanoTime() - exitCheckStarted) / 1_000_000d) + " ms. "
                    + (expected instanceof TimeoutException ? "Only a deadline timeout was observed; it is not a confirmed network-closure exception." : ""));
        }
        if (!targetExitRejected) throw new AssertionError("Read unexpectedly succeeded after owned target exit.");
        closeClientBounded();
        observeThreads("After target exit and client close", 8, 1000);
    }

    private static Tick sampleCycle(JmxClient client, MBeanOperationInfo emit, int index, Path capture) throws Exception {
        long started = System.nanoTime();
        JmxClient.Sample sample = client.sample();
        double latency = (System.nanoTime() - started) / 1_000_000d;
        if (index % 10 == 0) {
            client.subscribe(BEAN); subscriptions++;
            client.invoke(BEAN, emit, List.of("resource soak sample " + index));
        } else if (index % 10 == 5) {
            client.unsubscribe(BEAN); removals++;
        }
        long bytes = 0;
        if (index % 5 == 0) {
            JmxClient.ThreadDump threads = client.threads(); threadCaptures++;
            SnapshotStore.Snapshot snapshot = new SnapshotStore.Snapshot(client.identity(), sample, threads,
                    "Owned fixture; standalone resource observation; does not measure entire IDE overhead.");
            SnapshotStore.save(capture, snapshot);
            SnapshotStore.Snapshot reopened = SnapshotStore.load(capture);
            if (!snapshot.identity().equals(reopened.identity()) || reopened.sample() == null || reopened.threads() == null)
                throw new AssertionError("Resource soak snapshot round trip failed.");
            snapshotRoundTrips++; bytes = Files.size(capture);
        }
        int notifications = client.notifications().size();
        if (notifications > JmxClient.MAX_NOTIFICATIONS) throw new AssertionError("Notification buffer exceeded declared bound.");
        return new Tick(latency, notifications, bytes);
    }

    private record Tick(double latencyMs, int notifications, long snapshotBytes) {}

    private static <T> T call(String phase, Callable<T> operation) throws Exception {
        AtomicBoolean abandoned = new AtomicBoolean(); AtomicReference<T> produced = new AtomicReference<>();
        Future<T> future = CALLS.submit(() -> {
            T result = operation.call(); produced.set(result);
            if (abandoned.get() && result instanceof JmxClient late) late.close();
            return result;
        });
        try { return future.get(REQUEST_SECONDS, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) {
            abandoned.set(true); future.cancel(true);
            T result = produced.get();
            if (result instanceof JmxClient late) {
                daemon(() -> { try { late.close(); } catch (Exception ignored) { } }, "beacon-soak-late-close").start();
            }
            throw new TimeoutException(phase);
        }
        catch (ExecutionException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    private static void closeClientBounded() {
        JmxClient client = OWNED_CLIENT.getAndSet(null);
        if (client == null) return;
        closeConfirmed = false;
        ExecutorService closer = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "beacon-soak-close"));
        Future<String> close = closer.submit(() -> { try { client.close(); return "returned normally"; } catch (Exception returned) { return "returned exception " + returned.getClass().getSimpleName(); } });
        try { String outcome = close.get(5, TimeUnit.SECONDS); closeConfirmed = true; line("- Client close call finished: " + outcome + ". An exception after target exit is distinct from a close call that remains blocked."); }
        catch (Exception problem) { close.cancel(true); line("- Client close was not confirmed within 5 seconds; completion of the underlying close remains unknown. Exception type: " + problem.getClass().getSimpleName()); }
        finally { closer.shutdownNow(); }
    }

    private static void cleanup() {
        FixtureProcess fixture = OWNED_FIXTURE.get();
        if (fixture != null) {
            try { fixture.close(); targetStopped = !fixture.process.isAlive(); }
            catch (Exception problem) {
                fixture.process.destroyForcibly();
                line("- Target cleanup in finally used forced-exit fallback; exception type: " + problem.getClass().getSimpleName());
                try { targetStopped = fixture.process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
        closeClientBounded(); CALLS.shutdownNow();
        try { cleaned = CALLS.awaitTermination(5, TimeUnit.SECONDS) && targetStopped; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        line("- Cleanup confirmation: owned fixture exited=" + targetStopped + ", collector executor terminated=" + CALLS.isTerminated() + ", client close call finished=" + closeConfirmed + ".");
    }

    private static void observeThreads(String label, int observations, int intervalMillis) throws InterruptedException {
        List<Map<String, Integer>> series = new ArrayList<>();
        for (int i = 0; i < observations; i++) {
            Thread.sleep(intervalMillis); series.add(relatedThreads());
        }
        boolean same = series.stream().allMatch(series.getFirst()::equals);
        line("- " + label + ": " + observations + " consecutive observations, " + intervalMillis + " ms apart; "
                + (same ? "counts were equal within this observation window" : "counts still changed within this observation window") + ".");
        for (int i = 0; i < series.size(); i++) line("  - #" + (i + 1) + " " + series.get(i));
        line("  - Total process platform threads=" + ManagementFactory.getThreadMXBean().getThreadCount()
                + ", heap used=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + " bytes.");
    }

    private static Map<String, Integer> relatedThreads() {
        Map<String, Integer> groups = new TreeMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (name.startsWith("JMX") || name.startsWith("RMI") || name.toLowerCase(Locale.ROOT).contains("beacon")) {
                groups.merge(name.replaceAll("\\d+", "#"), 1, Integer::sum);
            }
        }
        return groups;
    }

    private static void writeSummary(Throwable failure) throws Exception {
        line(""); line("## Observed results and limitations");
        line("- Completed metric samples=" + samples + "; thread captures=" + threadCaptures + "; save/load=" + snapshotRoundTrips
                + "; subscriptions=" + subscriptions + ", periodic removals=" + removals + " (plus final cleanup).");
        line("- Actual sampling window=" + String.format(Locale.ROOT, "%.3f", measuredNanos / 1_000_000_000d) + " seconds; maximum observed collector process heap used=" + maximumHeap + " bytes.");
        if (!LATENCIES.isEmpty()) {
            List<Double> sorted = LATENCIES.stream().sorted().toList();
            line(String.format(Locale.ROOT, "- Standard MXBean sample() latency: median=%.3f ms, p95=%.3f ms, max=%.3f ms. These statistics exclude additional thread, notification and file operations in the same cycle.",
                    sorted.get((sorted.size() - 1) / 2), sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * .95) - 1)), sorted.getLast()));
        }
        if (initialCpu >= 0 && finalCpu >= initialCpu && measuredNanos > 0) line(String.format(Locale.ROOT,
                "- Process CPU time delta during sampling=%.3f ms, average CPU=%.5f%% (100%% is one fully occupied logical CPU, not a whole-machine percentage). Includes this test's serialization, file writes and recording overhead.",
                (finalCpu - initialCpu) / 1_000_000d, (finalCpu - initialCpu) * 100d / measuredNanos));
        else line("- Process CPU time is unsupported or sampling did not finish; -1 in the CSV means missing, not zero overhead.");
        line("- Run status: " + (failure == null && targetExitRejected && targetStopped && cleaned && closeConfirmed ? "workflow completed" : "incomplete; see the failure and cleanup records above") + ".");
        line("- Equal thread counts mean only that counts matched within a finite observation window; they do not prove the absence of leaks. Changes may reflect RMI caches or timer lifecycles. No exact thread-count pass threshold was set.");
        line("- No forced GC, heap histogram or IDEA baseline comparison was performed. These results cannot establish long-term leak freedom of the full plugin, whole-IDE CPU/heap overhead, or compatibility with every JDK/OS.");
        line("- The harness stops at a deadline and attempts to close late connections, but cannot forcibly terminate RMI calls. Ending its owned fixture and this standalone test process additionally bounds their lifetime; this does not validate every late-response path inside IDEA.");
        line("- samples.csv records each actual sample's time, latency, CPU, heap, thread and notification counts. last-capture.jvmb is the last capture from the owned test target; review identifiers and thread stacks before sharing.");
        Files.write(reportDirectory.resolve("summary.md"), REPORT, StandardCharsets.UTF_8);
    }

    private static long cpuNanos() { return processMxBean == null ? -1 : processMxBean.getProcessCpuTime(); }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    private static String exceptionTypes(Throwable problem) {
        List<String> names = new ArrayList<>();
        for (int i = 0; problem != null && i < 8; i++, problem = problem.getCause()) names.add(problem.getClass().getSimpleName());
        return String.join(" → ", names);
    }
    private static void line(String text) { REPORT.add(text); System.out.println(text); }
}
