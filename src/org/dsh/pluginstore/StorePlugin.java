/*
 * PCL 商店插件 —— 入口
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 * 它从 GitHub topic 抓取插件仓库，提供浏览、搜索与一键安装。
 */
package org.dsh.pluginstore;

import javafx.application.Platform;
import javafx.geometry.Insets;
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
    /// <p>Registered as a **launcher page** rather than a settings page action. Settings-page
    /// actions are rendered inside `PluginPage`'s own `TransitionPane`, which never touches the
    /// navigator — so `PageAware.onPageShown` never fires there and the second click found a
    /// cached placeholder and did nothing. Launcher pages go through
    /// `Controllers.navigate(PluginPageProxy.of(page))`, i.e. a real navigator push, so every
    /// click reaches `onPageShown` and can open the store.</p>
    private final org.jackhuang.hmcl.plugin.PluginLauncherPage page =
            new org.jackhuang.hmcl.plugin.PluginLauncherPage() {

                @Override
                public String getTitle() {
                    return "插件商店";
                }

                @Override
                public org.jackhuang.hmcl.ui.SVG getIcon() {
                    return org.jackhuang.hmcl.ui.SVG.EXTENSION;
                }

                @Override
                public Node createPage() {
                    // The launcher calls this on every sidebar click and does not cache it for
                    // launcher pages, so opening the store from here works repeatedly — unlike
                    // the old settings-page action, whose result was cached after the first click.
                    javafx.application.Platform.runLater(StorePlugin.this::openFullWindowStore);
                    return new StoreEntryNode();
                }
            };

    /// A placeholder shown for the instant between the click and the store appearing.
    ///
    /// <p>Kept deliberately inert: `PluginPageProxy` does not forward `PageAware.onPageShown`,
    /// so the navigation trigger lives in `createPage` instead.</p>
    private static final class StoreEntryNode extends StackPane {

        StoreEntryNode() {
            getChildren().add(new javafx.scene.control.Label("正在打开插件商店…"));
        }
    }

    /// The full-window store page, created once and reused.
    private Node storePage;

    /// Whether the store currently occupies the launcher's content area.
    private boolean storeShowing;

    /// Intercepts clicks on the title bar's back arrow while the store is open.
    private javafx.event.EventHandler<javafx.scene.input.MouseEvent> backFilter;

    /// Keeps the store clear of the title bar as the window is resized.
    private javafx.beans.value.ChangeListener<Number> titleHeightListener;

    /// The title bar whose height drives the store's top inset.
    private javafx.scene.layout.Region titleHeightNode;

    @Override
    public void onEnable() {
        PluginMenuService.registerLauncherPage(page);
        installStyles();
        getContext().logInfo("插件商店已启用（全窗口模式），数据源：GitHub topic " + StoreSource.TOPIC);
    }

    @Override
    public void onDisable() {
        PluginMenuService.unregisterLauncherPage(page);
        removeStyles();
        leaveStore();
        getContext().logInfo("插件商店已停用");
    }

    /// Opens the store across the launcher's entire content area.
    ///
    /// <p>Navigates through the launcher's own navigator rather than writing straight into the
    /// decorator's content list. The title bar's back arrow runs
    /// [org.jackhuang.hmcl.ui.decorator.DecoratorController]'s `back()`, which only ever touches
    /// the navigator — a page placed directly in the content list is invisible to it, which is
    /// why the store used to stay stuck on top of everything. Going through the navigator keeps
    /// the page in the back stack, so the arrow pops it normally.</p>
    ///
    /// <p>The navigator fills the whole content area, so the store still covers the settings
    /// sidebar, and because it is a real navigator entry the launcher also restores its own page
    /// when the store is popped.</p>
    void openFullWindowStore() {
        try {
            javafx.scene.Scene scene = Controllers.getScene();
            if (scene == null || Controllers.getDecorator() == null) {
                getContext().logWarning("打开商店失败：场景或装饰器未就绪");
                return;
            }

            var decorator = Controllers.getDecorator().getDecorator();
            javafx.scene.layout.StackPane wrapper = decorator.getDrawerWrapper();
            if (wrapper == null) {
                getContext().logWarning("打开商店失败：drawerWrapper 为空");
                return;
            }

            // The store is an overlay on the decorator's wrapper, not a navigator page.
            //
            // A navigator page lives inside the launcher's back stack, and every attempt to make
            // its back arrow close the store fought that stack (a bound action handler, a pushed
            // settings page, a cached page factory). An overlay is independent of all of it: it
            // covers the whole window, and closing it is a plain removal this plugin controls.
            if (storePage != null && wrapper.getChildren().contains(storePage)) {
                getContext().logInfo("商店已经打开，忽略重复请求");
                return;
            }

            if (storePage == null)
                storePage = new StorePage(getContext(), this::closeStore);

            storeShowing = true;

            // The overlay is topmost, so it must not swallow the title bar.
            //
            // Earlier attempts padded the page, but the launcher's title bar lives inside the
            // decorator's frame, and the measured "jfx-tool-bar" height did not always match
            // where the frame actually draws it. Measuring the frame's title area directly is
            // exact: whatever the frame reports as its top inset is what has to stay uncovered.
            wrapper.getChildren().remove(storePage);
            wrapper.getChildren().add(storePage);
            storePage.toFront();

            double topInset = measureTitleBarHeight(scene.getRoot());
            if (storePage instanceof StorePage page)
                page.setTopInset(topInset);

            getContext().logInfo("商店已作为最上层覆盖层打开，顶边距=" + Math.round(topInset));
            installBackFilter();
        } catch (Throwable e) {
            getContext().logWarning("无法打开全窗口商店：" + e);
        }
    }

    /// Intercepts clicks on the title bar's back arrow while the store is open.
    ///
    /// <p>Watches both press and release and matches the arrow structurally: the title bar is
    /// rebuilt by the launcher, so relying on one exact parent chain was fragile. Any click that
    /// lands on a `jfx-decorator-button` which is *not* one of the window buttons on the right
    /// counts as the back arrow and closes the store.</p>
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

            // Window controls must keep their own behaviour.
            if (isWindowButton(button))
                return;

            // Consume on release only: consuming the press would stop the button from ever
            // producing a click, so the two events are handled differently.
            if (event.getEventType() == javafx.scene.input.MouseEvent.MOUSE_PRESSED) {
                event.consume();
                closeStore();
            } else {
                event.consume();
            }
        };

        javafx.scene.Scene scene = Controllers.getScene();
        if (scene != null) {
            scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, backFilter);
            scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, backFilter);
        }
        getContext().logInfo("商店：已安装返回箭头拦截");
    }

    /// Returns whether a title-bar button is one of the window controls on the right.
    ///
    /// <p>Matched by position rather than parentage: the window buttons live in the decorator's
    /// overlay layer, which is separate from the nav bar's left box.</p>
    ///
    /// @param button the button
    /// @return whether it is minimize/close
    private boolean isWindowButton(Node button) {
        try {
            Node navPane = findById(Controllers.getScene().getRoot(),
                    "decoratorTitleTransitionPane");
            if (navPane == null)
                return false;
            // Everything inside the nav bar pane is page navigation; the window buttons are not.
            Node node = button;
            while (node != null) {
                if (node == navPane)
                    return false;
                node = node.getParent();
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /// Measures how tall the launcher's title bar is, so the overlay can leave it uncovered.
    ///
    /// <p>Looked up by geometry rather than style class: the nav bar pane is nested inside the
    /// title container, and its own height is only part of the bar. Converting the nav bar's
    /// bounds into the window's coordinate space gives the exact band the overlay must skip.</p>
    ///
    /// @param root the scene root
    /// @return the height in pixels, or `0` when it cannot be determined
    private static double measureTitleBarHeight(Node root) {
        try {
            Node navPane = findById(root, "decoratorTitleTransitionPane");
            if (navPane == null || navPane.getScene() == null)
                return 0;

            javafx.geometry.Bounds inScene = navPane.localToScene(navPane.getBoundsInLocal());
            if (inScene == null)
                return 0;

            // The nav pane sits inside the title container, which may add padding around it; walk
            // up to the container that owns the "jfx-tool-bar" style for the real bar height.
            Node bar = navPane;
            while (bar != null && !bar.getStyleClass().contains("jfx-tool-bar"))
                bar = bar.getParent();

            if (bar != null) {
                javafx.geometry.Bounds barBounds = bar.localToScene(bar.getBoundsInLocal());
                if (barBounds != null)
                    return Math.max(0, barBounds.getMaxY());
            }
            return Math.max(0, inScene.getMaxY());
        } catch (Throwable e) {
            return 0;
        }
    }

    /// Removes the store overlay from the window.
    ///
    /// <p>Plain removal: the overlay is a child of the decorator's wrapper, so taking it out
    /// restores whatever the launcher was showing, with no back-stack involvement at all. This
    /// is the only thing that has to work for the store to close, which is why it cannot be
    /// defeated by the launcher's navigation.</p>
    void closeStore() {
        storeShowing = false;
        try {
            if (backFilter != null && Controllers.getScene() != null) {
                Controllers.getScene().removeEventFilter(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED, backFilter);
                Controllers.getScene().removeEventFilter(
                        javafx.scene.input.MouseEvent.MOUSE_PRESSED, backFilter);
                backFilter = null;
            }

            // Stop tracking the title bar height, otherwise the listener outlives the overlay.
            if (titleHeightNode != null && titleHeightListener != null) {
                titleHeightNode.heightProperty().removeListener(titleHeightListener);
                titleHeightNode = null;
                titleHeightListener = null;
            }

            if (storePage != null && storePage.getParent() instanceof javafx.scene.layout.Pane pane) {
                javafx.scene.layout.StackPane.setMargin(storePage, Insets.EMPTY);
                pane.getChildren().remove(storePage);
                getContext().logInfo("商店覆盖层已移除");
            }
        } catch (Throwable e) {
            getContext().logWarning("无法关闭商店：" + e);
        }
    }

    /// Removes the store from the navigator, if it is the current page.
    ///
    /// <p>Uses the navigator's own close, which pops exactly one entry; if the store is no longer
    /// on top (another plugin navigated meanwhile) nothing is popped, so this never destroys
    /// unrelated pages.</p>
    private void popStore() {
        if (storePage == null || Controllers.getDecorator() == null)
            return;

        var navigator = navigatorOf();
        if (navigator == null)
            return;

        if (navigator.getCurrentPage() == storePage)
            navigator.close();
    }

    /// Returns the launcher's navigator, or `null` when it cannot be reached.
    ///
    /// <p>The navigator is the single child the decorator's content list holds, so it is found
    /// by type rather than by reflection on private fields.</p>
    ///
    /// @return the navigator
    private static org.jackhuang.hmcl.ui.construct.Navigator navigatorOf() {
        try {
            for (Node node : Controllers.getDecorator().getDecorator().getContent()) {
                if (node instanceof org.jackhuang.hmcl.ui.construct.Navigator navigator)
                    return navigator;
            }
        } catch (Throwable ignored) {
            // The decorator may not be initialised yet.
        }
        return null;
    }

    /// Removes the store overlay if it is on screen, e.g. when the plugin is disabled.
    private void leaveStore() {
        try {
            if (!storeShowing)
                return;
            closeStore();
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
