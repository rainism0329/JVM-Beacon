package dev.jvmbeacon.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import dev.jvmbeacon.core.ConnectionProfile;
import dev.jvmbeacon.core.JmxClient;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/** App-local metadata. IDE persistence handles disk I/O; callers only touch bounded in-memory state. */
@Service(Service.Level.APP)
@State(name = "JvmBeaconConnections", storages = @Storage(value = "jvmBeaconConnections.xml", roamingType = RoamingType.DISABLED))
public final class ConnectionWorkspace implements PersistentStateComponent<ConnectionWorkspace.State> {
    public static final int MAX_PROFILES = 40;
    private final Map<String, ConnectionProfile> profiles = new LinkedHashMap<>();
    public static ConnectionWorkspace getInstance() { return ApplicationManager.getApplication().getService(ConnectionWorkspace.class); }

    public static final class State { public List<Entry> connections = new ArrayList<>(); }
    public static final class Entry {
        public String id = "", alias = "", group = "", address = "", username = "";
        public boolean tlsRegistry = true;
        public long lastUsed;
        public String runtimeName = "", vmName = "", vmVersion = "";
        public long startTime;
    }
    public synchronized List<ConnectionProfile> all() { return List.copyOf(profiles.values()); }
    public synchronized List<ConnectionProfile> recent() {
        return profiles.values().stream().filter(p -> p.lastUsed() > 0)
                .sorted(Comparator.comparingLong(ConnectionProfile::lastUsed).reversed()).limit(10).toList();
    }
    public synchronized ConnectionProfile find(String id) { return profiles.get(id); }
    public synchronized ConnectionProfile save(String id, String alias, String group, String address, String user, boolean tls) {
        ConnectionProfile old = profiles.get(id);
        // A forgotten entry from another dialog must not silently be resurrected.
        if (id != null && old == null) throw new IllegalArgumentException("This saved connection was removed. Choose Save as new to keep a new copy.");
        if (old == null && profiles.size() >= MAX_PROFILES) throw new IllegalArgumentException("The 40-connection limit is reached. Forget an unused connection first.");
        ConnectionProfile next = new ConnectionProfile(old == null ? UUID.randomUUID().toString() : id, alias, group, address, user, tls, 0, null);
        if (next.sameEndpoint(old)) next = new ConnectionProfile(next.id(), next.alias(), next.group(), next.address(), next.username(), tls, old.lastUsed(), old.lastIdentity());
        profiles.put(next.id(), next); return next;
    }
    public synchronized boolean recordSuccess(ConnectionProfile attempted, JmxClient.Identity identity, long now) {
        ConnectionProfile current = profiles.get(attempted.id());
        // Endpoint edits/forget from another tab win over a late connection result; aliases stay current.
        if (current == null || !current.sameEndpoint(attempted)) return false;
        profiles.put(current.id(), new ConnectionProfile(current.id(), current.alias(), current.group(), current.address(), current.username(), current.tlsRegistry(), Math.max(0, now), identity));
        return true;
    }
    public synchronized void forget(String id) { profiles.remove(id); }
    @Override public synchronized @NotNull State getState() {
        State copy = new State();
        for (ConnectionProfile p : profiles.values()) {
            Entry e = new Entry(); e.id = p.id(); e.alias = p.alias(); e.group = p.group(); e.address = p.address(); e.username = p.username(); e.tlsRegistry = p.tlsRegistry(); e.lastUsed = p.lastUsed();
            if (p.lastIdentity() != null) { e.runtimeName = p.lastIdentity().runtimeName(); e.startTime = p.lastIdentity().startTime(); e.vmName = p.lastIdentity().vmName(); e.vmVersion = p.lastIdentity().vmVersion(); }
            copy.connections.add(e);
        }
        return copy;
    }
    @Override public synchronized void loadState(@NotNull State state) {
        profiles.clear();
        if (state.connections == null) return;
        for (Entry e : state.connections.stream().limit(MAX_PROFILES).toList()) {
            if (e == null) continue;
            try {
                JmxClient.Identity id = e.startTime > 0 ? new JmxClient.Identity(e.runtimeName, e.startTime, e.vmName, e.vmVersion) : null;
                ConnectionProfile p = new ConnectionProfile(e.id, e.alias, e.group, e.address, e.username, e.tlsRegistry, e.lastUsed, id);
                profiles.putIfAbsent(p.id(), p);
            } catch (IllegalArgumentException ignored) { /* Invalid settings are not connection candidates. Never log addresses or secrets. */ }
        }
    }
}
