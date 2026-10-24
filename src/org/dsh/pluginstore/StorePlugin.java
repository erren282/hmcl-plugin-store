/*
 * PCL 商店插件 —— 入口
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 * 它从 GitHub topic 抓取插件仓库，提供浏览、搜索与一键安装。
 */
package org.dsh.pluginstore;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.PluginPageAction;
import org.jackhuang.hmcl.ui.Controllers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Adds a plugin store page to the launcher's sidebar.
///
/// <p>The store discovers plugins from the GitHub topic `erren282s-launcher-plugins`, shows each
/// repository with its star count and summary, and installs a plugin straight from its latest
/// release jar.</p>
public final class StorePlugin extends HmclPlugin {

    /// Classpath location of the bundled style sheet.
    private static final String STYLE_RESOURCE = "/org/dsh/pluginstore/store.css";

    /// Name of the extracted style sheet inside the plugin data directory.
    private static final String STYLE_FILE = "store.css";

    /// The style sheet URL applied to the scene root, or `null`.
    private String styleUrl;

    /// The plugin-module entry that opens the store full-window.
    ///
    /// <p>The store must cover the whole window, including the settings sidebar, so it cannot be
    /// a page *inside* the settings page: the launcher renders plugin-module pages in the right
    /// content area, which leaves the sidebar visible beside it. This entry therefore only acts
    /// as a launch button — clicking it navigates the launcher's whole content area to a
    /// full-window store page.</p>
    private final PluginPageAction page = new PluginPageAction() {

        @Override
        public String getTitle() {
            return "插件商店";
        }

        @Override
        public String getDescription() {
            return "全屏浏览 GitHub topic：" + StoreSource.TOPIC + " 下的插件，一键安装";
        }

        @Override
        public Node createPage() {
            // Navigate is deferred: createPage runs while the launcher is building the plugin
            // page, and replacing the whole content area during that build would fight it.
            javafx.application.Platform.runLater(StorePlugin.this::openFullWindowStore);
            return new StackPane();
        }
    };

    /// The full-window store page, created once and reused.
    private Node storePage;

    /// Whether the store currently occupies the launcher's content area.
    private boolean storeShowing;

    /// Intercepts clicks on the title bar's back arrow while the store is open.
    private javafx.event.EventHandler<javafx.scene.input.MouseEvent> backFilter;

    @Override
    public void onEnable() {
        PluginMenuService.registerPage(page);
        installStyles();
        getContext().logInfo("插件商店已启用（全窗口模式），数据源：GitHub topic " + StoreSource.TOPIC);
    }

    @Override
    public void onDisable() {
        PluginMenuService.unregisterPage(page);
        removeStyles();
        leaveStore();
        getContext().logInfo("插件商店已停用");
    }

    /// Opens the store across the launcher's entire content area.
    ///
    /// <p>Uses the decorator's content list rather than navigating to the store as a standalone
    /// page: the launcher's navigator wraps pages in its own layout, while writing straight to
    /// the content list replaces everything, including the settings sidebar.</p>
    ///
    /// <p>The title bar's back arrow keeps its launcher binding (the button's `onAction` is
    /// bound to `onBackNavButtonActionProperty`, so replacing that property would break it), and
    /// an extra click filter is added on top: when the store is showing, the click closes the
    /// store instead of running the launcher's navigation, which knows nothing about this page
    /// and therefore did nothing.</p>
    void openFullWindowStore() {
        try {
            javafx.scene.Scene scene = Controllers.getScene();
            if (scene == null || Controllers.getDecorator() == null)
                return;

            if (storePage == null)
                storePage = new StorePage(getContext());

            Controllers.getDecorator().getDecorator().getContent().setAll(storePage);
            storeShowing = true;

            installBackFilter();
        } catch (Throwable e) {
            getContext().logWarning("无法打开全窗口商店：" + e);
        }
    }

