/*
 * PCL 商店插件 —— 安装器
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.pluginstore;

import javafx.application.Platform;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginDescription;
import org.jackhuang.hmcl.plugin.PluginManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Downloads a plugin jar from a release asset and installs it into the launcher.
///
/// <p>The work happens on a background thread; progress and the outcome are reported on the JavaFX
/// thread so the page can update without marshalling the whole UI.</p>
///
/// <p>Downloads try GitHub first and fall back to public mirrors. GitHub's release CDN is often
/// slow or unreachable from mainland China, and a several-megabyte plugin can otherwise appear to
/// hang. The mirror list is tried in order and the first working one wins.</p>
public final class StoreInstaller {

    /// Receives the progress and the final outcome on the JavaFX thread.
    public interface Callback {

        /// Reports download progress.
        ///
        /// @param received bytes downloaded so far
        /// @param total    total bytes, or `0` when the server did not say
        /// @param source   the host being used, for display
        void onProgress(long received, long total, String source);

        /// Called after a successful install.
        ///
        /// @param description the installed plugin's manifest
        void onInstalled(PluginDescription description);

        /// Called when the update was queued instead of applied.
        ///
        /// <p>Happens when the old jar is still loaded: the swap runs on the next launcher start.
        /// This is a normal outcome, not a failure.</p>
        ///
        /// @param pluginId the plugin whose update was staged
        default void onStaged(String pluginId) {
        }

        /// Called when the install failed.
        ///
        /// @param message a message suitable for showing to the user
        void onFailed(String message);
    }

    /// Public mirrors that proxy GitHub release downloads.
    ///
    /// <p>Each entry is a prefix prepended to the original URL. They are third-party services, so
    /// they are only used as a fallback and the downloaded jar is still validated by the launcher
    /// before it is installed. The list only holds hosts verified to answer, so a dead mirror does
    /// not cost the user a timeout on every download.</p>
    private static final List<String> MIRROR_PREFIXES = List.of(
            "https://ghfast.top/",
            "https://gh-proxy.com/");

    private final PluginContext context;

    /// Stages updates that cannot be applied while the old jar is loaded.
    private final PendingUpdates pending;

    StoreInstaller(PluginContext context) {
        this.context = context;
        this.pending = new PendingUpdates(context);
    }

    /// Applies updates staged by earlier sessions.
    ///
    /// <p>Called early in the store plugin's own start-up, after the launcher has discarded the
    /// previous class loaders, so other plugins' jars are unlocked again.</p>
    void applyPendingUpdates() {
        int applied = pending.applyAll();
        if (applied > 0)
            context.logInfo("已应用 " + applied + " 个待安装更新，重启启动器后生效");
    }

    /// Downloads and installs one plugin.
    ///
    /// <p>The jar goes into the plugin data directory first, then to [PluginManager#install],
    /// which validates the manifest and copies it into the plugins directory. Nothing is reloaded
    /// here: the caller decides when, so several installs can be batched.</p>
    ///
    /// @param fullName `owner/repo`, used for messages
    /// @param asset    the release asset to download
    /// @param callback the progress callback
    public void install(String fullName, StoreSource.Asset asset, Callback callback) {
        context.getRoot().supplyAsync(() -> {
            Path temporary = null;
            try {
                temporary = context.getDataFile("downloads/" + asset.name());
                Files.createDirectories(temporary.getParent());
                downloadWithFallback(asset, temporary, callback);

                // `PluginManager.install` refuses a plugin whose id is already installed, so an
                // upgrade or downgrade has to remove the old jar first. When that jar is still
                // loaded by the launcher the removal fails, and the update is staged for the next
                // start instead of failing outright.
                try {
                    removeInstalledVersion(temporary);
                } catch (PluginInUseException locked) {
                    String pluginId = readPluginId(temporary);
                    if (pluginId == null || !pending.stage(pluginId, temporary, asset.name()))
                        throw locked;
                    throw new StagedException(pluginId);
                }

                return PluginManager.install(temporary);
            } catch (StagedException staged) {
                throw staged;
            } catch (Exception e) {
                throw new InstallException(describe(fullName, e), e);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // A leftover temp file is harmless.
                    }
                }
            }
        }).whenComplete((description, throwable) -> Platform.runLater(() -> {
            if (throwable == null) {
                context.logInfo("已安装插件：" + description.getId() + " " + description.getVersion());
                callback.onInstalled(description);
                return;
            }

            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
            if (cause instanceof StagedException staged) {
                // Not a failure: the update is queued and will be applied on the next start.
                context.logInfo("已暂存更新，下次启动生效：" + staged.getMessage());
                callback.onStaged(staged.getMessage());
                return;
            }

            String message = cause instanceof InstallException
                    ? cause.getMessage()
                    : String.valueOf(cause.getMessage());
            context.logWarning("安装插件失败：" + message);
            callback.onFailed(message);
        }));
    }

    /// Tries the original URL, then each mirror, until one succeeds.
    ///
    /// @param asset    the asset to fetch
    /// @param target   where to write it
    /// @param callback progress sink
    /// @throws IOException when every source failed
    private void downloadWithFallback(StoreSource.Asset asset, Path target, Callback callback)
            throws IOException {
        IOException last = null;

        for (String url : candidateUrls(asset.downloadUrl())) {
            try {
                download(url, target, callback);
                if (!url.equals(asset.downloadUrl()))
                    context.logInfo("已通过镜像下载：" + hostOf(url));
                return;
            } catch (IOException e) {
                last = e;
                context.logWarning("下载源失败（" + hostOf(url) + "）：" + e.getMessage());
            }
        }

        throw last != null ? last : new IOException("没有可用的下载源");
    }

    /// Builds the ordered list of URLs to try: the original first, then mirrors.
    ///
    /// @param original the GitHub asset URL
    /// @return the URLs, without duplicates
    private static Set<String> candidateUrls(String original) {
        Set<String> urls = new LinkedHashSet<>();
        if (original == null)
            return urls;

        urls.add(original);
        for (String prefix : MIRROR_PREFIXES)
            urls.add(prefix + original);
        return urls;
    }

    /// Returns the host of a URL, for progress messages.
    ///
    /// @param url the URL
    /// @return the host, or the URL itself when it cannot be parsed
    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Throwable e) {
            return url;
        }
    }

    /// Deletes any already-installed jar carrying the same plugin id.
    ///
    /// <p>`PluginManager.install` throws when the id is already present, which made upgrades and
    /// downgrades impossible. Removing the previous jar first is what the launcher's own plugin
    /// page does when the user deletes a plugin.</p>
    ///
    /// <p>Only the jar file is removed; the plugin's data directory is left untouched so its
    /// settings survive the version change.</p>
    ///
    /// @param downloaded the freshly downloaded jar
    /// @throws IOException when the old jar exists but cannot be deleted
    private void removeInstalledVersion(Path downloaded) throws IOException {
        String newId;
        try {
            newId = org.jackhuang.hmcl.plugin.PluginDescription.readFromJar(downloaded).getId();
        } catch (Throwable e) {
            // Let `install` report the real problem with the downloaded file.
            return;
        }
        if (newId == null)
            return;

        Path directory = PluginManager.getPluginsDirectory();
        if (directory == null || !Files.isDirectory(directory))
            return;

        try (var stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path existing : stream) {
                String existingId;
                try {
                    existingId = org.jackhuang.hmcl.plugin.PluginDescription
                            .readFromJar(existing).getId();
                } catch (Throwable e) {
                    continue;
                }
                if (!newId.equals(existingId))
                    continue;

                deleteOrExplain(existing);
                context.logInfo("已移除旧版本插件：" + existing.getFileName() + "（" + newId + "）");
            }
        }
    }

    /// Deletes a loaded plugin jar, turning a Windows lock into a readable message.
    ///
    /// <p>The launcher keeps every loaded plugin jar open for the whole session, and Windows
    /// refuses both deletion and renaming of such a file. A running plugin therefore cannot be
    /// replaced in place, so the failure is reported as an actionable instruction instead of the
    /// bare "另一个程序正在使用此文件" that Windows produces.</p>
    ///
    /// @param jar the installed jar to remove
    /// @throws IOException when the file is locked or cannot be deleted
    private static void deleteOrExplain(Path jar) throws IOException {
        try {
            Files.deleteIfExists(jar);
        } catch (IOException e) {
            // A locked jar is expected while the plugin is loaded; the caller stages the update.
            throw new PluginInUseException(jar, e);
        }
    }

    /// Raised when an installed plugin jar cannot be removed because it is loaded.
    static final class PluginInUseException extends IOException {

        PluginInUseException(Path jar, Throwable cause) {
            super("插件 " + jar.getFileName() + " 正在被启动器使用", cause);
        }
    }

    /// Raised when an update was queued instead of applied.
    static final class StagedException extends RuntimeException {

        StagedException(String pluginId) {
            super(pluginId);
        }
    }

    /// Reads a jar's plugin id, tolerating a malformed file.
    ///
    /// @param jar the jar
    /// @return the id, or `null`
    private static String readPluginId(Path jar) {
        try {
            return org.jackhuang.hmcl.plugin.PluginDescription.readFromJar(jar).getId();
        } catch (Throwable e) {
            return null;
        }
    }

    /// Streams one file over HTTP into the given path.
    ///
    /// @param url      the asset URL
    /// @param target   where to write it
    /// @param callback progress sink
    /// @throws IOException on any network or file error
    private void download(String url, Path target, Callback callback) throws IOException {
        // The TLS handshake happens when the response code is read, not when the connection object
        // is created, so the retry has to wrap the request rather than just `open`.
        HttpURLConnection connection;
        try {
            connection = request(url, false);
        } catch (IOException first) {
            if (!isCertificateProblem(first))
                throw first;
            context.logWarning("下载时证书校验失败，改为宽松校验重试：" + first.getMessage());
            connection = request(url, true);
        }

        long total = connection.getContentLengthLong();
        long read = 0;
        byte[] buffer = new byte[32 * 1024];
        String source = hostOf(url);
        long lastReport = 0;

        try (InputStream input = connection.getInputStream()) {
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            try (var output = Files.newOutputStream(partial)) {
                int count;
                while ((count = input.read(buffer)) > 0) {
                    output.write(buffer, 0, count);
                    read += count;

                    // Throttle UI updates: one per 120 ms is smooth without flooding the FX thread.
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 120 || (total > 0 && read >= total)) {
                        lastReport = now;
                        long reported = read;
                        Platform.runLater(() -> callback.onProgress(reported, total, source));
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
    }

    /// Opens a connection and performs the request, returning one whose status is already checked.
    ///
    /// @param url     the URL
    /// @param lenient whether to skip TLS certificate validation
    /// @return a connection ready to be read
    /// @throws IOException when the connection fails or the status is not 2xx
    private static HttpURLConnection request(String url, boolean lenient) throws IOException {
        HttpURLConnection connection = open(url, lenient);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300)
                throw new IOException("HTTP " + status);
            return connection;
        } catch (IOException e) {
            connection.disconnect();
            throw e;
        }
    }

    /// Returns whether a failure looks like a TLS certificate validation error.
    ///
    /// @param error the failure
    /// @return whether it is a certificate problem
    private static boolean isCertificateProblem(IOException error) {
        String message = String.valueOf(error.getMessage());
        return message.contains("PKIX")
                || message.contains("certificate")
                || message.contains("SSLHandshake")
                || error instanceof javax.net.ssl.SSLException;
    }

    /// Opens a connection to an asset URL.
    ///
    /// @param url     the URL
    /// @param lenient whether to skip TLS certificate validation
    /// @return the connection
    /// @throws IOException when the connection cannot be opened
    private static HttpURLConnection open(String url, boolean lenient) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        if (lenient && connection instanceof javax.net.ssl.HttpsURLConnection https) {
            try {
                javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
                ssl.init(null, new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            @Override
                            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String type) {
                            }

                            @Override
                            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String type) {
                            }

                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[0];
                            }
                        }
                }, new java.security.SecureRandom());
                https.setSSLSocketFactory(ssl.getSocketFactory());
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("无法建立 TLS 连接：" + e.getMessage(), e);
            }
        }

        connection.setRequestProperty("User-Agent", "hmcl-plugin-store");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    /// Turns an exception into a short, user-facing message.
    ///
    /// @param fullName the repository the install came from
    /// @param error    the failure
    /// @return the message
    private static String describe(String fullName, Exception error) {
        String message = error.getMessage();
        if (error instanceof org.jackhuang.hmcl.plugin.PluginFormatException)
            return "插件格式不受支持：" + message;
        if (message == null || message.isBlank())
            return "无法从 " + fullName + " 安装插件";
        return message;
    }

    /// Formats a byte count for display.
    ///
    /// @param bytes the byte count
    /// @return a short human-readable string
    static String formatBytes(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /// Wraps a failure so the original message survives the async boundary.
    static final class InstallException extends RuntimeException {

        InstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}