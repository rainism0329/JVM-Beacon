package dev.jvmbeacon.fixture;

import jdk.jfr.*;
import java.nio.file.Path;
import java.time.Duration;

/** Owned child only: at most 256 MiB total churn, 2 MiB retained, 3 s loop. Never runs in the IDE test host. */
public final class MemoryRecordingFixture {
    private static volatile Object[] retained;
    @Name("beacon.AllocationWeight") public static class Weight extends Event {
        public Class<?> objectClass;
        @DataAmount(DataAmount.BYTES) public long weight;
    }
    @Name("beacon.AllocationWrongUnit") public static class WrongUnit extends Event {
        public Class<?> objectClass = byte[].class;
        @DataAmount(DataAmount.BITS) public long weight = 8;
    }
    @Name("beacon.InvalidGc") public static class InvalidGc extends Event {
        public long gcId = -1;
        public String name = "invalid";
    }
    public static void main(String[] args) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("jdk.GarbageCollection").withThreshold(Duration.ZERO);
            recording.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000/s").withoutStackTrace();
            recording.enable(Weight.class).withoutStackTrace(); recording.enable(WrongUnit.class).withoutStackTrace(); recording.enable(InvalidGc.class).withoutStackTrace();
            recording.start();
            retained = new Object[128]; long deadline = System.nanoTime() + 3_000_000_000L;
            for (int i = 0; i < 16_384 && System.nanoTime() < deadline; i++) {
                retained[i % 128] = new byte[16_384];
                if (i % 256 == 0) Thread.sleep(5);
            }
            retained = null; System.gc(); // Explicitly authorized workload in this disposable child only.
            for (long value : new long[]{Long.MAX_VALUE, Long.MAX_VALUE, 0, -1}) {
                Weight event = new Weight(); event.objectClass = byte[].class; event.weight = value; event.commit();
            }
            new WrongUnit().commit(); new InvalidGc().commit();
            recording.stop(); recording.dump(Path.of(args[0]));
        }
        System.out.println("MEMORY_RECORDING_PASS");
    }
}
