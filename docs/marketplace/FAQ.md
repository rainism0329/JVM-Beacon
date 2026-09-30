# JVM Beacon FAQ

## What is JVM Beacon for?

JVM Beacon is a JMX and JFR diagnostics workbench inside IntelliJ IDEA. Connect to a running JVM, inspect metrics and MBeans, investigate platform threads, save a capture, or read a JFR recording alongside your project. Its interface uses the IDE theme and English labels.

## Can I connect to a remote JVM with hostname:port?

Yes. Use `server.example:9010`, bracketed IPv6 such as `[::1]:9010`, or a full `service:jmx:rmi:` URL. The shorthand is normalized locally into a JMX/RMI URL. It does not enable JMX on the server, discover credentials, tunnel through SSH, or guess a different connector endpoint.

The server's registry port and advertised connector host/port must both be reachable. RMI often makes the second connection to an address returned by the server. A reachable registry alone is not enough. Check DNS, routing and firewall rules for both stages.

## Does remote JMX support authentication and TLS?

Authentication is supported when the server requires it. TLS must match the server's registry and connector configuration. **Use TLS for the RMI registry** controls the registry lookup; the server's RMI stub also determines connector TLS. The plugin does not automatically downgrade a secure connection.

Certificate trust comes from the IDE runtime's trust configuration. There is no certificate-import wizard. Local integration tests cover controlled TLS targets; this does not certify every enterprise certificate, mTLS setup, network or JDK combination.

## Why is my local Java process missing or refusing to connect?

Local visibility and Attach permissions depend on the operating system, user identity and target JVM configuration. Try the current PID and check whether the process runs as your user. Disabled Attach, containers and restricted targets can require a different access path.

If the target has no local management endpoint, you must explicitly allow starting its local management agent. This changes the target's management state. Restarting the application usually means a new PID; the previous PID is not a persistent identity.

## Can I trigger a method exposed by an MBean?

Yes. Turn off **Read-only**, select the MBean's **Operations** page, choose the exact signature, enter supported arguments, and confirm the target before invoking. Supported return structures can be explored. Overloaded methods are selected by their signatures.

Scalar inputs include primitive/boxed values, String, BigInteger, BigDecimal and ObjectName. Supported one-dimensional primitive/String arrays use JSON input. Arbitrary Java objects, nested arrays, null inputs and custom target types are not constructed. Client validation cannot replace the target's own rules or permissions.

An operation can run target code and have side effects. If it times out, the plugin stopped waiting; the target may still execute it. Mutations are not retried automatically. Check the target before repeating them.

## Why does selecting an MBean not immediately show every value?

Selecting an object loads its definitions. Choose an attribute and click **Read value**, or press Enter, to call that getter. This keeps browsing deliberate when getters are expensive, restricted or have side effects.

The value records its own read window. **Explore…**, expansion and search use the captured immutable result rather than calling the getter again. Results have size and depth limits, with truncation made visible. Reading an attribute is not guaranteed to be free of overhead or side effects.

## Why is the trend flat, paused or STALE?

The initial connection takes one sample. Start **Telemetry → Start live trend** or enable **Auto · 2 s** for continued sampling. Changing the Trend metric only changes the display. Only the visible live connection tab automatically polls, and sampling waits while another request or confirmation is active.

If timestamps advance but the line is flat, the reported value may be unchanged. JVM uptime is useful for checking updates on an idle application. Missing values, longer sampling gaps and invalid timestamps are not drawn as zero or continuous observations.

**STALE** means the connection ended and the displayed captures are older evidence. Read the retained reason. **CAPACITY** means the bounded worker/connection capacity is occupied; an existing session is preserved and automatic sampling pauses. Wait for cleanup, then resume manually.

## Can I keep several connections open?

Yes. Use the tool-window **+** or **Alt+Insert** to add an independent tab, up to eight per project. A tab can hold a live connection or an offline capture. Switching tabs changes which live page automatically polls. Closing a tab disposes its session; closing the last tab leaves a new disconnected page.

Remote setup metadata can be saved with aliases and groups. Reconnect is explicit: it resets history and read-only mode, pauses sampling, and does not replay operations, writes or notification subscriptions. The plugin does not automatically connect at IDE startup.

## Does the thread view include virtual threads?

ThreadMXBean snapshots and Hot threads cover platform threads. They must not be treated as an inventory of every virtual thread. JFR event availability varies by JDK and recording settings; ordinary virtual-thread parks may not appear as ThreadPark events.

Lock chains link reported owner IDs in the captured data. A missing owner does not establish that a thread exited. Two matching stacks do not prove continuous blocking between captures. Hot-thread percentages are relative to one core, and the final stack cannot identify which method consumed the measured CPU time.

## What do the JFR flame graph, allocation and wait views mean?

The flame graph and call tree aggregate retained ExecutionSample or NativeMethodSample stack samples separately. Width represents sample count, not elapsed time or a measured percentage of CPU. Source navigation checks available class, method and line candidates; it does not certify matching application versions or class loaders.

Allocation views aggregate recorded ObjectAllocationSample weights when the fields and units are supported. They do not measure exact object counts or the live heap. GC cycles and top-level pauses are displayed separately. Wait analysis covers recorded monitor-entry, Object.wait and park events; overlapping thread durations can sum to more than wall-clock time, and a park does not automatically mean lock contention.

A shared UTC interval can be applied across the analysis views. Each apply rescans the original file within the scan budget. Retained-event and omitted-data counters remain visible. Large or partial recordings may need a specialist analysis workflow.

## What is saved in a .jvmb capture?

The v3 format saves reported target identity, notes, up to 120 retained metric samples, and an optional separately collected platform-thread snapshot. A saved Timeline interval contains metrics, identity and notes only. Files are limited to 5 MiB, and v1/v2 captures can be reopened with their original scope.

Arbitrary MBean attribute values, operation results, notifications, Watch history, Hot threads and JFR analysis state are not included. Save `.jfr` files separately. Missing or previously discarded history cannot be reconstructed. Comparisons are observations from two collection windows, not proof of everything that happened between them.

## Does the plugin upload data or require an account?

Core features need no cloud account or external AI service. JVM Beacon has no telemetry or automatic upload. Saved passwords use IDEA PasswordSafe. Remote setup metadata is local, non-roaming IDE configuration and can contain endpoints, usernames, aliases and groups.

Captures, notes, thread names/stacks and JFR recordings can contain application information. They are not automatically redacted. Review exports before sharing them. Removing a saved setup does not delete its PasswordSafe entry, and uninstalling does not promise to erase existing settings or user-saved files.

## Which versions have been evaluated?

The initial GUI acceptance environment is IntelliJ IDEA Community 2025.1.3 (251.26927.53), JBR 21, Windows x64 and a Corretto 21.0.9 target. Ultimate of the same build passed API verification and loading checks in the first candidate, with GUI acceptance pending. A declared compatibility range is not a statement that every IDE/JDK/OS combination was tested.

Local JFR analysis accepts files up to 64 MiB and scans at most 200,000 events with a five-second cooperative budget, plus separate retained-data limits. A single JDK parser call is not hard-isolated. Connect waiting is bounded to 20 seconds and ordinary requests to eight seconds; underlying RMI/native calls may continue after cancellation.

## What should I include in a bug report?

Include the plugin version, exact IDEA build, IDE JBR and target JDK, operating system, the step that failed, error category, whether Auto was enabled, and a small reproduction using a controlled test JVM when possible. A screenshot of the relevant state can help.

Remove passwords, private endpoints and sensitive target content. Do not attach production captures or recordings by default. Verify that a timed-out mutation did not already execute before reproducing it.
