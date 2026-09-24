package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** Every test target is a separately-owned process, with bounded startup and forced cleanup fallback. */
final class FixtureProcess implements AutoCloseable {
    final Process process;
    final String password = UUID.randomUUID().toString();
    final String url;
    final String pid;
    final BufferedWriter input;
    private final boolean interactive;
    private final ExecutorService readerThread = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "fixture-startup-reader"); thread.setDaemon(true); return thread; });

    FixtureProcess(boolean remote) throws Exception {
        this(remote, Map.of(), List.of());
    }

    FixtureProcess(boolean remote, Map<String, String> environment, List<String> extraArguments) throws Exception {
        interactive = extraArguments.contains("--interactive");
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = System.getProperty("beacon.fixture.classes");
        if (classes == null || classes.isBlank()) {
            var codeSource = DemoApplication.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) throw new IllegalStateException("Set beacon.fixture.classes to the compiled test class directory when the IDE classloader has no class directory.");
            classes = Path.of(codeSource.getLocation().toURI()).toString();
        }
        List<String> command = new ArrayList<>(List.of(java, "-Xms32m", "-Xmx96m", "-cp", classes, DemoApplication.class.getName(), remote ? "--remote" : "--local"));
        command.addAll(extraArguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        process = builder.start();
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Future<String[]> ready = readerThread.submit(() -> {
            String foundUrl = null, foundPid = null;
            for (int i = 0; i < 30; i++) {
                String line = output.readLine();
                if (line == null) throw new IOException("Fixture exited before ready.");
                if (line.startsWith("PASSWORD_REQUIRED")) { input.write(password); input.newLine(); input.flush(); }
                if (line.startsWith("JMX_URL=")) foundUrl = line.substring(8);
                if (line.startsWith("PID=")) foundPid = line.substring(4);
                if (line.startsWith("READY")) return new String[]{foundUrl, foundPid};
            }
            throw new IOException("Fixture did not report READY.");
        });
        try {
            String[] details = ready.get(15, TimeUnit.SECONDS);
            url = details[0]; pid = details[1];
        } catch (Exception e) { process.destroyForcibly(); readerThread.shutdownNow(); throw e; }
        readerThread.shutdown();
    }

    JmxClient remote(String user) throws Exception { return JmxClient.connectRemote(url, user, password.toCharArray(), false); }

    @Override public void close() throws Exception {
        try { if (interactive) input.write("quit"); input.newLine(); input.flush(); } catch (IOException ignored) { }
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Fixture could not be stopped."); }
        readerThread.shutdownNow();
    }
}
