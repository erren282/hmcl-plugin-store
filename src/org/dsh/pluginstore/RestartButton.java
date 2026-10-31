/*
 * 商店插件 —— 重启启动器按钮
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.pluginstore;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import org.jackhuang.hmcl.ui.Controllers;

/// Adds a restart button to the title bar, left of the minimise button.
///
/// <p>Installing or updating a plugin leaves the new jar staged: Windows keeps the running jar
/// mapped, so the swap happens after the launcher exits. Restarting is therefore a normal part of
/// updating, and the button sits where the user already looks.</p>
///
/// <p>The button only appears while an update is waiting, so the title bar is untouched at all
/// other times.</p>
final class RestartButton {

    /// Style class used to recognise the injected button.
    private static final String STYLE = "store-restart-button";

    /// Style class of the wrapper node placed in the title bar.
    ///
    /// <p>Separate from [#STYLE] because the row-identification check in [#isWindowButtonRow]
    /// has to skip known additions rather than fail on them.</p>
    private static final String WRAPPER_STYLE = "store-restart-button-container";

    /// Style class of the download hub's title bar wrapper.
    ///
    /// <p>Used to place the restart button immediately to its left, so the two title bar entries
    /// this plugin family adds always sit together in a stable order.</p>
    private static final String DOWNLOAD_HUB_WRAPPER = "download-hub-button-container";

    private final org.jackhuang.hmcl.plugin.PluginContext context;

    RestartButton(org.jackhuang.hmcl.plugin.PluginContext context) {
        this.context = context;
    }

    /// Shows the restart button.
    ///
    /// <p>Visible for the whole session, not only while an update is staged: restarting is a
    /// normal thing to want, and a button that comes and goes is hard to find. When an update is
    /// waiting, the tooltip says so.</p>
    ///
    /// <p>The launcher builds its window after plugins are enabled, so at `onEnable` time the
    /// scene and the title bar may not exist yet. A single attempt therefore silently did nothing
    /// and the button never appeared, leaving staged updates with no way to be applied — the
    /// refresh is retried for a while instead, and gives up long after the window is up.</p>
    void refresh() {
        refreshAttempt(0);
    }

    /// How many times the title bar is polled before giving up, and how far apart.
    private static final int REFRESH_ATTEMPTS = 40;
    private static final long REFRESH_INTERVAL_MS = 250;

    /// One attempt at injecting the button, retried until the title bar exists.
    ///
    /// @param attempt how many attempts have already failed
    private void refreshAttempt(int attempt) {
        Platform.runLater(() -> {
            try {
                Node container = findWindowButtons();
                if (!(container instanceof HBox buttons)) {
                    // The window is not up yet: retry rather than give up, or a start-up refresh
                    // would never reach a title bar that appears a moment later.
                    if (attempt < REFRESH_ATTEMPTS)
                        scheduleRetry(attempt + 1);
                    else
                        context.logWarning("始终找不到标题栏，未添加重启按钮");
                    return;
                }

                boolean pending = !new PendingUpdates(context).list().isEmpty();

                Node existing = null;
                for (Node child : buttons.getChildren()) {
                    if (child.getStyleClass().contains(STYLE)) {
                        existing = child;
                        break;
                    }
                }

                if (existing == null) {
                    existing = createButton();
                    buttons.getChildren().add(insertIndex(buttons), existing);
                    context.logInfo("已添加重启按钮" + (pending ? "（有待应用的插件更新）" : ""));
                }

                updateTooltip(existing, pending);
            } catch (Throwable e) {
                context.logWarning("无法添加重启按钮：" + e);
            }
        });
    }

    /// Returns the index the restart button should occupy.
    ///
    /// <p>It goes immediately left of the download hub button when that one is present, and at the
    /// far left of the window-button group otherwise — so it stays next to the download entry in a
    /// fixed order instead of drifting as buttons are added.</p>
    ///
    /// @param buttons the title bar's window-button row
    /// @return the insertion index
    private static int insertIndex(HBox buttons) {
        for (int i = 0; i < buttons.getChildren().size(); i++) {
            Node child = buttons.getChildren().get(i);
            if (child.getStyleClass().contains(DOWNLOAD_HUB_WRAPPER)
                    || child.getStyleClass().contains("download-hub-button"))
                return i;
        }
        return 0;
    }

    /// Enables or disables the restart button.
    ///
    /// <p>Used while a game is launching. Restarting then would kill the launcher mid-launch and
    /// take the starting game down with it — the process is still being set up, and the launcher
    /// is the parent that holds the log window and the exit hook.</p>
    ///
    /// <p>The wrapper is what sits in the title bar and receives the click, so the state goes
    /// there; the opacity change is what makes it look disabled.</p>
    ///
    /// @param enabled whether the button should respond to clicks
    void setEnabled(boolean enabled) {
        Platform.runLater(() -> {
            try {
                Node wrapper = findExistingButton();
                if (wrapper == null)
                    return;
                wrapper.setDisable(!enabled);
                wrapper.setOpacity(enabled ? 1.0 : 0.45);
                updateTooltip(wrapper, !new PendingUpdates(context).list().isEmpty(), enabled);
            } catch (Throwable e) {
                context.logWarning("无法切换重启按钮状态：" + e);
            }
        });
    }

    /// Finds the already-injected button, without creating it.
    ///
    /// @return the wrapper node, or `null` when the title bar is not up yet
    private static Node findExistingButton() {
        Node container = findWindowButtons();
        if (!(container instanceof HBox buttons))
            return null;
        for (Node child : buttons.getChildren()) {
            if (child.getStyleClass().contains(STYLE))
                return child;
        }
        return null;
    }

    /// Updates the tooltip to reflect whether an update is waiting.
    ///
    /// @param node    the injected button
    /// @param pending whether an update is staged
    /// @param enabled whether the button is currently usable
    private void updateTooltip(Node node, boolean pending) {
        updateTooltip(node, pending, true);
    }

    /// Updates the tooltip, including the "busy launching" state.
    ///
    /// @param node    the injected button
    /// @param pending whether an update is staged
    /// @param enabled whether the button is currently usable
    private void updateTooltip(Node node, boolean pending, boolean enabled) {
        try {
            org.jackhuang.hmcl.ui.FXUtils.installFastTooltip(node, !enabled
                    ? "游戏启动中，暂时无法重启启动器"
                    : pending
                    ? "重启启动器以应用已下载的插件更新"
                    : "重启启动器");
        } catch (Throwable ignored) {
            // A tooltip is cosmetic.
        }
    }

    /// Schedules the next attempt after a short delay.
    ///
    /// @param attempt the attempt number to run next
    private void scheduleRetry(int attempt) {
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(REFRESH_INTERVAL_MS));
        pause.setOnFinished(event -> refreshAttempt(attempt));
        pause.play();
    }

    /// Builds the restart button, styled like the launcher's window buttons.
    ///
    /// @return the button
    private Node createButton() {
        com.jfoenix.controls.JFXButton button = new com.jfoenix.controls.JFXButton();
        button.setFocusTraversable(false);
        button.setGraphic(org.jackhuang.hmcl.ui.SVG.REFRESH
                .createIcon(org.jackhuang.hmcl.theme.Themes.titleFillProperty()));
        button.getStyleClass().add("jfx-decorator-button");
        org.jackhuang.hmcl.ui.FXUtils.installFastTooltip(button, "重启启动器");
        button.setOnAction(event -> restart());

        // The marker goes on the wrapper, which is the node actually added to the title bar: the
        // download hub's button does the same, and both sides look for the wrapper's style class
        // when deciding where to insert and whether the button already exists.
        javafx.scene.layout.StackPane container = new javafx.scene.layout.StackPane(button);
        container.getStyleClass().addAll(WRAPPER_STYLE, STYLE);
        container.setPickOnBounds(false);
        return container;
    }

    /// Restarts the launcher so staged updates are applied.
    ///
    /// <p>Exposed so the store page can restart after the user accepts its post-install prompt:
    /// that new jar is not loaded yet, and there is nothing staged, so the plain self restart is
    /// the right path.</p>
    void restartNow() {
        restart();
    }

    /// Restarts the launcher so the staged update can be applied.
    ///
    /// <p>When an update is staged this does NOT call `Restarter.restartSelf()`: the apply script
    /// starts the new process itself, and only after replacing the jars. Starting the launcher here
    /// as well made two processes race — the fresh one loaded the still-old jar, then the script
    /// deleted it, which left a duplicate plugin and needed another restart to settle.</p>
    private void restart() {
        try {
            context.logInfo("正在重启启动器以完成插件更新");

            java.nio.file.Path script = new PendingUpdates(context).writeApplyScript();
            if (script == null) {
                // Nothing staged, or the script could not be written: a plain restart is enough.
                org.jackhuang.hmcl.util.Restarter.restartSelf();
            } else {
                new ProcessBuilder("cmd", "/c", script.toAbsolutePath().toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                context.logInfo("已启动更新脚本，替换完成后会自动启动启动器：" + script.getFileName());
            }
        } catch (Throwable e) {
            context.logWarning("重启失败：" + e);
            toast("重启失败：" + e.getMessage());
            return;
        }

        // Exit promptly: the script waits for this process to disappear before touching the jars.
        javafx.animation.PauseTransition pause =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));
        pause.setOnFinished(event -> {
            Platform.exit();
            Runtime.getRuntime().halt(0);
        });
        pause.play();
    }

    /// Shows a short message through the launcher's toast, ignoring unsupported cases.
    ///
    /// @param message the text
    private void toast(String message) {
        try {
            context.showToast(message);
        } catch (Throwable ignored) {
            // Toast support is optional.
        }
    }

    /// Finds the title bar's window-button container.
    ///
    /// @return the container, or `null`
    private static Node findWindowButtons() {
        var scene = Controllers.getScene();
        if (scene == null)
            return null;
        return findButtonsIn(scene.getRoot());
    }

    /// Searches a subtree for the window-button row.
    ///
    /// <p>Identified by structure: every child carries the decorator button style and the row sits
    /// inside an `AnchorPane`, which distinguishes it from the navigation bar.</p>
    ///
    /// @param root the node to search
    /// @return the container, or `null`
    private static Node findButtonsIn(Node root) {
        if (root instanceof HBox box && isWindowButtonRow(box))
            return box;

        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findButtonsIn(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /// Returns whether a row is the window-control group.
    ///
    /// @param box the row
    /// @return whether it holds the title bar's window buttons
    private static boolean isWindowButtonRow(HBox box) {
        if (box.getChildren().size() < 2)
            return false;

        for (Node child : box.getChildren()) {
            // Skip the wrappers plugin buttons bring: they are not decorator buttons themselves,
            // and counting them as foreign would make the row unrecognisable once a button was
            // added — which stopped the button from ever being found again after the first add.
            if (child.getStyleClass().contains(WRAPPER_STYLE)
                    || child.getStyleClass().contains(DOWNLOAD_HUB_WRAPPER))
                continue;
            if (!child.getStyleClass().contains("jfx-decorator-button"))
                return false;
        }

        // The window buttons are right-aligned inside an AnchorPane wrapper, unlike the nav bar.
        return box.getParent() instanceof javafx.scene.layout.AnchorPane;
    }
}
