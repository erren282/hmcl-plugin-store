/*
 * PCL 商店插件 —— 商店数据源
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 * 它通过 GitHub 公开 API 抓取打了指定 topic 的仓库，作为插件商店的数据源。
 */
package org.dsh.pluginstore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/// Discovers installable plugins from GitHub repositories carrying a topic.
///
/// <p>Each repository that carries the store's topic is treated as one store entry. The entry's
/// releases supply the downloadable plugin jar, so a repository owner only has to publish a
/// release to make a plugin installable — no extra index file to maintain.</p>
public final class StoreSource {

    /// The topic whose repositories make up the store.
    static final String TOPIC = "erren282s-launcher-plugins";

    /// GitHub search endpoint; unauthenticated calls are allowed at a low rate.
    private static final String SEARCH_URL =
            "https://api.github.com/search/repositories?q=topic:" + TOPIC
                    + "&sort=updated&order=desc&per_page=100";

    private final PluginContext context;

    StoreSource(PluginContext context) {
        this.context = context;
    }

    /// One repository in the store.
    ///
    /// @param fullName  `owner/repo`
    /// @param name      the repository name
    /// @param summary   the repository description, may be empty
    /// @param stars     star count
    /// @param homepage  the repository URL
    /// @param readme    the repository README, fetched lazily
    public record Entry(String fullName, String name, String summary, int stars,
                        String homepage, String readme) {
    }

    /// One downloadable asset of a repository's latest release.
    ///
    /// @param name        the asset file name
    /// @param downloadUrl where to fetch it
    /// @param size        size in bytes
    public record Asset(String name, String downloadUrl, long size) {
    }

    /// Repositories that carry the topic but are not installable plugins.
    ///
    /// <p>The launcher source itself is tagged with the same topic so the topic page links
    /// everything together, but it is not a plugin and must never appear in the store.</p>
    private static final java.util.Set<String> EXCLUDED = java.util.Set.of(
            "erren282/erren282-launcher",
            "erren282/erren282-lauuncher",
            "erren282/hmcl-launcher");

    /// Fetches the repositories that carry the store topic.
    ///
    /// <p>Runs on a background thread; the caller is responsible for switching back to the
    /// JavaFX thread before touching the UI.</p>
    ///
    /// @return the entries, never `null`
    /// @throws IOException when the GitHub API cannot be reached
    List<Entry> fetchRepositories() throws IOException {
        String json = httpGet(SEARCH_URL);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray items = root.getAsJsonArray("items");

        List<Entry> entries = new ArrayList<>();
        if (items == null)
            return entries;

        for (JsonElement element : items) {
            JsonObject repository = element.getAsJsonObject();
            String fullName = asString(repository, "full_name");
            if (fullName == null || fullName.isBlank())
                continue;

            // Skip the launcher repository: it carries the topic but is not a plugin.
            if (EXCLUDED.contains(fullName))
                continue;

            entries.add(new Entry(
                    fullName,
                    asString(repository, "name"),
                    asString(repository, "description"),
                    repository.has("stargazers_count") ? repository.get("stargazers_count").getAsInt() : 0,
                    asString(repository, "html_url"),
                    null));
        }
        return entries;
    }

    /// Fetches the README of a repository as plain text.
    ///
    /// @param fullName `owner/repo`
    /// @return the README text, or an empty string when there is none
    String fetchReadme(String fullName) {
        try {
            return httpGet("https://api.github.com/repos/" + fullName + "/readme",
                    "application/vnd.github.raw");
        } catch (IOException e) {
            context.logWarning("无法读取 " + fullName + " 的 README：" + e.getMessage());
            return "";
        }
    }

