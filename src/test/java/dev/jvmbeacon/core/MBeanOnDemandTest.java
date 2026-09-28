package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MBeanOnDemandTest {
    private static final String BEAN = DemoApplication.ON_DEMAND_NAME;

    @Test void metadataAndOperationsNeverReadGettersAndWriteReadBackTouchesOnlyOneAttribute() throws Exception {
        try (var fixture = new FixtureProcess(true); var client = fixture.remote("operator")) {
            var metadata = MBeanMetadata.read(client, BEAN);
            assertEquals(4, metadata.attributes().size());
            assertEquals("pong; no getter was needed", invoke(client, metadata, "ping"));
            assertEquals("Fast=0, Slow=0, NullValue=0", invoke(client, metadata, "readCounts"));
            assertEquals("7", client.readAttribute(BEAN, "Fast").text());
            client.setAttribute(BEAN, "Fast", "int", "42");
            assertEquals("42", client.readAttribute(BEAN, "Fast").text());
            assertEquals("Fast=2, Slow=0, NullValue=0", invoke(client, metadata, "readCounts"));
            var writeOnly = metadata.attributes().stream().filter(a -> a.name().equals("WriteOnly")).findFirst().orElseThrow();
            assertTrue(writeOnly.writable()); assertFalse(writeOnly.readable());
            client.setAttribute(BEAN, "WriteOnly", "java.lang.String", "valid");
            assertEquals("Fast=2, Slow=0, NullValue=0", invoke(client, metadata, "readCounts"));
            var slow = client.readAttribute(BEAN, "Slow");
            assertNull(slow.error()); assertTrue(slow.text().contains("Bounded slow getter completed"));
            assertTrue(slow.end() >= slow.start());
            assertEquals("Fast=2, Slow=1, NullValue=0", invoke(client, metadata, "readCounts"));
        }
    }

    @Test void nullFailuresAndComplexValuesRemainDistinctWithoutBreakingConnection() throws Exception {
        try (var fixture = new FixtureProcess(true); var client = fixture.remote("operator")) {
            var nullValue = client.readAttribute(BEAN, "NullValue");
            assertNull(nullValue.error()); assertEquals("null", nullValue.text()); assertNotNull(nullValue.structure());
            for (String name : List.of("Forbidden", "IoFailureNumber", "TlsFailureNumber", "ErrorFailureNumber")) {
                var failed = client.readAttribute(DemoApplication.BEAN_NAME, name);
                assertNotNull(failed.error()); assertNull(failed.structure()); assertTrue(failed.error().contains("TARGET"));
            }
            for (String name : List.of("Rows", "Summary")) {
                var value = client.readAttribute(DemoApplication.BEAN_NAME, name);
                assertNull(value.error()); assertNotNull(value.structure()); assertFalse(value.text().isBlank());
            }
            assertEquals("7", client.readAttribute(BEAN, "Fast").text());
        }
    }

    @Test void serverReadOnlyPermissionStillAllowsMetadataAndSingleReadButDeniesMutation() throws Exception {
        try (var fixture = new FixtureProcess(true); var client = fixture.remote("observer")) {
            assertFalse(MBeanMetadata.read(client, BEAN).operations().isEmpty());
            assertEquals("7", client.readAttribute(BEAN, "Fast").text());
            assertThrows(SecurityException.class, () -> client.setAttribute(BEAN, "Fast", "int", "42"));
            assertEquals("7", client.readAttribute(BEAN, "Fast").text());
        }
    }
    private Object invoke(JmxClient client, MBeanMetadata metadata, String name) throws Exception {
        return client.invoke(BEAN, metadata.operations().stream().filter(op -> op.getName().equals(name)).findFirst().orElseThrow(), List.of());
    }
}
