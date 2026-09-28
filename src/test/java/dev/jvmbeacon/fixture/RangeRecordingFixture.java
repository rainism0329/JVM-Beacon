package dev.jvmbeacon.fixture;

import jdk.jfr.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Two finite phases in an owned 64 MiB JVM. No external targets; no permanent blocked threads. */
public final class RangeRecordingFixture {
    @Name("beacon.RangePhase") public static class Phase extends Event { public String phase; }
    private static volatile Object[] retained;
    private static volatile long sink;
    public static void main(String[] args) throws Exception {
        try (Recording r = new Recording()) {
            for (String name : new String[]{"jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark", "jdk.GarbageCollection", "jdk.GCPhasePause"})
                r.enable(name).withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ObjectAllocationSample").with("throttle", "1000/s");
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
            r.enable(Phase.class).withoutStackTrace(); r.start();
            mark("A start"); workload(false); mark("A end"); Thread.sleep(180);
            mark("B start"); workload(true); mark("B end");
            r.stop(); r.dump(Path.of(args[0]));
        }
        System.out.println("RANGE_RECORDING_PASS - Two bounded phases completed; owned child exiting");
    }
    private static void mark(String name) { Phase p = new Phase(); p.phase = name; p.commit(); }
    private static void workload(boolean second) throws Exception {
        retained = new Object[64];
        for (int i = 0; i < 2048; i++) retained[i % 64] = second ? new int[4096] : new byte[16384];
        long deadline = System.nanoTime() + 180_000_000L, value = 1;
        while (System.nanoTime() < deadline) { for (int i = 0; i < 10000; i++) value = value * 1664525 + 1013904223; sink = value; }
        retained = null; System.gc();
        Object gate = new Object(); CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread owner = Thread.ofPlatform().daemon().name("beacon-range-owner").unstarted(() -> {
            synchronized (gate) { holding.countDown(); try { release.await(120, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        });
        try { owner.start(); if (!holding.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Owner missing"); synchronized (gate) { sink++; } }
        finally { release.countDown(); owner.join(2000); }
        if (owner.isAlive()) throw new IllegalStateException("Owner did not finish");
        synchronized (gate) { gate.wait(35); }
        LockSupport.parkNanos(gate, 40_000_000L);
    }
}
