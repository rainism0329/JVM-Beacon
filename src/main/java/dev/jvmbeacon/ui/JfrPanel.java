package dev.jvmbeacon.ui;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.JfrCapture;
import dev.jvmbeacon.core.JfrSummary;
import dev.jvmbeacon.core.JfrStacks;
import dev.jvmbeacon.core.JmxClient;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.event.DocumentListener;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Explicit JFR workflow: no extra executor, polling timer or automatic recording. */
final class JfrPanel extends JPanel {
    interface Jobs {
        <T> void run(String label, boolean network, long deadline, Callable<T> work, Consumer<T> success, Consumer<String> failure);
    }
    private final Project project;
    private final Jobs jobs;
    private final Consumer<String> status;
    private final JfrStacksPanel stacks;
    private final JButton refresh = new JButton("Check / refresh");
    private final JButton record = new JButton("Record…");
    private final JButton stop = new JButton("Stop…");
    private final JButton download = new JButton("Download .jfr…");
    private final JButton release = new JButton("Release…");
    private final JButton open = new JButton("Open local .jfr…");
    private final JButton copyPath = new JButton("Copy file path");
    private final JButton copySummary = new JButton("Copy inventory");
    private final JTextArea stateText = BeaconUi.text("Connect a JVM, then check Flight Recorder support. No recording starts automatically.", 3);
    private final JTextArea inventory = BeaconUi.text("Download a stopped recording or open a local .jfr to inspect its event inventory.\nThen use Sampled stacks for a local flame graph and call tree; Coverage explains their limits.", 12);
    private final JLabel badge = BeaconUi.title("FLIGHT RECORDER");
    private final JToggleButton captureToggle = new JToggleButton("Show capture controls");
    private final JPanel captureControls = BeaconUi.panel(4);
    private final JLabel compactState = BeaconUi.label("Local analysis · No target connected", true);
    private final JBTabbedPane views = new JBTabbedPane();
    private final JLabel inventorySummary = BeaconUi.label("No local recording inspected", true);
    private final JBTextField search = new JBTextField();
    private final DefaultTableModel events = new DefaultTableModel(new String[]{"Event type", "Events inspected"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? String.class : Long.class; }
    };
    private final JBTable eventTable = new JBTable(events);
    private final TableRowSorter<DefaultTableModel> eventSorter = new TableRowSorter<>(events);
    private JmxClient client;
    private JfrCapture.State state;
    private boolean busy;
    private boolean readOnly = true;
    private boolean uncertain;
    private Path localFile;

