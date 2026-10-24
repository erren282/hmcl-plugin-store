/*
 * 插件商店 —— 小控件
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.pluginstore;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/// Small controls shared by the store page.
final class StoreWidgets {

    /// How long one wheel step takes to settle.
    private static final Duration WHEEL_DURATION = Duration.millis(220);

    /// Fraction of the remaining distance covered per wheel step; keeps fast spins from
    /// overshooting while still feeling responsive.
    private static final double WHEEL_STEP = 0.22;

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

    /// Makes a scroll pane follow the mouse wheel with an eased animation.
    ///
    /// <p>JavaFX's default jumps the viewport by a fixed amount per notch, which reads as
    /// steppy. This consumes the wheel event and animates `vvalue` towards the target instead,
    /// so a fast spin glides rather than snapping, and a slow spin still moves a useful amount.</p>
    ///
    /// @param scroll the scroll pane to tune
    static void installSmoothWheel(ScrollPane scroll) {
        // Keep one animation per pane in the node's properties so repeated installs replace it.
        String key = "store.wheel.animation";

        scroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            double delta = event.getDeltaY();
            if (delta == 0)
                return;

            // Let horizontal two-finger swipes fall through to the default handling.
            if (Math.abs(delta) < Math.abs(event.getDeltaX()))
                return;

            double contentHeight = scroll.getContent() == null
                    ? 0
                    : scroll.getContent().getBoundsInLocal().getHeight();
            double viewportHeight = scroll.getViewportBounds().getHeight();
            // Before the first layout pass the viewport reports zero; fall back to the control's
            // own height so the handler still does something sensible.
            if (viewportHeight <= 0)
                viewportHeight = scroll.getHeight();
            double scrollable = contentHeight - viewportHeight;

            // Nothing to scroll: leave the event alone so an outer pane can handle it.
            if (scrollable <= 0)
                return;

            double current = scroll.getVvalue();
            // One wheel notch is ~40px of content; move a comfortable fraction of the viewport
            // per notch and ease into the target so fast spins glide instead of jumping.
            double stepPixels = (delta / 40.0) * Math.max(40.0, viewportHeight * 0.12);

            double target = clamp(current - (stepPixels / scrollable) * WHEEL_STEP * 4.0);

            if (scroll.getProperties().get(key) instanceof Timeline running)
                running.stop();

            Timeline timeline = new Timeline(new KeyFrame(WHEEL_DURATION,
                    new KeyValue(scroll.vvalueProperty(), target, Interpolator.EASE_BOTH)));
            scroll.getProperties().put(key, timeline);
            timeline.play();

            event.consume();
        });
    }

    /// Clamps a value to `[0, 1]`.
    ///
    /// @param value the value
    /// @return the clamped value
    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
