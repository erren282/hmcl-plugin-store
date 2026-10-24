/*
 * 插件商店 —— 市场页面
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
import org.jackhuang.hmcl.ui.construct.MarkdownRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// The store page shown across the launcher's whole content area.
///
/// <p>One card per plugin repository: name, star count, a one-line summary and an install
/// button. Expanding a card renders that repository's README as Markdown through the launcher's
/// own [MarkdownRenderer].</p>
///
/// <p>Implements [DecoratorPage] so the launcher draws its normal title bar with a working back
/// arrow; the page simply fills the content area the launcher gives it.</p>
public final class StorePage extends VBox implements
        org.jackhuang.hmcl.ui.decorator.DecoratorPage,
        org.jackhuang.hmcl.ui.construct.PageAware {

    /// Longest description kept, in characters; roughly two lines at the card's width.
    private static final int SUMMARY_MAX_CHARS = 110;

    /// Height cap for the description text, in pixels (about two 12.5px lines).
    private static final double SUMMARY_MAX_HEIGHT = 34;

    /// Vertical padding the description box adds around the text.
    private static final double SUMMARY_BOX_PADDING = 12;

    /// Visible height of the README viewport inside a card, in pixels.
    private static final double README_VIEWPORT_HEIGHT = 190;

    /// Page state reported to the launcher's title bar.
    private final javafx.beans.property.ReadOnlyObjectWrapper<org.jackhuang.hmcl.ui.decorator.DecoratorPage.State> state =
            new javafx.beans.property.ReadOnlyObjectWrapper<>(
                    new org.jackhuang.hmcl.ui.decorator.DecoratorPage.State(
                            "插件商店", null, true, false, true));

    @Override
    public javafx.beans.property.ReadOnlyObjectProperty<org.jackhuang.hmcl.ui.decorator.DecoratorPage.State> stateProperty() {
        return state.getReadOnlyProperty();
    }

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
        // Fills whatever the launcher's page host gives it; the scroll pane handles overflow.
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setFillWidth(true);
        setSpacing(0);

        getChildren().addAll(buildHeader(), buildScrollableBody());
        reload();
    }

    /// Pads the page down so it starts below the launcher's title bar.
    ///
    /// <p>Kept for callers that host the store outside the launcher's page host; the normal
    /// launcher page already sits below the title bar, so it passes `0`.</p>
    ///
    /// @param height the title bar height in pixels
    public void setTopInset(double height) {
        double value = Math.max(0, height);
        if (Math.abs(getPadding().getTop() - value) < 0.5)
            return;
        setPadding(new Insets(value, 0, 0, 0));
        context.logInfo("商店顶边距已设为 " + Math.round(value) + "px");
    }

    @Override
    public void onPageShown() {
        if (entries.isEmpty())
            reload();
    }

    // -------------------------------------------------------------------- header

    /// Builds the title bar: title, search field and refresh.
    ///
    /// <p>No back button here on purpose — the launcher's own title bar already shows a back
    /// arrow, and that arrow is wired to the store's close action by [StorePlugin].</p>
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
        searchField.setPrefWidth(240);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        StackPane refresh = StoreWidgets.button("刷新", this::reload);

        HBox actions = new HBox(8, searchField, refresh);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, texts, StoreWidgets.spacer(), actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 16, 12, 16));
        row.getStyleClass().add("store-header");
        return row;
    }

    /// Builds the card area, which is now the page's own scroller.
    ///
    /// <p>The page is pushed straight into the launcher's navigator, so unlike a plugin page in
    /// `PluginPageProxy` it has no host scroll pane above it — removing the local scroller left
    /// the content with nothing to scroll it. This is therefore the single, page-wide scroller:
    /// cards and expanded README blocks are ordinary children of it, and the wheel carries the
    /// whole page from the first card to the last.</p>
    ///
    /// @return the scroll pane
    private Node buildScrollableBody() {
        statusLabel.getStyleClass().add("store-status");

        VBox content = new VBox(10, statusLabel, cards);
        content.getStyleClass().add("store-body");
        content.setPadding(new Insets(12, 16, 16, 16));
        content.setFillWidth(true);
        content.setMaxWidth(Double.MAX_VALUE);

        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().add("store-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        enableSmoothScrolling(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /// Attaches the launcher's eased wheel scrolling.
    ///
    /// <p>`FXUtils.smoothScrolling` is the public entry point but it does nothing when the user
    /// disables launcher animations, so the package-private helper behind it is used directly;
    /// if reflection fails the public helper is still applied, so scrolling never disappears.</p>
    ///
    /// @param scroll the scroll pane to tune
    private static void enableSmoothScrolling(ScrollPane scroll) {
        try {
            Class<?> utils = Class.forName("org.jackhuang.hmcl.ui.ScrollUtils");
            var method = utils.getDeclaredMethod("addSmoothScrolling", ScrollPane.class);
            method.setAccessible(true);
            method.invoke(null, scroll);
            return;
        } catch (Throwable ignored) {
            // Fall through to the public helper.
        }
        FXUtils.smoothScrolling(scroll);
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

        Label summary = new Label(shorten(entry.summary()));
        summary.getStyleClass().add("store-summary");
        summary.setWrapText(true);
        // Two lines at most: the box below caps the height so a long description cannot stretch
        // the whole card. `shorten` keeps the text near that bound so nothing is clipped abruptly.
        summary.setMaxHeight(SUMMARY_MAX_HEIGHT);

        StackPane summaryBox = new StackPane(summary);
        summaryBox.getStyleClass().add("store-summary-box");
        summaryBox.setMaxWidth(Double.MAX_VALUE);
        summaryBox.setMaxHeight(SUMMARY_MAX_HEIGHT + SUMMARY_BOX_PADDING);
        // Inset from the card's left edge so the description reads as a nested block.
        VBox.setMargin(summaryBox, new Insets(0, 0, 0, 18));
        StackPane.setAlignment(summary, Pos.CENTER_LEFT);

        HBox titleRow = new HBox(10, name, stars, StoreWidgets.spacer());
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // The full README lives in a collapsible area so the list stays scannable.
        VBox details = new VBox(6);
        details.setVisible(false);
        details.setManaged(false);
        details.getStyleClass().add("store-details");

        StackPane toggle = StoreWidgets.button("显示全部", null);
        toggle.getStyleClass().add("store-toggle");

        // Three separate actions, so each has an obvious meaning:
        //   安装 / 更新  – install the newest release (or the one picked below)
        //   版本         – open the release picker and install the chosen version
        StackPane install = StoreWidgets.accentButton("安装", null);
        StackPane versions = StoreWidgets.button("版本", null);
        versions.getStyleClass().add("store-versions-button");
        versions.setVisible(false);
        versions.setManaged(false);

        Label installedLabel = new Label();
        installedLabel.getStyleClass().add("store-installed-label");

        Label progress = new Label();
        progress.getStyleClass().add("store-progress");

        // A fixed-height bar keeps its own space in the layout; showing and hiding it only toggles
        // visibility so the card does not jump around while a download runs.
        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
        bar.getStyleClass().add("store-progress-bar");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(6);
        bar.setMinHeight(6);
        bar.setVisible(false);

        String installedVersion = installedVersion(entry);
        if (installedVersion != null)
            installedLabel.setText("已安装 v" + installedVersion);

        toggle.setOnMouseClicked(event -> {
            boolean show = !details.isVisible();
            details.setVisible(show);
            details.setManaged(show);
            ((Label) toggle.getChildren().get(0)).setText(show ? "收起" : "显示全部");
            if (show && details.getChildren().isEmpty())
                loadDetails(entry, details);
        });

        // Load the release list once, then enable the version picker and the update hint.
        loadVersions(entry, install, versions, installedLabel, installedVersion);

        install.setOnMouseClicked(event -> {
            if (install.isDisabled())
                return;
            install.setDisable(true);
            ((Label) install.getChildren().get(0)).setText("准备中…");
            progress.setText("");
            bar.setVisible(true);
            bar.setProgress(0);
            installEntry(entry, install, progress, bar, null);
        });

        versions.setOnMouseClicked(event -> {
            if (versions.isDisabled())
                return;
            promptVersionChoice(entry, install, versions, progress, bar);
        });

        HBox footer = new HBox(8, toggle, install, versions, installedLabel,
                StoreWidgets.spacer(), progress);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, titleRow, summaryBox, details, footer, bar);
        card.getStyleClass().add("store-card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /// Asks which release to install, then installs it.
    ///
    /// <p>A popup list is used instead of an always-visible combo box: the version picker is a
    /// rarely needed action, and keeping it behind a button leaves the card uncluttered.</p>
    ///
    /// @param entry    the repository
    /// @param install  the install button, restored on failure
    /// @param progress the progress label
    /// @param bar      the progress bar
    private void promptVersionChoice(StoreSource.Entry entry, StackPane install, StackPane versions,
                                     Label progress, javafx.scene.control.ProgressBar bar) {
        context.getRoot().supplyAsync(() -> source.findReleases(entry.fullName()))
                .whenComplete((releases, throwable) -> Platform.runLater(() -> {
                    if (throwable != null || releases == null || releases.isEmpty()) {
                        progress.setText("该仓库没有可选版本");
                        return;
                    }

                    javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
                    for (StoreSource.Release release : releases) {
                        javafx.scene.control.MenuItem item =
                                new javafx.scene.control.MenuItem(release.version());
                        item.setOnAction(e -> {
                            install.setDisable(true);
                            ((Label) install.getChildren().get(0)).setText("准备中…");
                            progress.setText("");
                            bar.setVisible(true);
                            bar.setProgress(0);
                            installEntry(entry, install, progress, bar, release.version());
                        });
                        menu.getItems().add(item);
                    }

                    // Anchored to the version button itself, so the list appears where clicked.
                    menu.show(versions, javafx.geometry.Side.BOTTOM, 0, 0);
                }));
    }

    /// Shortens a repository description so a card cannot grow tall.
    ///
    /// <p>JavaFX labels have no "maximum lines" setting, and a wrapped label simply grows. Cutting
    /// the text near the two-line mark keeps the card compact and lets the full text stay
    /// available through the expanded README.</p>
    ///
    /// @param text the raw description, possibly `null`
    /// @return the description to display
    private static String shorten(String text) {
        if (text == null || text.isBlank())
            return "（该仓库没有简介）";

        String trimmed = text.strip().replaceAll("\\s+", " ");
        if (trimmed.length() <= SUMMARY_MAX_CHARS)
            return trimmed;
        return trimmed.substring(0, SUMMARY_MAX_CHARS).stripTrailing() + "…";
    }

    /// Lazily loads the README into a card's detail area and renders it as Markdown.
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
                        details.getChildren().add(hint("该仓库没有可显示的 README。"));
                        return;
                    }

                    VBox readmeBox = new VBox(6);
                    readmeBox.getStyleClass().add("store-readme-box");
                    readmeBox.setFillWidth(true);
                    readmeBox.setMaxWidth(Double.MAX_VALUE);

                    try {
                        readmeBox.getChildren().add(readmeViewport(MarkdownRenderer.render(readme)));
                    } catch (Throwable e) {
                        // A malformed README must not break the page; fall back to plain text.
                        context.logWarning("README 渲染失败，改为纯文本：" + e);
                        TextArea area = new TextArea(readme);
                        area.setEditable(false);
                        area.setWrapText(true);
                        area.setPrefHeight(README_VIEWPORT_HEIGHT);
                        area.setMinHeight(README_VIEWPORT_HEIGHT);
                        area.setMaxHeight(README_VIEWPORT_HEIGHT);
                        readmeBox.getChildren().add(area);
                    }

                    details.getChildren().add(readmeBox);

                    Label link = new Label("在 GitHub 上查看：" + entry.homepage());
                    link.getStyleClass().add("store-link");
                    link.setOnMouseClicked(event -> FXUtils.openLink(entry.homepage()));
                    details.getChildren().add(link);
                }));
    }

    /// Puts the rendered README into a fixed-height viewport.
    ///
    /// <p>A README can run to hundreds of lines. Laid out inline it stretched its card over the
    /// whole page, so the introduction is now kept inside a small bordered box of its own and
    /// scrolls internally — the card stays compact and the description remains readable.</p>
    ///
    /// <p>[MarkdownRenderer#render] already returns a [ScrollPane], so it is reused rather than
    /// wrapped a second time.</p>
    ///
    /// @param rendered the node returned by the renderer
    /// @return the viewport holding the README
    private ScrollPane readmeViewport(Node rendered) {
        ScrollPane scroll = rendered instanceof ScrollPane inner
                ? inner
                : new ScrollPane(rendered);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefHeight(README_VIEWPORT_HEIGHT);
        scroll.setMinHeight(README_VIEWPORT_HEIGHT);
        scroll.setMaxHeight(README_VIEWPORT_HEIGHT);
        scroll.getStyleClass().add("store-readme");
        enableSmoothScrolling(scroll);
        return scroll;
    }

    // ------------------------------------------------------------------ install

    /// Downloads and installs one entry.
    ///
    /// @param entry    the repository
    /// @param button   the install button to restore on failure
    /// @param progress where to show the download progress
    /// @param bar      the progress bar
    /// @param version  the chosen version, or `null` for the newest release
    private void installEntry(StoreSource.Entry entry, StackPane button, Label progress,
                              javafx.scene.control.ProgressBar bar, String version) {
        String target = version;

        context.getRoot().supplyAsync(() -> {
            // Resolve the asset for the requested version; fall back to the latest release.
            if (target != null) {
                for (StoreSource.Release release : source.findReleases(entry.fullName())) {
                    if (release.version().equals(target) || release.tag().equals(target))
                        return release.asset();
                }
            }
            return source.findLatestJar(entry.fullName());
        }).whenComplete((asset, throwable) -> Platform.runLater(() -> {
            if (throwable != null || asset == null) {
                failInstall(button, progress, bar, "该仓库没有可安装的发行版 jar");
                return;
            }

            installer.install(entry.fullName(), asset, new StoreInstaller.Callback() {
                @Override
                public void onProgress(long received, long total, String sourceHost) {
                    if (total > 0) {
                        double fraction = (double) received / total;
                        bar.setProgress(fraction);
                        progress.setText(Math.round(fraction * 100) + "% · "
                                + StoreInstaller.formatBytes(received)
                                + " / " + StoreInstaller.formatBytes(total));
                    } else {
                        // Unknown size: keep the bar indeterminate and show the byte count.
                        bar.setProgress(javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS);
                        progress.setText(StoreInstaller.formatBytes(received));
                    }
                }

                @Override
                public void onInstalled(PluginDescription description) {
                    bar.setProgress(1.0);
                    bar.setVisible(false);
                    progress.setText(description.getName() + " " + description.getVersion() + " 已安装");
                    ((Label) button.getChildren().get(0)).setText("重新安装");
                    button.setDisable(false);
                    announceInstalled(description);
                }

                @Override
                public void onFailed(String message) {
                    failInstall(button, progress, bar, message);
                }
            });
        }));
    }

    /// Restores the install button after a failure.
    ///
    /// @param button   the button
    /// @param progress the progress label
    /// @param bar      the progress bar
    /// @param message  the reason
    private void failInstall(StackPane button, Label progress,
                             javafx.scene.control.ProgressBar bar, String message) {
        button.setDisable(false);
        ((Label) button.getChildren().get(0)).setText("安装");
        bar.setVisible(false);
        progress.setText(message);
    }

    /// Loads the release list and decides what the buttons should say.
    ///
    /// <p>Offers an update only when the newest release differs from the installed version, so the
    /// button never nags about something already up to date.</p>
    ///
    /// @param entry            the repository
    /// @param install          the install / update button
    /// @param versions         the version-picker button
    /// @param installedLabel   the label showing the installed version
    /// @param installedVersion the installed version, or `null` when absent
    private void loadVersions(StoreSource.Entry entry, StackPane install, StackPane versions,
                              Label installedLabel, String installedVersion) {
        context.getRoot().supplyAsync(() -> source.findReleases(entry.fullName()))
                .whenComplete((releases, throwable) -> Platform.runLater(() -> {
                    if (throwable != null || releases == null || releases.isEmpty())
                        return;

                    // The picker is only useful when there is something to pick from.
                    if (releases.size() > 1) {
                        versions.setVisible(true);
                        versions.setManaged(true);
                    }

                    if (installedVersion == null)
                        return;

                    String newest = releases.get(0).version();
                    installedLabel.setText("已安装 v" + installedVersion);

                    if (newest.equals(installedVersion)) {
                        ((Label) install.getChildren().get(0)).setText("重新安装");
                    } else {
                        installedLabel.setText("已安装 v" + installedVersion + " · 可更新到 v" + newest);
                        installedLabel.getStyleClass().add("store-update-available");
                        ((Label) install.getChildren().get(0)).setText("更新");
                    }
                }));
    }

    /// Reports a finished install without interrupting the user.
    ///
    /// <p>Deliberately not a confirmation dialog: installing several plugins in a row should not
    /// require dismissing a prompt after each one. The card shows the result and the launcher picks
    /// the new jar up on its next start.</p>
    ///
    /// @param description the installed plugin
    private void announceInstalled(PluginDescription description) {
        context.logInfo("插件已安装：" + description.getName() + " " + description.getVersion());
    }

    /// Returns the installed version of a repository's plugin.
    ///
    /// @param entry the repository
    /// @return the version string, or `null` when not installed
    private String installedVersion(StoreSource.Entry entry) {
        String name = entry.name();
        if (name == null)
            return null;
        for (var handle : PluginManager.getPlugins()) {
            String id = handle.getId();
            if (id != null && name.contains(id.replace("org.dsh.", ""))) {
                try {
                    return handle.getDescription().getVersion();
                } catch (Throwable e) {
                    return "?";
                }
            }
        }
        return null;
    }

    /// Returns whether a repository's plugin is already installed.
    ///
    /// @param entry the repository
    /// @return whether a plugin with the same repository name is present
    private boolean isInstalled(StoreSource.Entry entry) {
        return installedVersion(entry) != null;
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
