package com.oc.ide.bridge;

import android.util.Log;

import com.oc.ide.core.CoreReadyProbe;
import com.oc.ide.core.CoreRuntimeState;
import com.oc.ide.core.CoreServerService;
import com.oc.ide.runtime.RuntimeManager;
import com.oc.ide.runtime.RuntimePaths;
import com.oc.ide.util.AtomicFileWriter;
import com.oc.ide.util.CoreUrl;
import com.oc.ide.util.FileModeUtil;
import com.oc.ide.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Exposes OpenCode server state, hot-reload/restart controls, live core.log reader,
 * and the 1-Tap "Built for Android" MCP Plugin Hub (downloading native ARM64 Rust/Bionic
 * plugins from Yhoanes/antigravity-android-plugins, rewriting sandbox paths to com.oc.ide,
 * running install-plugin.sh, syncing SKILL.md + rules/*.md, registering mcp.servers in
 * ~/.config/opencode/opencode.json, and executing `opencode reload`).
 */
public final class EngineBridge {

    private static final String TAG = "EngineBridge";
    private static final String ANDROID_PLUGINS_REPO_BASE =
            "https://raw.githubusercontent.com/Yhoanes/antigravity-android-plugins/main/plugins/";
    private static final long MAX_ZIP_DOWNLOAD_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_TOTAL_BYTES = 600L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 5000;
    private static final int MAX_HOOK_OUTPUT_BYTES = 32768;

    private final RuntimeManager runtime;

    public EngineBridge(RuntimeManager runtime) {
        this.runtime = runtime;
    }

    public boolean isReady() {
        return CoreRuntimeState.serverUrl != null;
    }

    public int getPort() {
        int live = CoreRuntimeState.httpsPort;
        return live > 0 ? live : runtime.getHttpsPort();
    }

    public String getBaseUrl() {
        return CoreUrl.baseUrl(getPort());
    }

    public String getCsrfToken() {
        String live = CoreRuntimeState.csrfToken;
        return live != null ? live : runtime.getCsrfToken();
    }

    private File getPluginHubDir() {
        File d = new File(runtime.getOcConfigDir(), "plugins-hub");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    private File getHubStateFile() {
        return new File(runtime.getOcConfigDir(), "android-plugins.json");
    }

    private static String resolvePluginArchiveUrl(String safeName, String archiveUrl) {
        if (archiveUrl != null && !archiveUrl.trim().isEmpty()) {
            String trimmed = archiveUrl.trim();
            try {
                URL candidate = new URL(trimmed);
                if (!isAllowedPluginArchiveUrl(candidate)) {
                    throw new SecurityException("Untrusted plugin archive URL: " + trimmed);
                }
                return trimmed;
            } catch (SecurityException se) {
                throw se;
            } catch (Exception e) {
                throw new SecurityException("Invalid plugin archive URL: " + trimmed);
            }
        }
        return ANDROID_PLUGINS_REPO_BASE + safeName + "/" + safeName + ".zip";
    }

    private static boolean isAllowedPluginArchiveUrl(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())) {
            return false;
        }
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String lowerHost = host.trim().toLowerCase(java.util.Locale.ROOT);
        String path = url.getPath() != null ? url.getPath() : "";
        if ("raw.githubusercontent.com".equals(lowerHost)
                || "github.com".equals(lowerHost)
                || "codeload.githubusercontent.com".equals(lowerHost)) {
            return path.startsWith("/Yhoanes/");
        }
        return "objects.githubusercontent.com".equals(lowerHost)
                || "release-assets.githubusercontent.com".equals(lowerHost);
    }

    private static void downloadPluginZipVerified(String initialUrl, File destZip) throws Exception {
        String current = initialUrl;
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(current);
            if (!isAllowedPluginArchiveUrl(u)) {
                throw new SecurityException("Blocked redirect or untrusted host: " + current);
            }
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "OpenCode-Android/" + RuntimeManager.CORE_VERSION);
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        throw new IllegalStateException("Redirect missing Location header");
                    }
                    URL nextUrl = new URL(u, loc);
                    if (!isAllowedPluginArchiveUrl(nextUrl)) {
                        throw new SecurityException("Blocked redirect to untrusted host: " + nextUrl.getHost());
                    }
                    current = nextUrl.toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code + " downloading plugin archive");
                }
                long declaredLen = conn.getContentLengthLong();
                if (declaredLen > MAX_ZIP_DOWNLOAD_BYTES) {
                    throw new IllegalStateException("Plugin archive exceeds 256 MiB safety limit");
                }
                long total = 0L;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(destZip)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_ZIP_DOWNLOAD_BYTES) {
                            throw new IllegalStateException("Plugin archive exceeded 256 MiB limit during download");
                        }
                        out.write(buf, 0, n);
                    }
                }
                return;
            } finally {
                conn.disconnect();
            }
        }
        throw new IllegalStateException("Too many redirects downloading plugin archive");
    }

    /** Returns bundled __oc_plugins_catalog.json directly from filesDir/web for 0-ms UI rendering. */
    public String getPluginsCatalogJson() {
        File catFile = new File(runtime.getWebDir(), "__oc_plugins_catalog.json");
        if (catFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(catFile)) {
                return IoUtil.readAll(fis, "UTF-8");
            } catch (Exception ignored) {
            }
        }
        return "{\"plugins\":[]}";
    }

    /** Returns JSON map of installed Android MCP plugins and their enabled/MCP state. */
    public String listAndroidPlugins() {
        try {
            JSONObject state = readJsonObject(getHubStateFile());
            JSONObject installed = state.optJSONObject("plugins");
            if (installed == null) {
                installed = new JSONObject();
            }
            JSONObject ocCfg = readJsonObject(new File(runtime.getOcConfigDir(), "opencode.json"));
            JSONObject mcpRoot = ocCfg.optJSONObject("mcp");
            JSONObject servers = mcpRoot != null ? mcpRoot.optJSONObject("servers") : null;

            JSONObject result = new JSONObject();
            File[] dirs = getPluginHubDir().listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                    String name = d.getName();
                    JSONObject meta = installed.optJSONObject(name);
                    JSONObject item = new JSONObject();
                    boolean enabled = meta == null || meta.optBoolean("enabled", true);
                    item.put("installed", true);
                    item.put("enabled", enabled);
                    item.put("version", meta != null ? meta.optString("version", "1.0.0") : "1.0.0");
                    JSONArray serverNames = meta != null ? meta.optJSONArray("servers") : null;
                    if (serverNames != null && servers != null && serverNames.length() > 0) {
                        String firstSrv = serverNames.optString(0, "");
                        JSONObject srvObj = servers.optJSONObject(firstSrv);
                        if (srvObj != null) {
                            enabled = !srvObj.optBoolean("disabled", false);
                            item.put("enabled", enabled);
                        }
                    }
                    result.put(name, item);
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String installMarketplacePlugin(String pluginName, String archiveUrl,
                                           String catalogId, String marketplace, String version) {
        JSONObject res = new JSONObject();
        File hubDir = getPluginHubDir();
        String safeName = sanitizeName(pluginName);
        if (safeName.isEmpty() || ".".equals(safeName) || "..".equals(safeName)) {
            try {
                res.put("ok", false);
                res.put("error", "Invalid plugin name");
            } catch (Exception ignored) {
            }
            return res.toString();
        }
        File targetDir = new File(hubDir, safeName);
        File stagingDir = new File(hubDir, ".staging-" + safeName + "-" + System.currentTimeMillis());
        File backupDir = new File(hubDir, ".backup-" + safeName);
        File tmpZip = new File(runtime.appContext().getCacheDir(),
                safeName + "-" + System.currentTimeMillis() + ".ocplugin.zip");
        boolean swappedTarget = false;
        boolean hadPreviousInstall = targetDir.exists();

        try {
            String url = resolvePluginArchiveUrl(safeName, archiveUrl);
            downloadPluginZipVerified(url, tmpZip);

            deleteRecursive(stagingDir);
            if (!stagingDir.mkdirs() && !stagingDir.isDirectory()) {
                throw new IllegalStateException("Cannot create staging directory");
            }

            String canonicalStaging = stagingDir.getCanonicalPath();
            int entryCount = 0;
            long totalExtracted = 0L;
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tmpZip))) {
                ZipEntry entry;
                byte[] buf = new byte[16384];
                while ((entry = zis.getNextEntry()) != null) {
                    entryCount++;
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw new IllegalStateException("Plugin archive exceeds maximum entry limit (" + MAX_ZIP_ENTRIES + ")");
                    }
                    String entryName = entry.getName().replace('\\', '/');
                    while (entryName.startsWith("/")) {
                        entryName = entryName.substring(1);
                    }
                    if (entryName.startsWith(safeName + "/")) {
                        entryName = entryName.substring(safeName.length() + 1);
                    }
                    if (entryName.isEmpty() || entryName.contains("..")) {
                        zis.closeEntry();
                        continue;
                    }
                    File outFile = new File(stagingDir, entryName);
                    if (!outFile.getCanonicalPath().startsWith(canonicalStaging + File.separator)) {
                        throw new SecurityException("Blocked Zip-Slip entry: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.isDirectory()) {
                            parent.mkdirs();
                        }
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) {
                                totalExtracted += n;
                                if (totalExtracted > MAX_EXTRACTED_TOTAL_BYTES) {
                                    throw new IllegalStateException("Extracted plugin exceeds 600 MiB quota");
                                }
                                fos.write(buf, 0, n);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            } finally {
                tmpZip.delete();
            }

            deleteRecursive(backupDir);
            if (hadPreviousInstall) {
                if (!targetDir.renameTo(backupDir)) {
                    copyRecursive(targetDir, backupDir);
                    deleteRecursive(targetDir);
                }
            }
            if (!stagingDir.renameTo(targetDir)) {
                copyRecursive(stagingDir, targetDir);
                deleteRecursive(stagingDir);
            }
            swappedTarget = true;

            // Mirror/symlink under ~/.gemini/config/plugins/<safeName> for script compatibility
            mirrorCompatPluginPath(safeName, targetDir);

            JSONObject hookRes = runPostInstallAndWireOpenCode(
                    safeName, targetDir, version != null && !version.isEmpty() ? version : "1.0.0");

            deleteRecursive(backupDir);

            res.put("ok", true);
            res.put("path", targetDir.getAbsolutePath());
            res.put("hookExit", hookRes.optInt("hookExit", 0));
            res.put("servers", hookRes.optJSONArray("servers"));
            res.put("skills", hookRes.optJSONArray("skills"));
            res.put("reloaded", reloadEngineConfigBool());
            return res.toString();
        } catch (Exception e) {
            tmpZip.delete();
            deleteRecursive(stagingDir);
            if (swappedTarget) {
                deleteRecursive(targetDir);
                if (hadPreviousInstall && backupDir.exists()) {
                    if (!backupDir.renameTo(targetDir)) {
                        try {
                            copyRecursive(backupDir, targetDir);
                        } catch (Exception ignored) {
                        }
                        deleteRecursive(backupDir);
                    }
                    mirrorCompatPluginPath(safeName, targetDir);
                } else {
                    deleteRecursive(new File(new File(runtime.getGeminiDir(), "config/plugins"), safeName));
                }
            }
            try {
                res.put("ok", false);
                res.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
            return res.toString();
        }
    }

    public String postProcessInstalledPlugin(String pluginName) {
        try {
            String safeName = sanitizeName(pluginName);
            File targetDir = new File(getPluginHubDir(), safeName);
            if (!targetDir.isDirectory()) {
                return "{\"ok\":false,\"error\":\"Plugin directory not found\"}";
            }
            JSONObject r = runPostInstallAndWireOpenCode(safeName, targetDir, "1.0.0");
            r.put("ok", true);
            r.put("reloaded", reloadEngineConfigBool());
            return r.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public String toggleAndroidPlugin(String pluginName, boolean enabled) {
        JSONObject res = new JSONObject();
        try {
            String safeName = sanitizeName(pluginName);
            File stateFile = getHubStateFile();
            JSONObject state = readJsonObject(stateFile);
            JSONObject plugins = state.optJSONObject("plugins");
            if (plugins == null || !plugins.has(safeName)) {
                res.put("ok", false);
                res.put("error", "Plugin not installed");
                return res.toString();
            }
            JSONObject pMeta = plugins.getJSONObject(safeName);
            pMeta.put("enabled", enabled);
            AtomicFileWriter.writeText(stateFile, state.toString(2) + "\n");

            // Update mcp.servers.<srv>.disabled in opencode.json
            File ocCfgFile = new File(runtime.getOcConfigDir(), "opencode.json");
            JSONObject ocCfg = readJsonObject(ocCfgFile);
            JSONObject mcpRoot = ocCfg.optJSONObject("mcp");
            JSONObject servers = mcpRoot != null ? mcpRoot.optJSONObject("servers") : null;
            JSONArray srvNames = pMeta.optJSONArray("servers");
            if (servers != null && srvNames != null) {
                for (int i = 0; i < srvNames.length(); i++) {
                    String sName = srvNames.optString(i, "");
                    JSONObject sObj = servers.optJSONObject(sName);
                    if (sObj != null) {
                        sObj.put("disabled", !enabled);
                    }
                }
                AtomicFileWriter.writeText(ocCfgFile, ocCfg.toString(2) + "\n");
            }

            // Toggle rules/*.md and skills/*
            File targetDir = new File(getPluginHubDir(), safeName);
            if (enabled) {
                syncSkillsAndRules(safeName, targetDir);
            } else {
                removeSkillsAndRules(safeName, pMeta);
            }

            res.put("ok", true);
            res.put("enabled", enabled);
            res.put("reloaded", reloadEngineConfigBool());
            return res.toString();
        } catch (Exception e) {
            try {
                res.put("ok", false);
                res.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
            return res.toString();
        }
    }

    public boolean uninstallMarketplacePlugin(String pluginName) {
        try {
            String safeName = sanitizeName(pluginName);
            if (safeName.isEmpty()) return false;

            File stateFile = getHubStateFile();
            JSONObject state = readJsonObject(stateFile);
            JSONObject plugins = state.optJSONObject("plugins");
            JSONObject pMeta = plugins != null ? plugins.optJSONObject(safeName) : null;

            // Remove MCP servers from opencode.json
            File ocCfgFile = new File(runtime.getOcConfigDir(), "opencode.json");
            JSONObject ocCfg = readJsonObject(ocCfgFile);
            JSONObject mcpRoot = ocCfg.optJSONObject("mcp");
            JSONObject servers = mcpRoot != null ? mcpRoot.optJSONObject("servers") : null;
            if (servers != null) {
                if (pMeta != null && pMeta.optJSONArray("servers") != null) {
                    JSONArray arr = pMeta.optJSONArray("servers");
                    for (int i = 0; i < arr.length(); i++) {
                        servers.remove(arr.optString(i, ""));
                    }
                } else {
                    servers.remove(safeName);
                }
                AtomicFileWriter.writeText(ocCfgFile, ocCfg.toString(2) + "\n");
            }

            removeSkillsAndRules(safeName, pMeta);
            deleteRecursive(new File(getPluginHubDir(), safeName));
            deleteRecursive(new File(new File(runtime.getGeminiDir(), "config/plugins"), safeName));

            if (plugins != null && plugins.has(safeName)) {
                plugins.remove(safeName);
                AtomicFileWriter.writeText(stateFile, state.toString(2) + "\n");
            }

            reloadEngineConfigBool();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String reloadEngineConfig() {
        boolean ok = reloadEngineConfigBool();
        return "{\"ok\":" + ok + "}";
    }

    private boolean reloadEngineConfigBool() {
        boolean httpOk = false;
        int port = getPort();
        String csrf = getCsrfToken();
        if (port > 0) {
            httpOk = postLocationReload(port, csrf, null);
            File defaultWs = runtime.getDefaultWorkspaceDir();
            if (defaultWs != null && defaultWs.isDirectory()) {
                postLocationReload(port, csrf, defaultWs.getAbsolutePath());
            }
            if (httpOk) {
                return true;
            }
        }
        try {
            File coreBin = new File(runtime.getBinDir(), RuntimePaths.CORE_NAME);
            if (!coreBin.isFile()) return false;
            ProcessBuilder pb = new ProcessBuilder(coreBin.getAbsolutePath(), "reload");
            pb.directory(runtime.appContext().getFilesDir());
            pb.redirectErrorStream(true);
            runtime.applyEnvironment(pb);
            Process p = pb.start();
            boolean done = p.waitFor(12, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            Log.w(TAG, "opencode reload notice: " + e.getMessage());
            return false;
        }
    }

    private static boolean postLocationReload(int port, String password, String directory) {
        HttpURLConnection conn = null;
        try {
            String endpoint = "http://127.0.0.1:" + port + "/api/location/reload";
            if (directory != null && !directory.isEmpty()) {
                endpoint += "?directory=" + URLEncoder.encode(directory, "UTF-8");
            }
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);
            if (password != null && !password.isEmpty()) {
                conn.setRequestProperty("Authorization", CoreReadyProbe.basicAuthHeader(password));
            }
            conn.setRequestProperty("Accept", "application/json");
            conn.setFixedLengthStreamingMode(0);
            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public boolean restartEngine() {
        return CoreServerService.requestRestart();
    }

    public String getEngineLogs(int maxLines) {
        int limit = (maxLines <= 0 || maxLines > 500) ? 120 : maxLines;
        File logFile = new File(runtime.appContext().getFilesDir(), "logs/core.log");
        if (!logFile.isFile()) {
            return "(core.log not created yet)";
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (lines.size() > limit) {
                    lines.remove(0);
                }
            }
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private JSONObject runPostInstallAndWireOpenCode(String safeName, File targetDir, String version)
            throws Exception {
        File filesDir = runtime.appContext().getFilesDir();
        String homePath = filesDir.getAbsolutePath();
        normalizeExtractedTree(targetDir, false, homePath);

        int hookExit = 0;
        File installHook = new File(new File(targetDir, "scripts"), "install-plugin.sh");
        if (!installHook.isFile()) {
            installHook = new File(targetDir, "install-plugin.sh");
        }
        if (installHook.isFile()) {
            File prefixDir = new File(filesDir, "usr");
            File prefixBin = new File(prefixDir, "bin");
            File runtimeBin = runtime.getBinDir();
            File bashBin = new File(prefixBin, "bash");
            String shell = bashBin.isFile() ? bashBin.getAbsolutePath() : "/system/bin/sh";

            ProcessBuilder pb = new ProcessBuilder(shell, installHook.getAbsolutePath());
            pb.directory(targetDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("HOME", homePath);
            env.put("PREFIX", prefixDir.getAbsolutePath());
            env.put("TMPDIR", new File(prefixDir, "tmp").getAbsolutePath());
            env.put("OPENCODE_CONFIG_DIR", runtime.getOcConfigDir().getAbsolutePath());
            env.put("ANTIGRAVITY_APP_DATA_DIR", runtime.getOcDataDir().getAbsolutePath());
            env.put("PLUGIN_ROOT", targetDir.getAbsolutePath());
            env.put("PATH", prefixBin.getAbsolutePath() + ":" + runtimeBin.getAbsolutePath()
                    + ":" + homePath + "/.local/bin:/system/bin");
            env.put("LD_LIBRARY_PATH", new File(prefixDir, "lib").getAbsolutePath());
            File certFile = new File(prefixDir, "etc/tls/cert.pem");
            if (!certFile.isFile()) {
                certFile = runtime.getCertFile();
            }
            if (certFile.isFile()) {
                env.put("SSL_CERT_FILE", certFile.getAbsolutePath());
                env.put("CURL_CA_BUNDLE", certFile.getAbsolutePath());
            }
            Process p = pb.start();
            try {
                p.getOutputStream().close();
            } catch (Exception ignored) {
            }
            final InputStream pis = p.getInputStream();
            final java.io.ByteArrayOutputStream tailBuf = new java.io.ByteArrayOutputStream(4096);
            Thread drainer = new Thread(() -> {
                byte[] chunk = new byte[4096];
                try {
                    int n;
                    while ((n = pis.read(chunk)) != -1) {
                        synchronized (tailBuf) {
                            tailBuf.write(chunk, 0, n);
                            if (tailBuf.size() > MAX_HOOK_OUTPUT_BYTES * 2) {
                                byte[] cur = tailBuf.toByteArray();
                                tailBuf.reset();
                                tailBuf.write(cur, cur.length - MAX_HOOK_OUTPUT_BYTES, MAX_HOOK_OUTPUT_BYTES);
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        pis.close();
                    } catch (Exception ignored) {
                    }
                }
            }, "OcPluginHookDrain");
            drainer.setDaemon(true);
            drainer.start();

            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (finished) {
                hookExit = p.exitValue();
                drainer.join(2000);
            } else {
                p.destroyForcibly();
                try {
                    pis.close();
                } catch (Exception ignored) {
                }
                drainer.join(1500);
                throw new IllegalStateException("Plugin post-install timed out after 120s");
            }
            if (hookExit != 0) {
                String tail;
                synchronized (tailBuf) {
                    byte[] rawTail = tailBuf.toByteArray();
                    int start = Math.max(0, rawTail.length - 512);
                    tail = new String(rawTail, start, rawTail.length - start, StandardCharsets.UTF_8).trim();
                }
                throw new IllegalStateException("Plugin post-install failed (exit " + hookExit + ")"
                        + (tail.isEmpty() ? "" : ": " + tail));
            }
            normalizeExtractedTree(targetDir, false, homePath);
        }

        // Sync skills + rules into ~/.config/opencode/{skills,rules}
        JSONArray syncedSkills = syncSkillsAndRules(safeName, targetDir);

        // Convert mcp_config.json / .mcp.json -> ~/.config/opencode/opencode.json (mcp.servers)
        JSONArray registeredServers = wireMcpConfigIntoOpenCode(safeName, targetDir, homePath);

        // Persist plugin metadata in android-plugins.json
        File stateFile = getHubStateFile();
        JSONObject state = readJsonObject(stateFile);
        JSONObject plugins = state.optJSONObject("plugins");
        if (plugins == null) {
            plugins = new JSONObject();
            state.put("plugins", plugins);
        }
        JSONObject pMeta = new JSONObject();
        pMeta.put("enabled", true);
        pMeta.put("version", version);
        pMeta.put("servers", registeredServers);
        pMeta.put("skills", syncedSkills);
        plugins.put(safeName, pMeta);
        AtomicFileWriter.writeText(stateFile, state.toString(2) + "\n");

        JSONObject out = new JSONObject();
        out.put("hookExit", hookExit);
        out.put("servers", registeredServers);
        out.put("skills", syncedSkills);
        return out;
    }

    private JSONArray syncSkillsAndRules(String safeName, File targetDir) {
        JSONArray syncedSkills = new JSONArray();
        try {
            File ocSkillsRoot = new File(runtime.getOcConfigDir(), "skills");
            ocSkillsRoot.mkdirs();
            File pluginSkills = new File(targetDir, "skills");
            if (pluginSkills.isDirectory()) {
                File[] children = pluginSkills.listFiles();
                if (children != null) {
                    for (File skillDir : children) {
                        if (skillDir.isDirectory() && new File(skillDir, "SKILL.md").isFile()) {
                            File dst = new File(ocSkillsRoot, skillDir.getName());
                            deleteRecursive(dst);
                            copyRecursive(skillDir, dst);
                            syncedSkills.put(skillDir.getName());
                        }
                    }
                }
            }
            File ocRulesRoot = new File(runtime.getOcConfigDir(), "rules");
            ocRulesRoot.mkdirs();
            File pluginRules = new File(targetDir, "rules");
            if (pluginRules.isDirectory()) {
                File[] ruleFiles = pluginRules.listFiles();
                if (ruleFiles != null) {
                    for (File rf : ruleFiles) {
                        if (rf.isFile() && rf.getName().endsWith(".md")) {
                            File dst = new File(ocRulesRoot, safeName + "-" + rf.getName());
                            copyFile(rf, dst);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "syncSkillsAndRules warning: " + e.getMessage());
        }
        return syncedSkills;
    }

    private void removeSkillsAndRules(String safeName, JSONObject pMeta) {
        try {
            File ocSkillsRoot = new File(runtime.getOcConfigDir(), "skills");
            if (pMeta != null && pMeta.optJSONArray("skills") != null) {
                JSONArray skills = pMeta.optJSONArray("skills");
                for (int i = 0; i < skills.length(); i++) {
                    String sk = skills.optString(i, "");
                    if (!sk.isEmpty()) {
                        deleteRecursive(new File(ocSkillsRoot, sk));
                    }
                }
            }
            File ocRulesRoot = new File(runtime.getOcConfigDir(), "rules");
            File[] rfs = ocRulesRoot.listFiles();
            if (rfs != null) {
                for (File rf : rfs) {
                    if (rf.getName().startsWith(safeName + "-")) {
                        rf.delete();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private JSONArray wireMcpConfigIntoOpenCode(String safeName, File targetDir, String homePath) {
        JSONArray names = new JSONArray();
        try {
            File ocCfgFile = new File(runtime.getOcConfigDir(), "opencode.json");
            JSONObject ocCfg = readJsonObject(ocCfgFile);
            if (!ocCfg.has("$schema")) {
                ocCfg.put("$schema", "https://opencode.ai/config.json");
            }
            ensureSkillsPathInConfig(ocCfg, new File(runtime.getOcConfigDir(), "skills").getAbsolutePath());

            File mcpFile = new File(targetDir, "mcp_config.json");
            if (!mcpFile.isFile()) {
                mcpFile = new File(targetDir, ".mcp.json");
            }
            if (!mcpFile.isFile()) {
                mcpFile = new File(targetDir, "mcp.json");
            }
            if (!mcpFile.isFile()) {
                AtomicFileWriter.writeText(ocCfgFile, ocCfg.toString(2) + "\n");
                return names;
            }
            JSONObject src = readJsonObject(mcpFile);
            JSONObject mcpServers = src.optJSONObject("mcpServers");
            if (mcpServers == null) {
                mcpServers = src.optJSONObject("servers");
            }
            if (mcpServers == null) {
                AtomicFileWriter.writeText(ocCfgFile, ocCfg.toString(2) + "\n");
                return names;
            }

            JSONObject mcpRoot = ocCfg.optJSONObject("mcp");
            if (mcpRoot == null) {
                mcpRoot = new JSONObject();
                ocCfg.put("mcp", mcpRoot);
            }
            JSONObject dstServers = mcpRoot.optJSONObject("servers");
            if (dstServers == null) {
                dstServers = new JSONObject();
                mcpRoot.put("servers", dstServers);
            }

            String pluginRoot = targetDir.getAbsolutePath();
            String prefixPath = new File(homePath, "usr").getAbsolutePath();
            Iterator<String> keys = mcpServers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entry = mcpServers.optJSONObject(key);
                if (entry == null) continue;

                String serverName = key.equals(safeName) ? safeName : key;
                JSONObject ocEntry = new JSONObject();

                String remoteUrl = entry.optString("url", entry.optString("serverUrl", ""));
                if (!remoteUrl.isEmpty()) {
                    ocEntry.put("type", "remote");
                    ocEntry.put("url", expandVars(remoteUrl, pluginRoot, homePath, prefixPath));
                    JSONObject hdrs = entry.optJSONObject("headers");
                    if (hdrs != null) {
                        ocEntry.put("headers", hdrs);
                    }
                } else {
                    ocEntry.put("type", "local");
                    JSONArray cmdArr = new JSONArray();
                    String cmd = entry.optString("command", "");
                    if (!cmd.isEmpty()) {
                        cmdArr.put(expandVars(cmd, pluginRoot, homePath, prefixPath));
                    }
                    JSONArray args = entry.optJSONArray("args");
                    if (args != null) {
                        for (int i = 0; i < args.length(); i++) {
                            cmdArr.put(expandVars(args.optString(i, ""), pluginRoot, homePath, prefixPath));
                        }
                    }
                    if (cmdArr.length() == 0) continue;
                    ocEntry.put("command", cmdArr);

                    JSONObject envIn = entry.optJSONObject("env");
                    JSONObject envOut = new JSONObject();
                    envOut.put("HOME", homePath);
                    envOut.put("PREFIX", prefixPath);
                    envOut.put("TMPDIR", prefixPath + "/tmp");
                    envOut.put("LD_LIBRARY_PATH", prefixPath + "/lib");
                    envOut.put("PATH", prefixPath + "/bin:" + homePath + "/runtime/bin:" + homePath + "/.local/bin:/system/bin");
                    if (envIn != null) {
                        Iterator<String> ek = envIn.keys();
                        while (ek.hasNext()) {
                            String k = ek.next();
                            envOut.put(k, expandVars(envIn.optString(k, ""), pluginRoot, homePath, prefixPath));
                        }
                    }
                    ocEntry.put("environment", envOut);
                }
                ocEntry.put("disabled", false);
                dstServers.put(serverName, ocEntry);
                names.put(serverName);
            }
            AtomicFileWriter.writeText(ocCfgFile, ocCfg.toString(2) + "\n");
        } catch (Exception e) {
            Log.w(TAG, "wireMcpConfigIntoOpenCode warning: " + e.getMessage());
        }
        return names;
    }

    private static void ensureSkillsPathInConfig(JSONObject ocCfg, String skillsDirPath) {
        try {
            JSONObject skillsObj = ocCfg.optJSONObject("skills");
            if (skillsObj == null) {
                JSONArray legacy = ocCfg.optJSONArray("skills");
                skillsObj = new JSONObject();
                skillsObj.put("paths", legacy != null ? legacy : new JSONArray());
                ocCfg.put("skills", skillsObj);
            }
            JSONArray paths = skillsObj.optJSONArray("paths");
            if (paths == null) {
                paths = new JSONArray();
                skillsObj.put("paths", paths);
            }
            for (int i = 0; i < paths.length(); i++) {
                if (skillsDirPath.equals(paths.optString(i))) {
                    return;
                }
            }
            paths.put(skillsDirPath);
        } catch (Exception ignored) {
        }
    }

    private static String expandVars(String val, String pluginRoot, String homePath, String prefixPath) {
        if (val == null) return "";
        return val.replace("${CLAUDE_PLUGIN_ROOT}", pluginRoot)
                .replace("$CLAUDE_PLUGIN_ROOT", pluginRoot)
                .replace("${PLUGIN_ROOT}", pluginRoot)
                .replace("$PLUGIN_ROOT", pluginRoot)
                .replace("/data/user/0/com.agy/files", homePath)
                .replace("/data/data/com.agy/files", homePath)
                .replace("${PREFIX}", prefixPath)
                .replace("${HOME}", homePath);
    }

    private void mirrorCompatPluginPath(String safeName, File targetDir) {
        try {
            File compatParent = new File(runtime.getGeminiDir(), "config/plugins");
            compatParent.mkdirs();
            File compatLink = new File(compatParent, safeName);
            deleteRecursive(compatLink);
            try {
                android.system.Os.symlink(targetDir.getAbsolutePath(), compatLink.getAbsolutePath());
            } catch (Throwable t) {
                copyRecursive(targetDir, compatLink);
            }
        } catch (Exception ignored) {
        }
    }

    private static void normalizeExtractedTree(File dir, boolean inExecDir, String homePath) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String name = f.getName();
            if (f.isDirectory()) {
                boolean childExec = inExecDir || "bin".equals(name) || "scripts".equals(name);
                normalizeExtractedTree(f, childExec, homePath);
                continue;
            }
            if (f.length() > 0 && f.length() < 1024 * 1024) {
                try {
                    byte[] raw = new byte[(int) f.length()];
                    try (FileInputStream fis = new FileInputStream(f)) {
                        int off = 0;
                        while (off < raw.length) {
                            int r = fis.read(raw, off, raw.length - off);
                            if (r <= 0) break;
                            off += r;
                        }
                    }
                    if (!(raw.length >= 4 && raw[0] == 0x7F && raw[1] == 'E' && raw[2] == 'L' && raw[3] == 'F')) {
                        String txt = new String(raw, StandardCharsets.UTF_8);
                        if (txt.indexOf('\r') >= 0 || txt.contains("com.agy")) {
                            String clean = txt.replace("\r\n", "\n")
                                    .replace("/data/user/0/com.agy/files/.gemini/config/plugins/",
                                            homePath + "/.config/opencode/plugins-hub/")
                                    .replace("/data/user/0/com.agy/files", homePath)
                                    .replace("/data/data/com.agy/files", homePath);
                            AtomicFileWriter.writeText(f, clean);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (inExecDir || name.endsWith(".sh")) {
                FileModeUtil.setExec(f);
            }
        }
    }

    private static String sanitizeName(String s) {
        return s == null ? "" : s.trim().replaceAll("[^a-zA-Z0-9._-]", "");
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyRecursive(c, new File(dst, c.getName()));
                }
            }
        } else if (src.isFile()) {
            copyFile(src, dst);
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        try {
            if (android.system.Os.lstat(f.getAbsolutePath()).st_mode != 0
                    && android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getAbsolutePath()).st_mode)) {
                f.delete();
                return;
            }
        } catch (Throwable ignored) {
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static JSONObject readJsonObject(File f) {
        if (f != null && f.isFile()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                String txt = IoUtil.readAll(fis, "UTF-8").trim();
                if (!txt.isEmpty()) {
                    return new JSONObject(txt);
                }
            } catch (Exception ignored) {
            }
        }
        return new JSONObject();
    }
}
