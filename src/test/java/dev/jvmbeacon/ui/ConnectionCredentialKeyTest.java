package dev.jvmbeacon.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionCredentialKeyTest {
    @Test void shorthandAndFullUrlUseSameCredentialScopeWithoutMergingUsersOrPorts() {
        var shorthand = ConnectionDialog.credentialKeyFor("localhost:09010", "observer");
        var full = ConnectionDialog.credentialKeyFor("service:jmx:rmi:///jndi/rmi://localhost:9010/jmxrmi", "observer");
        assertEquals(full, shorthand);
        assertNotEquals(shorthand, ConnectionDialog.credentialKeyFor("localhost:9011", "observer"));
        assertNotEquals(shorthand, ConnectionDialog.credentialKeyFor("localhost:9010", "operator"));
        assertThrows(IllegalArgumentException.class, () -> ConnectionDialog.credentialKeyFor("localhost:0", "observer"));
    }
}
