# JVM Beacon · User guide

JVM Beacon is an English-language JMX and JVM diagnostics workbench inside IntelliJ IDEA. Core features run locally without a cloud account or an AI service.

## Install

The current release candidate is **1.0.0-rc.2**. The initial evaluated environment is **IntelliJ IDEA Community 2025.1.3 (251.26927.53), its complete JBR 21, and Windows x64**. Ultimate of the same build passes API verification; GUI acceptance remains pending. Build with a complete JDK 21. Target JVM validation uses Corretto 21.0.9; other JDKs, operating systems and remote-development frontends are not certified by this release. See the exact [acceptance scope](release-candidate.md), which records each candidate separately.

1. In **Settings → Plugins → gear → Install Plugin from Disk…**, select `jvm-beacon-1.0.0-rc.2.zip`. Keep the ZIP intact. Restart IDEA when prompted.
2. Open a project, then **View → Tool Windows → JVM Beacon**.
3. Choose **Connect JVM…** for a live target, or **Open snapshot…** for a saved `.jvmb` file. JFR files open from the **Flight Recorder** page.

Update using the same installation steps. To roll back, uninstall the plugin, restart, and install the previous ZIP. Keep copies of diagnostic files: older releases may not read newer formats. Uninstalling does not promise to erase existing PasswordSafe entries, IDE settings or files you saved.

## First connection

**Local:** select a process or enter its PID. Only connect to an application you are authorized to inspect. Visibility does not guarantee Attach permission. If no management endpoint exists, **Allow starting the local management agent if needed** explicitly permits changing that target's management state. A restarted process requires its new PID.

**Remote:** enter `server.example:9010`, `[::1]:9010`, or a full `service:jmx:rmi:` URL. Supply credentials when required. **Use TLS for the RMI registry** must match the server configuration; connector TLS is also determined by the server's RMI stub. There is no automatic downgrade. Trust is provided by the IDE runtime's trust configuration; the plugin has no certificate-import wizard. Registry and advertised connector host/port must both be reachable. See [TLS boundaries](tls-validation.md).

**Saved connections** stores local, non-roaming endpoint metadata, aliases and groups. **Remember credentials after connecting** stores the password in IDEA PasswordSafe only after a successful connection. Forgetting a setup does not delete its PasswordSafe entry. Reconnect is explicit: it starts fresh history, resets read-only mode, pauses polling and does not replay mutations or subscriptions.

Use the tool-window **+** or **Alt+Insert** to add another independent tab (up to eight per project). Only the visible live tab polls automatically. Closing a tab releases its session; closing the last tab leaves a fresh disconnected page.

## Everyday workflow

| Task | Steps and expected behavior |
|---|---|
| See a live trend | **Telemetry → Start live trend** or **Auto · 2 s**. The initial connection takes one sample; selecting a metric alone does not start sampling. Use JVM uptime to see a changing signal on an idle application. |
| Read an MBean | Search **MBeans**, select the object, then select an attribute and use **Read value** or **Enter**. Browsing loads definitions, not every getter. Each value has its own read window. |
| Read complex output | After reading a value, use **Explore…**. Browse/search the captured structure; supported tabular values have a Rows view. This does not call the getter again. |
| Change an attribute | Turn off **Read-only**, choose **Edit…**, enter a supported value and confirm the exact target. A successful write reads back only that attribute; write-only attributes are not read back. |
| Trigger an exposed method | Select **Operations**, find the exact signature, enter supported parameters, invoke and confirm. Return values can be explored. No automatic retry occurs, including after timeout. |
| Receive notifications | Select an MBean and subscribe on **Notifications**. The bounded buffer keeps at most 200 entries. Unsubscribe or disconnect to remove the listener. |
| Investigate threads | **Threads → Capture threads**, then filter the existing snapshot. Use **Lock chains**, **Pin baseline A / Compare A → current B**, or **Hot threads → Measure CPU · 1 s**. Supported source candidates are checked before navigation. |
| Preserve evidence | Add notes in **Snapshots**, then **Save snapshot…**. Use **Open snapshot…** in another tab and **Compare with file…**. **Timeline → Freeze & select → Save interval…** saves only the selected metric interval. |
| Analyze JFR | On **Flight Recorder**, use **Check / refresh**, then explicitly start a bounded recording. Refresh its state, download after it stops, or open a local `.jfr`. Inspect sampled stacks/call tree, GC/allocation samples and waits. Apply a shared UTC time range to all views or focus a selected event. |

