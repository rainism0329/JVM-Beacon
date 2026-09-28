package dev.jvmbeacon.core;

import java.nio.file.*;
import java.util.ArrayList;

/** Reproducible, real observations from an owned authenticated loopback target. */
public final class TimelineCaptureMain {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        Path file = output.resolve("timeline.jvmb");
        try (FixtureProcess fixture = new FixtureProcess(true); JmxClient client = fixture.remote("observer")) {
            var samples = new ArrayList<JmxClient.Sample>();
            for (int i = 0; i < 8; i++) {
                if (i > 0) Thread.sleep(i == 4 ? 6000 : 1000);
                samples.add(client.sample());
            }
            var timeline = new CaptureTimeline(samples);
            var capture = new SnapshotStore.Snapshot(client.identity(), samples.getLast(), null,
                    "Eight observations from an owned observer-only fixture. Deliberate six-second sampling gap. No target operations or threads collected.", samples);
            SnapshotStore.save(file, capture);
            var reopened = SnapshotStore.load(file);
            if (reopened.history().size() != 8 || !reopened.identity().equals(client.identity()) || reopened.threads() != null)
                throw new IllegalStateException("Timeline identity, retention or missing capture changed.");
            for (int i = 0; i < samples.size(); i++) {
                var before = samples.get(i); var after = reopened.history().get(i);
                if (before.captureStart() != after.captureStart() || before.captureEnd() != after.captureEnd())
                    throw new IllegalStateException("Capture window changed.");
                for (var track : CaptureTimeline.TRACKS)
                    if (!CaptureTimeline.exact(before, track).equals(CaptureTimeline.exact(after, track)))
                        throw new IllegalStateException("Captured metric changed on reopen: " + track.key());
            }
            if (timeline.gaps() < 1) throw new IllegalStateException("Expected deliberate gap.");
            var interval = timeline.select(2, 5).samples();
            SnapshotStore.save(output.resolve("interval.jvmb"), new SnapshotStore.Snapshot(client.identity(), interval.getLast(), null, "Selected samples 3–6", interval));
            if (SnapshotStore.load(output.resolve("interval.jvmb")).history().size() != 4) throw new IllegalStateException("Interval selection changed.");
            Files.writeString(output.resolve("evidence.txt"), "Real authenticated loopback observations.\nSamples: 8\nGaps > 5 s: " + timeline.gaps()
                    + "\nCapture range: " + capture.captureStart() + " — " + capture.captureEnd() + " epoch ms\nBytes: " + Files.size(file)
                    + "\n" + SnapshotStore.REDACTION_NOTICE, StandardOpenOption.CREATE_NEW);
        }
        System.out.println("TIMELINE_CAPTURE_PASS\nOwned fixture closed. Open timeline.jvmb or interval.jvmb in JVM Beacon.\n" + output.toAbsolutePath());
    }
}
