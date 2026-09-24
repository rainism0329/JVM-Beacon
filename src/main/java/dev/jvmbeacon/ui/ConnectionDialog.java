package dev.jvmbeacon.ui;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.jvmbeacon.core.JmxClient;
import dev.jvmbeacon.core.RemoteEndpoint;
import dev.jvmbeacon.core.ConnectionProfile;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ConnectionDialog extends DialogWrapper {
    record Target(boolean local, String address, String username, boolean tlsRegistry,
                  ConnectionProfile profile, String alias, JmxClient.Identity previous) { }
    record Request(boolean local, String address, boolean allowAgent, String username, char[] password,
                   boolean tlsRegistry, boolean remember, ConnectionProfile profile, String alias, JmxClient.Identity previous) {
        void erasePassword() { Arrays.fill(password, '\0'); }
        CredentialAttributes credentialKey() { return credentialKeyFor(address, username); }
        Target target(JmxClient.Identity identity) { return new Target(local, address, username, tlsRegistry, profile, alias, identity); }
    }

    private final SessionRunner runner = new SessionRunner();
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final DefaultListModel<JmxClient.LocalJvm> processes = new DefaultListModel<>();
    private final JBList<JmxClient.LocalJvm> processList = new JBList<>(processes);
    private final JBTextField pid = new JBTextField();
    private final JCheckBox allowAgent = new JCheckBox("Allow starting the local management agent if needed", false);
    private final JBTextField url = new JBTextField("localhost:9010");
    private final JTextArea endpointPreview = description("", 2);
    private final JBTextField username = new JBTextField();
    private final JPasswordField password = new JPasswordField();
    private final JCheckBox tlsRegistry = new JCheckBox("Use TLS for the RMI registry", true);
    private final JCheckBox remember = new JCheckBox("Remember credentials after connecting", false);
    private final JCheckBox saveProfile = new JCheckBox("Save connection setup in this IDE", true);
    private final JBTextField alias = new JBTextField(), group = new JBTextField();
    private final JBTextField savedSearch = new JBTextField();
    private final JCheckBox recentOnly = new JCheckBox("Recently used", false);
    private final DefaultListModel<ConnectionProfile> savedModel = new DefaultListModel<>();
    private final JBList<ConnectionProfile> savedList = new JBList<>(savedModel);
    private final JTextArea savedDetails = description("Select a saved connection to review its address and identity.", 5);
    private final ConnectionWorkspace workspace = ConnectionWorkspace.getInstance();
    private String profileId;
    private final Target initialTarget;
    private final JTextArea message = description("Discovering Java processes visible to the current user…", 2);
    private String localMessage = "Discovering local JVMs…";
    private boolean localError;
    private Request request;

    ConnectionDialog(Project project) {
        this(project, null);
    }

    ConnectionDialog(Project project, Target seed) {
        super(project, true);
        initialTarget = seed;
        setTitle("Connect JVM · JVM Beacon");
        setOKButtonText("Connect");
        setCancelButtonText("Cancel");
        init();
        BeaconUi.applyTypography(getContentPane());
        DocumentListener clearCredential = new DocumentListener() {
            private void changed() { password.setText(""); updateEndpointPreview(); }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        };
        url.getDocument().addDocumentListener(clearCredential);
        username.getDocument().addDocumentListener(clearCredential);
        updateEndpointPreview();
        tabs.addChangeListener(e -> {
            setOKButtonText(tabs.getSelectedIndex() == 2 ? "Use setup…" : "Connect");
            if (tabs.getSelectedIndex() == 0) showMessage(localMessage, localError);
            else if (tabs.getSelectedIndex() == 1) showMessage("Remote JMX must already be enabled. Verify the advertised RMI host, ports and transport settings.", false);
            else { refreshSaved(); showMessage("Saved setup stays in this IDE. Selecting an entry does not contact its target.", false); }
        });
        if (seed != null) {
            if (seed.local()) { pid.setText(seed.address()); allowAgent.setSelected(false); }
            else { applyTarget(seed); }
        } else if (!workspace.all().isEmpty()) tabs.setSelectedIndex(2);
        refreshProcesses();
    }

    @Override protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(12)));
        panel.setPreferredSize(JBUI.size(720, 520));
        panel.setMinimumSize(JBUI.size(580, 400));
        tabs.setBorder(JBUI.Borders.empty());
        tabs.addTab("Local processes", localPage());
        tabs.addTab("Remote JMX", remotePage());
        tabs.addTab("Saved connections", savedPage());
        panel.add(tabs, BorderLayout.CENTER);
        message.setBorder(JBUI.Borders.empty(0, 4));
        message.getAccessibleContext().setAccessibleName("Connection setup status");
        panel.add(message, BorderLayout.SOUTH);
        BeaconUi.applyTypography(panel);
        return panel;
    }

    private JComponent localPage() {
        JPanel local = new JPanel(new BorderLayout(0, JBUI.scale(12)));
        local.setBorder(JBUI.Borders.empty(14, 4, 0, 4));
        JPanel heading = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        heading.add(sectionHeading("Choose a running JVM", "Discovery does not start a management agent."), BorderLayout.CENTER);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshProcesses());
        JPanel refreshAction = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        refreshAction.add(refresh);
        heading.add(refreshAction, BorderLayout.EAST);
        local.add(heading, BorderLayout.NORTH);

        processList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        processList.setVisibleRowCount(4);
        processList.setCellRenderer(new ProcessRenderer());
        processList.setEmptyText("Discovering local JVMs…");
        processList.getAccessibleContext().setAccessibleName("Local JVMs with launch entry and process ID");
        processList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && processList.getSelectedValue() != null) pid.setText(processList.getSelectedValue().pid());
        });
        JBScrollPane processScroll = new JBScrollPane(processList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        processScroll.setBorder(JBUI.Borders.empty());
        local.add(processScroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(12)));
        JPanel line = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JLabel pidLabel = new JLabel("Process ID");
        pidLabel.setLabelFor(pid);
        pid.getAccessibleContext().setAccessibleName("Process ID; manual entry is supported");
        line.add(pidLabel, BorderLayout.WEST);
        line.add(pid, BorderLayout.CENTER);
        JLabel manualHint = new JLabel("Or enter a PID");
        manualHint.setForeground(secondaryForeground());
        line.add(manualHint, BorderLayout.EAST);
        bottom.add(line, BorderLayout.NORTH);
        JPanel agent = new JPanel(new BorderLayout(0, JBUI.scale(3)));
        agent.add(allowAgent, BorderLayout.NORTH);
        JTextArea agentHelp = description("Starts an agent only when needed and changes target process state.", 1);
        agentHelp.setBorder(JBUI.Borders.emptyLeft(22));
        agent.add(agentHelp, BorderLayout.CENTER);
        bottom.add(agent, BorderLayout.SOUTH);
        local.add(bottom, BorderLayout.SOUTH);
        return local;
    }

    private JComponent remotePage() {
        url.setColumns(42); username.setColumns(16); password.setColumns(16);
        JPanel remote = new JPanel(new GridBagLayout());
        remote.setBorder(JBUI.Borders.empty(14, 4, 0, 4));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST; c.insets = JBUI.insetsBottom(14);
        remote.add(sectionHeading("Connect to a remote JVM", "Use hostname:port, [IPv6]:port, or a complete JMX/RMI URL. The target must already expose JMX."), c); c.gridy++;
        JPanel endpoint = labeledField("Remote address", url);
        endpoint.add(endpointPreview, BorderLayout.SOUTH);
        endpointPreview.getAccessibleContext().setAccessibleName("Resolved JMX endpoint");
        remote.add(endpoint, c); c.gridy++;
        JPanel naming = new JPanel(new GridLayout(1, 2, JBUI.scale(16), 0));
        naming.add(labeledField("Alias (optional)", alias)); naming.add(labeledField("Group (optional)", group));
        remote.add(naming, c); c.gridy++;
        JPanel profileActions = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        profileActions.add(saveProfile, BorderLayout.WEST);
        saveProfile.setToolTipText("Stores address, username, alias, group and reported JVM identity in local IDE settings. Passwords use PasswordSafe separately.");
        JButton save = new JButton("Save setup"), asNew = new JButton("Save as new");
        save.addActionListener(e -> saveSetup(false)); asNew.addActionListener(e -> saveSetup(true));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0)); buttons.add(save); buttons.add(asNew);
        profileActions.add(buttons, BorderLayout.EAST);
        remote.add(profileActions, c); c.gridy++;
        JPanel authentication = new JPanel(new GridLayout(1, 2, JBUI.scale(16), 0));
        authentication.add(labeledField("Username (optional)", username));
        authentication.add(labeledField("Password", password));
        remote.add(authentication, c); c.gridy++;

        JPanel credentials = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel credentialActions = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        credentialActions.add(remember, BorderLayout.WEST);
        JButton load = new JButton("Load saved credentials");
        load.addActionListener(e -> loadCredentials());
        load.getAccessibleContext().setAccessibleName("Load credentials from PasswordSafe for this address and username");
        credentialActions.add(load, BorderLayout.EAST);
        credentials.add(credentialActions, BorderLayout.NORTH);
        credentials.add(description("Credentials are keyed by address and username in IDE PasswordSafe, never in project files or snapshots.", 2), BorderLayout.CENTER);
        remote.add(credentials, c); c.gridy++;

        JPanel transport = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        transport.add(tlsRegistry, BorderLayout.NORTH);
        JTextArea tlsHelp = description("Registry only; the server stub controls JMX server TLS.\nTrust uses IDEA's JBR. Certificate validation and target settings stay unchanged.", 2);
        tlsHelp.setBorder(JBUI.Borders.emptyLeft(22));
        transport.add(tlsHelp, BorderLayout.CENTER);
        remote.add(transport, c); c.gridy++;
        c.weighty = 1; c.fill = GridBagConstraints.BOTH; c.insets = JBUI.emptyInsets();
        remote.add(Box.createVerticalGlue(), c);
        JBScrollPane scroll = new JBScrollPane(remote, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());
        return scroll;
    }

    private JComponent savedPage() {
        JPanel page = BeaconUi.panel(8); page.setBorder(JBUI.Borders.empty(12, 4));
        savedSearch.getEmptyText().setText("Search alias, group, address or username…");
        savedSearch.getAccessibleContext().setAccessibleName("Search saved connections");
        savedSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshSaved(); }
            public void removeUpdate(DocumentEvent e) { refreshSaved(); }
            public void changedUpdate(DocumentEvent e) { refreshSaved(); }
        });
        recentOnly.addActionListener(e -> refreshSaved());
        JPanel filters = BeaconUi.panel(6); filters.add(savedSearch, BorderLayout.CENTER); filters.add(recentOnly, BorderLayout.EAST);
        page.add(filters, BorderLayout.NORTH);
        savedList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        savedList.setCellRenderer(new DefaultListCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                ConnectionProfile p = (ConnectionProfile) value;
                return super.getListCellRendererComponent(list, (p.group().isEmpty() ? "" : p.group() + " / ") + p.label(), index, selected, focus);
            }
        });
        savedList.addListSelectionListener(e -> {
            ConnectionProfile p = savedList.getSelectedValue();
            savedDetails.setText(p == null ? "Select a saved connection; filtering never contacts the target." : p.label() + "\n" + p.address()
                    + "\nUsername: " + (p.username().isEmpty() ? "Not set" : p.username()) + " · Registry TLS: " + p.tlsRegistry()
                    + "\nLast success: " + (p.lastUsed() == 0 ? "Never" : java.time.Instant.ofEpochMilli(p.lastUsed()))
                    + "\nPrevious JVM: " + (p.lastIdentity() == null ? "Not recorded" : p.lastIdentity().runtimeName() + " · Started " + java.time.Instant.ofEpochMilli(p.lastIdentity().startTime())));
            savedDetails.setCaretPosition(0);
        });
        page.add(BeaconUi.scroll(savedList), BorderLayout.CENTER);
        JButton use = new JButton("Use setup…"), forget = new JButton("Forget setup");
        use.addActionListener(e -> useSaved());
        savedList.addMouseListener(new java.awt.event.MouseAdapter() { @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) useSaved(); } });
        forget.addActionListener(e -> {
            ConnectionProfile p = savedList.getSelectedValue(); if (p == null) return;
            if (com.intellij.openapi.ui.Messages.showYesNoDialog(getContentPane(), "Forget this saved connection setup?\nCredentials in PasswordSafe are retained; existing connections stay open.", "Forget Connection Setup", com.intellij.openapi.ui.Messages.getQuestionIcon()) == com.intellij.openapi.ui.Messages.YES) {
                workspace.forget(p.id()); if (p.id().equals(profileId)) profileId = null; refreshSaved();
            }
        });
        JPanel bottom = BeaconUi.panel(6);
        savedDetails.setFocusable(true);
        JBScrollPane detailsScroll = BeaconUi.scroll(savedDetails);
        detailsScroll.setPreferredSize(JBUI.size(580, 110));
        bottom.add(detailsScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)); actions.add(use); actions.add(forget);
        bottom.add(actions, BorderLayout.SOUTH); page.add(bottom, BorderLayout.SOUTH);
        refreshSaved(); return page;
    }

    private void refreshSaved() {
        String selected = savedList.getSelectedValue() == null ? null : savedList.getSelectedValue().id();
        String query = savedSearch.getText().strip().toLowerCase(Locale.ROOT);
        List<ConnectionProfile> entries = recentOnly.isSelected() ? workspace.recent() : workspace.all().stream()
                .sorted(Comparator.comparing(ConnectionProfile::group, String.CASE_INSENSITIVE_ORDER).thenComparing(ConnectionProfile::label, String.CASE_INSENSITIVE_ORDER)).toList();
        savedModel.clear();
        entries.stream().filter(p -> (p.alias() + " " + p.group() + " " + p.address() + " " + p.username()).toLowerCase(Locale.ROOT).contains(query)).forEach(savedModel::addElement);
        savedList.getEmptyText().setText("No matching saved setups. Use Remote JMX to save one (up to 40).");
        for (int i = 0; i < savedModel.size(); i++) if (savedModel.get(i).id().equals(selected)) savedList.setSelectedIndex(i);
    }

    private void useSaved() {
        ConnectionProfile selected = savedList.getSelectedValue(); if (selected == null) { showMessage("Select a saved setup first.", false); return; }
        ConnectionProfile p = workspace.find(selected.id());
        if (p == null) { refreshSaved(); showMessage("This setup was removed in another window.", false); return; }
        applyTarget(new Target(false, p.address(), p.username(), p.tlsRegistry(), p, p.alias(), p.lastIdentity()));
    }
    private void applyTarget(Target seed) {
        url.setText(seed.address()); username.setText(seed.username()); tlsRegistry.setSelected(seed.tlsRegistry()); alias.setText(seed.alias());
        profileId = seed.profile() == null ? null : seed.profile().id(); group.setText(seed.profile() == null ? "" : seed.profile().group());
        password.setText(""); saveProfile.setSelected(seed.profile() != null); tabs.setSelectedIndex(1);
    }
    private ConnectionProfile saveSetup(boolean asNew) {
        try {
            ConnectionProfile p = workspace.save(asNew ? null : profileId, alias.getText(), group.getText(), url.getText(), username.getText(), tlsRegistry.isSelected());
            profileId = p.id(); saveProfile.setSelected(true);
            showMessage("Connection setup saved locally. Passwords are separate; no connection was started.", false); return p;
        } catch (IllegalArgumentException e) { showMessage(e.getMessage(), true); return null; }
    }

    private static JPanel labeledField(String name, JComponent input) {
        JPanel field = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        JLabel label = new JLabel(name); label.setLabelFor(input);
        field.add(label, BorderLayout.NORTH);
        field.add(input, BorderLayout.CENTER);
        input.getAccessibleContext().setAccessibleName(name);
        return field;
    }

    private static JPanel sectionHeading(String title, String explanation) {
        JPanel heading = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JLabel label = new JLabel(title);
        label.putClientProperty("beacon.bold", Boolean.TRUE);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        heading.add(label, BorderLayout.NORTH);
        heading.add(description(explanation, 1), BorderLayout.CENTER);
        return heading;
    }

    private static JTextArea description(String text, int rows) {
        JTextArea area = new JTextArea(text, rows, 0);
        area.setEditable(false); area.setFocusable(false); area.setOpaque(false);
        area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(UIUtil.getLabelFont()); area.setForeground(secondaryForeground());
        area.setBorder(JBUI.Borders.empty()); area.setMargin(JBUI.emptyInsets());
        return area;
    }

    private static Color secondaryForeground() {
        return BeaconUi.MUTED;
    }

    private static final class ProcessRenderer extends JPanel implements ListCellRenderer<JmxClient.LocalJvm> {
        private final DefaultListCellRenderer nativeStyle = new DefaultListCellRenderer();
        private final JLabel entry = new JLabel();
        private final JLabel detail = new JLabel();

        private ProcessRenderer() {
            super(new BorderLayout(0, JBUI.scale(3)));
            nativeStyle.putClientProperty("html.disable", Boolean.TRUE);
            entry.putClientProperty("html.disable", Boolean.TRUE);
            detail.putClientProperty("html.disable", Boolean.TRUE);
            add(entry, BorderLayout.NORTH); add(detail, BorderLayout.CENTER);
        }

        @Override public Component getListCellRendererComponent(JList<? extends JmxClient.LocalJvm> list, JmxClient.LocalJvm value,
                                                                int index, boolean selected, boolean focus) {
            nativeStyle.getListCellRendererComponent(list, value, index, selected, focus);
            setBackground(nativeStyle.getBackground());
            setBorder(BorderFactory.createCompoundBorder(nativeStyle.getBorder(), JBUI.Borders.empty(7, 10)));
            entry.setFont(list.getFont()); detail.setFont(list.getFont());
            entry.setForeground(nativeStyle.getForeground());
            detail.setForeground(selected ? nativeStyle.getForeground() : secondaryForeground());
            String command = value == null || value.displayName() == null ? "" : value.displayName().trim();
            String pidSuffix = value == null ? "" : " · PID " + value.pid();
            if (!pidSuffix.isEmpty() && command.endsWith(pidSuffix)) command = command.substring(0, command.length() - pidSuffix.length());
            entry.setText(command.isEmpty() ? "Launch entry unavailable" : command);
            detail.setText(value == null ? "" : "PID " + value.pid());
            setToolTipText(value == null ? null : "PID " + value.pid() + " · Launch entry: " + command);
            getAccessibleContext().setAccessibleName(entry.getText() + ", " + detail.getText());
            return this;
        }
    }

    private void refreshProcesses() {
        showLocalMessage("Discovering local JVMs…", false);
        if (!runner.submit("Discover local JVMs", JmxClient::listLocal, values -> {
            if (isDisposed()) return;
            String selectedPid = pid.getText().trim();
            processes.clear(); values.forEach(processes::addElement);
            values.stream().filter(value -> value.pid().equals(selectedPid)).findFirst()
                    .ifPresent(value -> processList.setSelectedValue(value, true));
            processList.setEmptyText("No visible JVMs. You can enter a PID below.");
            showLocalMessage(values.isEmpty() ? "No visible JVMs. Enter a PID manually; permissions or Attach settings may limit discovery." : "Found " + values.size() + " candidate processes. Visibility does not guarantee that connection is allowed.", false);
        }, error -> {
            if (!isDisposed()) {
                processList.setEmptyText("JVM discovery failed. You can enter a PID below.");
                showLocalMessage(error, true);
            }
        })) showLocalMessage("A task is running. Retry once it finishes.", false);
        else processList.setEmptyText("Discovering local JVMs…");
    }

    private void loadCredentials() {
        String endpoint;
        try { endpoint = RemoteEndpoint.normalize(url.getText()); }
        catch (IllegalArgumentException e) { showMessage(e.getMessage(), true); return; }
        String originalInput = url.getText(), user = username.getText().trim();
        if (!runner.submit(SessionRunner.Lane.LOCAL_IO, "Read PasswordSafe", () -> PasswordSafe.getInstance().get(credentialKeyFor(endpoint, user)), credentials -> {
            if (isDisposed()) return;
            if (!originalInput.equals(url.getText()) || !user.equals(username.getText().trim())) {
                showMessage("The address or username changed. Credentials read for the previous target were discarded.", false); return;
            }
            if (credentials == null) { showMessage("No saved credentials for this address and username.", false); return; }
            password.setText(credentials.getPasswordAsString());
            showMessage("Loaded from PasswordSafe. Credentials are excluded from project files and snapshots.", false);
        }, error -> { if (!isDisposed()) showMessage(error, true); })) showMessage("A task is running. Load credentials once it finishes.", false);
        else showMessage("Reading credentials for this address and username from PasswordSafe…", false);
    }

    private void showMessage(String text, boolean error) {
        message.setText(text);
        message.setForeground(error ? JBColor.namedColor("Label.errorForeground", JBColor.RED) : secondaryForeground());
    }

    private void showLocalMessage(String text, boolean error) {
        localMessage = text; localError = error;
        if (tabs.getSelectedIndex() == 0) showMessage(text, error);
    }

    static CredentialAttributes credentialKeyFor(String url, String username) {
        return new CredentialAttributes("JVM Beacon: " + RemoteEndpoint.normalize(url), username);
    }

    private void updateEndpointPreview() {
        try { endpointPreview.setText("Connects to: " + RemoteEndpoint.normalize(url.getText())); }
        catch (IllegalArgumentException e) { endpointPreview.setText(e.getMessage()); }
        endpointPreview.setCaretPosition(0);
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        if (tabs.getSelectedIndex() == 2) return savedList.getSelectedValue() == null ? new ValidationInfo("Select a saved setup, or choose Local processes / Remote JMX.", savedList) : null;
        if (tabs.getSelectedIndex() == 0) {
            if (!pid.getText().trim().matches("[1-9][0-9]*")) return new ValidationInfo("Enter a valid process ID.", pid);
        } else {
            try {
                RemoteEndpoint.normalize(url.getText());
                ConnectionProfile.text(alias.getText(), 80, "Alias");
                ConnectionProfile.text(group.getText(), 80, "Group");
                ConnectionProfile.text(username.getText(), 256, "Username");
            } catch (IllegalArgumentException e) { return new ValidationInfo(e.getMessage(), url); }
        }
        return null;
    }

    @Override protected void doOKAction() {
        if (tabs.getSelectedIndex() == 2) { useSaved(); return; }
        if (doValidate() != null) { setErrorText("Check the process ID or remote address."); return; }
        ConnectionProfile profile = tabs.getSelectedIndex() == 1 && saveProfile.isSelected() ? saveSetup(false) : null;
        if (tabs.getSelectedIndex() == 1 && saveProfile.isSelected() && profile == null) return;
        String normalized = tabs.getSelectedIndex() == 0 ? pid.getText().trim() : RemoteEndpoint.normalize(url.getText());
        // Editing an endpoint makes the saved identity inapplicable; one-time entries do not inherit another target's identity.
        JmxClient.Identity expected = profile == null ? null : profile.lastIdentity();
        if (expected == null && initialTarget != null && initialTarget.local() == (tabs.getSelectedIndex() == 0)
                && normalized.equals(initialTarget.address()) && (initialTarget.local() || initialTarget.username().equals(username.getText().trim()) && initialTarget.tlsRegistry() == tlsRegistry.isSelected())) expected = initialTarget.previous();
        request = new Request(tabs.getSelectedIndex() == 0,
                normalized, allowAgent.isSelected(), username.getText().trim(), password.getPassword(), tlsRegistry.isSelected(), remember.isSelected(), profile, tabs.getSelectedIndex() == 0 ? "" : alias.getText().strip(), expected);
        password.setText("");
        super.doOKAction();
    }

    Request request() { return request; }
    @Override protected void dispose() { runner.close(); password.setText(""); super.dispose(); }
}
