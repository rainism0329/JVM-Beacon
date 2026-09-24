package dev.jvmbeacon.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Generates ephemeral certificates under build/, starts only loopback peers, deletes material in finally. */
public final class TlsSmokeMain {
    private static final List<String> CASES = List.of("trusted", "observer", "wrong-auth", "missing-auth", "untrusted", "plaintext-registry");

    public static void main(String[] args) throws Exception {
        System.out.println("TLS environment: " + System.getProperty("os.name") + "; " + System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        for (String result : runAll()) System.out.println(result);
        System.out.println("TLS_SMOKE_PASS");
    }

    public static List<String> runAll() throws Exception {
        Path base = Path.of("build", "tls-validation").toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path temporary = Files.createTempDirectory(base, "run-");
        String storePassword = UUID.randomUUID().toString();
        String originalTrust = System.getProperty("javax.net.ssl.trustStore");
        List<String> results = new ArrayList<>();
        try {
            Path keyStore = temporary.resolve("server.p12"), certificate = temporary.resolve("server.cer"), trusted = temporary.resolve("trusted.p12"), untrusted = temporary.resolve("untrusted.p12");
            Map<String, String> generationEnvironment = Map.of("BEACON_TEST_STORE_PASSWORD", storePassword);
            keytool(temporary, generationEnvironment, "-genkeypair", "-alias", "beacon-fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                    "-dname", "CN=localhost,OU=Ephemeral JVM Beacon Test", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                    "-storepass:env", "BEACON_TEST_STORE_PASSWORD", "-keypass:env", "BEACON_TEST_STORE_PASSWORD");
            keytool(temporary, generationEnvironment, "-exportcert", "-alias", "beacon-fixture", "-keystore", keyStore.toString(), "-storepass:env", "BEACON_TEST_STORE_PASSWORD", "-file", certificate.toString());
            keytool(temporary, generationEnvironment, "-importcert", "-noprompt", "-alias", "beacon-fixture", "-file", certificate.toString(), "-keystore", trusted.toString(), "-storetype", "PKCS12", "-storepass:env", "BEACON_TEST_STORE_PASSWORD");
            KeyStore empty = KeyStore.getInstance("PKCS12");
            empty.load(null, storePassword.toCharArray());
            try (var output = Files.newOutputStream(untrusted)) { empty.store(output, storePassword.toCharArray()); }
            Map<String, String> serverEnvironment = Map.of("BEACON_TEST_KEYSTORE", keyStore.toString(), "BEACON_TEST_TRUSTSTORE", trusted.toString(), "BEACON_TEST_STORE_PASSWORD", storePassword);
            try (FixtureProcess fixture = new FixtureProcess(true, serverEnvironment, List.of("--tls"))) {
                for (String mode : CASES) {
                    Map<String, String> clientEnvironment = Map.of("BEACON_TEST_TRUSTSTORE", (mode.equals("untrusted") ? untrusted : trusted).toString(),
                            "BEACON_TEST_STORE_PASSWORD", storePassword, "BEACON_TEST_LOGIN_PASSWORD", fixture.password);
                    String output = run(temporary, List.of(executable("java"), "-Xms24m", "-Xmx96m", "-cp", probeClasspath(), TlsProbeMain.class.getName(), mode, fixture.url), clientEnvironment, 20);
                    if (!output.contains("TLS_CASE_PASS " + mode)) throw new AssertionError("TLS child did not confirm " + mode + ": " + output);
                    results.add("TLS_CASE_PASS " + mode);
                }
            }
            if (!Objects.equals(originalTrust, System.getProperty("javax.net.ssl.trustStore"))) throw new AssertionError("Parent trust configuration changed.");
            results.add("TLS_PARENT_TRUST_UNCHANGED");
            return List.copyOf(results);
        } finally {
            // All paths originate in this uniquely created directory; Files.walk does not follow symlinks.
            Path resolved = temporary.toAbsolutePath().normalize();
            if (!resolved.startsWith(base) || resolved.equals(base)) throw new IOException("Unsafe TLS test cleanup path.");
            try (var paths = Files.walk(resolved)) {
                for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            }
        }
    }

    private static String probeClasspath() throws Exception {
        String tests = System.getProperty("beacon.fixture.classes");
        if (tests == null || tests.isBlank()) tests = classDirectory(TlsProbeMain.class, "beacon.fixture.classes");
        String core = System.getProperty("beacon.core.classes");
        if (core == null || core.isBlank()) core = classDirectory(JmxClient.class, "beacon.core.classes");
        return tests + File.pathSeparator + core;
    }

    private static String classDirectory(Class<?> type, String property) throws Exception {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null)
            throw new IllegalStateException("Set " + property + " when the IDE classloader does not expose a class directory.");
        return Path.of(source.getLocation().toURI()).toString();
    }

    private static void keytool(Path temporary, Map<String, String> environment, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(executable("keytool")));
        command.addAll(Arrays.asList(arguments));
        run(temporary, command, environment, 30);
    }
    private static String executable(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
    }
    private static String run(Path temporary, List<String> command, Map<String, String> environment, int timeoutSeconds) throws Exception {
        Path log = Files.createTempFile(temporary, "child-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw new IOException("TLS test child exceeded " + timeoutSeconds + " seconds.");
            String output;
            try (var input = Files.newInputStream(log)) { output = new String(input.readNBytes(16_384), java.nio.charset.StandardCharsets.UTF_8); }
            if (process.exitValue() != 0) throw new IOException("TLS test child failed (exit " + process.exitValue() + "): " + output);
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }
}
