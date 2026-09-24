package dev.jvmbeacon.core;

import javax.management.remote.JMXServiceURL;
import java.net.URI;

/** Parses connection input without DNS, network I/O, or changing transport security. */
public final class RemoteEndpoint {
    private RemoteEndpoint() { }

    public static String normalize(String input) {
        String value = input == null ? "" : input.strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter hostname:port or a complete JMX service URL.");
        if (value.length() > 4096 || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalArgumentException("The address must be at most 4096 characters and contain no whitespace.");
        if (value.startsWith("service:jmx:")) {
            try {
                JMXServiceURL url = new JMXServiceURL(value);
                if (!"rmi".equals(url.getProtocol())) throw new IllegalArgumentException("Only JMX/RMI service URLs are supported.");
                return url.toString();
            } catch (java.net.MalformedURLException e) {
                throw new IllegalArgumentException("Enter a valid service:jmx:rmi: URL.");
            }
        }
        String host, port;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 2 || end + 1 >= value.length() || value.charAt(end + 1) != ':')
                throw new IllegalArgumentException("Use [IPv6-address]:port, for example [::1]:9010.");
            host = value.substring(0, end + 1); port = value.substring(end + 2);
            try {
                // URI validates the literal locally; InetAddress would risk DNS on the UI thread.
                URI literal = new URI("rmi://" + host + ":1");
                if (literal.getHost() == null || !host.contains(":")) throw new IllegalArgumentException();
            } catch (Exception e) { throw new IllegalArgumentException("Enter a valid bracketed IPv6 address."); }
        } else {
            int colon = value.indexOf(':');
            if (colon < 1 || colon != value.lastIndexOf(':'))
                throw new IllegalArgumentException("Use hostname:port; enclose IPv6 addresses in brackets.");
            host = value.substring(0, colon); port = value.substring(colon + 1);
            if (host.length() > 253 || !host.matches("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9.])?"))
                throw new IllegalArgumentException("Enter a hostname or IP address without a scheme, path, or credentials.");
        }
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
        int number = Integer.parseInt(port);
        if (number < 1 || number > 65535) throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
        return "service:jmx:rmi:///jndi/rmi://" + host + ":" + number + "/jmxrmi";
    }
}
