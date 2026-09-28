package dev.jvmbeacon.ui;

import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import dev.jvmbeacon.core.AttributeSeries;
import dev.jvmbeacon.core.JmxClient;
import dev.jvmbeacon.core.MBeanMetadata.Attribute;
import dev.jvmbeacon.core.TypeCodec;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** EDT-owned selection/cache; all target access is explicit and submitted to the shared session runner. */
final class MBeanAttributesPanel extends JPanel {
    interface Jobs {
        boolean submit(String label, Callable<JmxClient.AttributeReading> work,
                       Consumer<JmxClient.AttributeReading> success, Consumer<String> failure);
    }
    interface Reader { JmxClient.AttributeReading read(String name) throws Exception; }
    private static final int CACHE_LIMIT = 8;
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Attribute", "Type", "Value / status", "Access"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JBTable table = new JBTable(model);
    private final JBTextField search = new JBTextField();
    private final JTextArea detail = BeaconUi.text("Select an attribute. Read value invokes only its getter.", 6);
    private final JLabel count = BeaconUi.label("No attribute definitions", true);
    private final JButton read = new JButton("Read value"), edit = new JButton("Edit…"), watch = new JButton("Watch…"),
            explore = new JButton("Explore…"), copy = new JButton("Copy");
    // In insertion order: eight most recently completed reads. Browsing never renews retention or calls the server.
    private final Map<String, JmxClient.AttributeReading> captured = new LinkedHashMap<>();
    private final Map<String, String> states = new HashMap<>();
    private List<Attribute> definitions = List.of();
    private final Jobs jobs;
    private final Consumer<String> status;
    private final BiConsumer<String, JmxClient.AttributeReading> explorer;
    private Reader reader;
    private String context = "", pending;
    private long generation;
    private boolean live, busy, writable, rebuilding;

