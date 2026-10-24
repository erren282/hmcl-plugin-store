/*
 * PCL 商店插件 —— 商店页面
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.pluginstore;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginDescription;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.ui.FXUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// The store page: one card per plugin repository, with stars, a collapsible description and an
/// install button.
///
/// <p>Data is fetched on a background thread; the page shows a loading hint and replaces it once
/// the repositories arrive. Filtering is local, so typing in the search field never hits the
/// network again.</p>
public final class StorePage extends VBox {

    private final PluginContext context;
    private final StoreSource source;
    private final StoreInstaller installer;

    private final VBox cards = new VBox(10);
    private final Label statusLabel = new Label();
    private final TextField searchField = new TextField();

    /// Every entry fetched so far, so the search box can filter without refetching.
    private final List<StoreSource.Entry> entries = new ArrayList<>();

    public StorePage(PluginContext context) {
        this.context = context;
        this.source = new StoreSource(context);
        this.installer = new StoreInstaller(context);

        getStyleClass().add("store-page");
        setPadding(new Insets(16));
        setSpacing(12);

        getChildren().addAll(buildHeader(), buildStatusRow(), buildBody());
        reload();
    }

    // -------------------------------------------------------------------- header

    /// Builds the title, the search field and the refresh button.
    ///
    /// @return the header row
    private Node buildHeader() {
        Label title = new Label("插件商店");
        title.getStyleClass().add("store-title");

        Label subtitle = new Label("来自 GitHub topic：" + StoreSource.TOPIC);
        subtitle.getStyleClass().add("store-subtitle");

        VBox texts = new VBox(2, title, subtitle);

        searchField.setPromptText("搜索插件名称或简介");
        searchField.getStyleClass().add("store-search");
        searchField.setPrefWidth(220);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        StackPane refresh = StoreWidgets.button("刷新", this::reload);
        refresh.getStyleClass().add("store-refresh");

        HBox actions = new HBox(8, searchField, refresh);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, texts, StoreWidgets.spacer(), actions);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /// Builds the small status line under the header.
    ///
    /// @return the status row
    private Node buildStatusRow() {
        statusLabel.getStyleClass().add("store-status");
        return new HBox(statusLabel);
    }

    /// Builds the scrollable card area.
    ///
    /// @return the scroll pane
    private Node buildBody() {
        cards.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("store-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        FXUtils.smoothScrolling(scroll);
        return scroll;
    }

    // --------------------------------------------------------------------- data

    /// Fetches the repository list again.
    private void reload() {
        statusLabel.setText("正在从 GitHub 获取插件列表…");
        cards.getChildren().clear();

        context.getRoot().supplyAsync(() -> {
            try {
                return source.fetchRepositories();
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((result, throwable) -> Platform.runLater(() -> {
            if (throwable != null) {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                statusLabel.setText("获取失败：" + cause.getMessage() + "（点刷新重试）");
                context.logWarning("插件商店获取失败：" + cause.getMessage());
                return;
            }
            entries.clear();
            entries.addAll(result);
            statusLabel.setText("共 " + entries.size() + " 个插件");
            applyFilter();
        }));
    }

    /// Rebuilds the card list for the current search text.
    private void applyFilter() {
        String query = searchField.getText() == null
                ? ""
                : searchField.getText().trim().toLowerCase(Locale.ROOT);

        cards.getChildren().clear();
        for (StoreSource.Entry entry : entries) {
            if (!query.isEmpty() && !matches(entry, query))
                continue;
            cards.getChildren().add(buildCard(entry));
        }

        if (cards.getChildren().isEmpty() && !entries.isEmpty())
            cards.getChildren().add(hint("没有匹配的插件"));
    }

    /// Returns whether an entry matches the search query.
    ///
    /// @param entry the entry
    /// @param query the lower-cased query
    /// @return whether the name, summary or repository matches
    private static boolean matches(StoreSource.Entry entry, String query) {
        return contains(entry.name(), query)
                || contains(entry.fullName(), query)
                || contains(entry.summary(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    // --------------------------------------------------------------------- card

    /// Builds one plugin card.
    ///
    /// @param entry the repository
    /// @return the card
    private Node buildCard(StoreSource.Entry entry) {
        Label name = new Label(entry.name());
        name.getStyleClass().add("store-card-name");

        Label stars = new Label("★ " + entry.stars());
        stars.getStyleClass().add("store-stars");

        Label summary = new Label(entry.summary() == null || entry.summary().isBlank()
                ? "（该仓库没有简介）"
                : entry.summary());
        summary.getStyleClass().add("store-summary");
        summary.setWrapText(true);

        HBox titleRow = new HBox(10, name, stars, StoreWidgets.spacer());
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // The full README lives in a collapsible area so the list stays scannable.
        VBox details = new VBox();
        details.setVisible(false);
        details.setManaged(false);
        details.setSpacing(6);
        details.getStyleClass().add("store-details");

        StackPane toggle = StoreWidgets.button("显示全部", null);
        toggle.getStyleClass().add("store-toggle");
        StackPane install = StoreWidgets.accentButton("安装", null);

        Label progress = new Label();
        progress.getStyleClass().add("store-progress");

        final boolean[] installed = {isInstalled(entry)};
        if (installed[0]) {
            install.setDisable(true);
            install.getStyleClass().add("store-installed");
            ((Label) ((StackPane) install).getChildren().get(0)).setText("已安装");
        }

        toggle.setOnMouseClicked(event -> {
            boolean show = !details.isVisible();
            details.setVisible(show);
            details.setManaged(show);
            ((Label) toggle.getChildren().get(0)).setText(show ? "收起" : "显示全部");
            if (show && details.getChildren().isEmpty())
                loadDetails(entry, details);
        });

        install.setOnMouseClicked(event -> {
            if (install.isDisabled())
                return;
            install.setDisable(true);
            ((Label) install.getChildren().get(0)).setText("安装中…");
            progress.setText("");
            installEntry(entry, install, progress);
        });

        HBox footer = new HBox(8, toggle, install, progress);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, titleRow, summary, details, footer);
        card.getStyleClass().add("store-card");
        return card;
    }

    /// Lazily loads the README into a card's detail area.
    ///
    /// @param entry   the repository
    /// @param details the container to fill
    private void loadDetails(StoreSource.Entry entry, VBox details) {
        Label loading = new Label("正在加载简介…");
        loading.getStyleClass().add("store-status");
        details.getChildren().setAll(loading);

        context.getRoot().supplyAsync(() -> source.fetchReadme(entry.fullName()))
                .whenComplete((readme, throwable) -> Platform.runLater(() -> {
                    details.getChildren().clear();
                    if (throwable != null || readme == null || readme.isBlank()) {
                        details.getChildren().add(new Label("该仓库没有可显示的 README。"));
                        return;
                    }

                    TextArea area = new TextArea(readme);
                    area.setEditable(false);
                    area.setWrapText(true);
                    area.setPrefRowCount(10);
                    area.getStyleClass().add("store-readme");
                    details.getChildren().add(area);

                    Label link = new Label("在 GitHub 上查看：" + entry.homepage());
                    link.getStyleClass().add("store-link");
                    link.setOnMouseClicked(event -> FXUtils.openLink(entry.homepage()));
                    details.getChildren().add(link);
                }));
    }

    // ------------------------------------------------------------------ install

    /// Downloads and installs one entry.
    ///
    /// @param entry    the repository
    /// @param button   the install button to restore on failure
    /// @param progress where to show the download progress
    private void installEntry(StoreSource.Entry entry, StackPane button, Label progress) {
        context.getRoot().supplyAsync(() -> source.findLatestJar(entry.fullName()))
                .whenComplete((asset, throwable) -> Platform.runLater(() -> {
                    if (throwable != null || asset == null) {
                        failInstall(button, progress, "该仓库没有可安装的发行版 jar");
                        return;
                    }

                    installer.install(entry.fullName(), asset, new StoreInstaller.Callback() {
                        @Override
                        public void onProgress(double fraction) {
                            progress.setText(Math.round(fraction * 100) + "%");
                        }

                        @Override
                        public void onInstalled(PluginDescription description) {
                            progress.setText(description.getName() + " " + description.getVersion() + " 已安装");
                            ((Label) button.getChildren().get(0)).setText("已安装");
                            button.setDisable(true);
                            button.getStyleClass().add("store-installed");
                            promptReload();
                        }

                        @Override
                        public void onFailed(String message) {
                            failInstall(button, progress, message);
                        }
                    });
                }));
    }

    /// Restores the install button after a failure.
    ///
    /// @param button   the button
    /// @param progress the progress label
    /// @param message  the reason
    private void failInstall(StackPane button, Label progress, String message) {
        button.setDisable(false);
        ((Label) button.getChildren().get(0)).setText("安装");
        progress.setText(message);
    }

    /// Tells the user that a reload is needed for the new plugin to run.
    private void promptReload() {
        context.confirm("插件已安装完成。重新加载插件后即可生效，现在重新加载吗？",
                "插件商店",
                PluginManager::reload,
                () -> { });
    }

    /// Returns whether a repository's plugin is already installed.
    ///
    /// @param entry the repository
    /// @return whether a plugin with the same repository name is present
    private boolean isInstalled(StoreSource.Entry entry) {
        String name = entry.name();
        if (name == null)
            return false;
        for (var handle : PluginManager.getPlugins()) {
            String id = handle.getId();
            if (id != null && name.contains(id.replace("org.dsh.", "")))
                return true;
        }
        return false;
    }

    /// Builds a neutral hint label.
    ///
    /// @param text the text
    /// @return the label
    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("store-status");
        return label;
    }
}
