/*
 * PCL 商店插件 —— 待安装更新
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.pluginstore;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/// Records plugin updates that could not be applied because the jar was still loaded.
///
/// <p>A running plugin jar is locked by its class loader: Windows refuses both deletion and
/// renaming, and closing the loader is not something a plugin can do to itself. Updates are
/// therefore staged — the new jar is kept next to the pending list, and the swap happens at the
/// next launcher start, before the plugin is loaded.</p>
///
/// <p>The swap is performed by this same plugin, which runs early enough in its own `onEnable`
/// for every *other* plugin: by then the launcher has discarded the previous class loaders, so
/// those jars are unlocked again.</p>
final class PendingUpdates {

    /// File listing the staged swaps.
    private static final String INDEX = "pending-updates.json";

    /// Directory holding the staged jars.
    private static final String STAGING = "pending";

    private final PluginContext context;

    PendingUpdates(PluginContext context) {
        this.context = context;
    }

    /// One staged swap.
    ///
    /// @param pluginId  the plugin id the jar belongs to
    /// @param staged    the downloaded jar waiting to be installed
    /// @param fileName  the file name to install it under
    public record Pending(String pluginId, String staged, String fileName) {
    }

    /// Returns the staged swaps, skipping entries whose file vanished.
    ///
    /// @return the pending swaps
    List<Pending> list() {
        List<Pending> pending = new ArrayList<>();
        Path index = context.getDataFile(INDEX);
        if (!Files.isRegularFile(index))
            return pending;

        try {
            String json = Files.readString(index, StandardCharsets.UTF_8);
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (var element : array) {
                JsonObject object = element.getAsJsonObject();
                String id = text(object, "pluginId");
                String staged = text(object, "staged");
                String fileName = text(object, "fileName");
                if (id == null || staged == null || fileName == null)
                    continue;
                if (!Files.isRegularFile(context.getDataFile(staged)))
                    continue;
                pending.add(new Pending(id, staged, fileName));
            }
        } catch (IOException | RuntimeException e) {
            context.logWarning("无法读取待安装更新：" + e.getMessage());
        }
        return pending;
    }

    /// Stages a jar for installation on the next launcher start.
    ///
    /// <p>The jar is copied into the plugin data directory first: the download directory is
    /// temporary and may be cleaned by the launcher.</p>
    ///
    /// @param pluginId the plugin id
    /// @param jar      the downloaded jar
    /// @param fileName the file name to install it under
    /// @return whether staging succeeded
    public boolean stage(String pluginId, Path jar, String fileName) {
        try {
            Path staging = context.getDataDirectory().resolve(STAGING);
            Files.createDirectories(staging);
            Path target = staging.resolve(fileName);
            Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);

            List<Pending> existing = list();
            existing.removeIf(item -> item.pluginId().equals(pluginId));

            JsonArray array = new JsonArray();
            for (Pending item : existing)
                array.add(toJson(item));
            array.add(toJson(new Pending(pluginId,
                    context.getDataDirectory().relativize(target).toString(), fileName)));

            Files.writeString(context.getDataFile(INDEX), array.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            context.logWarning("无法暂存更新：" + e.getMessage());
            return false;
        }
    }

    /// Writes a helper script that performs the swap after the launcher exits.
    ///
    /// <p>Replacing a jar from inside the launcher does not work: the plugin's own class loader
    /// keeps it open, and Windows refuses to delete a file that is still mapped — the in-process
    /// attempt logged "仍被占用" every time. A short script waits for this process to end, copies
    /// the staged jars into place, then deletes itself.</p>
    ///
    /// @return the script path, or `null` when nothing is pending
    public Path writeApplyScript() {
        List<Pending> pending = list();
        if (pending.isEmpty())
            return null;

        Path directory = org.jackhuang.hmcl.plugin.PluginManager.getPluginsDirectory();
        if (directory == null)
            return null;

        // The script has to survive the launcher: it waits for this process to end (by watching for
        // the pid to disappear), retries the copy until the jar is no longer locked, and only then
        // starts the launcher again.
        StringBuilder script = new StringBuilder();
        script.append("@echo off\r\n");
        script.append("chcp 65001 >nul\r\n");
        script.append("setlocal enabledelayedexpansion\r\n");
        script.append("set PID=").append(ProcessHandle.current().pid()).append("\r\n");

        script.append(":waitloop\r\n");
        script.append("tasklist /fi \"PID eq %PID%\" 2>nul | find \"%PID%\" >nul\r\n");
        script.append("if not errorlevel 1 (\r\n");
        script.append("  timeout /t 1 /nobreak >nul\r\n");
        script.append("  goto waitloop\r\n");
        script.append(")\r\n");

        // Give the OS a moment to release the file handles after the process is gone.
        script.append("timeout /t 2 /nobreak >nul\r\n");

        for (Pending item : pending) {
            Path staged = context.getDataFile(item.staged());
            if (!Files.isRegularFile(staged))
                continue;

            int tag = Math.abs(item.pluginId().hashCode());

            // Remove every jar of this plugin first, whatever its file name. Copying only the new
            // name left the previous jar in place, so the launcher loaded two versions of the same
            // plugin and the update appeared to need several restarts to settle.
            for (Path existing : jarsOf(item.pluginId())) {
                script.append("del /f /q \"").append(existing.toAbsolutePath())
                        .append("\" >nul 2>&1\r\n");
            }

            Path target = directory.resolve(item.fileName());
            script.append(":copy_").append(tag).append("\r\n");
            script.append("copy /y \"").append(staged.toAbsolutePath()).append("\" \"")
                    .append(target.toAbsolutePath()).append("\" >nul 2>&1\r\n");
            script.append("if errorlevel 1 (\r\n");
            script.append("  timeout /t 1 /nobreak >nul\r\n");
            script.append("  goto :copy_").append(tag).append("\r\n");
            script.append(")\r\n");
        }


        script.append("del /f /q \"").append(context.getDataFile(INDEX).toAbsolutePath())
                .append("\" >nul 2>&1\r\n");
        script.append("rmdir /s /q \"")
                .append(context.getDataDirectory().resolve(STAGING).toAbsolutePath())
                .append("\" >nul 2>&1\r\n");

        // Start the launcher again only after the swap succeeded.
        script.append("start \"\" \"").append(launcherJar()).append("\"\r\n");
        script.append("del /f /q \"%~f0\"\r\n");

        try {
            Path scriptFile = context.getDataFile("apply-updates.cmd");
            Files.writeString(scriptFile, script.toString(), StandardCharsets.UTF_8);
            return scriptFile;
        } catch (IOException e) {
            context.logWarning("无法生成更新脚本：" + e.getMessage());
            return null;
        }
    }

    /// Returns the running launcher jar, used to start it again after the swap.
    ///
    /// @return the jar path, or a best-effort command when it cannot be determined
    /// Lists the installed jars belonging to one plugin id.
    ///
    /// @param pluginId the plugin id
    /// @return the jars, possibly empty
    private static List<Path> jarsOf(String pluginId) {
        List<Path> jars = new ArrayList<>();
        Path directory = org.jackhuang.hmcl.plugin.PluginManager.getPluginsDirectory();
        if (directory == null || !Files.isDirectory(directory))
            return jars;

        try (var stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : stream) {
                try {
                    String id = org.jackhuang.hmcl.plugin.PluginDescription
                            .readFromJar(jar).getId();
                    if (pluginId.equals(id))
                        jars.add(jar);
                } catch (Throwable ignored) {
                    // A jar without a manifest is not a plugin; leave it alone.
                }
            }
        } catch (IOException ignored) {
            // Nothing to report.
        }
        return jars;
    }

    private static String launcherJar() {
        try {
            return org.jackhuang.hmcl.util.io.JarUtils.thisJarPath().toAbsolutePath().toString();
        } catch (Throwable e) {
            return "HMCL.jar";
        }
    }

    public int applyAll() {
        List<Pending> pending = list();
        if (pending.isEmpty())
            return 0;

        List<Pending> remaining = new ArrayList<>();
        int applied = 0;

        for (Pending item : pending) {
            if (apply(item)) {
                applied++;
                try {
                    Files.deleteIfExists(context.getDataFile(item.staged()));
                } catch (IOException ignored) {
                    // A leftover staged jar is harmless.
                }
            } else {
                remaining.add(item);
            }
        }

        writeIndex(remaining);
        return applied;
    }

    /// Applies one staged swap.
    ///
    /// @param item the swap
    /// @return whether it succeeded
    private boolean apply(Pending item) {
        Path staged = context.getDataFile(item.staged());
        Path directory = org.jackhuang.hmcl.plugin.PluginManager.getPluginsDirectory();
        if (directory == null)
            return false;

        try {
            // Remove the old jar of the same id first: the launcher rejects duplicate ids.
            try (var stream = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path existing : stream) {
                    String id;
                    try {
                        id = org.jackhuang.hmcl.plugin.PluginDescription.readFromJar(existing).getId();
                    } catch (Throwable e) {
                        continue;
                    }
                    if (item.pluginId().equals(id))
                        Files.deleteIfExists(existing);
                }
            }

            Files.copy(staged, directory.resolve(item.fileName()), StandardCopyOption.REPLACE_EXISTING);
            context.logInfo("已应用待安装更新：" + item.fileName());
            return true;
        } catch (IOException e) {
            context.logInfo("更新 " + item.fileName() + " 仍被占用，下次启动再试");
            return false;
        }
    }

    /// Rewrites the pending index.
    ///
    /// @param items the remaining swaps
    private void writeIndex(List<Pending> items) {
        try {
            if (items.isEmpty()) {
                Files.deleteIfExists(context.getDataFile(INDEX));
                return;
            }
            JsonArray array = new JsonArray();
            for (Pending item : items)
                array.add(toJson(item));
            Files.writeString(context.getDataFile(INDEX), array.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            context.logWarning("无法保存待安装更新：" + e.getMessage());
        }
    }

    /// Serialises one pending swap.
    ///
    /// @param item the swap
    /// @return the JSON object
    private static JsonObject toJson(Pending item) {
        JsonObject object = new JsonObject();
        object.addProperty("pluginId", item.pluginId());
        object.addProperty("staged", item.staged());
        object.addProperty("fileName", item.fileName());
        return object;
    }

    /// Reads a string field, tolerating a missing value.
    ///
    /// @param object the JSON object
    /// @param field  the field name
    /// @return the value, or `null`
    private static String text(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull())
            return null;
        return object.get(field).getAsString();
    }
}