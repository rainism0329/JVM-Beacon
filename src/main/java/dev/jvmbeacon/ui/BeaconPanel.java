package dev.jvmbeacon.ui;

import com.intellij.credentialStore.Credentials;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBColor;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.jvmbeacon.core.JmxClient;
import dev.jvmbeacon.core.SnapshotStore;
import dev.jvmbeacon.core.TypeCodec;
import dev.jvmbeacon.core.ValueFormatter;
import dev.jvmbeacon.core.AttributeSeries;
import dev.jvmbeacon.core.StructuredValue;
import dev.jvmbeacon.core.TrendSeries;
import dev.jvmbeacon.core.ThreadComparison;
import dev.jvmbeacon.core.LockChains;
import dev.jvmbeacon.core.ConnectionIdentity;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One bounded, independently disposable workbench per connection tab. */
public final class BeaconPanel extends JPanel implements Disposable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private final Project project;
    private final SessionRunner runner = new SessionRunner();
    private final ConfirmationGate confirmations = new ConfirmationGate();
    private final JfrPanel jfr;
    private Connected session;
    private JmxClient client;
    private JmxClient.Identity identity;
    private JmxClient.Sample sample;
    private JmxClient.ThreadDump dump;
    private boolean disposed;
    private boolean offline;
    private Runnable presentationListener = () -> {};
    private String subscribedBean;
    private final JLabel target = BeaconUi.label("Disconnected · Start a Java app, then connect locally or through JMX", true);
    private final JTextArea status = textArea("Connect to a JVM to begin, or open a snapshot to inspect captured metrics and platform threads offline.", 2);
    private final JButton connect = new JButton("Connect JVM…");
    private final JButton reconnect = new JButton(com.intellij.icons.AllIcons.Actions.Refresh);
    private ConnectionDialog.Target lastTarget;
    private String identityNotice;
    private final JButton disconnect = new JButton("Disconnect / Stop waiting");
    private final JCheckBox observe = new JCheckBox("Read-only", true);
    private final JCheckBox autoSample = new JCheckBox("Auto · 2 s", false);
    private final javax.swing.Timer timer;
    private final javax.swing.Timer presentationTimer = new javax.swing.Timer(1000, e -> refreshSamplingPresentation());
    private AtomicReference<String> connectionStage = new AtomicReference<>();
    private long connectStarted;
    private String connectionProblem;
    private final JTextArea connectionNotice = textArea("", 2);
    private final JBTabbedPane pages = new JBTabbedPane();
    private final HotThreadsPanel hotThreads;
    private final LockChainsPanel lockChains;
    private final ThreadComparisonPanel threadComparison;
    private final JBTabbedPane threadTabs = new JBTabbedPane();
    private final JButton pinThreads = new JButton("Pin baseline A");
    private final JButton compareThreads = new JButton("Compare A → current B");
    private final JLabel baselineLabel = BeaconUi.label("No baseline pinned", true);
    private SnapshotStore.Snapshot threadBaseline;
    private long comparisonGeneration;
    private final List<AbstractButton> liveActions = new ArrayList<>();
    private final DefaultTableModel metricModel = model("Metric", "Value", "Unit", "Availability / details");
    private final JLabel sampleWindow = new JLabel("No samples · Source: standard Management MXBeans");
    private final JComboBox<MetricChoice> trendMetric = new JComboBox<>();
    private final TrendChart chart = new TrendChart(this::metricTrend);
    private final TimelinePanel timeline;
    private final JButton timelineLive = new JButton("Start live");
    private final Deque<JmxClient.Sample> history = new ArrayDeque<>();
    private final JTextArea trendInfo = textArea("No samples", 3);
    private final JButton startTrend = new JButton("Start live trend");
    private final JBTextField search = new JBTextField();
    private final JCheckBox favoritesOnly = new JCheckBox("Favorites");
    private final Set<String> favorites = BeaconSettings.favorites();
    private List<String> objectNames = List.of();
    private final DefaultListModel<String> beanModel = new DefaultListModel<>();
    private final JBList<String> beans = new JBList<>(beanModel);
    private final JLabel beanCount = new JLabel("Connect to search MBeans by ObjectName");
    private final JTextArea beanHeading = textArea("Select an MBean to read its metadata and attributes. Reads may incur target-side overhead or side effects.", 4);
    private final DefaultTableModel attributeModel = model("Attribute", "Type", "Value / status", "Access");
    private final JBTable attributes = new JBTable(attributeModel);
    private List<JmxClient.AttributeValue> attributeValues = List.of();
    private record AttributePresentation(String text, StructuredValue structure) { }
    private List<AttributePresentation> attributePresentations = List.of();
    private String attributeContext = "";
    private final JButton exploreAttribute = new JButton("Explore value…");
    private final JButton exploreOperation = new JButton("Explore result…");
    private StructuredValue operationStructure;
    private String operationContext = "";
    private boolean invocationPending;
    private long operationSelectionGeneration;
    private String inspectedBean;
    private String pendingBean;
    private boolean rebuildingBeanList;
    private final JTextArea valueDetail = textArea("Select an attribute to inspect its bounded text representation. Press Ctrl+C to copy.", 6);
    private final DefaultListModel<MBeanOperationInfo> operationModel = new DefaultListModel<>();
    private final JBList<MBeanOperationInfo> operations = new JBList<>(operationModel);
    private final JTextArea operationResult = textArea("Select an operation to inspect its parameters and invoke it. Read-only mode blocks writes and invocations by default.", 6);
    private final JLabel subscription = new JLabel("No active subscription");
    private final JTextArea notificationText = textArea("Notifications start at subscription time. Only the latest 200 are retained; earlier history cannot be recovered.", 8);
    private final JLabel threadWindow = new JLabel("Not captured · ThreadMXBean covers platform threads only");
    private final DefaultListModel<JmxClient.ThreadRecord> threadModel = new DefaultListModel<>();
    private final JBList<JmxClient.ThreadRecord> threads = new JBList<>(threadModel);
    private final DefaultListModel<StackTraceElement> frameModel = new DefaultListModel<>();
    private final JBList<StackTraceElement> frames = new JBList<>(frameModel);
    private final JTextArea threadDetail = textArea("Capture platform threads on demand. This does not enable thread CPU-time or contention monitoring.", 3);
    private final JTextArea notes = new JTextArea(3, 30);
    private final JTextArea snapshotText = textArea("No snapshot yet. Capture metrics or threads to save a snapshot, or open an existing file.", 10);
    private final JTextArea comparisonText = textArea("Choose Compare with file… to create a fixed comparison. Later samples will not overwrite it.", 10);
    private final JBTabbedPane snapshotViews = new JBTabbedPane();
    private final JLabel connectionState = BeaconUi.title("Disconnected");
    private final JPanel overviewBody = new JPanel(new CardLayout());
    private final Map<String, JLabel> highlights = new LinkedHashMap<>();
    private final JBTextField threadSearch = new JBTextField();
    private final JComboBox<String> threadState = new JComboBox<>(new String[]{"All states", "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "NEW", "TERMINATED"});
    private final JLabel threadCount = BeaconUi.label("No thread capture", true);
    private final JBTabbedPane mbeanTabs = new JBTabbedPane();
    private final AttributeSeries watchSeries = new AttributeSeries();
    private record WatchTarget(String bean, String attribute, String type) { }
    private WatchTarget watchTarget;
    private boolean watching;
    private long watchGeneration;
    private final JTextArea watchCaption = textArea("Select a readable numeric attribute in Attributes, then choose Watch attribute…", 3);
    private final JLabel watchState = BeaconUi.label("No attribute selected for tracking", true);
    private final JButton watchToggle = new JButton("Resume");
    private final DefaultTableModel watchModel = model("Window end", "Exact value", "Window · ms", "Availability / details");
    private final TrendChart watchChart = new TrendChart(() -> TrendSeries.of(watchSeries.readings().stream()
            .map(r -> new JmxClient.Sample(r.start(), r.end(), List.of(new JmxClient.Metric("watch", "Attribute", r.plotted(), "unit unspecified", r.error())))).toList(),
            "watch", "unit unspecified"));

    public BeaconPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        jfr = new JfrPanel(project, this, this::backgroundWithDeadline, this::status, confirmations);
        liveActions.add(timelineLive);
        timelineLive.addActionListener(e -> {
            autoSample.setSelected(!autoSample.isSelected()); updateSamplingTimer();
            if (autoSample.isSelected() && !runner.isBusy()) sampleNow(false);
            refreshSamplingPresentation();
        });
        timeline = new TimelinePanel(liveButton("Sample now", () -> sampleNow(true)),
                timelineLive,
                () -> openSnapshot(false), this::saveInterval);
        hotThreads = new HotThreadsPanel(project, this, this::captureHotThreads, this::status);
        lockChains = new LockChainsPanel(project, liveButton("Capture threads", this::captureThreads), frame -> SourceNavigator.navigate(project, this, frame, this::status));
        pinThreads.addActionListener(e -> pinThreadBaseline());
        compareThreads.addActionListener(e -> compareCapturedThreads());
        JButton clearComparison = new JButton("Clear baseline / comparison"); clearComparison.addActionListener(e -> resetThreadComparison());
        JPanel comparisonActions = BeaconUi.panel(4);
        comparisonActions.add(row(pinThreads, liveButton("Capture threads", this::captureThreads), compareThreads, clearComparison), BorderLayout.NORTH);
        comparisonActions.add(baselineLabel, BorderLayout.SOUTH);
        threadComparison = new ThreadComparisonPanel(project, comparisonActions);
        setBorder(JBUI.Borders.empty());
        target.putClientProperty("beacon.mono", true);
        connectionState.putClientProperty("beacon.mono", true);
        operations.putClientProperty("beacon.mono", true);
        frames.putClientProperty("beacon.mono", true);
        beanHeading.setLineWrap(true); beanHeading.setWrapStyleWord(true);
        threadDetail.setLineWrap(true); threadDetail.setWrapStyleWord(true);
        BeaconUi.table(attributes, "Select an MBean to inspect its attributes");
        trendMetric.setRenderer(new DefaultListCellRenderer() {{ putClientProperty("html.disable", Boolean.TRUE); }});
        JPanel header = BeaconUi.panel(4); header.setBorder(JBUI.Borders.empty(12, 16));
        JPanel identityPanel = BeaconUi.panel(4);
        JLabel brand = BeaconUi.title("JVM Beacon"); brand.putClientProperty("beacon.fontScale", 1.18f);
        brand.setIcon(BeaconUi.signalIcon()); brand.setIconTextGap(JBUI.scale(8));
        identityPanel.add(row(brand, connectionState), BorderLayout.NORTH);
        target.setForeground(BeaconUi.MUTED);
        connectionNotice.setLineWrap(true); connectionNotice.setWrapStyleWord(true); connectionNotice.setVisible(false);
        connectionNotice.setBackground(BeaconUi.SURFACE); connectionNotice.setForeground(BeaconUi.MUTED); connectionNotice.setBorder(JBUI.Borders.empty());
        JPanel targetDetails = BeaconUi.panel(4); targetDetails.add(target, BorderLayout.NORTH); targetDetails.add(connectionNotice, BorderLayout.CENTER);
        header.add(targetDetails, BorderLayout.SOUTH);
        header.add(identityPanel, BorderLayout.CENTER);
        JPanel toolbar = row(observe, connect, reconnect, disconnect);
        toolbar.setLayout(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3)));
        header.add(toolbar, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        pages.addTab("Telemetry", overviewPage());
        pages.addTab("MBeans", mbeanPage());
        pages.addTab("Threads", threadsPage());
        pages.addTab("Snapshots", snapshotPage());
        pages.addTab("Timeline", timeline);
        pages.addTab("Flight Recorder", jfr);
        add(pages, BorderLayout.CENTER);
        status.setRows(2); status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setBackground(BeaconUi.SURFACE); status.setForeground(BeaconUi.MUTED);
        status.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(6, 16)));
        status.getAccessibleContext().setAccessibleName("Connection and task status");
        JBScrollPane statusScroll = BeaconUi.scroll(status);
        statusScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel statusBar = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                Insets padding = status.getInsets();
                size.height = status.getFontMetrics(status.getFont()).getHeight() * 2 + padding.top + padding.bottom;
                return size;
            }
        };
        statusBar.add(statusScroll, BorderLayout.CENTER); add(statusBar, BorderLayout.SOUTH);
        connect.addActionListener(e -> connect());
        reconnect.setToolTipText("Reconnect to the previous endpoint. Starts a fresh read-only session; nothing is resumed automatically.");
        reconnect.getAccessibleContext().setAccessibleName("Reconnect to previous endpoint");
        reconnect.addActionListener(e -> reconnect());
        disconnect.addActionListener(e -> {
            boolean pending = runner.isBusy();
            connectionProblem = "Disconnected by user. Captured data is retained; connect again to resume.";
            releaseSession();
            status(pending ? "Stopped waiting and disconnected. The target may still be executing the request. Mutation outcome is unknown; check the target before taking further action." : "Disconnected. Displayed data is stale. Reconnecting starts a new sampling window.");
        });
        observe.setToolTipText("Client-side guard only. Server authorization still applies; reads may have overhead or side effects.");
        observe.addActionListener(e -> { updateActions(); status(observe.isSelected() ? "Read-only mode is a client-side guard, not server authorization. Reads may still have overhead or side effects." : "Writes and invocations are available. Each request still requires confirmation of its target and parameters."); });
        timer = new javax.swing.Timer(2000, e -> {
            if (client != null && !runner.isBusy() && !confirmations.isPaused() && isShowing()) {
                if (watching) pollWatch(); else if (autoSample.isSelected()) sampleNow(false);
            }
        });
        autoSample.addActionListener(e -> {
            updateSamplingTimer();
            if (autoSample.isSelected() && client != null && !runner.isBusy()) sampleNow(false);
            refreshSamplingPresentation();
        });
        autoSample.setToolTipText("Polls only while this connection tab is visible. Hidden tabs retain their connection and captured data.");
        connect.setToolTipText("Connect in this tab. Use + in the window header (Alt+Insert) to keep another connection in its own tab.");
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !disposed) {
                if (isShowing()) { presentationTimer.start(); filterBeans(); } else presentationTimer.stop();
                refreshSamplingPresentation();
            }
        });
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(LafManagerListener.TOPIC,
                source -> SwingUtilities.invokeLater(() -> { if (!disposed) { BeaconUi.applyTypography(this); revalidate(); repaint(); } }));
        BeaconUi.applyTypography(this);
        renderWatch();
        updateActions();
    }

    private JComponent overviewPage() {
        JPanel panel = BeaconUi.panel(12); panel.setBorder(JBUI.Borders.empty(12, 16));
        JButton sampleButton = liveButton("Sample now", () -> sampleNow(true));
        JPanel top = BeaconUi.panel(8);
        JPanel sampleActions = row(sampleButton, autoSample);
        sampleActions.setLayout(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3)));
        top.add(sampleActions, BorderLayout.EAST);
        top.add(BeaconUi.title("Runtime telemetry"), BorderLayout.WEST);
        sampleWindow.setForeground(BeaconUi.MUTED); top.add(sampleWindow, BorderLayout.SOUTH);
        JPanel dashboard = BeaconUi.panel(12); dashboard.add(top, BorderLayout.NORTH);
        JPanel metricsBody = BeaconUi.panel(12);
        JPanel cards = new JPanel(new GridLayout(1, 4, JBUI.scale(12), 0));
        cards.add(metricCard("heap.used", "Heap", "Used · MiB"));
        cards.add(metricCard("cpu.process", "Process CPU", "Recent target JVM load"));
        cards.add(metricCard("threads.live", "Threads", "Platform threads only"));
        cards.add(metricCard("gc.time", "GC time", "Since JVM start · ms"));
        metricsBody.add(cards, BorderLayout.NORTH);
        JPanel bottom = BeaconUi.panel(8); bottom.setBorder(JBUI.Borders.empty(0, 12));
        bottom.add(row(BeaconUi.title("Trend"), trendMetric), BorderLayout.NORTH);
        chart.setPreferredSize(JBUI.size(400, 180)); bottom.add(chart, BorderLayout.CENTER);
        trendInfo.setForeground(BeaconUi.MUTED); trendInfo.setBackground(BeaconUi.SURFACE); trendInfo.setBorder(JBUI.Borders.empty(3, 0));
        trendInfo.setLineWrap(true); trendInfo.setWrapStyleWord(true);
        JPanel trendFooter = BeaconUi.panel(4); trendFooter.add(BeaconUi.scroll(trendInfo), BorderLayout.CENTER);
        startTrend.addActionListener(e -> { autoSample.setSelected(true); updateSamplingTimer(); if (!runner.isBusy()) sampleNow(false); refreshSamplingPresentation(); });
        trendFooter.add(startTrend, BorderLayout.SOUTH); bottom.add(trendFooter, BorderLayout.SOUTH);
        trendMetric.addActionListener(e -> { chart.repaint(); refreshSamplingPresentation(); });
        JBTable metrics = new JBTable(metricModel); BeaconUi.table(metrics, "No metrics captured"); metrics.setAutoCreateRowSorter(true);
        metrics.getColumnModel().getColumn(0).setPreferredWidth(240);
        metrics.getColumnModel().getColumn(1).setPreferredWidth(110);
        metrics.getColumnModel().getColumn(2).setPreferredWidth(65);
        metricsBody.add(BeaconUi.split(false, "overview", BeaconUi.section("All metrics", BeaconUi.scroll(metrics), null), bottom, .58f), BorderLayout.CENTER);
        dashboard.add(metricsBody, BorderLayout.CENTER);
        overviewBody.add(welcome(), "welcome"); overviewBody.add(dashboard, "data");
        panel.add(overviewBody, BorderLayout.CENTER);
        return panel;
    }

    private JComponent welcome() {
        JPanel center = new JPanel(new GridBagLayout());
        JPanel content = BeaconUi.panel(14); content.setBorder(JBUI.Borders.empty(24));
        JLabel title = BeaconUi.title("Your JVM. In focus."); title.putClientProperty("beacon.fontScale", 1.65f);
        JPanel heading = BeaconUi.panel(8); heading.add(title, BorderLayout.NORTH);
        heading.add(BeaconUi.label("Explore a running Java application without leaving your IDE.", true), BorderLayout.SOUTH);
        content.add(heading, BorderLayout.NORTH);
        JPanel steps = new JPanel(new GridLayout(3, 1, 0, JBUI.scale(14)));
        steps.add(welcomeStep("01   CONNECT", "Discover a local JVM or connect over JMX. Verify the target first."));
        steps.add(welcomeStep("02   INSPECT", "Explore telemetry, MBeans and platform threads. Read-only by default."));
        steps.add(welcomeStep("03   CAPTURE", "Keep captured data and notes. Reopen and compare snapshots offline."));
        content.add(steps, BorderLayout.CENTER);
        JButton start = new JButton("Connect JVM…"); start.addActionListener(e -> connect());
        JButton open = new JButton("Open snapshot…"); open.addActionListener(e -> openSnapshot(false));
        content.add(row(start, open), BorderLayout.SOUTH); center.add(content);
        return new JBScrollPane(center, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED) {{ setBorder(JBUI.Borders.empty()); }};
    }

    private JPanel welcomeStep(String title, String description) {
        JPanel panel = BeaconUi.panel(4); panel.add(BeaconUi.title(title), BorderLayout.NORTH);
        panel.add(BeaconUi.label(description, true), BorderLayout.SOUTH); return panel;
    }

    private JPanel metricCard(String key, String title, String hint) {
        JPanel panel = BeaconUi.panel(5); BeaconUi.metricCard(panel);
        panel.add(BeaconUi.label(title, true), BorderLayout.NORTH);
        JLabel value = BeaconUi.title("—"); value.putClientProperty("beacon.fontScale", 1.8f); value.putClientProperty("beacon.mono", true); highlights.put(key, value);
        panel.add(value, BorderLayout.CENTER); panel.add(BeaconUi.label(hint, true), BorderLayout.SOUTH); return panel;
    }

    private JComponent mbeanPage() {
        JPanel browser = BeaconUi.panel(8); browser.setBorder(JBUI.Borders.empty(12));
        search.getEmptyText().setText("Search ObjectName, domain, type, name…");
        search.getAccessibleContext().setAccessibleName("Search MBeans");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterBeans(); }
            public void removeUpdate(DocumentEvent e) { filterBeans(); }
            public void changedUpdate(DocumentEvent e) { filterBeans(); }
        });
        favoritesOnly.addActionListener(e -> filterBeans());
        JButton favorite = new JButton("Favorite"); favorite.addActionListener(e -> toggleFavorite());
        JButton refresh = liveButton("Refresh", this::refreshNames);
        JPanel filters = BeaconUi.panel(8); filters.add(search, BorderLayout.NORTH); filters.add(row(favoritesOnly, favorite, refresh), BorderLayout.SOUTH);
        browser.add(filters, BorderLayout.NORTH); browser.add(BeaconUi.scroll(beans), BorderLayout.CENTER); browser.add(beanCount, BorderLayout.SOUTH);
        beanCount.setForeground(BeaconUi.MUTED); beans.getEmptyText().setText("Connect to browse MBeans, or adjust your filters");
        beans.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        beans.setCellRenderer(new DefaultListCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                String name = String.valueOf(value); int colon = name.indexOf(':');
                String domain = colon < 0 ? "MBean" : name.substring(0, colon);
                String properties = colon < 0 ? name : name.substring(colon + 1);
                JPanel cell = listCell(list, (favorites.contains(name) ? "★ " : "") + properties, domain, selected, focus);
                cell.setToolTipText("ObjectName: " + name); return cell;
            }
        });
        beans.addListSelectionListener(e -> {
            if (rebuildingBeanList || e.getValueIsAdjusting()) return;
            String selected = beans.getSelectedValue();
            if (selected == null) clearBeanDetails(emptyBeanMessage());
            else if (!Objects.equals(selected, inspectedBean)) loadBean();
        });
        JPanel detail = BeaconUi.panel(8); detail.setBorder(JBUI.Borders.empty(12));
        beanHeading.setRows(3); beanHeading.setBackground(BeaconUi.SURFACE); beanHeading.setForeground(BeaconUi.MUTED);
        beanHeading.setBorder(JBUI.Borders.empty(2, 4, 8, 4));
        JBScrollPane beanCaption = BeaconUi.scroll(beanHeading); beanCaption.setPreferredSize(JBUI.size(300, 82));
        detail.add(beanCaption, BorderLayout.NORTH);
        JBTabbedPane tabs = mbeanTabs;
        JPanel attrPage = new JPanel(new BorderLayout());
        attrPage.add(row(liveButton("Refresh", this::loadBean), liveButton("Edit attribute…", this::writeAttribute), liveButton("Watch attribute…", this::startWatch)), BorderLayout.NORTH);
        attributes.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        exploreAttribute.setEnabled(false); exploreOperation.setEnabled(false);
        exploreAttribute.addActionListener(e -> {
            int row = attributes.getSelectedRow();
            if (row >= 0 && row < attributePresentations.size() && attributePresentations.get(row).structure() != null)
                new ValueExplorerDialog(project, attributeContext + "\nAttribute: " + attributeValues.get(row).name(), attributePresentations.get(row).structure()).show();
        });
        exploreOperation.addActionListener(e -> {
            if (operationStructure != null) new ValueExplorerDialog(project, operationContext, operationStructure).show();
        });
        attributes.getSelectionModel().addListSelectionListener(e -> {
            int row = attributes.getSelectedRow();
            exploreAttribute.setEnabled(row >= 0 && row < attributePresentations.size() && attributePresentations.get(row).structure() != null);
            exploreAttribute.setToolTipText(exploreAttribute.isEnabled() ? "Explore this captured value without another remote read"
                    : "Select a successfully captured value. Failed reads and values beyond the shared display budget cannot be explored.");
            if (!e.getValueIsAdjusting() && row >= 0 && row < attributeValues.size()) {
                JmxClient.AttributeValue attribute = attributeValues.get(row);
                valueDetail.setText(attribute.name() + " : " + attribute.type() + "\n" + attributePresentations.get(row).text());
                valueDetail.setCaretPosition(0);
            }
        });
        attributes.getColumnModel().getColumn(0).setPreferredWidth(160); attributes.getColumnModel().getColumn(1).setPreferredWidth(180);
        attributes.getColumnModel().getColumn(2).setPreferredWidth(300); attributes.getColumnModel().getColumn(3).setPreferredWidth(85);
        attrPage.add(BeaconUi.split(true, "attributes", BeaconUi.scroll(attributes), BeaconUi.section("Attribute value", BeaconUi.scroll(valueDetail), row(exploreAttribute, copyButton(valueDetail))), .60f), BorderLayout.CENTER);
        tabs.addTab("Attributes", attrPage);
        JPanel opPage = new JPanel(new BorderLayout()); opPage.add(row(liveButton("Invoke…", this::invokeOperation)), BorderLayout.NORTH);
        operations.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        operations.setCellRenderer(new DefaultListCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                if (value instanceof MBeanOperationInfo op) return listCell(list, op.getName() + "(" + String.join(", ", Arrays.stream(op.getSignature()).map(MBeanParameterInfo::getType).toList()) + ")", "Returns " + op.getReturnType() + " · " + op.getSignature().length + " parameters", selected, focus);
                return super.getListCellRendererComponent(list, value, index, selected, focus);
            }
        });
        operations.addListSelectionListener(e -> {
            MBeanOperationInfo op = operations.getSelectedValue();
            if (!e.getValueIsAdjusting()) {
                operationSelectionGeneration++;
                invocationPending = false;
                operationStructure = null; exploreOperation.setEnabled(false);
                if (op != null) operationResult.setText(operationSignature(op) + "\n" + op.getDescription() + "\nMBean impact=" + op.getImpact() + "; metadata is informational and does not guarantee a side-effect-free invocation.");
                else operationResult.setText("Select an operation to inspect its parameters and result.");
            }
        });
        operations.getEmptyText().setText("Operations for the selected MBean appear here");
        opPage.add(BeaconUi.split(true, "operations", BeaconUi.scroll(operations), BeaconUi.section("Description & result", BeaconUi.scroll(operationResult), row(exploreOperation, copyButton(operationResult))), .45f), BorderLayout.CENTER);
        tabs.addTab("Operations", opPage);
        JPanel notificationPage = new JPanel(new BorderLayout());
        JPanel notifyHeader = new JPanel(new BorderLayout());
        notifyHeader.add(row(liveButton("Subscribe…", this::subscribe), liveButton("Unsubscribe", this::unsubscribe), liveButton("Refresh", this::refreshNotifications)), BorderLayout.NORTH);
        subscription.setForeground(BeaconUi.MUTED); notifyHeader.add(subscription, BorderLayout.SOUTH);
        notificationPage.add(notifyHeader, BorderLayout.NORTH); notificationPage.add(BeaconUi.section("Received notifications", BeaconUi.scroll(notificationText), copyButton(notificationText)), BorderLayout.CENTER);
        tabs.addTab("Notifications", notificationPage);
        tabs.addTab("Watch", watchPage());
        tabs.addChangeListener(e -> {
            // The watch carries its own target and window; the selected MBean may be different.
            beanCaption.setVisible(tabs.getSelectedIndex() != 3);
            detail.revalidate();
        });
        detail.add(tabs, BorderLayout.CENTER);
        return BeaconUi.split(false, "mbeans", browser, detail, .30f);
    }

    private JComponent watchPage() {
        JPanel page = BeaconUi.panel(8);
        watchCaption.setLineWrap(true); watchCaption.setWrapStyleWord(true); watchCaption.setBackground(BeaconUi.SURFACE);
        watchCaption.setBorder(JBUI.Borders.empty(2, 4));
        watchCaption.getAccessibleContext().setAccessibleName("Tracked attribute and capture window");
        watchToggle.addActionListener(e -> {
            if (watchTarget == null || client == null) return;
            watchGeneration++; watching = !watching;
            if (!watching && !watchSeries.readings().isEmpty()) {
                long now = System.currentTimeMillis();
                watchSeries.add(AttributeSeries.Reading.missing(now, now, "Paused by user; sampling gap."));
            }
            updateSamplingTimer(); renderWatch();
            status(watching ? "Attribute tracking resumed. New readings start now; paused history cannot be recovered."
                    : "Attribute tracking paused. An in-flight getter may still finish; its result will be discarded.");
        });
        JButton clear = new JButton("Clear history");
        clear.addActionListener(e -> { watchGeneration++; watchSeries.clear(); renderWatch(); });
        JPanel top = BeaconUi.panel(4); top.add(row(watchToggle, clear, watchState), BorderLayout.NORTH);
        top.add(BeaconUi.scroll(watchCaption), BorderLayout.CENTER); page.add(top, BorderLayout.NORTH);
        JBTable readings = new JBTable(watchModel); BeaconUi.table(readings, "No readings yet. Start tracking from Attributes.");
        readings.getColumnModel().getColumn(0).setPreferredWidth(190);
        readings.getColumnModel().getColumn(1).setPreferredWidth(100);
        readings.getColumnModel().getColumn(2).setPreferredWidth(100);
        readings.getColumnModel().getColumn(3).setPreferredWidth(160);
        page.add(BeaconUi.split(false, "attribute-watch-columns", watchChart, BeaconUi.scroll(readings), .40f), BorderLayout.CENTER);
        JTextArea hint = textArea("Source: one JMX attribute getter · Unit unspecified · Approximate chart; exact values in the table.\n120 points including gap markers · Every 2 s when visible and idle · Not saved in .jvmb files.", 2);
        hint.setLineWrap(true); hint.setWrapStyleWord(true); hint.setForeground(BeaconUi.MUTED); hint.setBackground(BeaconUi.SURFACE);
        hint.setBorder(JBUI.Borders.empty(2, 4));
        page.add(BeaconUi.scroll(hint), BorderLayout.SOUTH); return page;
    }

    private void startWatch() {
        if (runner.isBusy()) { status("A request is running. Wait for it to finish before reviewing attribute tracking."); return; }
        int index = attributes.getSelectedRow();
        if (client == null || inspectedBean == null || !Objects.equals(inspectedBean, beans.getSelectedValue())
                || index < 0 || index >= attributeValues.size()) { status("Select a loaded, readable numeric attribute first."); return; }
        JmxClient.AttributeValue attribute = attributeValues.get(index);
        if (!attribute.readable() || !AttributeSeries.supports(attribute.type())) {
            status("Tracking supports readable numeric scalar attributes only. Arrays, strings and complex objects are not converted to numbers."); return;
        }
        JmxClient active = client; WatchTarget selected = new WatchTarget(inspectedBean, attribute.name(), attribute.type());
        if (confirmations.show(() -> Messages.showYesNoDialog(project, identityLabel() + "\n" + selected.bean() + "\n" + selected.attribute() + " : " + selected.type()
                + "\nRead this getter every 2 seconds while the workbench is visible and idle? Getters may have overhead or side effects."
                + "\nThis replaces the previous watch and clears its history. Nothing is uploaded or added to snapshot files.",
                "Watch numeric attribute", "Start tracking", "Cancel", Messages.getQuestionIcon())) != Messages.YES) return;
        if (active != client) { status("The target changed. Select the attribute again."); return; }
        if (runner.isBusy()) { status("Another request is running. Attribute tracking was not changed. Confirm again after it finishes."); return; }
        watchGeneration++; watchTarget = selected; watchSeries.clear(); watching = true;
        mbeanTabs.setSelectedIndex(3); renderWatch(); updateSamplingTimer();
        if (!runner.isBusy()) pollWatch();
    }

    private void updateSamplingTimer() {
        if (client != null && (autoSample.isSelected() || watching)) timer.start(); else timer.stop();
        refreshSamplingPresentation();
    }

    private TrendSeries metricTrend() {
        MetricChoice choice = (MetricChoice) trendMetric.getSelectedItem();
        return TrendSeries.of(new ArrayList<>(history), choice == null ? "" : choice.key(), choice == null ? "" : choice.unit());
    }

    private void refreshSamplingPresentation() {
        if (disposed) return;
        String stage = connectionStage.get();
        if (stage != null) {
            connectionState.setText("CONNECTING");
            status("Connecting · " + stage + " · " + Math.max(0, (System.nanoTime() - connectStarted) / 1_000_000_000) + " / 20 s · Stop waiting cancels UI waiting, not necessarily the underlying call.");
        }
        String mode = offline ? "OFFLINE · Reading " + history.size() + " saved metric samples."
                : client == null ? "DISCONNECTED · Captured history is no longer updating."
                : !autoSample.isSelected() ? "PAUSED · Auto is off. Choose Start live trend or Sample now."
                : !isShowing() ? "PAUSED · This connection tab is hidden."
                : confirmations.isPaused() ? "PAUSED · Reviewing a connection or target action; automatic polling resumes after the dialog closes."
                : runner.isBusy() ? "AUTO · Waiting for the current request; no overlapping calls."
                : "AUTO · Sampling every 2 s while this connection tab is visible.";
        String age = sample == null ? "No metrics captured yet." : "Last sample: " + time(sample.captureEnd()) + " · "
                + (System.currentTimeMillis() < sample.captureEnd() ? "Client clock precedes capture" : (System.currentTimeMillis() - sample.captureEnd()) / 1000 + " s ago");
        String text = mode + "\n" + age + "\n" + history.size() + " / 120 samples · Missing values or gaps > 5 s break the line.";
        if (!text.equals(trendInfo.getText())) { trendInfo.setText(text); trendInfo.setCaretPosition(0); }
        timeline.sampling(mode);
        timelineLive.setText(autoSample.isSelected() ? "Pause live" : "Start live");
        startTrend.setVisible(client != null && !autoSample.isSelected() && !offline);
        String notice = connectionProblem == null ? Objects.requireNonNullElse(identityNotice, "") : connectionProblem;
        if (!notice.equals(connectionNotice.getText())) { connectionNotice.setText(notice); connectionNotice.setCaretPosition(0); }
        connectionNotice.setRows(connectionProblem == null ? 1 : 2);
        connectionNotice.setToolTipText(notice.isEmpty() ? null : "Connection: " + notice); connectionNotice.setVisible(!notice.isEmpty());
    }

    private record WatchPoll(JmxClient.Sample metrics, String notifications, AttributeSeries.Reading reading) { }
    private void pollWatch() {
        if (client == null || watchTarget == null || !watching || runner.isBusy()) return;
        JmxClient active = client; WatchTarget selected = watchTarget; long generation = watchGeneration;
        boolean withMetrics = autoSample.isSelected();
        background("Read tracked attribute", true, () -> {
            JmxClient.Sample metrics = withMetrics ? active.sample() : null;
            AttributeSeries.Reading reading = active.readNumericAttribute(selected.bean(), selected.attribute());
            return new WatchPoll(metrics, renderNotifications(active.notifications()), reading);
        }, result -> {
            if (result.metrics() != null) showSample(result.metrics());
            showNotifications(result.notifications());
            if (generation != watchGeneration || !watching || active != client) return;
            watchSeries.add(result.reading());
            if (result.reading().error() != null) { watching = false; watchGeneration++; updateSamplingTimer(); }
            renderWatch();
            if (result.reading().error() != null) status("Tracking paused after an unavailable read. Inspect the recorded gap before resuming.");
        }, false);
    }

    private void renderWatch() {
        watchToggle.setEnabled(client != null && watchTarget != null); watchToggle.setText(watching ? "Pause" : "Resume");
        List<AttributeSeries.Reading> readings = watchSeries.readings();
        watchState.setText((watchTarget == null ? "No watch" : client == null ? "STALE" : watching ? "TRACKING" : "PAUSED") + " · " + readings.size() + " / 120 points");
        if (watchTarget == null) watchCaption.setText("Select a readable numeric attribute in Attributes, then choose Watch attribute…");
        else watchCaption.setText(watchTarget.bean() + "\n" + watchTarget.attribute() + " : " + watchTarget.type()
                + (readings.isEmpty() ? "\nNo readings yet." : "\nLatest window: " + window(readings.getLast().start(), readings.getLast().end())));
        watchCaption.setCaretPosition(0); watchModel.setRowCount(0);
        for (int i = readings.size() - 1; i >= 0; i--) {
            AttributeSeries.Reading r = readings.get(i);
            watchModel.addRow(new Object[]{time(r.end()), r.exact() == null ? "—" : r.exact(), r.end() - r.start(), r.error() == null ? "Captured" : r.error()});
        }
        watchChart.repaint();
    }

    private JComponent threadsPage() {
        threadTabs.addTab("Snapshot", threadSnapshotPage());
        threadTabs.addTab("Lock chains", lockChains);
        threadTabs.addTab("Compare", threadComparison);
        threadTabs.addTab("Hot threads", hotThreads);
        return threadTabs;
    }

    private void pinThreadBaseline() {
        if (identity == null || dump == null) { status("Capture or open threads before pinning a baseline."); return; }
        comparisonGeneration++; threadBaseline = new SnapshotStore.Snapshot(identity, null, dump, "");
        baselineLabel.setText("A pinned · " + window(dump.captureStart(), dump.captureEnd()));
        threadComparison.clear(); updateActions();
        status("Baseline A pinned. Capture again, then compare. No target reads were triggered by pinning.");
    }

    private void resetThreadComparison() {
        comparisonGeneration++; threadBaseline = null; baselineLabel.setText("No baseline pinned"); threadComparison.clear();
        comparisonText.setText("Choose Compare with file… to create a fixed comparison. Later samples will not overwrite it.");
        updateActions();
    }

    private void compareCapturedThreads() {
        if (threadBaseline == null || dump == null || identity == null) { status("Pin baseline A and capture threads for B first."); return; }
        SnapshotStore.Snapshot before = threadBaseline;
        SnapshotStore.Snapshot after = new SnapshotStore.Snapshot(identity, null, dump, "");
        long generation = ++comparisonGeneration;
        background("Compare captured platform threads", false,
                () -> ThreadComparison.compare(before.identity(), before.threads(), after.identity(), after.threads()),
                report -> { if (generation != comparisonGeneration) return;
                    threadComparison.showReport(report, comparisonCaptureLabel("A · Pinned baseline", before) + "\n\n" + comparisonCaptureLabel("B · Fixed at comparison", after));
                    status("Fixed thread comparison ready. Capture order, missing observations and identity limits are shown in the report."); });
    }

    private void captureHotThreads() {
        JmxClient active = client;
        if (active == null) { status("Connect a JVM before measuring CPU activity."); return; }
        background("Measure platform-thread CPU · Two bulk reads with a 1 s wait; monitoring unchanged", true, active::hotThreads, report -> {
            hotThreads.showReport(report);
            status(report.unavailable() == null ? "CPU activity captured. End stacks are separate observations; see Capture details for timing and coverage." : report.unavailable());
        });
    }

    private JComponent threadSnapshotPage() {
        JPanel panel = BeaconUi.panel(10); panel.setBorder(JBUI.Borders.empty(12, 16));
        JPanel header = new JPanel(new BorderLayout());
        header.add(row(liveButton("Capture threads", this::captureThreads), new JButton(new AbstractAction("Go to source") {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { navigateFrame(); }
        })), BorderLayout.NORTH); header.add(threadWindow, BorderLayout.SOUTH); panel.add(header, BorderLayout.NORTH);
        threadWindow.setForeground(BeaconUi.MUTED);
        threadSearch.getEmptyText().setText("Filter by thread name or ID…"); threadSearch.getAccessibleContext().setAccessibleName("Filter captured platform threads");
        threadState.getAccessibleContext().setAccessibleName("Platform thread state");
        threadSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterThreads(); }
            public void removeUpdate(DocumentEvent e) { filterThreads(); }
            public void changedUpdate(DocumentEvent e) { filterThreads(); }
        });
        threadState.addActionListener(e -> filterThreads());
        threads.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        threads.setCellRenderer(new DefaultListCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                if (value instanceof JmxClient.ThreadRecord t) return listCell(list, t.name(), "#" + t.id() + " · " + t.state() + (dump != null && dump.deadlockedIds().contains(t.id()) ? " · Reported by deadlock query" : ""), selected, focus);
                return super.getListCellRendererComponent(list, value, index, selected, focus);
            }
        });
        threads.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            JmxClient.ThreadRecord t = threads.getSelectedValue(); frameModel.clear();
            if (t == null) { threadDetail.setText("Select a platform thread to inspect its captured state and stack."); return; }
            t.frames().forEach(frameModel::addElement);
            threadDetail.setText("#" + t.id() + " " + t.name() + " · " + t.state() + "\nLock: " + t.lockName() + "  Owner: " + t.lockOwnerName() + " · " + LockChains.ownerLabel(t.lockOwnerId()) + "\nblockedCount=" + t.blockedCount() + ", waitedCount=" + t.waitedCount() + " (cumulative counts, not durations). Double-click a frame or press Enter to navigate to source.");
            threadDetail.setCaretPosition(0);
        });
        frames.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        frames.setCellRenderer(new DefaultListCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setBorder(JBUI.Borders.empty(6, 12)); setFont(list.getFont()); return this;
            }
        });
        frames.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) navigateFrame(); } });
        frames.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "beacon.navigate");
        frames.getActionMap().put("beacon.navigate", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { navigateFrame(); } });
        JPanel left = BeaconUi.panel(8); left.setBorder(JBUI.Borders.emptyRight(12));
        JPanel filter = BeaconUi.panel(8); filter.add(threadSearch, BorderLayout.CENTER); filter.add(threadState, BorderLayout.SOUTH);
        left.add(filter, BorderLayout.NORTH); left.add(BeaconUi.scroll(threads), BorderLayout.CENTER); left.add(threadCount, BorderLayout.SOUTH);
        threads.getEmptyText().setText("Capture a snapshot to inspect platform threads"); frames.getEmptyText().setText("Select a thread to inspect its stack; double-click a frame to navigate");
        JPanel right = BeaconUi.panel(8); right.setBorder(JBUI.Borders.emptyLeft(12));
        threadDetail.setRows(4); right.add(BeaconUi.scroll(threadDetail), BorderLayout.NORTH); right.add(BeaconUi.scroll(frames), BorderLayout.CENTER);
        panel.add(BeaconUi.split(false, "threads", left, right, .32f), BorderLayout.CENTER);
        return panel;
    }

    private JComponent snapshotPage() {
        JPanel panel = BeaconUi.panel(10); panel.setBorder(JBUI.Borders.empty(12, 16));
        snapshotText.setLineWrap(true); snapshotText.setWrapStyleWord(true);
        comparisonText.setLineWrap(true); comparisonText.setWrapStyleWord(true);
        JButton save = new JButton("Save snapshot…"); save.addActionListener(e -> saveSnapshot());
        JButton open = new JButton("Open snapshot…"); open.addActionListener(e -> openSnapshot(false));
        JButton compare = new JButton("Compare with file…"); compare.addActionListener(e -> openSnapshot(true));
        JPanel top = new JPanel(new BorderLayout()); top.add(row(save, open, compare), BorderLayout.NORTH);
        top.add(BeaconUi.label("Save captured metrics, platform threads and notes. Credentials, arbitrary MBeans and heap contents are excluded.", true), BorderLayout.SOUTH);
        snapshotViews.addTab("Current snapshot", BeaconUi.section("Capture details", BeaconUi.scroll(snapshotText), copyButton(snapshotText)));
        snapshotViews.addTab("Comparison", BeaconUi.section("Fixed comparison · A → B", BeaconUi.scroll(comparisonText), copyButton(comparisonText)));
        panel.add(top, BorderLayout.NORTH); panel.add(snapshotViews, BorderLayout.CENTER);
        JPanel bottom = BeaconUi.panel(6); bottom.add(BeaconUi.label("Notes · Exported with the snapshot. Thread names, stacks and notes are not automatically redacted; review before sharing.", true), BorderLayout.NORTH);
        notes.setLineWrap(true); notes.setWrapStyleWord(true); notes.setBorder(JBUI.Borders.empty(8, 12));
        notes.getAccessibleContext().setAccessibleName("Snapshot notes"); bottom.add(BeaconUi.scroll(notes), BorderLayout.CENTER); panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private void connect() {
        if (runner.isBusy()) { status("A request is still running. Disconnect or stop waiting before starting another connection."); return; }
        ConnectionDialog dialog = new ConnectionDialog(project);
        if (!confirmations.show(dialog::showAndGet)) return;
        ConnectionDialog.Request request = dialog.request();
        connect(request);
    }

    private void reconnect() {
        if (lastTarget == null || offline || runner.isBusy()) return;
        ConnectionDialog.Target target = lastTarget;
        if (target.local() || target.username().isEmpty()) { connect(reconnectRequest(target, new char[0])); return; }
        // Read on the dedicated local lane. Cancellation also wipes credentials returned late.
        background("Read credentials for explicit reconnect", false, () -> {
            Credentials found = PasswordSafe.getInstance().get(ConnectionDialog.credentialKeyFor(target.address(), target.username()));
            return new ReconnectSecret(found == null || found.getPassword() == null ? null : found.getPassword().toCharArray());
        }, secret -> {
            try {
                if (secret.value == null) {
                    ConnectionDialog dialog = new ConnectionDialog(project, target);
                    if (confirmations.show(dialog::showAndGet)) connect(dialog.request());
                } else connect(reconnectRequest(target, secret.take()));
            } finally { secret.close(); }
        });
    }

    static final class ReconnectSecret implements BeaconExecutors.ManagedConnection {
        private char[] value;
        ReconnectSecret(char[] value) { this.value = value; }
        char[] take() { char[] taken = value; value = null; return taken; }
        @Override public void close() { if (value != null) { Arrays.fill(value, '\0'); value = null; } }
    }

    static ConnectionDialog.Request reconnectRequest(ConnectionDialog.Target target, char[] password) {
        // Do not carry forward agent-start permission or credential-saving requests.
        return new ConnectionDialog.Request(target.local(), target.address(), false, target.username(), password,
                target.tlsRegistry(), false, target.profile(), target.alias(), target.previous());
    }

    private void connect(ConnectionDialog.Request request) {
        if (disposed || runner.isBusy()) {
            request.erasePassword();
            if (!disposed) status("Another request is running. The connection was not started. Wait for it to finish, then connect again.");
            return;
        }
        releaseSession();
        lastTarget = request.target(request.previous()); identityNotice = null;
        connectionProblem = null; connectStarted = System.nanoTime();
        AtomicReference<String> stage = new AtomicReference<>("Waiting for a connection worker"); connectionStage = stage;
        status("Connecting and verifying target identity…");
        BeaconExecutors runtime = BeaconExecutors.getInstance();
        boolean accepted = runner.submit(SessionRunner.Lane.NETWORK, "Connect and read target identity", 20_000, () -> {
            BeaconExecutors.ConnectionLease lease = null;
            try {
                lease = runtime.reserveConnection();
                JmxClient fresh = request.local() ? JmxClient.connectLocal(request.address(), request.allowAgent(), stage::set)
                        : JmxClient.connectRemote(request.address(), request.username(), request.password(), request.tlsRegistry(), stage::set);
                lease.attach(fresh);
                JmxClient.Identity id = fresh.identity();
                stage.set("Query the MBean directory");
                List<String> names = fresh.queryNames();
                stage.set("Read initial JVM metrics");
                JmxClient.Sample initial = fresh.sample();
                char[] credentialsToSave = !request.local() && request.remember() ? request.password().clone() : new char[0];
                return new Connected(fresh, id, names, fresh.namesTruncated(), initial, lease, new AtomicReference<>(credentialsToSave));
            } catch (Exception | LinkageError e) {
                if (lease != null) lease.close();
                throw e;
            } finally { request.erasePassword(); }
        }, result -> {
            if (disposed) { SessionRunner.closeLater(result); return; }
            connectionStage.set(null); connectionProblem = null;
            session = result; client = result.client(); identity = result.identity(); offline = false;
            lastTarget = request.target(identity);
            ConnectionIdentity.Match match = ConnectionIdentity.compare(request.previous(), identity);
            identityNotice = switch (match) {
                case FIRST -> null;
                case SAME_REPORTED_IDENTITY -> "RECONNECTED · Same reported JVM identity. Fresh capture; read-only was reset and sampling was not resumed.";
                case CHANGED -> "TARGET CHANGED · Previous start " + time(request.previous().startTime()) + " → " + time(identity.startTime())
                        + ". Reported JVM identity differs. Fresh read-only session; previous actions were not resumed.";
                case INCOMPLETE -> "IDENTITY UNVERIFIED · Incomplete JVM identity metadata. A fresh read-only session started; no previous actions were resumed.";
            };
            if (request.profile() != null) {
                try { ConnectionWorkspace.getInstance().recordSuccess(request.profile(), identity, System.currentTimeMillis()); }
                catch (IllegalArgumentException ignored) { identityNotice = "IDENTITY NOT SAVED · Connected, but target metadata exceeds saved-setup limits. This capture remains available."; }
            }
            hotThreads.clear();
            resetThreadComparison();
            watchTarget = null; watching = false; watchGeneration++; watchSeries.clear(); renderWatch();
            dump = null; threadModel.clear(); frameModel.clear(); notes.setText(""); clearBeans();
            observe.setSelected(true); history.clear(); timeline.reset(); trendMetric.removeAllItems();
            objectNames = result.names(); filterBeans();
            beanCount.setToolTipText(result.truncated() ? "The MBean directory reached its limit and was truncated." : "Results from this directory query");
            showSample(result.sample()); showThreads(null); updateActions();
            status("Connected. Read-only mode is on." + (result.truncated() ? " The MBean directory is truncated." : "") + " Auto-sampling is off by default.");
            if (!request.local() && request.remember()) {
                CredentialAttributes key = request.credentialKey(); String username = request.username(); char[] secret = result.takeCredentials();
                background("Save credentials for the connected target", false, () -> persistCredentials(key, username, secret), ignored -> status("Connected. Credentials saved in PasswordSafe. Read-only mode is on; auto-sampling is off."));
            }
        }, error -> {
            request.erasePassword();
            if (!disposed) { connectionProblem = error + " Last phase: " + connectionStage.get(); releaseSession(); status(connectionProblem); }
        });
        if (!accepted) { connectionStage.set(null); request.erasePassword(); status("Another request is running. Connection has not started."); }
        updateActions();
    }

    private record Connected(JmxClient client, JmxClient.Identity identity, List<String> names,
                             boolean truncated, JmxClient.Sample sample, BeaconExecutors.ConnectionLease lease,
                             AtomicReference<char[]> credentialsToSave) implements BeaconExecutors.ManagedConnection {
        char[] takeCredentials() { return credentialsToSave.getAndSet(new char[0]); }
        @Override public void close() { client.jfr().cancel(); Arrays.fill(takeCredentials(), '\0'); lease.close(); }
    }

    private static boolean persistCredentials(CredentialAttributes key, String username, char[] secret) {
        try { PasswordSafe.getInstance().set(key, new Credentials(username, secret)); return true; }
        finally { Arrays.fill(secret, '\0'); }
    }

    private void releaseSession() {
        if (invocationPending) {
            invocationPending = false;
            operationResult.setText("Stopped waiting for this invocation. Its outcome is unknown; the target may still be executing. Inspect the target before another invocation.");
            operationResult.setCaretPosition(0);
        }
        connectionStage = new AtomicReference<>(); // Late worker progress must not resurrect a cancelled connection.
        timer.stop(); autoSample.setSelected(false); runner.cancel();
        Connected old = session; session = null; client = null;
        watching = false; watchGeneration++; renderWatch();
        if (old != null) SessionRunner.closeLater(old);
        pendingBean = null;
        subscribedBean = null; subscription.setText("Not subscribed · Listeners are removed on disconnect");
        observe.setSelected(true); updateActions();
    }

    private <T> boolean background(String label, boolean network, Callable<T> work, Consumer<T> success) {
        return background(label, network, work, success, true);
    }

    private <T> boolean background(String label, boolean network, Callable<T> work, Consumer<T> success, boolean showProgress) {
        return backgroundWithDeadline(label, network, 8_000, work, success, ignored -> {}, showProgress);
    }

    private <T> boolean backgroundWithDeadline(String label, boolean network, long deadline, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
        return backgroundWithDeadline(label, network, deadline, work, success, failure, true);
    }

    private <T> boolean backgroundWithDeadline(String label, boolean network, long deadline, Callable<T> work, Consumer<T> success, Consumer<String> failure, boolean showProgress) {
        if (disposed) return false;
        boolean accepted = runner.submit(network ? SessionRunner.Lane.NETWORK : SessionRunner.Lane.LOCAL_IO, label, deadline, work, value -> {
            if (disposed) return;
            success.accept(value); updateActions();
            drainPendingBean();
        }, error -> {
            if (disposed) return;
            if (network && SessionRunner.invalidatesConnection(error)) {
                connectionProblem = error + " Collection stopped; prior captures are stale. Verify the target is still running, then connect again.";
                releaseSession();
            } else if (network && error.contains("[CAPACITY]")) {
                autoSample.setSelected(false); watching = false; watchGeneration++; updateSamplingTimer(); renderWatch();
                connectionProblem = error + " Connection retained; automatic sampling paused. Resume manually when workers become available.";
            }
            failure.accept(error); status(error); updateActions(); drainPendingBean();
        });
        if (!accepted) status("A request is already running. The new request was not submitted. Wait for it to finish, or disconnect to stop waiting. Requests are neither queued nor overlapped.");
        else if (showProgress) status(label + "…");
        updateActions();
        return accepted;
    }

    /** Only resumes the user's selected MBean read; never retains or retries a failed mutation. */
    private void drainPendingBean() {
        if (disposed || client == null || runner.isBusy() || pendingBean == null) return;
        String pending = pendingBean; pendingBean = null;
        if (Objects.equals(pending, beans.getSelectedValue())) loadBean();
    }

    private void sampleNow(boolean manual) {
        JmxClient active = client; if (active == null) return;
        if (!manual && runner.isBusy()) return;
        background("Sample JVM metrics", true, () -> new SampleResult(active.sample(), renderNotifications(active.notifications())), result -> {
            showSample(result.sample()); showNotifications(result.notifications());
            if (manual) status("Metrics updated · " + time(result.sample().captureEnd()) + ". Missing values are not treated as zero.");
        }, manual);
    }
    private record SampleResult(JmxClient.Sample sample, String notifications) {}

    private void showSample(JmxClient.Sample value) {
        sample = value; metricModel.setRowCount(0);
        refreshHighlights();
        ((CardLayout) overviewBody.getLayout()).show(overviewBody, identity == null ? "welcome" : "data");
        if (value == null) {
            sampleWindow.setText("No metrics were captured in this snapshot"); history.clear(); trendMetric.removeAllItems();
            timeline.update(List.of(), offline);
            trendInfo.setText("0 / 120 samples · No metrics in this snapshot; no trend history can be recovered.");
            chart.repaint(); updateSnapshotSummary(); return;
        }
        for (JmxClient.Metric metric : value.metrics()) {
            metricModel.addRow(new Object[]{metricLabel(metric.key(), metric.label()), metric.available() ? NumberFormat.getNumberInstance().format(metric.value()) : "—", metric.unit(), metric.available() ? "Captured" : metric.error()});
            boolean exists = false;
            for (int i = 0; i < trendMetric.getItemCount(); i++) if (trendMetric.getItemAt(i).key().equals(metric.key())) exists = true;
            if (!exists) trendMetric.addItem(new MetricChoice(metric.key(), metricLabel(metric.key(), metric.label()), metric.unit()));
        }
        sampleWindow.setText("Capture window: " + window(value.captureStart(), value.captureEnd()) + " · Source: standard MXBeans");
        history.addLast(value); while (history.size() > 120) history.removeFirst();
        timeline.update(new ArrayList<>(history), offline);
        if (client != null) connectionProblem = null;
        refreshSamplingPresentation();
        chart.repaint(); updateSnapshotSummary();
    }

    private void refreshHighlights() {
        for (var entry : highlights.entrySet()) {
            JmxClient.Metric metric = sample == null ? null : sample.metrics().stream().filter(m -> m.key().equals(entry.getKey())).findFirst().orElse(null);
            String text = "—";
            if (metric != null && metric.available()) {
                double value = metric.value().doubleValue();
                text = entry.getKey().equals("heap.used") ? String.format(Locale.ROOT, "%.1f", value / 1_048_576)
                        : entry.getKey().equals("cpu.process") ? String.format(Locale.ROOT, "%.1f %%", value)
                        : NumberFormat.getNumberInstance().format(metric.value());
            }
            entry.getValue().setText(text);
            entry.getValue().setToolTipText("Metric details: " + (metric == null ? "Not captured" : metric.available() ? metric.label() + " · " + metric.unit() : metric.error()));
        }
    }

    private static String metricLabel(String key, String fallback) {
        return switch (key) {
            case "heap.used" -> "Heap · used"; case "heap.committed" -> "Heap · committed"; case "heap.max" -> "Heap · maximum";
            case "nonheap.used" -> "Non-heap · used"; case "nonheap.committed" -> "Non-heap · committed"; case "nonheap.max" -> "Non-heap · maximum";
            case "cpu.process" -> "Process CPU · recent load"; case "cpu.time" -> "Process CPU · total time";
            case "threads.live" -> "Live platform threads"; case "threads.daemon" -> "Daemon platform threads";
            case "classes.loaded" -> "Loaded classes"; case "runtime.uptime" -> "JVM uptime";
            case "gc.count" -> "GC · total collections"; case "gc.time" -> "GC · total time"; default -> fallback;
        };
    }

    private void refreshNames() {
        JmxClient active = client; if (active == null) return;
        background("Query MBean directory", true, () -> new Names(active.queryNames(), active.namesTruncated()), result -> {
            objectNames = result.names(); filterBeans(); status(result.truncated() ? "The MBean directory reached its limit and was truncated." : "MBean directory refreshed.");
        });
    }
    private record Names(List<String> names, boolean truncated) {}

    private void filterBeans() {
        favorites.clear(); favorites.addAll(BeaconSettings.favorites());
        String selected = beans.getSelectedValue(), query = search.getText().toLowerCase(Locale.ROOT).trim();
        rebuildingBeanList = true;
        try {
            beanModel.clear();
            for (String name : objectNames) if ((!favoritesOnly.isSelected() || favorites.contains(name)) && name.toLowerCase(Locale.ROOT).contains(query)) beanModel.addElement(name);
            if (selected != null && beanModel.contains(selected)) beans.setSelectedValue(selected, true);
        } finally { rebuildingBeanList = false; }
        beanCount.setText(beanModel.size() + " / " + objectNames.size() + " MBeans" + (beanModel.isEmpty() ? " · No matches" : ""));
        if (beans.getSelectedValue() == null) clearBeanDetails(emptyBeanMessage());
    }

    private void toggleFavorite() {
        String name = beans.getSelectedValue(); if (name == null) { status("Select an MBean first."); return; }
        // Read current application preferences before changing one item; another tab may have edited them.
        favorites.clear(); favorites.addAll(BeaconSettings.favorites());
        if (!favorites.remove(name)) {
            if (favorites.size() >= 200) { status("The 200-favorite limit has been reached. Remove a favorite first."); return; }
            favorites.add(name);
        }
        BeaconSettings.saveFavorites(favorites); filterBeans(); beans.repaint();
    }

    private void loadBean() {
        JmxClient active = client; String name = beans.getSelectedValue();
        if (name == null) { clearBeanDetails(emptyBeanMessage()); return; }
        if (active == null) return;
        if (!Objects.equals(name, inspectedBean)) {
            clearBeanDetails(name + "\nWaiting to read metadata and attributes…");
        }
        if (runner.isBusy()) { pendingBean = name; status("A request is running. The selected MBean will be read when it finishes."); return; }
        pendingBean = null;
        background("Read selected MBean", true, () -> readBean(active, name), data -> {
            if (!Objects.equals(beans.getSelectedValue(), data.name())) {
                if (beans.getSelectedValue() == null) status("No MBean is selected. The previous object's result was discarded.");
                loadBean(); return;
            }
            inspectedBean = data.name();
            if (Objects.equals(pendingBean, data.name())) pendingBean = null;
            beanHeading.setText(data.name() + "\n" + data.info().getDescription() + "\nRead window: " + window(data.captureStart(), data.captureEnd()) + "; attributes are read sequentially, not as an atomic snapshot.");
            beanHeading.setCaretPosition(0);
            attributeValues = data.attributes(); attributePresentations = data.presentations(); attributeModel.setRowCount(0); operationModel.clear();
            attributeContext = "Target: " + identityLabel() + "\nMBean: " + data.name() + "\nRead window: " + window(data.captureStart(), data.captureEnd()) + " · Sequential attribute reads; not atomic.";
            operationSelectionGeneration++;
            operationStructure = null; exploreOperation.setEnabled(false); operationResult.setText("");
            for (int i = 0; i < attributeValues.size(); i++) {
                JmxClient.AttributeValue a = attributeValues.get(i);
                attributeModel.addRow(new Object[]{a.name(), a.type(), shortened(attributePresentations.get(i).text(), 160), (a.readable() ? "Read" : "") + (a.writable() ? "/Write" : "")});
            }
            for (MBeanOperationInfo op : data.info().getOperations()) operationModel.addElement(op);
            valueDetail.setText("Select an attribute to inspect complex values. Display size is bounded; large values are truncated. Arbitrary Java objects cannot be edited.");
            status("Read " + data.name() + ". Disable Read-only and confirm each request to write attributes or invoke operations.");
        });
    }
    private String emptyBeanMessage() {
        if (offline) return "Offline snapshots do not include arbitrary MBean data. Connect to a JVM to browse MBeans.";
        if (client == null) return "Not connected. Connect to a JVM to search and select MBeans.";
        return beanModel.isEmpty() ? "No matching MBeans. Adjust the search or turn off Favorites." : "Select an MBean to read its metadata and attributes.";
    }

    private void clearBeanDetails(String message) {
        inspectedBean = null; pendingBean = null; attributeValues = List.of(); attributePresentations = List.of(); attributeContext = "";
        invocationPending = false;
        operationSelectionGeneration++;
        operationStructure = null; operationContext = ""; exploreAttribute.setEnabled(false); exploreOperation.setEnabled(false);
        attributeModel.setRowCount(0); operationModel.clear(); valueDetail.setText(""); operationResult.setText("");
        beanHeading.setText(message);
    }

    private static BeanData readBean(JmxClient active, String name) throws Exception {
        long captureStart = System.currentTimeMillis();
        MBeanInfo info = active.info(name);
        List<JmxClient.AttributeValue> values = active.readAttributes(name);
        long captureEnd = System.currentTimeMillis();
        List<AttributePresentation> presentations = new ArrayList<>(); List<JmxClient.AttributeValue> metadata = new ArrayList<>();
        StructuredValue.Budget structureBudget = new StructuredValue.Budget(4096, 262_144);
        int remaining = 1_048_576;
        for (JmxClient.AttributeValue a : values) {
            String text = remaining <= 0 ? "[Combined attribute display limit reached: about 1 million characters. This value was not expanded.]"
                    : a.error() == null ? ValueFormatter.format(a.value()) : "Read failed / unsupported: " + a.error();
            if (text.length() > remaining && remaining > 0) text = shortened(text, remaining);
            StructuredValue structure = a.error() == null && a.readable() ? StructuredValue.capture(a.name(), a.value(), structureBudget) : null;
            if (structure == null && a.error() == null && a.readable()) text += "\n[Explorer batch limit reached: 4096 nodes / 262144 characters.]";
            remaining -= text.length(); presentations.add(new AttributePresentation(text, structure));
            // Only bounded presentation text survives on the UI; discard arbitrary remote object graphs.
            metadata.add(new JmxClient.AttributeValue(a.name(), a.type(), a.readable(), a.writable(), null, a.error()));
        }
        return new BeanData(name, info, List.copyOf(metadata), List.copyOf(presentations), captureStart, captureEnd);
    }
    private record BeanData(String name, MBeanInfo info, List<JmxClient.AttributeValue> attributes, List<AttributePresentation> presentations,
                            long captureStart, long captureEnd) {}

    private boolean writableContext() {
        if (client == null) { status("Connect to a JVM first."); return false; }
        if (observe.isSelected()) { status("Read-only mode is on. To make a change, turn it off in the toolbar and verify the target in each confirmation."); return false; }
        if (runner.isBusy()) { status("A request is running. Wait for it to finish before reviewing a write or invocation. Nothing was submitted."); return false; }
        if (inspectedBean == null || !Objects.equals(inspectedBean, beans.getSelectedValue())) { status("Wait for the selected MBean to finish loading."); return false; }
        return true;
    }

    private void writeAttribute() {
        if (!writableContext()) return;
        int index = attributes.getSelectedRow();
        if (index < 0 || index >= attributeValues.size()) { status("Select an attribute to edit."); return; }
        JmxClient.AttributeValue attribute = attributeValues.get(index);
        if (!attribute.writable()) { status("The attribute's metadata marks it as read-only."); return; }
        if (!TypeCodec.supports(attribute.type())) { status("Editing is not supported for type " + attribute.type() + ". Arbitrary objects are not constructed through reflection."); return; }
        JmxClient active = client; String bean = inspectedBean;
        ValueInputDialog input = new ValueInputDialog(project, "Edit attribute · " + attribute.name(), identityLabel() + "\n" + bean + "\nWriting may change application behavior. A timed-out write is never retried automatically.", List.of(attribute.name()), List.of(attribute.type()));
        if (!confirmations.show(input::showAndGet)) return;
        if (client != active || observe.isSelected()) { status("The session or Read-only mode changed. Nothing was written. Verify the target again."); return; }
        if (runner.isBusy()) { status("Another request is running. Nothing was written. Review and confirm the write again after it finishes."); return; }
        String text = input.values().getFirst();
        background("Write attribute (outcome unknown if timed out)", true, () -> { active.setAttribute(bean, attribute.name(), attribute.type(), text); return true; }, ignored -> {
            status("Attribute write returned successfully. Reading back the current value…"); loadBean();
        });
    }

    private void invokeOperation() {
        if (!writableContext()) return;
        MBeanOperationInfo op = operations.getSelectedValue();
        if (op == null) { status("Select an operation."); return; }
        List<String> names = new ArrayList<>(), types = new ArrayList<>();
        for (MBeanParameterInfo parameter : op.getSignature()) {
            if (!TypeCodec.supports(parameter.getType())) { status("This operation has an unsupported parameter type: " + parameter.getType()); return; }
            names.add(parameter.getName()); types.add(parameter.getType());
        }
        JmxClient active = client; String bean = inspectedBean;
        ValueInputDialog input = new ValueInputDialog(project, "Invoke operation · " + op.getName(), identityLabel() + "\n" + bean + "\n" + operationSignature(op) + "\n" + op.getDescription() + "\nInvocation may modify, block or restart the target. A parameterless operation is not necessarily safe. Timed-out invocations are never retried automatically.", names, types);
        if (!confirmations.show(input::showAndGet)) return;
        if (client != active || observe.isSelected()) { status("The session or Read-only mode changed. The operation was not invoked. Verify the target again."); return; }
        if (runner.isBusy()) { status("Another request is running. The operation was not invoked. Review and confirm it again after the request finishes."); return; }
        List<String> inputs = input.values();
        String invocationTarget = identityLabel();
        long expectedSelection = ++operationSelectionGeneration;
        boolean accepted = backgroundWithDeadline("Invoke " + op.getName() + " (outcome unknown if timed out)", true, 8_000, () -> {
            long captureStart = System.currentTimeMillis();
            Object value = active.invoke(bean, op, inputs);
            long captureEnd = System.currentTimeMillis();
            return new OperationResult(ValueFormatter.format(value), StructuredValue.capture("Return value", value), captureStart, captureEnd);
        }, result -> {
            invocationPending = false;
            if (expectedSelection != operationSelectionGeneration || !Objects.equals(inspectedBean, bean) || operations.getSelectedValue() != op) {
                status("The operation returned, but the selected operation changed. Its result was discarded; it will not be retried."); return;
            }
            operationContext = "Target: " + invocationTarget + "\nMBean: " + bean + "\n" + operationSignature(op) + "\nInvocation window: " + window(result.captureStart(), result.captureEnd());
            operationStructure = result.structure(); exploreOperation.setEnabled(operationStructure != null);
            operationResult.setText(operationContext + "\n" + result.value());
            operationResult.setCaretPosition(0); status("The operation returned. Its result is shown below; the invocation will not be retried automatically.");
        }, error -> {
            invocationPending = false;
            if (expectedSelection != operationSelectionGeneration || !Objects.equals(inspectedBean, bean) || operations.getSelectedValue() != op) return;
            operationResult.setText(error.contains("[CAPACITY]") ? "Invocation was not started.\n" + error
                    : "Invocation did not return a result.\n" + error
                        + "\nInspect the target before another invocation; a failed response cannot establish whether it executed.");
            operationResult.setCaretPosition(0);
        });
        if (accepted) {
            invocationPending = true;
            operationStructure = null; exploreOperation.setEnabled(false);
            operationResult.setText("Invocation pending. If waiting times out, its outcome is unknown; do not automatically repeat it.");
        }
    }
    private record OperationResult(String value, StructuredValue structure, long captureStart, long captureEnd) {}

    private void subscribe() {
        if (runner.isBusy()) { status("A request is running. Wait for it to finish before subscribing."); return; }
        JmxClient active = client; String name = beans.getSelectedValue(); if (active == null || name == null) { status("Connect and select the MBean to subscribe to."); return; }
        if (confirmations.show(() -> Messages.showOkCancelDialog(project, "Target: " + identityLabel() + "\nMBean: " + name + "\nThis registers a notification listener and may add target and network overhead. Only the latest 200 notifications received from now on are retained. Subscribing to another MBean removes the current subscription.", "Subscribe to JMX notifications", "Subscribe", "Cancel", Messages.getQuestionIcon())) != Messages.OK) return;
        if (client != active) { status("The session changed. No subscription was added. Verify the target again."); return; }
        if (runner.isBusy()) { status("Another request is running. No subscription was added. Confirm again after it finishes."); return; }
        String previous = subscribedBean;
        subscription.setText("Switching subscriptions. If this fails, subscription state is uncertain; subscribe again or disconnect.");
        background("Subscribe to MBean notifications", true, () -> { if (previous != null) active.unsubscribe(previous); active.subscribe(name); return true; }, ignored -> {
            subscribedBean = name; subscription.setText("Subscribed: " + name + " · " + time(System.currentTimeMillis())); status("Notification listener registered. Refresh to read the buffer; auto-sampling also updates notifications.");
        });
    }

    private void unsubscribe() {
        JmxClient active = client; String name = subscribedBean;
        if (active == null || name == null) { status("No active subscription."); return; }
        background("Remove notification listener", true, () -> { active.unsubscribe(name); return true; }, ignored -> { subscribedBean = null; subscription.setText("Unsubscribed · Previously received notifications remain below"); status("Notification listener removed."); });
    }

    private void refreshNotifications() {
        JmxClient active = client; if (active == null) return;
        background("Read notification buffer", true, () -> renderNotifications(active.notifications()), events -> { showNotifications(events); status("Notification buffer refreshed. Up to 200 recent notifications are shown."); });
    }

    private static String renderNotifications(List<JmxClient.NotificationEvent> events) {
        StringBuilder text = new StringBuilder("Latest " + events.size() + "/200 notifications · Local receive buffer. Notifications from before subscription cannot be recovered.\n\n");
        for (JmxClient.NotificationEvent event : events) {
            text.append(time(event.timestamp())).append(" #").append(event.sequence()).append(" ").append(event.type()).append("\nSource: ").append(event.source()).append("\n").append(shortened(event.message(), 1500)).append("\nData: ").append(shortened(ValueFormatter.format(event.userData()), 1500)).append("\n\n");
        }
        return text.toString();
    }

    private void showNotifications(String text) { notificationText.setText(text); notificationText.setCaretPosition(0); }

    private void captureThreads() {
        JmxClient active = client; if (active == null) return;
        background("Capture platform threads", true, active::threads, result -> { showThreads(result); status("Thread snapshot updated. See capture coverage and deadlock-query results above. No reported problem does not rule one out."); });
    }

    private void showThreads(JmxClient.ThreadDump value) {
        dump = value; threadModel.clear(); frameModel.clear();
        lockChains.showCapture(value);
        pinThreads.setEnabled(identity != null && value != null);
        compareThreads.setEnabled(threadBaseline != null && value != null);
        if (value == null) { threadWindow.setText("No threads captured in this snapshot · This does not mean there are zero threads"); threadWindow.setToolTipText(null); threadCount.setText("No thread capture"); threads.getEmptyText().setText("Capture a snapshot to inspect platform threads"); threadDetail.setText("No thread snapshot."); updateSnapshotSummary(); return; }
        filterThreads();
        threadWindow.setText(window(value.captureStart(), value.captureEnd()) + " · " + value.threads().size() + " platform threads" + (value.truncated() ? " (truncated)" : "") + " · Up to 64 frames per stack");
        threadWindow.setToolTipText("Coverage: " + value.coverage());
        threadDetail.setText("Deadlock query: " + value.deadlockStatus() + "\nReported thread IDs: " + value.deadlockedIds() + "\nPlatform threads only; this cannot rule out virtual-thread problems. Coverage: " + value.coverage());
        threadDetail.setCaretPosition(0);
        updateSnapshotSummary();
    }

    private void filterThreads() {
        JmxClient.ThreadRecord selected = threads.getSelectedValue();
        threadModel.clear(); frameModel.clear();
        String query = threadSearch.getText().strip().toLowerCase(Locale.ROOT);
        String state = String.valueOf(threadState.getSelectedItem());
        if (dump != null) for (JmxClient.ThreadRecord thread : dump.threads()) {
            if ((state.equals("All states") || thread.state().equals(state))
                    && (thread.name().toLowerCase(Locale.ROOT).contains(query) || Long.toString(thread.id()).contains(query))) threadModel.addElement(thread);
        }
        if (selected != null) for (int i = 0; i < threadModel.size(); i++) if (threadModel.get(i).id() == selected.id()) { threads.setSelectedIndex(i); break; }
        threadCount.setText(dump == null ? "No thread capture" : threadModel.size() + " / " + dump.threads().size() + " captured platform threads" + (dump.truncated() ? " · Truncated" : ""));
        threads.getEmptyText().setText(dump == null ? "Capture a snapshot to inspect platform threads"
                : dump.threads().isEmpty() ? "No platform threads returned; check capture coverage"
                : "No matches; adjust the name or state filter");
        if (threads.getSelectedValue() == null) threadDetail.setText(dump == null ? "No platform threads captured. Capture a snapshot before filtering."
                : threadModel.isEmpty() ? "No captured threads match. Adjust the filters; this does not mean the target has no threads." : "Select a platform thread to inspect its captured state and stack.");
    }

    private void navigateFrame() {
        StackTraceElement selected = frames.getSelectedValue();
        if (selected == null) { status("Select a stack frame with a file name and line number."); return; }
        SourceNavigator.navigate(project, this, selected, this::status);
    }

    private SnapshotStore.Snapshot currentSnapshot() { return new SnapshotStore.Snapshot(identity, sample, dump, notes.getText(), new ArrayList<>(history)); }

    private void saveInterval(List<JmxClient.Sample> selected) {
        if (identity == null || selected.isEmpty()) return;
        if (runner.isBusy()) { status("A request is running. The frozen interval is retained; save when it finishes."); return; }
        SnapshotStore.Snapshot capture = new SnapshotStore.Snapshot(identity, selected.getLast(), null, notes.getText(), selected);
        var file = confirmations.show(() -> FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Save captured timeline interval",
                "Includes selected metrics, target identity and notes. No threads, credentials or MBean values. Review notes before sharing.", "jvmb"), project)
                .save((com.intellij.openapi.vfs.VirtualFile) null, "jvm-beacon-interval-" + System.currentTimeMillis() + ".jvmb"));
        if (file == null) return;
        Path path = file.getFile().toPath();
        background("Save timeline interval", false, () -> { SnapshotStore.save(path, capture); return path; },
                saved -> status("Saved " + capture.history().size() + " captured samples: " + saved + ". Open this file to inspect the interval offline."));
    }

    private void saveSnapshot() {
        if (identity == null || (sample == null && dump == null)) { status("Capture metrics or threads from a connected JVM, or open an existing snapshot first."); return; }
        if (runner.isBusy()) { status("A request is running. You can save the captured snapshot once it finishes."); return; }
        var file = confirmations.show(() -> FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Save JVM Beacon snapshot", "Includes captured metrics, platform threads and notes. Thread names and stacks are not automatically redacted.", "jvmb"), project).save((com.intellij.openapi.vfs.VirtualFile) null, "jvm-beacon-" + System.currentTimeMillis() + ".jvmb"));
        if (file == null) return;
        SnapshotStore.Snapshot capture = currentSnapshot(); Path path = file.getFile().toPath();
        background("Save snapshot", false, () -> { SnapshotStore.save(path, capture); return path; }, saved -> status("Snapshot saved: " + saved + ". Only data actually captured is included."));
    }

    private void openSnapshot(boolean compare) {
        if (runner.isBusy()) { status("A request is running. You can open or compare snapshots once it finishes."); return; }
        if (compare && identity == null) { status("Capture or open a snapshot to use as the comparison baseline."); return; }
        var descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("jvmb");
        descriptor.setTitle(compare ? "Select a JVM Beacon snapshot to compare" : "Open JVM Beacon snapshot (switches to offline on success)");
        var file = confirmations.show(() -> FileChooser.chooseFile(descriptor, project, null));
        if (file == null) return;
        if (runner.isBusy()) { status("Another request is running. The snapshot was not opened or compared. Select the file again after it finishes."); return; }
        Path path = Path.of(file.getPath());
        if (compare) {
            SnapshotStore.Snapshot current = currentSnapshot();
            record Compared(String text, ThreadComparison.Report threads, String labels, SnapshotStore.Snapshot baseline) { }
            long generation = ++comparisonGeneration;
            background("Load and compare snapshots", false, () -> {
                SnapshotStore.Snapshot before = SnapshotStore.load(path);
                String labels = comparisonCaptureLabel("A · Snapshot from file", before) + "\n\n"
                        + comparisonCaptureLabel("B · Snapshot at file selection", current);
                return new Compared(labels + "\n\n" + SnapshotStore.compare(before, current),
                        ThreadComparison.compare(before.identity(), before.threads(), current.identity(), current.threads()), labels,
                        new SnapshotStore.Snapshot(before.identity(), null, before.threads(), ""));
            }, result -> {
                if (generation != comparisonGeneration) return;
                threadBaseline = result.baseline().threads() == null ? null : result.baseline();
                baselineLabel.setText(threadBaseline == null ? "File A has no threads; pin a captured baseline to compare again" : "A from file · " + window(threadBaseline.threads().captureStart(), threadBaseline.threads().captureEnd()));
                comparisonText.setText("Fixed comparison; later samples will not overwrite these results.\nOrder: A is the selected file; B is the snapshot at file selection.\nFile: " + path + "\n\n" + result.text());
                threadComparison.showReport(result.threads(), result.labels());
                comparisonText.setCaretPosition(0); snapshotViews.setSelectedIndex(1); pages.setSelectedIndex(3);
                status("Results are retained in Comparison and Threads → Compare. Different windows or targets cannot be assumed to represent deltas from the same process.");
            });
        } else background("Load snapshot", false, () -> SnapshotStore.load(path), loaded -> {
            releaseSession(); offline = true; identity = loaded.identity(); history.clear(); timeline.reset(); trendMetric.removeAllItems();
            if (!loaded.history().isEmpty()) history.addAll(loaded.history().subList(0, loaded.history().size() - 1));
            connectionProblem = null; identityNotice = null; lastTarget = null;
            hotThreads.clear();
            resetThreadComparison();
            watchTarget = null; watchSeries.clear(); renderWatch();
            clearBeans(); notes.setText(loaded.notes()); showSample(loaded.sample()); showThreads(loaded.threads());
            updateActions(); snapshotViews.setSelectedIndex(0); pages.setSelectedIndex(loaded.history().size() > 1 ? 4 : 3); status("Offline snapshot opened; the live connection is closed. Data absent from the file cannot be recovered.");
        });
    }

    private static String comparisonCaptureLabel(String label, SnapshotStore.Snapshot snapshot) {
        JmxClient.Identity capturedIdentity = snapshot.identity();
        return label + "\nruntimeName: " + capturedIdentity.runtimeName() + "\nvmName: " + capturedIdentity.vmName()
                + "\nJVM started: " + time(capturedIdentity.startTime()) + " (" + capturedIdentity.startTime() + " epoch ms)"
                + "\nMetric capture window: " + (snapshot.sample() == null ? "Not captured" : window(snapshot.sample().captureStart(), snapshot.sample().captureEnd()))
                + "\nThread capture window: " + (snapshot.threads() == null ? "Not captured" : window(snapshot.threads().captureStart(), snapshot.threads().captureEnd()))
                + "\nMetrics and threads are collected separately, not atomically.";
    }

    private void updateSnapshotSummary() {
        if (identity == null) return;
        snapshotText.setText("Target: " + identityLabel() + "\nJVM: " + identity.vmName() + " " + identity.vmVersion() + "\nProcess started: " + time(identity.startTime())
                + "\n\nMetric window: " + (sample == null ? "Not captured" : window(sample.captureStart(), sample.captureEnd()))
                + "\nThread window: " + (dump == null ? "Not captured" : window(dump.captureStart(), dump.captureEnd()))
                + "\n\nSource: Management MXBeans. Metrics and threads may come from different times; this is not an atomic snapshot."
                + "\nThread coverage: " + (dump == null ? "Not captured" : dump.coverage())
                + "\nThis format does not export arbitrary MBean attributes, operation results, notifications, credentials or heap contents."
                + "\nTarget identity, thread names, stacks, locks and notes are not automatically redacted. Review the file before sharing."
                + "\nRetention: " + history.size() + " metric samples (maximum 120) and one separately captured thread snapshot. Only retained observations are saved."
                + "\nTimeline → Freeze & select → Save interval exports selected metrics and notes without threads. Older data and pauses cannot be recovered."
                + "\n\nInspect captured data in Telemetry, Timeline and Threads.");
        snapshotText.setCaretPosition(0);
    }

    private void clearBeans() {
        objectNames = List.of(); beanModel.clear(); clearBeanDetails("Offline snapshots do not include arbitrary MBean values.");
        beanHeading.setText("Offline snapshots do not include arbitrary MBean data. Connect to a JVM to browse MBeans."); valueDetail.setText(""); operationResult.setText(""); notificationText.setText("Snapshots do not include notifications."); beanCount.setText("Offline · No MBean directory");
    }

    private void updateActions() {
        boolean live = client != null;
        jfr.setSession(client, runner.isBusy(), observe.isSelected());
        hotThreads.setConnectionState(live, runner.isBusy());
        pinThreads.setEnabled(identity != null && dump != null && !runner.isBusy());
        compareThreads.setEnabled(threadBaseline != null && dump != null && !runner.isBusy());
        liveActions.forEach(button -> button.setEnabled(live)); observe.setEnabled(live); autoSample.setEnabled(live);
        disconnect.setEnabled(live || runner.isBusy()); connect.setText(live ? "Replace JVM…" : "Connect JVM…");
        reconnect.setEnabled(lastTarget != null && !offline && !runner.isBusy());
        disconnect.setText(runner.isBusy() ? "Stop waiting" : "Disconnect");
        disconnect.setToolTipText("Disconnect and stop waiting. The underlying call may continue; mutation outcome may be unknown.");
        BeaconUi.connectionBadge(connectionState, live, offline, identity != null);
        target.setText(identity == null ? "JMX management & JVM diagnostics" : identity.runtimeName() + " · " + identity.vmName() + " · Started " + time(identity.startTime()));
        target.setToolTipText("Target: " + target.getText());
        ((CardLayout) overviewBody.getLayout()).show(overviewBody, identity == null ? "welcome" : "data");
        presentationListener.run();
        refreshSamplingPresentation();
    }

    JComponent connectionFocus() { return connect; }
    void setPresentationListener(Runnable listener) { presentationListener = listener; listener.run(); }
    String tabTitle(int id) {
        String name = !offline && lastTarget != null && !lastTarget.alias().isBlank() ? lastTarget.alias() : identity == null ? "New connection" : identity.runtimeName().replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (name.length() > 28) name = name.substring(0, 27) + "…";
        String state = client != null ? "" : runner.isBusy() ? " · Waiting" : offline ? " · Snapshot" : identity != null ? " · Stale" : "";
        return id + " · " + name + state;
    }
    String tabDescription(int id) {
        // Content descriptions are rendered as a single-line native tooltip; keep it screen-bounded.
        return tabTitle(id) + " · " + (client != null ? "Connected" : offline ? "Offline" : "Disconnected")
                + (identity == null ? "" : " · Started " + time(identity.startTime()));
    }

    private String identityLabel() { return identity == null ? "Unknown target" : identity.runtimeName() + " / " + identity.vmName(); }
    private void status(String message) { if (!disposed) { status.setText(message); status.setCaretPosition(0); } }
    @Override public void dispose() { disposed = true; presentationListener = () -> {}; timer.stop(); presentationTimer.stop(); connectionStage.set(null); runner.close(); Connected old = session; session = null; client = null; SessionRunner.closeLater(old); }

    private JButton liveButton(String label, Runnable action) { JButton button = new JButton(label); button.addActionListener(e -> action.run()); liveActions.add(button); return button; }
    private static JPanel row(Component... items) { JPanel panel = new JPanel(new BeaconUi.WrapLayout()); panel.setOpaque(false); for (Component item : items) panel.add(item); return panel; }
    private static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int column) { return false; } }; }
    private static JLabel plainLabel(String text) { JLabel label = new JLabel(); label.putClientProperty("html.disable", Boolean.TRUE); label.setText(text); return label; }
    private static JTextArea textArea(String text, int rows) { return BeaconUi.text(text, rows); }
    private static JButton copyButton(JTextArea area) {
        JButton button = new JButton("Copy");
        button.addActionListener(e -> com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(area.getSelectedText() == null ? area.getText() : area.getSelectedText())));
        button.setToolTipText("Copy selected text, or copy the entire area when nothing is selected"); return button;
    }
    private static JPanel listCell(JList<?> list, String primary, String secondary, boolean selected, boolean focus) {
        JPanel panel = BeaconUi.panel(3); panel.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        panel.setBorder(focus ? JBUI.Borders.compound(JBUI.Borders.customLine(BeaconUi.ACCENT), JBUI.Borders.empty(7, 9)) : JBUI.Borders.empty(8, 10));
        JLabel first = BeaconUi.label(primary, false), second = BeaconUi.label(secondary, true);
        first.setFont(list.getFont()); second.setFont(list.getFont());
        first.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
        second.setForeground(selected ? list.getSelectionForeground() : BeaconUi.MUTED);
        panel.add(first, BorderLayout.NORTH); panel.add(second, BorderLayout.SOUTH); return panel;
    }
    private static String time(long timestamp) { return TIME.format(Instant.ofEpochMilli(timestamp)); }
    private static String window(long start, long end) { return time(start) + " — " + time(end) + " (" + Math.max(0, end - start) + " ms)"; }
    private static String shortened(String value, int limit) { if (value == null) return "null"; return value.length() <= limit ? value : value.substring(0, limit) + "… [display truncated]"; }
    private static String operationSignature(MBeanOperationInfo operation) { return operation.getName() + "(" + String.join(", ", Arrays.stream(operation.getSignature()).map(p -> p.getType() + " " + p.getName()).toList()) + ") : " + operation.getReturnType(); }
    private record MetricChoice(String key, String label, String unit) { @Override public String toString() { return label + " (" + unit + ")"; } }

    private static final class ValueInputDialog extends DialogWrapper {
        private final String explanation;
        private final List<String> names, types;
        private final List<JBTextField> inputs = new ArrayList<>();
        ValueInputDialog(Project project, String title, String explanation, List<String> names, List<String> types) {
            super(project, true); this.explanation = explanation; this.names = names; this.types = types;
            setTitle(title); setOKButtonText("Execute once"); setCancelButtonText("Cancel"); init();
        }
        @Override protected JComponent createCenterPanel() {
            int gap = JBUI.scale(8);
            Dimension available = availableDialogArea();
            int width = Math.min(JBUI.scale(640), Math.max(JBUI.scale(320), available.width - JBUI.scale(100)));
            int heightLimit = Math.min(JBUI.scale(460), Math.max(JBUI.scale(160), available.height - JBUI.scale(180)));
            JPanel panel = new JPanel(new BorderLayout(0, gap));
            JTextArea caption = textArea(explanation, 0); caption.setLineWrap(true); caption.setWrapStyleWord(true);
            caption.getAccessibleContext().setAccessibleName("Target and impact of this request");
            int captionRows = Math.min(8, Math.max(4, (int) explanation.lines().count() + 2));
            int captionHeight = Math.min(heightLimit / 2, captionRows * caption.getFontMetrics(caption.getFont()).getHeight() + gap);
            JBScrollPane captionScroll = new JBScrollPane(caption, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            captionScroll.setBorder(JBUI.Borders.empty());
            captionScroll.setPreferredSize(new Dimension(width, captionHeight));
            captionScroll.setMinimumSize(new Dimension(0, JBUI.scale(32)));
            panel.add(captionScroll, BorderLayout.NORTH);
            JPanel fields = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints();
            c.insets = JBUI.insets(5); c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.NORTHWEST;
            for (int i = 0; i < names.size(); i++) {
                JLabel label = plainLabel(names.get(i) + " : " + types.get(i));
                c.gridy = i * 2; c.gridx = 0; c.weightx = 0; c.gridwidth = 1; fields.add(label, c);
                JBTextField field = new JBTextField(); field.getAccessibleContext().setAccessibleName(names.get(i)); inputs.add(field);
                label.setLabelFor(field);
                c.gridx = 1; c.weightx = 1; fields.add(field, c);
                c.gridy++; c.gridx = 0; c.gridwidth = 2; fields.add(new JLabel(TypeCodec.inputHint(types.get(i))), c);
            }
            if (names.isEmpty()) {
                c.gridy = 0; c.gridx = 0; c.gridwidth = 2; c.weightx = 1;
                fields.add(new JLabel("This operation has no parameters. Review the target and impact above."), c);
            }
            // Let only the final spacer absorb extra viewport height; parameter rows stay at the top.
            c.gridy = Math.max(1, names.size() * 2); c.gridx = 0; c.gridwidth = 2;
            c.weightx = 1; c.weighty = 1; c.fill = GridBagConstraints.BOTH; c.insets = JBUI.emptyInsets();
            fields.add(Box.createVerticalGlue(), c);
            int fieldsHeight = Math.max(JBUI.scale(36), Math.min(fields.getPreferredSize().height + gap,
                    Math.min(JBUI.scale(260), heightLimit - captionHeight - gap)));
            JBScrollPane fieldsScroll = new JBScrollPane(fields);
            fieldsScroll.getAccessibleContext().setAccessibleName("Operation parameters");
            fieldsScroll.setBorder(JBUI.Borders.empty());
            fieldsScroll.setPreferredSize(new Dimension(width, fieldsHeight));
            fieldsScroll.setMinimumSize(new Dimension(0, JBUI.scale(36)));
            panel.add(fieldsScroll, BorderLayout.CENTER);
            int height = captionHeight + gap + fieldsHeight;
            panel.setPreferredSize(new Dimension(width, height));
            panel.setMinimumSize(new Dimension(Math.min(width, JBUI.scale(320)), Math.min(height, JBUI.scale(160))));
            BeaconUi.applyTypography(panel);
            return panel;
        }

        private Dimension availableDialogArea() {
            if (GraphicsEnvironment.isHeadless()) return JBUI.size(1000, 800);
            Window owner = getOwner();
            GraphicsConfiguration configuration = owner == null ? null : owner.getGraphicsConfiguration();
            if (configuration == null) return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().getSize();
            Rectangle bounds = configuration.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            return new Dimension(bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
        }
        @Override protected ValidationInfo doValidate() {
            for (int i = 0; i < inputs.size(); i++) {
                try { TypeCodec.parse(types.get(i), inputs.get(i).getText()); }
                catch (IllegalArgumentException e) { return new ValidationInfo(names.get(i) + ": " + e.getMessage(), inputs.get(i)); }
            }
            return null;
        }
        List<String> values() { return inputs.stream().map(JBTextField::getText).toList(); }
    }
}
