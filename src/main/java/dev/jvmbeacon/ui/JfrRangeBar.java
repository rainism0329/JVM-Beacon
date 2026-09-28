package dev.jvmbeacon.ui;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.JfrSummary;
import dev.jvmbeacon.core.JfrTimeRange;
import javax.swing.*;
import java.awt.BorderLayout;
import java.util.function.Consumer;

/** Only the successfully applied scope is displayed. Editing never mutates the visible evidence. */
final class JfrRangeBar extends JPanel {
    private final JButton edit = new JButton("Time range…"), full = new JButton("Full recording");
    private final JLabel label = BeaconUi.label("Open a local recording", true);
    private final Consumer<JfrTimeRange> apply;
    private JfrSummary.Report report;
    private boolean busy;
    JfrRangeBar(JButton open, JToggleButton capture, Consumer<JfrTimeRange> apply, ConfirmationGate confirmations) {
        super(new BorderLayout(JBUI.scale(8), 0)); this.apply = apply;
        add(BeaconUi.row(open, edit, full, label, capture), BorderLayout.NORTH);
        label.putClientProperty("beacon.mono", true);
        edit.addActionListener(e -> {
            if (report == null || busy || report.first() == null) return;
            var dialog = new RangeDialog(report);
            if (confirmations.show(dialog::showAndGet)) apply.accept(dialog.value());
        });
        full.addActionListener(e -> { if (!busy && report != null && report.range() != null) apply.accept(null); });
        edit.setToolTipText("Rescan this local file and apply one time range to every JFR analysis view.");
        show(null);
    }
    void show(JfrSummary.Report next) {
        report = next;
        label.setText(next == null ? "Open a local recording" : (next.range() == null ? "FULL" : "RANGE")
                + " · " + next.events() + " / " + next.inspected() + " events" + (next.partial() ? " · PARTIAL scan" : ""));
        label.setForeground(next != null && next.range() != null ? BeaconUi.ACCENT : BeaconUi.MUTED);
        label.setToolTipText(next == null ? null : next.range() == null ? "Full recording · " + next.first() + " → " + next.last()
                : "Applied UTC: [" + next.range().from() + ", " + next.range().until() + ") · Full event durations retained");
        actions();
    }
    void failed(String error) {
        show(report); label.setText(label.getText() + " · Update failed");
        label.setToolTipText("Previous scope retained. " + error);
    }
    void setBusy(boolean busy) { this.busy = busy; actions(); }
    private void actions() { edit.setEnabled(!busy && report != null && report.first() != null); full.setEnabled(!busy && report != null && report.range() != null); }
    private static final class RangeDialog extends DialogWrapper {
        private final JfrSummary.Report report;
        private final JBTextField from = new JBTextField(), until = new JBTextField();
        RangeDialog(JfrSummary.Report report) {
            super(true); this.report = report;
            from.setText(report.range() == null ? "0" : JfrTimeRange.offset(report.first(), report.range().from()));
            until.setText(JfrTimeRange.offset(report.first(), report.range() == null ? report.last().plusNanos(1) : report.range().until()));
            setTitle("JFR time range · All analysis views"); setOKButtonText("Apply range"); init();
        }
        JfrTimeRange value() { return JfrTimeRange.offsets(report.first(), report.last(), from.getText(), until.getText()); }
        @Override protected ValidationInfo doValidate() {
            try { value(); return null; } catch (IllegalArgumentException e) { return new ValidationInfo(e.getMessage(), from); }
        }
        @Override public JComponent getPreferredFocusedComponent() { return from; }
        @Override protected JComponent createCenterPanel() {
            JPanel panel = BeaconUi.panel(8); panel.setPreferredSize(JBUI.size(610, 220));
            from.setColumns(18); until.setColumns(18);
            from.getAccessibleContext().setAccessibleName("Start offset in seconds"); until.getAccessibleContext().setAccessibleName("Exclusive end offset in seconds");
            panel.add(BeaconUi.row(BeaconUi.label("From · s", true), from, BeaconUi.label("Until · s", true), until), BorderLayout.NORTH);
            JTextArea help = BeaconUi.text("Offsets from " + report.first() + " (UTC).\nMaximum end: "
                    + JfrTimeRange.offset(report.first(), report.last().plusNanos(1)) + " seconds. End is exclusive.\n\n"
                    + "Samples and allocation weights are recomputed from this local file.\n"
                    + "GC and waits are included on overlap; full durations are retained.\n"
                    + "All views update together. Applied view filters are kept; selections and zoom reset.\n"
                    + "No target calls or file writes. A partial scan cannot establish complete range coverage.", 8);
            help.setLineWrap(true); help.setWrapStyleWord(true); panel.add(BeaconUi.scroll(help), BorderLayout.CENTER);
            BeaconUi.applyTypography(panel); return panel;
        }
    }
}
