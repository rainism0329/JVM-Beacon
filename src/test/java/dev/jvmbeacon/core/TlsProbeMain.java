package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;
import javax.net.ssl.SSLException;
import java.util.Arrays;
import java.util.List;

/** One test case per owned JVM so JSSE defaults never alter the IDE or the JUnit process. */
public final class TlsProbeMain {
    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0], url = arguments[1];
        String storePassword = requiredEnvironment("BEACON_TEST_STORE_PASSWORD");
        System.setProperty("javax.net.ssl.trustStore", requiredEnvironment("BEACON_TEST_TRUSTSTORE"));
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.trustStorePassword", storePassword);
        String user = mode.equals("observer") ? "observer" : mode.equals("missing-auth") ? "" : "operator";
        char[] password = mode.equals("missing-auth") ? new char[0]
                : mode.equals("wrong-auth") ? "intentionally-wrong".toCharArray()
                : requiredEnvironment("BEACON_TEST_LOGIN_PASSWORD").toCharArray();
        boolean expectedFailure = List.of("untrusted", "wrong-auth", "missing-auth", "plaintext-registry").contains(mode);
        try (JmxClient client = JmxClient.connectRemote(url, user, password, !mode.equals("plaintext-registry"))) {
            if (expectedFailure) throw new AssertionError("Connection unexpectedly succeeded: " + mode);
            if (client.sample().metrics().stream().noneMatch(JmxClient.Metric::available)) throw new AssertionError("No real metrics available over TLS.");
            var add = Arrays.stream(client.info(DemoApplication.BEAN_NAME).getOperations()).filter(operation -> operation.getName().equals("add")).findFirst().orElseThrow();
            if (mode.equals("observer")) {
                boolean writeDenied = false, invokeDenied = false;
                try { client.setAttribute(DemoApplication.BEAN_NAME, "Counter", "int", "99"); } catch (SecurityException expected) { writeDenied = true; }
                try { client.invoke(DemoApplication.BEAN_NAME, add, List.of("1", "2")); } catch (SecurityException expected) { invokeDenied = true; }
                if (!writeDenied || !invokeDenied) throw new AssertionError("Observer permission boundary failed.");
            } else {
                client.setAttribute(DemoApplication.BEAN_NAME, "Counter", "int", "22");
                Object actual = client.readAttributes(DemoApplication.BEAN_NAME).stream().filter(attribute -> attribute.name().equals("Counter")).findFirst().orElseThrow().value();
                if (!Integer.valueOf(22).equals(actual)) throw new AssertionError("TLS write/read round trip failed.");
                if (!Integer.valueOf(3).equals(client.invoke(DemoApplication.BEAN_NAME, add, List.of("1", "2")))) throw new AssertionError("TLS operation failed.");
            }
        } catch (JmxClient.ConnectionException failure) {
            if (!expectedFailure) throw failure;
            if (mode.equals("untrusted") && !hasCause(failure, SSLException.class)) throw new AssertionError("Untrusted case failed outside TLS validation.", failure);
            if ((mode.equals("wrong-auth") || mode.equals("missing-auth")) && !hasCause(failure, SecurityException.class)) throw new AssertionError("Authentication case failed before authorization could be checked.", failure);
            if (mode.equals("untrusted") && !failure.getMessage().contains("TLS handshake failed")) throw new AssertionError("TLS failure does not have an actionable classification.");
        } finally { Arrays.fill(password, '\0'); }
        System.out.println("TLS_CASE_PASS " + mode);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (int depth = 0; failure != null && depth < 20; depth++, failure = failure.getCause()) if (type.isInstance(failure)) return true;
        return false;
    }
    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) throw new IllegalStateException("Missing test environment: " + key);
        return value;
    }
}
