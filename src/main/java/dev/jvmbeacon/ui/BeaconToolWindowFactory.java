package dev.jvmbeacon.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.Disposable;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;
import java.util.List;

public final class BeaconToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        new ConnectionTabs(project, toolWindow).addTab();
    }

    /** Native contents own their workbenches; only the bounded worker pools are shared. */
    private static final class ConnectionTabs implements Disposable {
        private static final int MAX_TABS = 8;
        private final Project project;
        private final ToolWindow toolWindow;
        private final ContentManager contents;
        private int nextId;
        private boolean disposed;

        ConnectionTabs(Project project, ToolWindow toolWindow) {
            this.project = project;
            this.toolWindow = toolWindow;
            this.contents = toolWindow.getContentManager();
            Disposer.register(contents, this);
            DumbAwareAction add = new DumbAwareAction("New connection tab", "Open an independent JVM or snapshot tab (up to 8 per project)", AllIcons.General.Add) {
                @Override public void actionPerformed(@NotNull AnActionEvent e) { addTab(); }
                @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
                @Override public void update(@NotNull AnActionEvent e) {
                    e.getPresentation().setEnabled(!disposed && !project.isDisposed() && contents.getContentCount() < MAX_TABS);
                }
            };
            toolWindow.setTitleActions(List.of(add));
            // Keep the shortcut consumed at the tab limit instead of falling through to IDE Generate.
            DumbAwareAction shortcut = new DumbAwareAction("New connection tab") {
                @Override public void actionPerformed(@NotNull AnActionEvent e) { addTab(); }
            };
            shortcut.registerCustomShortcutSet(new CustomShortcutSet(javax.swing.KeyStroke.getKeyStroke("alt INSERT")), toolWindow.getComponent(), this);
            ContentManagerListener listener = new ContentManagerListener() {
                @Override public void contentRemoved(@NotNull ContentManagerEvent event) {
                    if (disposed || project.isDisposed() || contents.isDisposed()) return;
                    // Defer until removal/disposal completes; never recreate UI during project/plugin disposal.
                    ToolWindowManager.getInstance(project).invokeLater(() -> {
                        if (!disposed && !project.isDisposed() && !contents.isDisposed() && contents.getContentCount() == 0) addTab();
                    });
                }
            };
            contents.addContentManagerListener(listener);
            Disposer.register(this, () -> contents.removeContentManagerListener(listener));
        }

        void addTab() {
            if (disposed || project.isDisposed() || contents.isDisposed() || contents.getContentCount() >= MAX_TABS) return;
            int id = ++nextId;
            BeaconPanel panel = new BeaconPanel(project);
            Content content = ContentFactory.getInstance().createContent(panel, panel.tabTitle(id), false);
            content.setCloseable(true);
            content.setDisposer(panel);
            content.setPreferredFocusableComponent(panel.connectionFocus());
            panel.setPresentationListener(() -> {
                // BeaconPanel clears this EDT-only listener before disposing its session.
                if (!disposed) {
                    content.setDisplayName(panel.tabTitle(id));
                    content.setDescription(panel.tabDescription(id));
                }
            });
            contents.addContent(content);
            contents.setSelectedContent(content, true);
        }

        @Override public void dispose() { disposed = true; }
    }
}