Supported input includes primitive/boxed scalar values, String, BigInteger, BigDecimal, ObjectName, and supported one-dimensional primitive/String arrays. Array input uses JSON syntax. Null input, arbitrary Java objects, nested arrays and custom target types are not constructed. Unsupported inputs explain their limits; target-side constraints can still reject valid client input.

## Understand the evidence

- **LIVE** means connected. Read the sample timestamps, windows and freshness separately. A flat line can be a real unchanged value. Gaps, missing values and invalid timestamps are not drawn as zero or bridged as continuous observations.
- ThreadMXBean snapshots and Hot threads cover **platform threads**, not all virtual threads. Two similar stacks do not prove a thread stayed blocked; a lock owner may be missing from the capture. CPU percentages are relative to one core, and the last stack is not method-level CPU attribution.
- JFR views describe recorded, retained events. Event counts are not CPU percentages. Allocation samples/weights are not exact object or live-heap counts. Concurrent wait durations can overlap. GC cycles are separate from top-level pauses. Coverage and omitted-data counters remain visible.
- `.jvmb` v3 preserves up to 120 metric samples, optional platform-thread data, reported identity and notes (5 MiB file limit). It reads v1/v2 as well. It does **not** save arbitrary MBean values, operation results, notifications, Watch, Hot threads or JFR analysis state. Save `.jfr` separately. Missing history cannot be reconstructed.
- Local JFR analysis accepts up to 64 MiB, scans at most 200,000 events with a five-second cooperative budget, and has separate retained-data limits. A JDK parser call is not hard-isolated. Use trusted targets/files; a bigger recording may require JDK Mission Control or another specialist tool.

## When something fails

| Signal | Next step |
|---|---|
| No process / ATTACH / AGENT | Check the current PID, same-user permissions, target Attach settings and management-agent consent. Containers and other users can require a different connection path. |
| CONNECTION / IO | Check whether the target exited, DNS, registry port, advertised RMI connector host/port and routing. A reachable registry alone is insufficient. |
| PERMISSION | Check credentials and server roles. The client read-only switch cannot grant server permission. |
| TLS | Check the registry checkbox and the IDE JBR's trusted certificate chain. Do not disable verification just to bypass the error. |
| TIMEOUT | The plugin stopped waiting; the underlying call may still run. Inspect the target before repeating a write or operation. Connect waits up to 20 s; ordinary calls 8 s. JFR transfer has a separate bounded deadline. |
| CAPACITY | Four network or two local workers, or the 16 opening/active/closing connection permits, are occupied. Polling pauses without pretending the session is broken. Wait for cleanup and resume manually. Repeated clicks do not create an unbounded queue. |
| STALE | The connection ended; displayed captures are older evidence. The reason remains visible. Restarted fixtures need a new PID. |
| FILE / invalid capture | Check format, permissions, free space and file limits. JFR download never overwrites an existing file. A failed range rescan retains the previous applied analysis. |

**Disconnect / Stop waiting** remains useful when a request hangs. Cancellation cannot force every RMI/native call to terminate. Calls and cleanup remain globally bounded; an IDE restart is the final recovery when an underlying call never returns.

## Data and reporting problems

There is no plugin telemetry or automatic upload. Passwords use PasswordSafe; endpoint metadata is local IDE configuration. Exported notes, thread names/stacks and JFR content can contain application information and are **not automatically redacted**. Review files before sharing. Mutable password copies owned by the plugin are cleared on completion/discard; Java/Swing/JMX internals can hold copies that cannot be guaranteed erased.

For a useful bug report, include plugin version, IDEA build, IDE JBR and target JDK, OS, the failed step, the error category and whether Auto was enabled. Remove credentials, private endpoints and sensitive target content. Do not attach production recordings by default. Full reproducible fixture instructions: [testing guide](testing.md). Release evidence and remaining limitations: [candidate acceptance](release-candidate.md).