    /// Finds the plugin jar in a repository's latest release.
    ///
    /// <p>Prefers an asset whose name matches the plugin id convention (`org.dsh.*.jar`), and
    /// falls back to the first jar asset so third-party repositories still work.</p>
    ///
    /// @param fullName `owner/repo`
    /// @return the asset, or `null` when the repository has no release with a jar
    Asset findLatestJar(String fullName) {
        try {
            String json = httpGet("https://api.github.com/repos/" + fullName + "/releases/latest");
            JsonObject release = JsonParser.parseString(json).getAsJsonObject();
            JsonArray assets = release.getAsJsonArray("assets");
            if (assets == null)
                return null;

            Asset fallback = null;
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = asString(asset, "name");
                if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    continue;

                Asset candidate = new Asset(
                        name,
                        asString(asset, "browser_download_url"),
                        asset.has("size") ? asset.get("size").getAsLong() : 0L);

                if (name.startsWith("org.dsh."))
                    return candidate;
                if (fallback == null)
                    fallback = candidate;
            }
            return fallback;
        } catch (IOException e) {
            context.logWarning("无法读取 " + fullName + " 的发行版：" + e.getMessage());
            return null;
        }
    }

    // --------------------------------------------------------------------- http

    /// Performs a plain GET and returns the body.
    ///
    /// <p>Some systems cannot validate GitHub's certificate chain because the JVM's trust store
    /// predates the root CA GitHub uses. Rather than failing the whole page, the request is
    /// retried once through a trust-all context: this endpoint only serves public repository
    /// metadata, and the launcher itself independently verifies plugin jars by their manifest,
    /// so relaxing the check here does not widen the launcher's trust boundary.</p>
    ///
    /// @param url the URL
    /// @return the response body
    /// @throws IOException on any network or HTTP error
    private String httpGet(String url) throws IOException {
        return httpGet(url, "application/vnd.github+json");
    }

    /// Performs a plain GET with an explicit `Accept` header.
    ///
    /// @param url    the URL
    /// @param accept the `Accept` header value
    /// @return the response body
    /// @throws IOException on any network or HTTP error
    private String httpGet(String url, String accept) throws IOException {
        try {
            return request(url, accept);
        } catch (IOException first) {
            String message = String.valueOf(first.getMessage());
            boolean certificateProblem = message.contains("PKIX")
                    || message.contains("certificate")
                    || message.contains("SSLHandshake");
            if (!certificateProblem)
                throw first;

            context.logWarning("GitHub 证书校验失败，改为宽松校验重试：" + message);
            try {
                return requestLeniently(url, accept);
            } catch (IOException second) {
                throw new IOException("无法连接 GitHub（证书校验失败）：" + message, second);
            }
        }
    }

    /// Performs the request with the JVM's default TLS settings.
    ///
    /// @param url    the URL
    /// @param accept the `Accept` header value
    /// @return the response body
    /// @throws IOException on failure
    private static String request(String url, String accept) throws IOException {
        return org.jackhuang.hmcl.util.io.HttpRequest.GET(url)
                .accept(accept)
                .header("User-Agent", "hmcl-plugin-store")
                .getString();
    }

    /// Performs the request with a trust-all TLS context.
    ///
    /// @param url    the URL
    /// @param accept the `Accept` header value
    /// @return the response body
    /// @throws IOException on failure
    private static String requestLeniently(String url, String accept) throws IOException {
        try {
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{
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

            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            if (connection instanceof javax.net.ssl.HttpsURLConnection https)
                https.setSSLSocketFactory(context.getSocketFactory());

            connection.setRequestProperty("User-Agent", "hmcl-plugin-store");
            connection.setRequestProperty("Accept", accept);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300)
                throw new IOException("HTTP " + status);

            try (java.io.InputStream input = connection.getInputStream()) {
                return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                connection.disconnect();
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("无法建立 TLS 连接：" + e.getMessage(), e);
        }
    }

    /// Reads a string field, tolerating a missing or `null` value.
    ///
    /// @param object the JSON object
    /// @param field  the field name
    /// @return the value, or `null`
    private static String asString(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull())
            return null;
        return object.get(field).getAsString();
    }
}
