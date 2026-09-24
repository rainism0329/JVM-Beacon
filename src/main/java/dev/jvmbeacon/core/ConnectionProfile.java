package dev.jvmbeacon.core;

import java.util.Objects;
import java.util.UUID;

/** Saved remote connection metadata only. Secrets and local PIDs never belong in this model. */
public record ConnectionProfile(String id, String alias, String group, String address, String username,
                                boolean tlsRegistry, long lastUsed, JmxClient.Identity lastIdentity) {
    public ConnectionProfile {
        if (id == null) throw new IllegalArgumentException("Missing connection ID.");
        UUID.fromString(id);
        alias = text(alias, 80, "Alias"); group = text(group, 80, "Group");
        username = text(username, 256, "Username");
        address = RemoteEndpoint.normalize(address);
        // Full JMX URLs can contain opaque serialized stubs. Only credential-free JNDI/RMI URLs are persisted.
        if (!address.matches("service:jmx:rmi://[^/]*?/jndi/rmi://[^/]+/.+")
                || address.indexOf('@') >= 0 || address.indexOf('?') >= 0 || address.indexOf('#') >= 0)
            throw new IllegalArgumentException("Saved connections require a JNDI/RMI URL without user info, query or fragment. Use a one-time connection for other URLs.");
        if (lastUsed < 0) throw new IllegalArgumentException("Invalid last-used timestamp.");
        if (lastIdentity != null) {
            lastIdentity = new JmxClient.Identity(text(lastIdentity.runtimeName(), 512, "Runtime name"), lastIdentity.startTime(),
                    text(lastIdentity.vmName(), 256, "VM name"), text(lastIdentity.vmVersion(), 256, "VM version"));
            if (lastIdentity.startTime() < 0) throw new IllegalArgumentException("Invalid JVM start time.");
        }
    }
    public String label() { return alias.isEmpty() ? address : alias; }
    public boolean sameEndpoint(ConnectionProfile other) {
        return other != null && address.equals(other.address) && username.equals(other.username) && tlsRegistry == other.tlsRegistry;
    }
    public static String text(String value, int limit, String label) {
        String result = Objects.requireNonNullElse(value, "").strip();
        if (result.length() > limit || result.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + " must be at most " + limit + " characters, without control characters.");
        return result;
    }
}
