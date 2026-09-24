package dev.jvmbeacon.core;

/** Identity evidence is not an authenticated process identifier or proof against PID reuse. */
public final class ConnectionIdentity {
    public enum Match { FIRST, SAME_REPORTED_IDENTITY, CHANGED, INCOMPLETE }
    private ConnectionIdentity() { }
    public static Match compare(JmxClient.Identity before, JmxClient.Identity after) {
        if (!complete(after)) return Match.INCOMPLETE;
        if (before == null) return Match.FIRST;
        if (!complete(before)) return Match.INCOMPLETE;
        return before.equals(after) ? Match.SAME_REPORTED_IDENTITY : Match.CHANGED;
    }
    private static boolean complete(JmxClient.Identity id) {
        return id != null && id.startTime() > 0 && present(id.runtimeName()) && present(id.vmName()) && present(id.vmVersion());
    }
    private static boolean present(String value) { return value != null && !value.isBlank() && !value.equals("—"); }
}