    MBeanAttributesPanel(Jobs jobs, Consumer<String> status, Runnable editAction, Runnable watchAction,
                         BiConsumer<String, JmxClient.AttributeReading> explorer) {
        super(new BorderLayout());
        this.jobs = jobs; this.status = status; this.explorer = explorer;
        detail.setLineWrap(true); detail.setWrapStyleWord(true);
        search.getEmptyText().setText("Filter attributes by name or type…");
        search.getAccessibleContext().setAccessibleName("Filter attribute definitions");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        BeaconUi.table(table, "No matching attributes"); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(160);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !rebuilding) render(); });
        read.setToolTipText("Invoke only the selected getter. Reads can have overhead or side effects. Enter in the table reads a value.");
        read.addActionListener(e -> readSelected());
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "beacon.readAttribute");
        table.getActionMap().put("beacon.readAttribute", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { readSelected(); }
        });
        edit.addActionListener(e -> editAction.run()); watch.addActionListener(e -> watchAction.run());
        explore.addActionListener(e -> {
            Attribute a = selected(); JmxClient.AttributeReading value = a == null ? null : captured.get(a.name());
            if (value != null && value.structure() != null) explorer.accept(context + "\nAttribute: " + a.name() + " : " + a.type() + "\n" + evidence(a, value), value);
        });
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(detail.getText()), null));
        JPanel browser = BeaconUi.panel(6); browser.add(search, BorderLayout.NORTH);
        browser.add(BeaconUi.scroll(table), BorderLayout.CENTER); browser.add(count, BorderLayout.SOUTH);
        JPanel value = BeaconUi.panel(0);
        value.add(BeaconUi.row(read, edit, watch, explore, copy), BorderLayout.NORTH);
        value.add(BeaconUi.scroll(detail), BorderLayout.CENTER);
        add(BeaconUi.split(false, "attribute-workbench-v15", browser, value, .52f), BorderLayout.CENTER);
        render();
    }

    void load(List<Attribute> attributes, String targetContext, Reader valueReader) {
        clear(); definitions = List.copyOf(attributes); context = targetContext; reader = valueReader;
        rebuilding = true;
        try {
            Object[][] rows = new Object[definitions.size()][4];
            for (int i = 0; i < rows.length; i++) {
                Attribute a = definitions.get(i);
                rows[i] = new Object[]{a.name(), a.type(), a.readable() ? "Not read" : "Write-only", access(a)};
            }
            model.setDataVector(rows, new String[]{"Attribute", "Type", "Value / status", "Access"});
        } finally { rebuilding = false; }
        filter(); render();
    }

    void clear() {
        generation++; pending = null; reader = null; context = ""; definitions = List.of(); captured.clear(); states.clear();
        rebuilding = true;
        try { model.setRowCount(0); search.setText(""); } finally { rebuilding = false; }
        render();
    }

    void setSession(boolean connected, boolean running, boolean allowWrite) {
        if (live && !connected) {
            generation++; reader = null;
            states.replaceAll((name, state) -> state.startsWith("Write pending") ? "Stopped waiting for write; outcome unknown. Inspect the target before repeating it." : state);
            if (pending != null) states.put(pending, "Stopped waiting; getter may still be running");
            pending = null; refreshRows();
        }
        live = connected; busy = running; writable = allowWrite; render();
    }

    Attribute selected() {
        int view = table.getSelectedRow();
        if (view < 0) return null;
        int index = table.convertRowIndexToModel(view);
        return index < definitions.size() ? definitions.get(index) : null;
    }
    long generation() { return generation; }

    void invalidate(Attribute a, long expected, String message) {
        if (expected != generation) return;
        captured.remove(a.name()); states.put(a.name(), message); refreshRows(); render();
    }

    void readBack(Attribute a, long expected) {
        if (expected != generation) { status.accept("Write returned successfully. The MBean view changed, so no read-back was requested."); return; }
        if (!a.readable()) { invalidate(a, expected, "Write returned successfully · Write-only; no read-back"); status.accept("Write returned successfully. This attribute is write-only; no getter was called."); return; }
        read(a, "Read back written attribute");
    }

    private void readSelected() { Attribute a = selected(); if (a != null) read(a, "Read attribute"); }
    private void read(Attribute a, String label) {
        if (!live || reader == null || !a.readable()) return;
        Reader active = reader; long expected = generation;
        boolean accepted = jobs.submit(label + " · " + a.name(), () -> active.read(a.name()), result -> {
            if (expected != generation) return;
            pending = null; states.remove(a.name()); captured.remove(a.name());
            if (captured.size() == CACHE_LIMIT) {
                String evicted = captured.keySet().iterator().next(); captured.remove(evicted);
                states.put(evicted, "Evicted · Read again");
            }
            captured.put(a.name(), result); refreshRows(); render();
            status.accept(result.error() == null ? "Captured " + a.name() + ". Other getters were not called. Values retain their own capture windows."
                    : "Could not read " + a.name() + ": " + result.error());
        }, error -> {
            if (expected != generation) return;
            pending = null; states.put(a.name(), "Read failed: " + error); refreshRows(); render();
        });
        if (accepted) {
            pending = a.name(); captured.remove(a.name()); states.put(a.name(), "Reading…"); refreshRows(); render();
        }
    }

    private void filter() {
        if (rebuilding) return;
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        @SuppressWarnings("unchecked") var sorter = (TableRowSorter<DefaultTableModel>) table.getRowSorter();
        sorter.setRowFilter(query.isEmpty() ? null : new RowFilter<>() {
            @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                return (entry.getStringValue(0) + " " + entry.getStringValue(1)).toLowerCase(Locale.ROOT).contains(query);
            }
        });
        render();
    }

    private void refreshRows() {
        for (int i = 0; i < definitions.size(); i++) {
            Attribute a = definitions.get(i); var value = captured.get(a.name());
            String text = value == null ? states.getOrDefault(a.name(), a.readable() ? "Not read" : "Write-only")
                    : value.error() != null ? "Read failed" : value.text().replace('\n', ' ').replace('\r', ' ');
            model.setValueAt(text.length() <= 100 ? text : text.substring(0, 99) + "…", i, 2);
        }
    }

    private void render() {
        Attribute a = selected(); var value = a == null ? null : captured.get(a.name());
        read.setEnabled(a != null && a.readable() && live && !busy && reader != null && pending == null);
        edit.setEnabled(a != null && a.writable() && TypeCodec.supports(a.type()) && live && !busy && writable);
        edit.setToolTipText(writable ? "Confirm a write; only this attribute is read back" : "Disable Read-only in the connection toolbar to edit");
        watch.setEnabled(a != null && a.readable() && AttributeSeries.supports(a.type()) && live && !busy);
        explore.setEnabled(value != null && value.structure() != null); copy.setEnabled(a != null);
        count.setText(table.getRowCount() + " / " + definitions.size() + " attributes · " + captured.size() + "/8 values retained");
        String text = a == null ? "Select an attribute to inspect its definition.\n\nRead value (Enter) invokes only the selected getter. Reads may have overhead or side effects.\n\nBrowsing and filtering do not read values. The eight most recently completed reads are retained for this MBean; eviction never triggers another read."
                : a.name() + " : " + a.type() + "\nAccess: " + access(a) + "\n\n"
                + (value == null ? states.getOrDefault(a.name(), a.readable() ? "Not read · Use Read value or press Enter. Other getters will not be called." : "Write-only · No getter is exposed.")
                : value.text() + "\n\n" + evidence(a, value))
                + "\n\n" + context + (a.description().isBlank() ? "" : "\nDefinition: " + a.description())
                + (!live ? "\n\nDisconnected · Retained values are from an earlier capture." : "");
        // Timer/busy updates must not reset selection or scroll in long captured values.
        if (!detail.getText().equals(text)) { detail.setText(text); detail.setCaretPosition(0); }
    }

    private String evidence(Attribute a, JmxClient.AttributeReading value) {
        return "Source: JMX getAttribute · " + a.name() + "\nRead window: " + Instant.ofEpochMilli(value.start()) + " → "
                + Instant.ofEpochMilli(value.end()) + (value.end() >= value.start() ? " (" + (value.end() - value.start()) + " ms)" : " · Clock moved backwards")
                + "\nCaptured once; different attributes are not an atomic snapshot.";
    }
    private static String access(Attribute a) { return a.readable() ? a.writable() ? "Read / Write" : "Read" : a.writable() ? "Write-only" : "None"; }
}
