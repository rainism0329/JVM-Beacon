package dev.jvmbeacon.ui;

import com.intellij.util.xmlb.XmlSerializer;
import dev.jvmbeacon.core.ConnectionIdentity;
import dev.jvmbeacon.core.JmxClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionWorkspaceTest {
    private static final JmxClient.Identity ID = new JmxClient.Identity("21@fixture", 1000, "VM", "21");

    @Test void xmlRoundTripRetainsSetupAndIdentityWithoutSecretFields() {
        var workspace = new ConnectionWorkspace();
        var p = workspace.save(null, "Payments", "Development", "localhost:09010", "observer", true);
        assertTrue(workspace.recordSuccess(p, ID, 2000));
        var xml = XmlSerializer.serialize(workspace.getState());
        var restored = new ConnectionWorkspace(); restored.loadState(XmlSerializer.deserialize(xml, ConnectionWorkspace.State.class));
        assertEquals(workspace.all(), restored.all());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:9010/jmxrmi", restored.all().getFirst().address());
        assertTrue(java.util.Arrays.stream(ConnectionWorkspace.Entry.class.getFields()).noneMatch(f -> f.getName().toLowerCase().matches(".*(password|secret|token).*")));
        var state = workspace.getState(); state.connections.getFirst().alias = "external edit"; state.connections.clear();
        assertEquals("Payments", workspace.find(p.id()).alias());
    }

    @Test void concurrentTabsDoNotEraseUnrelatedEntriesOrResurrectForgottenSetup() {
        var workspace = new ConnectionWorkspace();
        var a = workspace.save(null, "A", "", "localhost:9010", "one", false);
        var b = workspace.save(null, "B", "", "localhost:9011", "two", true);
        workspace.save(a.id(), "Renamed", "New group", a.address(), a.username(), false);
        assertTrue(workspace.recordSuccess(a, ID, 2000));
        assertEquals("Renamed", workspace.find(a.id()).alias()); assertNotNull(workspace.find(b.id()));
        workspace.forget(a.id());
        assertFalse(workspace.recordSuccess(a, ID, 3000));
        assertThrows(IllegalArgumentException.class, () -> workspace.save(a.id(), "A", "", a.address(), "one", false));
        assertEquals(1, workspace.all().size());
    }

    @Test void editedEndpointTlsOrUsernameRejectsLateIdentityAndClearsPreviousIdentity() {
        var workspace = new ConnectionWorkspace();
        var a = workspace.save(null, "A", "", "localhost:9010", "observer", true);
        workspace.recordSuccess(a, ID, 100);
        var renamed = workspace.save(a.id(), "B", "", a.address(), a.username(), true);
        assertEquals(ID, renamed.lastIdentity());
        var changed = workspace.save(a.id(), "B", "", a.address(), a.username(), false);
        assertNull(changed.lastIdentity()); assertEquals(0, changed.lastUsed());
        assertFalse(workspace.recordSuccess(a, ID, 200));
        var user = workspace.save(a.id(), "B", "", a.address(), "operator", false);
        assertFalse(workspace.recordSuccess(changed, ID, 300));
        workspace.save(a.id(), "B", "", "localhost:9020", "operator", false);
        assertFalse(workspace.recordSuccess(user, ID, 400));
    }

    @Test void limitsSavedAndRecentEntriesAndDiscardsMalformedState() {
        var workspace = new ConnectionWorkspace();
        for (int i = 0; i < 40; i++) {
            var p = workspace.save(null, "P" + i, "", "localhost:" + (9000 + i), "", true);
            if (i < 30) workspace.recordSuccess(p, ID, i + 1);
        }
        assertThrows(IllegalArgumentException.class, () -> workspace.save(null, "excess", "", "localhost:9050", "", true));
        assertEquals(10, workspace.recent().size()); assertEquals("P29", workspace.recent().getFirst().alias());
        var state = workspace.getState(); state.connections.getFirst().id = null;
        state.connections.get(1).address = "service:jmx:rmi:///stub/opaque";
        state.connections.add(state.connections.getLast());
        workspace.loadState(state); assertEquals(38, workspace.all().size());
    }

    @Test void metadataRejectsHiddenCredentialsControlCharactersAndOversizedLabels() {
        var workspace = new ConnectionWorkspace();
        for (String address : new String[]{"service:jmx:rmi:///jndi/rmi://user:secret@localhost:9010/jmxrmi", "service:jmx:rmi:///jndi/rmi://localhost:9010/jmxrmi?token=x", "service:jmx:rmi:///stub/opaque"})
            assertThrows(IllegalArgumentException.class, () -> workspace.save(null, "A", "", address, "", true));
        assertThrows(IllegalArgumentException.class, () -> workspace.save(null, "x".repeat(81), "", "localhost:9010", "", true));
        assertThrows(IllegalArgumentException.class, () -> workspace.save(null, "A", "g\nroup", "localhost:9010", "", true));
        assertTrue(workspace.all().isEmpty());
    }

    @Test void incompleteIdentityNeverCountsAsSameJvmAndChangesIncludeVmVersion() {
        assertEquals(ConnectionIdentity.Match.FIRST, ConnectionIdentity.compare(null, ID));
        assertEquals(ConnectionIdentity.Match.INCOMPLETE, ConnectionIdentity.compare(null, null));
        assertEquals(ConnectionIdentity.Match.INCOMPLETE, ConnectionIdentity.compare(null, new JmxClient.Identity("—", 1000, "VM", "21")));
        assertEquals(ConnectionIdentity.Match.SAME_REPORTED_IDENTITY, ConnectionIdentity.compare(ID, ID));
        assertEquals(ConnectionIdentity.Match.CHANGED, ConnectionIdentity.compare(ID, new JmxClient.Identity("21@fixture", 1001, "VM", "21")));
        assertEquals(ConnectionIdentity.Match.CHANGED, ConnectionIdentity.compare(ID, new JmxClient.Identity("21@fixture", 1000, "VM", "22")));
        assertEquals(ConnectionIdentity.Match.INCOMPLETE, ConnectionIdentity.compare(ID, new JmxClient.Identity("—", 1000, "VM", "21")));
        assertEquals(ConnectionIdentity.Match.INCOMPLETE, ConnectionIdentity.compare(ID, null));
    }
}
