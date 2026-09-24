package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Generates real, replayable evidence. Only contacts the authenticated loopback child it owns. */
public final class LockCaptureMain {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        try (FixtureProcess fixture = new FixtureProcess(true); JmxClient client = fixture.remote("operator")) {
            var methods = client.info(DemoApplication.BEAN_NAME).getOperations();
            var start = Arrays.stream(methods).filter(m -> m.getName().equals("startLockContention")).findFirst().orElseThrow();
            var release = Arrays.stream(methods).filter(m -> m.getName().equals("releaseLockContention")).findFirst().orElseThrow();
            client.invoke(DemoApplication.BEAN_NAME, start, List.of("30"));
            var identity = client.identity();
            var a = new SnapshotStore.Snapshot(identity, client.sample(), await(client, "BLOCKED"), "A: controlled three-thread contention; owned fixture only.");
            client.invoke(DemoApplication.BEAN_NAME, release, List.of());
            var b = new SnapshotStore.Snapshot(identity, client.sample(), await(client, "TIMED_WAITING"), "B: after explicit release; not proof of all intervening states.");
            var report = ThreadComparison.compare(identity, a.threads(), identity, b.threads());
            if (!report.comparable() || report.counts().stateChanges() < 2 || report.counts().lockChanges() < 2) throw new IllegalStateException("Expected fixture transitions were not observed.");
            var waiter = a.threads().threads().stream().filter(t -> t.name().equals("beacon-lock-waiter")).findFirst().orElseThrow();
            if (LockChains.from(a.threads()).path(waiter.id()).threads().size() != 3) throw new IllegalStateException("Expected three-member chain was not observed.");
            SnapshotStore.save(output.resolve("locks-A.jvmb"), a); SnapshotStore.save(output.resolve("locks-B.jvmb"), b);
            Files.writeString(output.resolve("comparison.txt"), SnapshotStore.compare(SnapshotStore.load(output.resolve("locks-A.jvmb")), SnapshotStore.load(output.resolve("locks-B.jvmb"))), StandardOpenOption.CREATE_NEW);
            System.out.println("LOCK_CAPTURE_PASS\nOpen B, then Compare with file A. Fixture exited after capture.\n" + output.toAbsolutePath());
        }
    }
    private static JmxClient.ThreadDump await(JmxClient client, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            var dump = client.threads();
            if (dump.threads().stream().filter(t -> t.name().equals("beacon-lock-waiter") || t.name().equals("beacon-lock-bridge")).filter(t -> t.state().equals(state)).count() == 2) return dump;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Fixture lock state did not become " + state);
    }
}
