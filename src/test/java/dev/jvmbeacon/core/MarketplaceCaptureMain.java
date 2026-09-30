package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Offline demo evidence from one owned, authenticated loopback JVM; never fabricated UI data. */
public final class MarketplaceCaptureMain {
    private static final String SCOPE = "Real observations from an owned authenticated loopback demo JVM. "
            + "The explicit --public-demo option aliases only the Runtime.Name host to beacon-demo; "
            + "PID, JVM start time, metrics, MBeans and thread observations are real. "
            + "ThreadMXBean covers platform threads, not all virtual threads. "
            + "Separate captures do not reconstruct unobserved history.";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply a new output directory.");
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output); // Do not replace evidence from a previous run.
        long started = System.nanoTime();
        FixtureProcess fixture = new FixtureProcess(true, Map.of(), List.of("--public-demo"));
        int chainMembers;
        long captureStart;
        long captureEnd;
        try (fixture; JmxClient client = fixture.remote("operator")) {
            var identity = client.identity();
            if (!identity.runtimeName().endsWith("@beacon-demo")) throw new IllegalStateException("Expected explicit public-demo identity alias.");
            var methods = client.info(DemoApplication.BEAN_NAME).getOperations();
            var start = Arrays.stream(methods).filter(m -> m.getName().equals("startLockContention")).findFirst().orElseThrow();
            var release = Arrays.stream(methods).filter(m -> m.getName().equals("releaseLockContention")).findFirst().orElseThrow();
            client.invoke(DemoApplication.BEAN_NAME, start, List.of("30"));
            var a = new SnapshotStore.Snapshot(identity, client.sample(), awaitState(client, "BLOCKED"),
                    "A: controlled three-member lock chain before release. " + SCOPE);
            var waiter = a.threads().threads().stream().filter(t -> t.name().equals("beacon-lock-waiter")).findFirst().orElseThrow();
            chainMembers = LockChains.from(a.threads()).path(waiter.id()).threads().size();
            if (chainMembers != 3) throw new IllegalStateException("Expected a real three-member owner-ID chain.");
            client.invoke(DemoApplication.BEAN_NAME, release, List.of());
            var b = new SnapshotStore.Snapshot(identity, client.sample(), awaitState(client, "TIMED_WAITING"),
                    "B: observed after explicit release of controlled contention. This does not describe every intervening state. " + SCOPE);
            var comparison = ThreadComparison.compare(identity, a.threads(), identity, b.threads());
            if (!comparison.comparable() || comparison.counts().stateChanges() < 2 || comparison.counts().lockChanges() < 2)
                throw new IllegalStateException("Expected fixture state/lock changes were not observed.");

            var samples = new ArrayList<JmxClient.Sample>();
            samples.add(b.sample());
            for (int i = 1; i < 8; i++) {
                if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(17)) throw new IllegalStateException("Capture exceeded the short demo budget.");
                Thread.sleep(2_000);
                samples.add(client.sample());
            }
            var timeline = new SnapshotStore.Snapshot(identity, samples.getLast(), null,
                    "Eight real metric observations, with two seconds between subsequent requests, after lock contention was released. " + SCOPE, samples);
            Path timelineFile = output.resolve("timeline.jvmb");
            Path aFile = output.resolve("locks-A.jvmb");
            Path bFile = output.resolve("locks-B.jvmb");
            SnapshotStore.save(timelineFile, timeline); SnapshotStore.save(aFile, a); SnapshotStore.save(bFile, b);
            var reopened = SnapshotStore.load(timelineFile);
            if (!reopened.identity().equals(identity) || reopened.threads() != null || reopened.history().size() != 8)
                throw new IllegalStateException("Timeline identity, sample count or missing thread evidence changed on reopen.");
            for (int i = 0; i < samples.size(); i++) {
                var original = samples.get(i); var restored = reopened.history().get(i);
                if (original.captureStart() != restored.captureStart() || original.captureEnd() != restored.captureEnd())
                    throw new IllegalStateException("Saved metric window changed.");
                for (var track : CaptureTimeline.TRACKS)
                    if (!CaptureTimeline.exact(original, track).equals(CaptureTimeline.exact(restored, track)))
                        throw new IllegalStateException("Saved metric value changed: " + track.key());
            }
            var reopenedA = SnapshotStore.load(aFile); var reopenedB = SnapshotStore.load(bFile);
            var reopenedWaiter = reopenedA.threads().threads().stream().filter(t -> t.name().equals("beacon-lock-waiter")).findFirst().orElseThrow();
            if (LockChains.from(reopenedA.threads()).path(reopenedWaiter.id()).threads().size() != 3)
                throw new IllegalStateException("Saved lock-chain evidence changed.");
            Files.writeString(output.resolve("comparison.txt"), SnapshotStore.compare(reopenedA, reopenedB), StandardOpenOption.CREATE_NEW);
            captureStart = timeline.captureStart(); captureEnd = timeline.captureEnd();
        }
        if (fixture.process.isAlive()) throw new IllegalStateException("Owned fixture did not exit.");
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        Files.writeString(output.resolve("evidence.txt"), SCOPE + "\n"
                + "Endpoint scope: authenticated loopback only; credentials and connection URL are not exported.\n"
                + "Explicit target operations: startLockContention(30), then releaseLockContention().\n"
                + "Timeline samples: 8; requested delay between samples: 2 seconds.\n"
                + "Observed A lock-chain members: " + chainMembers + "\n"
                + "Timeline window: " + captureStart + " — " + captureEnd + " epoch milliseconds.\n"
                + "Owned fixture exited: true.\n"
                + "Capture plus cleanup elapsed seconds: " + String.format(Locale.ROOT, "%.3f", elapsed) + "\n"
                + SnapshotStore.REDACTION_NOTICE + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("MARKETPLACE_CAPTURE_PASS\nOwned fixture exited. Open timeline.jvmb or locks-A.jvmb; compare locks-B.jvmb with A.\n"
                + output + "\nCapture plus cleanup seconds: " + String.format(Locale.ROOT, "%.3f", elapsed));
    }

    private static JmxClient.ThreadDump awaitState(JmxClient client, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);
        do {
            var dump = client.threads();
            if (dump.threads().stream()
                    .filter(t -> t.name().equals("beacon-lock-waiter") || t.name().equals("beacon-lock-bridge"))
                    .filter(t -> t.state().equals(expected)).count() == 2) return dump;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Owned fixture lock state did not become " + expected + " within the short capture budget.");
    }
}
