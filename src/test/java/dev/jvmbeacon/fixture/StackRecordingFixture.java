package dev.jvmbeacon.fixture;

import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import java.nio.file.Path;

/** Deterministic conversion evidence in an owned child JVM; never starts JFR in the IDE test host. */
public final class StackRecordingFixture {
    @Name("beacon.StackConversionTest") static class StackEvent extends Event { }
    private static void emitAtDepth(int depth) { if (depth == 0) new StackEvent().commit(); else emitAtDepth(depth - 1); }
    public static void main(String[] args) throws Exception {
        try (Recording r = new Recording()) {
            r.enable(StackEvent.class).with("stackTrace", args[1]);
            r.start(); emitAtDepth(Integer.parseInt(args[2])); r.stop(); r.dump(Path.of(args[0]));
        }
    }
}
