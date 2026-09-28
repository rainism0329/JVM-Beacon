package dev.jvmbeacon.fixture;

import jdk.jfr.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Disposable child: finite monitor contention, condition wait and platform/virtual parks. No permanent deadlock. */
public final class WaitRecordingFixture {
    static final class Gate { }
    @Name("beacon.WaitWithoutStack") public static class Missing extends Event { public Class<?> monitorClass = Gate.class; }
    @Name("beacon.WaitUnknownClass") public static class Unknown extends Event { public Class<?> parkedClass; }
    @Name("beacon.VirtualWaitProbe") public static class VirtualProbe extends Event { public Class<?> parkedClass = Gate.class; }
    private static volatile int entered;
    public static void main(String[] args) throws Exception {
        try (Recording recording = new Recording()) {
            for (String type : new String[]{"jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark"})
                recording.enable(type).withThreshold(Duration.ZERO).withStackTrace();
            recording.enable(Missing.class).withoutStackTrace(); recording.enable(Unknown.class).withoutStackTrace();
            recording.enable(VirtualProbe.class).withStackTrace(); recording.start();
            Gate gate = new Gate(); CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
            Thread owner = Thread.ofPlatform().daemon().name("beacon-wait-owner").unstarted(() -> {
                synchronized (gate) { holding.countDown(); try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
            });
            Thread entrant = Thread.ofPlatform().daemon().name("beacon-wait-entrant").unstarted(() -> enterGate(gate));
            try {
                owner.start(); if (!holding.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Owner did not acquire monitor");
                entrant.start(); long deadline = System.nanoTime() + 2_000_000_000L;
                while (entrant.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(2);
                if (entrant.getState() != Thread.State.BLOCKED) throw new IllegalStateException("Entrant did not block");
                Thread.sleep(140);
            } finally { release.countDown(); owner.join(2500); entrant.join(2500); }
            if (owner.isAlive() || entrant.isAlive() || entered != 1) throw new IllegalStateException("Monitor fixture did not finish");
            Thread condition = Thread.ofPlatform().daemon().name("beacon-wait-condition").start(() -> conditionWait(gate));
            condition.join(2000); if (condition.isAlive()) { condition.interrupt(); condition.join(1000); throw new IllegalStateException("Condition fixture did not finish"); }
            Thread platform = Thread.ofPlatform().daemon().name("beacon-wait-platform-park").start(() -> timedPark(gate));
            Thread virtual = Thread.ofVirtual().name("beacon-wait-virtual-park").start(() -> { timedPark(gate); new VirtualProbe().commit(); });
            for (Thread thread : new Thread[]{platform, virtual}) {
                thread.join(2000); if (thread.isAlive()) { thread.interrupt(); thread.join(1000); throw new IllegalStateException("Park fixture did not finish"); }
            }
            new Missing().commit(); new Unknown().commit();
            recording.stop(); recording.dump(Path.of(args[0]));
        }
        System.out.println("WAIT_RECORDING_PASS - All owned workload threads ended");
    }
    private static void enterGate(Gate gate) { synchronized (gate) { entered++; } }
    private static void conditionWait(Gate gate) {
        synchronized (gate) { try { gate.wait(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
    private static void timedPark(Gate gate) {
        long deadline = System.nanoTime() + 160_000_000L;
        while (!Thread.currentThread().isInterrupted()) { long remaining = deadline - System.nanoTime(); if (remaining <= 0) break; LockSupport.parkNanos(gate, remaining); }
    }
}
