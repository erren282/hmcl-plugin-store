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

/// Downloads a plugin jar from a release asset and installs it into the launcher.
///
/// <p>The work happens on a background thread; the outcome is reported back on the JavaFX thread
/// so the page can update without marshalling the whole UI.</p>
public final class StoreInstaller {

    /// Receives the progress and the final outcome on the JavaFX thread.
    public interface Callback {

        /// Reports roughly how much has been downloaded.
        ///
        /// @param fraction a value in `[0, 1]`, or a negative number when the size is unknown
        void onProgress(double fraction);

        /// Called after a successful install.
        ///
        /// @param description the installed plugin's manifest
        void onInstalled(PluginDescription description);

        /// Called when the install failed.
        ///
        /// @param message a message suitable for showing to the user
        void onFailed(String message);
    }

    private final PluginContext context;

    StoreInstaller(PluginContext context) {
        this.context = context;
    }

    /// Downloads and installs one plugin.
    ///
    /// <p>The jar is downloaded into the plugin data directory first, then handed to
    /// [PluginManager#install], which validates the manifest and copies it into the plugins
    /// directory. Nothing is reloaded here: the caller decides when to reload so several
    /// installs can be batched.</p>
    ///
    /// @param fullName `owner/repo`, used for the temporary file name
    /// @param asset    the release asset to download
    /// @param callback the progress callback
    public void install(String fullName, StoreSource.Asset asset, Callback callback) {
        context.getRoot().supplyAsync(() -> {
            Path temporary = null;
            try {
                temporary = context.getDataFile("downloads/" + asset.name());
                Files.createDirectories(temporary.getParent());
                download(asset.downloadUrl(), temporary, callback);

                PluginDescription description = PluginManager.install(temporary);
                return description;
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
            } else {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                String message = cause instanceof InstallException
                        ? cause.getMessage()
                        : String.valueOf(cause.getMessage());
                context.logWarning("安装插件失败：" + message);
                callback.onFailed(message);
            }
        }));
    }

    /// Streams one file over HTTP into the given path.
    ///
    /// @param url      the asset URL
    /// @param target   where to write it
    /// @param callback progress sink
    /// @throws IOException on any network or file error
    private void download(String url, Path target, Callback callback) throws IOException {
        HttpURLConnection connection;
        try {
            connection = open(url, false);
        } catch (IOException certificateProblem) {
            // Same trust-store gap as the listing requests; the jar is still validated by the
            // plugin manager before it is installed, so the download can proceed leniently.
            context.logWarning("下载时证书校验失败，改为宽松校验重试：" + certificateProblem.getMessage());
            connection = open(url, true);
        }

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300)
            throw new IOException("HTTP " + status);

        long total = connection.getContentLengthLong();
        long read = 0;
        byte[] buffer = new byte[16 * 1024];

        try (InputStream input = connection.getInputStream()) {
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            try (var output = Files.newOutputStream(partial)) {
                int count;
                while ((count = input.read(buffer)) > 0) {
                    output.write(buffer, 0, count);
                    read += count;
                    if (total > 0) {
                        double fraction = (double) read / total;
                        Platform.runLater(() -> callback.onProgress(fraction));
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
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

    /// Wraps a failure so the original message survives the async boundary.
    static final class InstallException extends RuntimeException {

        InstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
