/*
 * PCL 商店插件 —— 小控件
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.pluginstore;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/// Small controls shared by the store page.
final class StoreWidgets {

    private StoreWidgets() {
    }

    /// Builds a bordered button in the launcher's blue palette.
    ///
    /// @param text   the label
    /// @param action the click action, or `null` to leave it to the caller
    /// @return the button
    static StackPane button(String text, Runnable action) {
        Label label = new Label(text);
        label.getStyleClass().add("store-button-text");

        StackPane button = new StackPane(label);
        button.getStyleClass().add("store-button");
        if (action != null)
            button.setOnMouseClicked(event -> action.run());
        return button;
    }

    /// Builds a filled button for the primary action.
    ///
    /// @param text   the label
    /// @param action the click action, or `null`
    /// @return the button
    static StackPane accentButton(String text, Runnable action) {
        StackPane button = button(text, action);
        button.getStyleClass().add("accent");
        return button;
    }

    /// Creates the flexible spacer used to push siblings apart.
    ///
    /// @return the spacer
    static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
