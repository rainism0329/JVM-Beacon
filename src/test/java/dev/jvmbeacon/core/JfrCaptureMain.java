package dev.jvmbeacon.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Real authenticated loopback recording. Never attaches to an existing application. */
public final class JfrCaptureMain {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        try (FixtureProcess fixture = new FixtureProcess(true); JmxClient client = fixture.remote("operator")) {
            var start = client.jfr().start("default", 5);
            long deadline = System.nanoTime() + 15_000_000_000L;
            JfrCapture.State state;
            do { Thread.sleep(250); state = client.jfr().inspect(); }
            while (!"STOPPED".equals(state.state()) && System.nanoTime() < deadline);
            if (!"STOPPED".equals(state.state())) throw new IllegalStateException("Target did not report STOPPED within the test deadline.");
            Path file = client.jfr().download(output.resolve("capture.jfr"));
            Files.writeString(output.resolve("inventory.txt"), JfrSummary.read(file));
            Files.writeString(output.resolve("evidence.txt"), "Owned authenticated loopback fixture; operator role.\nTarget: "
                    + client.identity() + "\nRecording: " + start.id() + "\nRequested duration: 5 s\nReported start: "
                    + state.startTime() + "\nReported stop: " + state.stopTime() + "\nDownloaded bytes: " + Files.size(file)
                    + "\nTarget state: " + state.state() + "\nSource: FlightRecorderMXBean; file is NOT redacted.\n");
            if (client.jfr().release().id() != 0) throw new IllegalStateException("Owned recording not released.");
        }
        System.out.println("JFR_CAPTURE_PASS\nRecording released; owned fixture closed.\n" + output.toAbsolutePath());
    }
}
