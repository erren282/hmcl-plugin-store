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
import org.jackhuang.hmcl.ui.Controllers;
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

    /// Notified after an update is staged, so the host can offer a restart.
    private final Runnable onStagedListener;

    /// Asked to restart the launcher once the user accepts the restart prompt.
    private final Runnable restartAction;

    /// Whether this session already offered to restart after an install.
    ///
    /// <p>Only the first install after the launcher started prompts: a run of installs is one
    /// batch, and nagging after each of them would be worse than useless.</p>
    private boolean restartPromptShown = false;

    public StorePage(PluginContext context, Runnable onStagedListener) {
        this(context, onStagedListener, () -> {
        });
    }

    /// Builds the page with a restart action used by the post-install prompt.
    ///
    /// @param context           the plugin context
    /// @param onStagedListener  notified when an update is staged rather than installed
    /// @param restartAction     restarts the launcher
    public StorePage(PluginContext context, Runnable onStagedListener, Runnable restartAction) {
        this.context = context;
        this.onStagedListener = onStagedListener;
        this.restartAction = restartAction;
        this.source = new StoreSource(context);
        this.installer = new StoreInstaller(context);

        getStyleClass().add("store-page");
        // Fills whatever the launcher's page host gives it; the scroll pane handles overflow.
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setFillWidth(true);
        setSpacing(0);

        // Transparent root so the blurred wallpaper shows through, exactly like the launcher's own
        // pages: those keep the wallpaper visible behind translucent content instead of painting
        // an opaque sheet over it.
        setBackground(null);

        VBox content = new VBox(0, buildHeader(), buildScrollableBody());
        content.setFillWidth(true);
        content.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        javafx.scene.layout.StackPane backdrop = new javafx.scene.layout.StackPane();
        backdrop.getStyleClass().add("store-backdrop");
        backdrop.setEffect(storeBlur());
        backdrop.setMouseTransparent(true);

        javafx.scene.layout.StackPane layered = new javafx.scene.layout.StackPane(backdrop, content);
        layered.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(layered, Priority.ALWAYS);
        getChildren().add(layered);

        reload();
    }

    /// Builds the blur applied to the backdrop.
    ///
    /// <p>`BoxBlur` with a few iterations gives a soft, cheap frosted look; the launcher uses the
    /// same effect elsewhere (for example to hide a world seed).</p>
    ///
    /// @return the effect
    private static javafx.scene.effect.BoxBlur storeBlur() {
        javafx.scene.effect.BoxBlur blur = new javafx.scene.effect.BoxBlur();
        blur.setWidth(20);
        blur.setHeight(20);
        blur.setIterations(3);
        return blur;
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
            statusLabel.setText("共 " + entries.size() + " 个插件"
                    + (StoreSource.isRateLimited()
                    ? "　·　GitHub 接口已限流，更新检测不可用（见上方说明）"
                    : ""));
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

        // One action per card: install the newest release, or update to it.
        StackPane install = StoreWidgets.accentButton("安装", null);

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
        if (installedVersion != null) {
            installedLabel.setText("已安装 v" + installedVersion);
            // Grey the button immediately: without this the card stayed clickable until the
            // release list finished loading, so an installed plugin could be installed again.
            markInstalled(install);
        }

        toggle.setOnMouseClicked(event -> {
            boolean show = !details.isVisible();
            details.setVisible(show);
            details.setManaged(show);
            ((Label) toggle.getChildren().get(0)).setText(show ? "收起" : "显示全部");
            if (show && details.getChildren().isEmpty())
                loadDetails(entry, details);
        });

        // Only installed plugins need the release list: an uninstalled one just installs the latest,
        // so checking for it would waste an API call per card. GitHub's unauthenticated quota is
        // 60 requests per hour, and the store has a dozen repositories — querying them all at once
        // exhausted the quota and left every card without version information.
        if (installedVersion != null)
            loadVersions(entry, install, installedLabel, installedVersion);

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

        HBox footer = new HBox(8, toggle, install, installedLabel,
                StoreWidgets.spacer(), progress);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, titleRow, summaryBox, details, footer, bar);
        card.getStyleClass().add("store-card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
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
                        // Say why instead of a blanket "no README": the usual cause is GitHub's
                        // unauthenticated rate limit, which the user can lift with a token.
                        details.getChildren().add(hint("无法获取 README。"
                                + "常见原因是 GitHub 接口限流，"
                                + "可在插件数据目录的 github-token.txt 中填入 token 后重试。"));
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
                // Say what actually went wrong: "no release" and "GitHub was unreachable" are very
                // different problems, and the second one is far more common here.
                String reason = throwable != null
                        ? "无法获取发行版：" + throwable.getMessage()
                        : StoreSource.isRateLimited()
                        ? "GitHub 接口已限流，请稍后再试或配置 token"
                        : "无法获取发行版（网络或仓库状态异常）";
                failInstall(button, progress, bar, reason);
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
                    markInstalled(button);
                    announceInstalled(description);

                    // A freshly installed plugin is not loaded yet: the launcher only maps plugin
                    // jars at start-up. Offer the restart once per session so a run of installs
                    // does not produce a prompt for every single jar.
                    offerRestart(description);
                }

                @Override
                public void onStaged(String pluginId) {
                    bar.setProgress(1.0);
                    bar.setVisible(false);
                    progress.setText("已下载完成，重启启动器即可完成更新");
                    button.setDisable(false);
                    ((Label) button.getChildren().get(0)).setText("待安装");

                    // Offer the restart right away: the update cannot apply while the old jar is
                    // loaded, so restarting is the next step and should be one click away.
                    if (onStagedListener != null)
                        onStagedListener.run();
                    offerRestart(null);
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
    /// @param installedLabel   the label showing the installed version
    /// @param installedVersion the installed version, or `null` when absent
    private void loadVersions(StoreSource.Entry entry, StackPane install,
                              Label installedLabel, String installedVersion) {
        context.getRoot().supplyAsync(() -> source.findReleases(entry.fullName()))
                .whenComplete((releases, throwable) -> Platform.runLater(() -> {
                    if (throwable != null || releases == null || releases.isEmpty())
                        return;

                    if (installedVersion == null)
                        return;

                    String newest = releases.get(0).version();
                    installedLabel.setText("已安装 v" + installedVersion);

                    if (newest.equals(installedVersion)) {
                        // Already up to date: grey the button out instead of offering a reinstall.
                        markInstalled(install);
                    } else {
                        installedLabel.setText("已安装 v" + installedVersion + " · 可更新到 v" + newest);
                        installedLabel.getStyleClass().add("store-update-available");
                        // The card greyed the button as soon as it saw the plugin installed; an
                        // available update has to undo that, or "更新" would be disabled.
                        enableForUpdate(install);
                    }
                }));
    }

    /// Re-enables an install button for an available update.
    ///
    /// @param install the button
    private static void enableForUpdate(StackPane install) {
        install.setDisable(false);
        install.getStyleClass().remove("store-installed");
        ((Label) install.getChildren().get(0)).setText("更新");
    }

    /// Turns an install button into a disabled "已安装" state.
    ///
    /// @param install the button
    private static void markInstalled(StackPane install) {
        install.setDisable(true);
        install.getStyleClass().add("store-installed");
        ((Label) install.getChildren().get(0)).setText("已安装");
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

    /// Offers to restart the launcher so a new plugin actually loads.
    ///
    /// <p>The launcher maps plugin jars once, at start-up, so an installed jar stays inert until
    /// the next launch. This is the one moment a restart is genuinely required, and it is worth
    /// saying so — but only once per session: a user installing several plugins in a row is doing
    /// one piece of work, and a prompt per jar would be noise.</p>
    ///
    /// @param description the plugin that was installed, or `null` when an update was staged
    private void offerRestart(@org.jetbrains.annotations.Nullable PluginDescription description) {
        if (restartPromptShown)
            return;
        restartPromptShown = true;

        String name = description != null
                ? description.getName() + " " + description.getVersion()
                : "插件更新";

        // The launcher's own dialog rather than a JavaFX `Alert`: same styling as every other
        // prompt, and its action button is a JFXButton the confirm helper accepts directly.
        com.jfoenix.controls.JFXButton restartButton =
                new com.jfoenix.controls.JFXButton("立即重启");
        restartButton.getStyleClass().add("dialog-accept");
        restartButton.setOnAction(event -> {
            context.logInfo("用户确认重启启动器以加载新插件");
            if (restartAction != null)
                restartAction.run();
        });

        Controllers.confirmAction(
                name + " 已下载完成。\n\n启动器只在启动时加载插件，需要重启启动器，新插件才会生效。",
                "需要重启启动器",
                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.QUESTION,
                restartButton,
                null);
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