    JfrPanel(Project project, com.intellij.openapi.Disposable owner, Jobs jobs, Consumer<String> status) {
        super(new BorderLayout(JBUI.scale(10), JBUI.scale(10)));
        this.project = project; this.jobs = jobs; this.status = status;
        stacks = new JfrStacksPanel(jobs, frame -> SourceNavigator.navigateJfr(project, owner, frame, status), status);
        setBorder(JBUI.Borders.empty(12, 16));
        JPanel top = BeaconUi.panel(8);
        badge.putClientProperty("beacon.mono", true); badge.setForeground(BeaconUi.ACCENT);
        top.add(BeaconUi.row(badge, captureToggle, compactState), BorderLayout.NORTH);
        captureControls.add(BeaconUi.row(refresh, record, stop, download, release), BorderLayout.NORTH);
        stateText.setLineWrap(true); stateText.setWrapStyleWord(true);
        captureControls.add(BeaconUi.scroll(stateText), BorderLayout.CENTER);
        captureControls.setVisible(false); top.add(captureControls, BorderLayout.CENTER); add(top, BorderLayout.NORTH);
        captureToggle.addActionListener(e -> expandCapture(captureToggle.isSelected()));
        captureToggle.setToolTipText("Show recording controls and the last reported target state. Collapsing does not stop a recording.");
        inventory.putClientProperty("beacon.mono", true);
        JPanel local = BeaconUi.panel(6);
        JPanel tools = BeaconUi.panel(6);
        search.setColumns(24);
        tools.add(BeaconUi.row(open, copyPath, copySummary, search), BorderLayout.NORTH);
        search.getEmptyText().setText("Filter captured event types…");
        search.getAccessibleContext().setAccessibleName("Filter captured JFR event types");
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() { eventSorter.setRowFilter(search.getText().isBlank() ? null
                    : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(search.getText()), 0));
                eventTable.getEmptyText().setText(events.getRowCount() == 0 ? "No event inventory loaded" : "No captured event types match this filter"); }
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        tools.add(inventorySummary, BorderLayout.SOUTH);
        local.add(tools, BorderLayout.NORTH);
        BeaconUi.table(eventTable, "Open a local recording to explore captured event types");
        eventTable.putClientProperty("beacon.mono", true);
        eventTable.setRowSorter(eventSorter);
        eventSorter.setSortKeys(java.util.List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
        eventTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(650));
        eventTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(150));
        local.add(BeaconUi.scroll(eventTable), BorderLayout.CENTER);
        views.addTab("Event inventory", local);
        views.addTab("Inventory details", BeaconUi.scroll(inventory));
        views.addTab("Sampled stacks", stacks);
        JTextArea guide = BeaconUi.text("JFR / CAPTURE CONTRACT\n\n"
                + "1. Check / refresh discovers the target's Flight Recorder MXBean and default/profile presets.\n"
                + "2. Turn off Read-only. Record confirms the target, configuration and 5–120 s duration.\n"
                + "3. The target stops at its duration. Refresh to see the reported state, or stop early.\n"
                + "4. Download a STOPPED recording to a NEW local file. Then release target-side data.\n\n"
                + "Budget: 32 MiB target repository retention; duration and maxAge are set before start.\n"
                + "Retention is chunk-based, not a hard limit on disk, memory or target overhead.\n"
                + "Download: at most 64 MiB, 64 KiB per block; 45 s transfer budget, 60 s UI deadline.\n"
                + "No automatic retry. Stop waiting cancels future steps, not an in-flight remote call.\n\n"
                + "OWNERSHIP & RECOVERY\n"
                + "Only this connection's recording is managed. Other recordings are never stopped or closed.\n"
                + "Disconnect / replace / close requests cleanup of this recording and may discard unsaved data.\n"
                + "If the network fails, cleanup is not guaranteed. The target duration still limits recording time;\n"
                + "retained data may remain until an administrator closes the recording or the JVM exits.\n"
                + "A failed or timed-out start may have taken effect. Check the target; do not blindly retry.\n\n"
                + "PRIVACY & COVERAGE\n"
                + "JFR may include arguments, properties, paths, thread names, stacks and application data.\n"
                + "The .jfr file is NOT automatically redacted. Nothing is uploaded by JVM Beacon.\n"
                + "Concurrent recordings can enable additional events. Presets and events depend on the target JDK.\n"
                + "This is not a complete thread census; virtual-thread coverage is event/JDK-dependent.\n\n"
                + "DEEP ANALYSIS\n"
                + "Sampled stacks provides a flame graph and call tree for Java/native samples separately.\n"
                + "Select an event kind and retained thread, then Apply filters. Highlight never changes counts.\n"
                + "Click a frame or use the keyboard in Call tree, then Zoom selected / Find source candidate.\n"
                + "Coverage lists missing, truncated and omitted samples. Width counts samples, not CPU time.\n"
                + "For more event analysis, Copy file path, then File > Open File in a separately installed JDK Mission Control.\n"
                + "JVM Beacon does not install or launch another program automatically.\n"
                + "The local inventory counts events; it does not attribute CPU time or diagnose root causes.\n"
                + "Open only recordings from sources you trust. JDK parsing can allocate per-event metadata.\n"
                + "JFR files are independent of .jvmb snapshots.\n", 18);
        guide.setLineWrap(true); guide.setWrapStyleWord(true);
        views.addTab("Capture guide", BeaconUi.scroll(guide)); add(views, BorderLayout.CENTER);
        refresh.addActionListener(e -> run("Inspect Flight Recorder", 8_000, JfrCapture::inspect));
        record.addActionListener(e -> startRecording());
        stop.addActionListener(e -> { if (confirm("Stop recording", "Stop this recording early. Captured data remains available to download.")) run("Stop JFR recording", 8_000, JfrCapture::stop); });
        release.addActionListener(e -> { if (confirm("Release recording", "Close this recording and discard its target-side data. Download it first if needed.")) run("Release JFR recording", 8_000, JfrCapture::release); });
        download.addActionListener(e -> download());
        open.addActionListener(e -> {
            var descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("jfr");
            descriptor.setTitle("Open trusted local JFR · Events and sampled stacks (64 MiB limit)");
            FileChooser.chooseFile(descriptor, project, null, file -> analyze(Path.of(file.getPath())));
        });
        copyPath.addActionListener(e -> { if (localFile != null) CopyPasteManager.getInstance().setContents(new StringSelection(localFile.toString())); });
        copySummary.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(inventory.getText())));
        refresh.setToolTipText("Explicit remote capability/state read. Last reported state is not continuously polled.");
        record.setToolTipText("Requires Read-only off and a supported target preset. Confirmation is required.");
        download.setToolTipText("Stopped session-owned recording only. Opens a server stream; server control permission may be required.");
        updateActions();
    }

    void setSession(JmxClient next, boolean busy, boolean readOnly) {
        if (client != next) {
            client = next; state = null; uncertain = false; localFile = null;
            stacks.clear();
            compactState.setText(next == null ? "Local analysis · No target connected" : "Connected · JFR support not checked");
            expandCapture(next != null);
            events.setRowCount(0); search.setText(""); inventorySummary.setText("No local recording inspected");
            inventory.setText("Open a local .jfr or download a stopped recording to inspect captured events.");
            stateText.setText(next == null ? "Disconnected. Target-side cleanup was requested for any session-owned recording.\nIf cleanup could not reach the target, retained data may remain. Saved local files are unchanged."
                    : "Target: " + next.identity().runtimeName() + "\nCheck Flight Recorder support. No recording starts automatically.");
        }
        this.busy = busy; this.readOnly = readOnly; updateActions();
    }

    private void updateActions() {
        boolean ready = client != null && !busy;
        refresh.setEnabled(ready);
        record.setEnabled(ready && !readOnly && !uncertain && state != null && state.supported() && state.id() == 0 && !state.configurations().isEmpty());
        stop.setEnabled(ready && !readOnly && !uncertain && state != null && "RUNNING".equals(state.state()));
        download.setEnabled(ready && !uncertain && state != null && "STOPPED".equals(state.state()));
        release.setEnabled(ready && !readOnly && !uncertain && state != null && state.id() != 0);
        open.setEnabled(!busy); copyPath.setEnabled(localFile != null); copySummary.setEnabled(localFile != null);
        stacks.setBusy(busy);
    }

    @FunctionalInterface private interface Remote { JfrCapture.State call(JfrCapture capture) throws Exception; }
    private void expandCapture(boolean expanded) {
        captureToggle.setSelected(expanded); captureToggle.setText(expanded ? "Hide capture controls" : "Show capture controls");
        captureControls.setVisible(expanded); revalidate(); repaint();
    }
    private void run(String label, long deadline, Remote operation) {
        JmxClient active = client;
        if (active == null || busy) return;
        jobs.run(label, true, deadline, () -> operation.call(active.jfr()), this::show, this::failed);
    }

    private void show(JfrCapture.State next) {
        state = next; uncertain = false;
        compactState.setText("Reported: " + next.state() + (next.id() == 0 ? "" : " · #" + next.id()) + " · Refresh to verify");
        String details = !next.supported() ? "UNAVAILABLE · Flight Recorder MXBean is not registered on this target."
                : next.id() == 0 ? "READY · No recording owned by this connection. Available presets: " + String.join(", ", next.configurations())
                : "Reported state: " + next.state() + " · Recording #" + next.id() + " · " + next.durationSeconds() + " s limit · Stored bytes: " + next.bytes()
                + "\nStarted: " + instant(next.startTime()) + " · Stop (expected/actual): " + instant(next.stopTime())
                ;
        stateText.setText(details + "\nChecked: " + instant(next.checkedAt()) + " · Refresh to verify changes. Turn off Read-only to record.");
        stateText.setToolTipText("Target: " + (client == null ? "Disconnected" : client.identity().runtimeName()) + " · Recording name: " + next.name());
        stateText.setCaretPosition(0); updateActions(); status.accept("JFR state checked. No continuous polling; refresh after the duration to download.");
    }

    private static String instant(long millis) { return millis <= 0 ? "Not reported" : Instant.ofEpochMilli(millis).toString(); }

    private void failed(String message) {
        uncertain = true;
        compactState.setText("JFR state unconfirmed · Show controls and refresh"); expandCapture(true);
        stateText.setText("JFR state is unconfirmed. Last recording ID: " + (state == null ? "Not available" : state.id())
                + "\n" + message + "\nRefresh before taking another action. Failed mutations are not automatically retried.");
        updateActions();
    }

    private boolean confirm(String title, String impact) {
        JmxClient active = client;
        if (active == null || busy || readOnly) return false;
        boolean accepted = Messages.showYesNoDialog(project, "Target: " + active.identity().runtimeName()
                + "\nRecording: " + (state == null ? "Unknown" : state.id()) + "\n\n" + impact,
                title, "Confirm", "Cancel", Messages.getWarningIcon()) == Messages.YES;
        return accepted && client == active && !busy && !readOnly;
    }

    private void startRecording() {
        JmxClient active = client;
        if (active == null || readOnly || busy || state == null) return;
        JComboBox<String> presets = new JComboBox<>(state.configurations().toArray(String[]::new));
        JSpinner seconds = new JSpinner(new SpinnerNumberModel(30, 5, 120, 5));
        JLabel duration = new JLabel("Duration · seconds"); duration.setLabelFor(seconds);
        DialogWrapper dialog = new DialogWrapper(project, false) {
            { setTitle("Start bounded JFR recording"); setOKButtonText("Start recording"); init(); }
            @Override protected JComponent createCenterPanel() {
                JPanel content = BeaconUi.panel(10);
                JTextArea warning = BeaconUi.text("Target: " + active.identity().runtimeName()
                        + "\n\nRecording changes target behavior and uses CPU, memory and disk. Profile typically enables more detail."
                        + "\n32 MiB retention is chunk-based, not a hard resource limit. The target stops after the selected duration."
                        + "\nJFR can contain sensitive arguments, properties, paths and stacks; files are NOT redacted."
                        + "\nDisconnect / replace / close requests release of this recording. Download before leaving."
                        + "\nIf disconnected, cleanup is best-effort; retained data may require target-side administration.", 10);
                warning.setLineWrap(true); warning.setWrapStyleWord(true);
                content.add(BeaconUi.scroll(warning), BorderLayout.CENTER);
                content.add(BeaconUi.row(new JLabel("Preset"), presets, duration, seconds), BorderLayout.SOUTH);
                content.setPreferredSize(JBUI.size(640, 290)); return content;
            }
            @Override protected void doOKAction() {
                try { seconds.commitEdit(); super.doOKAction(); }
                catch (java.text.ParseException invalid) { setErrorText("Enter a duration from 5 to 120 seconds."); }
            }
        };
        if (dialog.showAndGet() && client == active && !busy && !readOnly) {
            String preset = (String) presets.getSelectedItem(); int durationSeconds = (Integer) seconds.getValue();
            run("Start bounded JFR recording (outcome may be unknown on timeout)", 20_000, c -> c.start(preset, durationSeconds));
        }
    }

    private void download() {
        JmxClient active = client;
        if (active == null || busy || state == null || !"STOPPED".equals(state.state())) return;
        var file = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Download JFR to a new local file",
                "Not redacted. Opens a target stream; up to 64 MiB / 45 s. Existing files are never overwritten.", "jfr"), project)
                .save((com.intellij.openapi.vfs.VirtualFile) null, "jvm-beacon-" + System.currentTimeMillis() + ".jfr");
        if (file == null || client != active || busy) return;
        jobs.run("Download JFR · No automatic retry", true, 60_000, () -> active.jfr().download(file.getFile().toPath()),
                path -> { localFile = path; status.accept("JFR saved locally: " + path + ". Target recording retained until release/disconnect."); analyze(path); }, this::failed);
    }

    private void analyze(Path path) {
        record Local(JfrSummary.Report report, JfrStacks.View view) { }
        jobs.run("Inspect local JFR events and sampled stacks", false, 8_000, () -> {
            var report = JfrSummary.inspect(path);
            return new Local(report, JfrStacks.aggregate(report.stacks(), JfrStacks.Kind.JAVA, null));
        }, result -> {
            var report = result.report(); stacks.load(report.stacks(), result.view()); expandCapture(false);
            localFile = path.toAbsolutePath(); inventory.setText(report.text()); inventory.setCaretPosition(0);
            events.setRowCount(0); search.setText("");
            for (var type : report.types()) events.addRow(new Object[]{type.name(), type.count()});
            inventorySummary.setText(String.format("%,d events · %,d bytes · %s · Counts, not CPU time", report.events(), report.bytes(), report.partial() ? "PARTIAL scan" : "End of file"));
            inventorySummary.setToolTipText("Observed event window: " + report.first() + " → " + report.last() + "; " + localFile);
            views.setSelectedIndex(0); updateActions();
            status.accept("Local JFR ready. Open Sampled stacks for the flame graph, call tree and coverage limits.");
        }, error -> { stacks.clear(); events.setRowCount(0); inventorySummary.setText("Local inventory unavailable · See details"); views.setSelectedIndex(1); inventory.setText(error + "\nThe file was not modified. Try opening it in JDK Mission Control."); localFile = path.toAbsolutePath(); updateActions(); });
    }
}