    /// Intercepts the title bar's back arrow while the store is open.
    ///
    /// <p>An event filter on the scene runs before the button's own action, so consuming the
    /// press keeps the launcher's bound handler from running and lets the store close instead.</p>
    private void installBackFilter() {
        if (backFilter != null)
            return;

        backFilter = event -> {
            if (!storeShowing)
                return;
            if (!(event.getTarget() instanceof Node target))
                return;

            Node button = target;
            while (button != null && !button.getStyleClass().contains("jfx-decorator-button"))
                button = button.getParent();
            if (button == null)
                return;

            // Only the left-hand back arrow, not the window buttons on the right.
            if (!isLeftNavButton(button))
                return;

            event.consume();
            closeStore();
        };

        javafx.scene.Scene scene = Controllers.getScene();
        if (scene != null)
            scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, backFilter);
    }

    /// Returns whether a title-bar button sits in the nav bar's left box.
    ///
    /// @param button the button
    /// @return whether it is the back arrow
    private boolean isLeftNavButton(Node button) {
        try {
            javafx.scene.Node navPane = findById(Controllers.getScene().getRoot(),
                    "decoratorTitleTransitionPane");
            if (navPane == null)
                return false;
            Node parent = navPane.getParent();
            if (!(parent instanceof javafx.scene.Parent nav) || nav.getChildrenUnmodifiable().isEmpty())
                return false;
            Node left = nav.getChildrenUnmodifiable().get(0);
            return left instanceof javafx.scene.Parent box && box.getChildrenUnmodifiable().contains(button);
        } catch (Throwable e) {
            return false;
        }
    }

    /// Returns to the launcher's own content.
    void closeStore() {
        storeShowing = false;
        try {
            if (backFilter != null && Controllers.getScene() != null) {
                Controllers.getScene().removeEventFilter(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED, backFilter);
                backFilter = null;
            }

            Controllers.getSettingsPage().showGameSettings(
                    org.jackhuang.hmcl.setting.GameDirectoryManager.getSelectedRepository());
            Controllers.navigate(Controllers.getSettingsPage());
        } catch (Throwable e) {
            getContext().logWarning("无法关闭商店：" + e);
        }
    }

    /// Leaves the store if it is currently shown, e.g. when the plugin is disabled.
    private void leaveStore() {
        try {
            if (storePage == null || Controllers.getDecorator() == null)
                return;
            if (Controllers.getDecorator().getDecorator().getContent().contains(storePage))
                Controllers.navigate(Controllers.getRootPage());
        } catch (Throwable ignored) {
            // Nothing to restore.
        }
    }

    // ------------------------------------------------------------------- styles

    /// Extracts the bundled style sheet and attaches it to the scene root.
    ///
    /// <p>The root node is used rather than the scene: the launcher rebinds its own scene style
    /// sheet list, which would drop a third-party sheet, and node style sheets also win in the
    /// cascade.</p>
    private void installStyles() {
        PluginContext context = getContext();
        Path target = context.getDataFile(STYLE_FILE);

        try (InputStream input = StorePlugin.class.getResourceAsStream(STYLE_RESOURCE)) {
            if (input == null) {
                context.logWarning("样式表资源缺失：" + STYLE_RESOURCE);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            styleUrl = target.toUri().toString();
        } catch (IOException e) {
            context.logWarning("无法释放样式表：" + e);
            return;
        }

        Runnable apply = () -> {
            Parent root = sceneRoot();
            if (root == null || styleUrl == null)
                return;
            if (!root.getStylesheets().contains(styleUrl))
                root.getStylesheets().add(styleUrl);
        };

        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
    }

    /// Removes the style sheet from the scene root.
    private void removeStyles() {
        Runnable remove = () -> {
            Parent root = sceneRoot();
            if (root != null && styleUrl != null)
                root.getStylesheets().remove(styleUrl);
        };

        if (Platform.isFxApplicationThread()) {
            remove.run();
        } else {
            Platform.runLater(remove);
        }
    }

    /// Finds a descendant by id without relying on CSS being applied.
    ///
    /// @param root the node to search from
    /// @param id   the id to look for
    /// @return the matching node, or `null`
    private static Node findById(Node root, String id) {
        if (root == null)
            return null;
        if (id.equals(root.getId()))
            return root;
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findById(child, id);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /// Returns the launcher's scene root, or `null` while the window is still being built.
    ///
    /// @return the root node
    private static Parent sceneRoot() {
        try {
            return Controllers.getScene() == null ? null : Controllers.getScene().getRoot();
        } catch (Throwable e) {
            return null;
        }
    }
}
