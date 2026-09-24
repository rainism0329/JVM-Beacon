package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteEndpointTest {
    @Test void expandsHostnameIpv4AndBracketedIpv6WithoutResolution() {
        assertEquals("service:jmx:rmi:///jndi/rmi://example.invalid:9010/jmxrmi", RemoteEndpoint.normalize(" example.invalid:09010 "));
        assertEquals("service:jmx:rmi:///jndi/rmi://127.0.0.1:65535/jmxrmi", RemoteEndpoint.normalize("127.0.0.1:65535"));
        assertEquals("service:jmx:rmi:///jndi/rmi://[::1]:9010/jmxrmi", RemoteEndpoint.normalize("[::1]:9010"));
        assertEquals("service:jmx:rmi:///jndi/rmi://[fe80::1%eth0]:1/jmxrmi", RemoteEndpoint.normalize("[fe80::1%eth0]:1"));
    }
    @Test void fullUrlsKeepCustomBindingAndShareStandardShorthandKey() {
        String standard = "service:jmx:rmi:///jndi/rmi://server:9010/jmxrmi";
        assertEquals(RemoteEndpoint.normalize(standard), RemoteEndpoint.normalize("server:9010"));
        String custom = "service:jmx:rmi:///jndi/rmi://server:9010/custom-name";
        assertEquals(custom, RemoteEndpoint.normalize(custom));
    }
    @Test void rejectsAmbiguousAddressesPortBoundsAndInjections() {
        for (String input : new String[]{"", "host", "host:", ":90", "host:0", "host:65536", "host:-1", "host:+1", "host:1.2",
                "host:999999999999", "::1:9010", "[not-ip]:90", "[::1]90", "http://host:90", "user@host:90",
                "host:90/path", "host:90?x", "host:90#x", "host:90\nextra", "service:jmx:iiop://host", "x".repeat(4097)})
            assertThrows(IllegalArgumentException.class, () -> RemoteEndpoint.normalize(input), input);
    }
}
