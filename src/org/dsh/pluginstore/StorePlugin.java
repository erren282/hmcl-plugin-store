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
///
/// <p>The page is a normal launcher page: the launcher navigates to it, shows its own title bar
/// with the back arrow, and closes it like any other page. Earlier versions tried to mount the
/// store as a window overlay to make it cover the settings sidebar; that needed a placeholder
/// page underneath and fought the launcher's layer order, which produced a stuck placeholder and
/// a translucent sheet over the content. Rendering the store as an ordinary page avoids all of
/// it, and the launcher's own back arrow works without any interception.</p>
public final class StorePlugin extends HmclPlugin {

    /// Classpath location of the bundled style sheet.
    private static final String STYLE_RESOURCE = "/org/dsh/pluginstore/store.css";

    /// Name of the extracted style sheet inside the plugin data directory.
    private static final String STYLE_FILE = "store.css";

    /// The style sheet URL applied to the scene root, or `null`.
    private String styleUrl;

    /// The single store page instance, reused so its state survives navigation.
    private StorePage storePage;

    /// The plugin-module entry that opens the store full-screen.
    ///
    /// <p>The entry lives in **设置 → 插件 → 插件页面** (and is pinned to the top), but clicking
    /// it does not render the store inside that page: rendering it there would leave the settings
    /// sidebar visible on the left. Instead the launcher's navigator is replaced with the store,
    /// which fills the entire window, sidebar included.</p>
    private final org.jackhuang.hmcl.plugin.PluginPageAction page =
            new org.jackhuang.hmcl.plugin.PluginPageAction() {

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
                    return new StoreEntryTrigger(StorePlugin.this);
                }
            };

    /// A tiny node whose only job is to hand the store to the navigator when it is shown.
    ///
    /// <p>`PluginPage` caches the result of `createPage`, so triggering the navigation from
    /// `createPage` itself only worked the first time. The cached node, however, is re-attached
    /// to the scene every time the entry is opened, so watching the parent handles repeated
    /// visits. `PageAware` is not usable here: `PluginPage` drives its own `TransitionPane`
    /// instead of the navigator, so `onPageShown` never fires.</p>
    private static final class StoreEntryTrigger extends StackPane {

        private final StorePlugin plugin;

        StoreEntryTrigger(StorePlugin plugin) {
            this.plugin = plugin;
            getChildren().add(new javafx.scene.control.Label("正在打开插件商店…"));

            parentProperty().addListener((observable, oldParent, newParent) -> {
                if (newParent != null)
                    javafx.application.Platform.runLater(plugin::openFullScreenStore);
            });
        }
    }

    /// Replaces the launcher's current page with the store, filling the whole window.
    ///
    /// <p>Navigating is what removes the settings sidebar: the sidebar belongs to the settings
    /// page itself, so only swapping the navigator's current page can cover it. The store is a
    /// normal page in the back stack, which keeps the launcher's own back arrow working.</p>
    public void openFullScreenStore() {
        try {
            if (Controllers.getScene() == null || Controllers.getDecorator() == null)
                return;

            if (storePage == null)
                storePage = new StorePage(getContext());

            // Re-navigating to the page already on top would be a no-op, but pushing it again
            // while it is already current would leave duplicates needing extra back presses.
            if (isStoreCurrent())
                return;

            Controllers.navigate(storePage);
            getContext().logInfo("插件商店已全屏打开");
        } catch (Throwable e) {
            getContext().logWarning("无法全屏打开商店：" + e);
        }
    }

    /// Returns whether the store is the navigator's current page.
    ///
    /// @return whether it is already showing
    private boolean isStoreCurrent() {
        try {
            for (Node node : Controllers.getDecorator().getDecorator().getContent()) {
                if (node instanceof org.jackhuang.hmcl.ui.construct.Navigator navigator)
                    return navigator.getCurrentPage() == storePage;
            }
        } catch (Throwable ignored) {
            // Treated as "not current".
        }
        return false;
    }

    /// Moves the store entry to the first position in the plugin-page list.
    ///
    /// <p>`registerPage` only appends, and it schedules the append on the FX thread, so the
    /// reorder has to run after that. Repeating it on every list change keeps the store on top
    /// even when other plugins register their pages later.</p>
    private void pinToTop() {
        PluginMenuService.getPages().addListener(
                (javafx.collections.ListChangeListener<org.jackhuang.hmcl.plugin.PluginPageAction>) change ->
                        moveToFirst());
        moveToFirst();
    }

    /// Reorders the plugin-page list so this plugin's entry is the first one.
    private void moveToFirst() {
        var pages = PluginMenuService.getPages();
        if (!pages.contains(page) || pages.indexOf(page) == 0)
            return;
        pages.remove(page);
        pages.add(0, page);
    }

    @Override
    public void onEnable() {
        PluginMenuService.registerPage(page);
        pinToTop();
        installStyles();
        getContext().logInfo("插件商店已启用（设置 → 插件 → 插件页面首位），数据源：GitHub topic "
                + StoreSource.TOPIC);
    }

    @Override
    public void onDisable() {
        PluginMenuService.unregisterPage(page);
        removeStyles();
        getContext().logInfo("插件商店已停用");
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
