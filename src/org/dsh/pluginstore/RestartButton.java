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

    private final org.jackhuang.hmcl.plugin.PluginContext context;

    RestartButton(org.jackhuang.hmcl.plugin.PluginContext context) {
        this.context = context;
    }

    /// Shows the restart button when updates are pending.
    ///
    /// <p>Called after staging an update, and once at start-up for updates carried over from a
    /// previous session.</p>
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

                if (!pending) {
                    if (existing != null)
                        buttons.getChildren().remove(existing);
                    return;
                }

                if (existing != null)
                    return;

                // Index 0 puts it immediately left of the minimise button.
                buttons.getChildren().add(0, createButton());
                context.logInfo("已添加重启按钮（有待应用的插件更新）");
            } catch (Throwable e) {
                context.logWarning("无法添加重启按钮：" + e);
            }
        });
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
        button.getStyleClass().addAll("jfx-decorator-button", STYLE);
        org.jackhuang.hmcl.ui.FXUtils.installFastTooltip(button, "重启启动器以完成插件更新");
        button.setOnAction(event -> restart());
        return button;
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
            if (!child.getStyleClass().contains("jfx-decorator-button"))
                return false;
        }

        // The window buttons are right-aligned inside an AnchorPane wrapper, unlike the nav bar.
        return box.getParent() instanceof javafx.scene.layout.AnchorPane;
    }
}
