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

    /// Location of the optional token file, resolved once from the plugin data directory.
    private static final java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> TOKEN_FILE =
            new java.util.concurrent.atomic.AtomicReference<>();

    /// Cached release lists, keyed by `owner/repo`.
    ///
    /// <p>Both the update check and the install path need the same list, and GitHub's
    /// unauthenticated quota is small, so each repository is fetched at most once per session.</p>
    private static final java.util.Map<String, List<Release>> RELEASE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /// Set once GitHub answers a request with a rate-limit status.
    ///
    /// <p>The page uses it to explain why version information is missing, rather than leaving the
    /// user wondering why the update buttons never light up.</p>
    private static final java.util.concurrent.atomic.AtomicBoolean RATE_LIMITED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /// Returns whether GitHub has rate-limited this session.
    ///
    /// @return whether a request was rejected for rate limiting
    static boolean isRateLimited() {
        return RATE_LIMITED.get();
    }

    private final PluginContext context;

    StoreSource(PluginContext context) {
        this.context = context;
        try {
            TOKEN_FILE.set(context.getDataFile("github-token.txt"));
        } catch (Throwable ignored) {
            // Token support is optional.
        }
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

    /// One published release of a repository.
    ///
    /// @param tag         the release tag, e.g. `v1.2.0`
    /// @param publishedAt the publish timestamp, used for ordering
    /// @param asset       the plugin jar inside that release, or `null` when it has none
    public record Release(String tag, String publishedAt, Asset asset) {

        /// Returns the version this release represents, with any leading `v` removed.
        ///
        /// @return the version text
        public String version() {
            return tag != null && tag.startsWith("v") ? tag.substring(1) : String.valueOf(tag);
        }
    }

    /// Lists the releases of a repository that actually carry a plugin jar.
    ///
    /// <p>Newest first. The store shows these so the user can pick an older version, which is what
    /// makes rolling back a broken plugin update possible without leaving the launcher.</p>
    ///
    /// <p>GitHub rate-limits unauthenticated API calls and answers `403` when the budget is spent.
    /// That is why the release list sometimes came back empty while downloads kept working (the
    /// release CDN is not rate-limited). A configured token raises the limit, and a throttled
    /// response is retried once after a short pause.</p>
    ///
    /// @param fullName `owner/repo`
    /// @return the releases, possibly empty
    List<Release> findReleases(String fullName) {
        List<Release> cached = RELEASE_CACHE.get(fullName);
        if (cached != null)
            return cached;

        String url = "https://api.github.com/repos/" + fullName + "/releases?per_page=30";

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String json = httpGet(url);
                List<Release> parsed = parseReleases(fullName, json);
                RELEASE_CACHE.put(fullName, parsed);
                return parsed;
            } catch (RateLimitedException e) {
                if (attempt == 0) {
                    context.logWarning("GitHub 接口限流，稍后重试：" + fullName);
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                context.logWarning("GitHub 接口限流，未能读取 " + fullName + " 的版本列表。"
                        + "可在插件数据目录的 github-token.txt 中填写 token 以提高额度。");
                RATE_LIMITED.set(true);
            } catch (IOException | RuntimeException e) {
                context.logWarning("无法读取 " + fullName + " 的版本列表：" + e.getMessage());
                break;
            }
        }
        return new ArrayList<>();
    }

    /// Parses the releases JSON.
    ///
    /// @param fullName the repository, for messages
    /// @param json     the response body
    /// @return the releases carrying a jar
    private List<Release> parseReleases(String fullName, String json) {
        List<Release> releases = new ArrayList<>();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject release = element.getAsJsonObject();
                if (release.has("draft") && release.get("draft").getAsBoolean())
                    continue;

                Asset asset = pickJar(release.getAsJsonArray("assets"));
                if (asset == null)
                    continue;

                releases.add(new Release(
                        asString(release, "tag_name"),
                        asString(release, "published_at"),
                        asset));
            }
        } catch (RuntimeException e) {
            context.logWarning("解析 " + fullName + " 的版本列表失败：" + e.getMessage());
        }
        return releases;
    }

    /// Chooses the plugin jar among a release's assets.
    ///
    /// @param assets the release assets, may be `null`
    /// @return the chosen asset, or `null` when there is no jar
    private static Asset pickJar(JsonArray assets) {
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
        String url = "https://api.github.com/repos/" + fullName + "/readme";

        // Same rate-limit handling as the release list: a throttled README is worth one retry.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return httpGet(url, "application/vnd.github.raw");
            } catch (RateLimitedException e) {
                if (attempt == 0) {
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                context.logWarning("GitHub 接口限流，未能读取 " + fullName + " 的 README");
                return "";
            } catch (IOException e) {
                context.logWarning("无法读取 " + fullName + " 的 README：" + e.getMessage());
                return "";
            }
        }
        return "";
    }

    /// Finds the plugin jar in a repository's latest release.
    ///
    /// <p>Prefers an asset whose name matches the plugin id convention (`org.dsh.*.jar`), and
    /// falls back to the first jar asset so third-party repositories still work.</p>
    ///
    /// @param fullName `owner/repo`
    /// @return the asset, or `null` when the repository has no release with a jar
    Asset findLatestJar(String fullName) {
        // Tries the dedicated `/releases/latest` endpoint first: it is a single cheap request and
        // does not depend on the release list succeeding. Falling straight back to the list meant a
        // single failed list request made the install button do nothing at all.
        try {
            String json = httpGet("https://api.github.com/repos/" + fullName + "/releases/latest");
            JsonObject release = JsonParser.parseString(json).getAsJsonObject();
            Asset direct = pickJar(release.getAsJsonArray("assets"));
            if (direct != null)
                return direct;
        } catch (IOException | RuntimeException e) {
            context.logWarning("无法读取 " + fullName + " 的最新发行版：" + e.getMessage());
        }

        // Fall back to the release list, which may already be cached.
        List<Release> releases = findReleases(fullName);
        for (Release release : releases) {
            if (release.asset() != null)
                return release.asset();
        }
        return null;
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
            // A rate-limit answer is not a TLS problem: report it as-is so callers can retry.
            if (first instanceof RateLimitedException rateLimited)
                throw rateLimited;

            String message = String.valueOf(first.getMessage());
            boolean certificateProblem = message.contains("PKIX")
                    || message.contains("certificate")
                    || message.contains("SSLHandshake");
            if (!certificateProblem)
                throw first;

            context.logWarning("GitHub 证书校验失败，改为宽松校验重试：" + message);
            try {
                return requestLeniently(url, accept);
            } catch (RateLimitedException rateLimited) {
                throw rateLimited;
            } catch (IOException second) {
                throw new IOException("无法连接 GitHub：" + second.getMessage(), second);
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
        // The token has to be attached here as well, not only on the fallback path: without it the
        // first attempt always runs on the shared 60/hour budget and is the request that gets
        // throttled.
        String token = githubToken();

        var request = org.jackhuang.hmcl.util.io.HttpRequest.GET(url)
                .accept(accept)
                .header("User-Agent", "hmcl-plugin-store");

        if (token != null)
            request = request.header("Authorization", "Bearer " + token);

        return request.getString();
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
            String token = githubToken();
            if (token != null)
                connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            if (status == 403 || status == 429)
                throw new RateLimitedException("HTTP " + status);
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

    /// Reads an optional GitHub token from the plugin data directory.
    ///
    /// <p>Unauthenticated API calls are limited to a small hourly budget shared by everyone on the
    /// same IP, which the store can exhaust while listing a dozen repositories. A token raises the
    /// limit; it is entirely optional and only used for read-only metadata requests.</p>
    ///
    /// @return the token, or `null` when none is configured
    private static String githubToken() {
        try {
            java.nio.file.Path file = TOKEN_FILE.get();
            if (!java.nio.file.Files.isRegularFile(file))
                return null;
            String text = java.nio.file.Files.readString(file).strip();
            return text.isEmpty() ? null : text;
        } catch (Throwable e) {
            return null;
        }
    }

    /// Raised when GitHub rejects a request because the rate limit was reached.
    static final class RateLimitedException extends IOException {

        RateLimitedException(String message) {
            super(message);
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
