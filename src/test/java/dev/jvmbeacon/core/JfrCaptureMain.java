package dev.jvmbeacon.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Real authenticated loopback recording. Never attaches to an existing application. */
public final class JfrCaptureMain {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        try (FixtureProcess fixture = new FixtureProcess(true, java.util.Map.of(), java.util.List.of("--cpu-demo")); JmxClient client = fixture.remote("operator")) {
            var start = client.jfr().start("profile", 8);
            long deadline = System.nanoTime() + 18_000_000_000L;
            JfrCapture.State state;
            do { Thread.sleep(250); state = client.jfr().inspect(); }
            while (!"STOPPED".equals(state.state()) && System.nanoTime() < deadline);
            if (!"STOPPED".equals(state.state())) throw new IllegalStateException("Target did not report STOPPED within the test deadline.");
            Path file = client.jfr().download(output.resolve("capture.jfr"));
            var report = JfrSummary.inspect(file);
            var stacks = JfrStacks.aggregate(report.stacks(), JfrStacks.Kind.JAVA, null);
            if (report.stacks().samples().stream().noneMatch(s -> s.kind() == JfrStacks.Kind.JAVA && s.leafFirst().stream().anyMatch(f -> f.method().equals("cpuPulse"))))
                throw new IllegalStateException("No execution sample captured the bounded CPU fixture; rerun and inspect evidence.");
            Files.writeString(output.resolve("inventory.txt"), report.text());
            Files.writeString(output.resolve("stacks.txt"), stacks.text());
            Files.writeString(output.resolve("evidence.txt"), "Owned authenticated loopback fixture; operator role.\nTarget: "
                    + client.identity() + "\nRecording: " + start.id() + "\nRequested duration: 8 s; profile; bounded 20% duty CPU pulse\nReported start: "
                    + state.startTime() + "\nReported stop: " + state.stopTime() + "\nDownloaded bytes: " + Files.size(file)
                    + "\nTarget state: " + state.state() + "\nSource: FlightRecorderMXBean; file is NOT redacted.\n");
            if (client.jfr().release().id() != 0) throw new IllegalStateException("Owned recording not released.");
        }
        System.out.println("JFR_CAPTURE_PASS\nRecording released; owned fixture closed.\n" + output.toAbsolutePath());
    }
}
