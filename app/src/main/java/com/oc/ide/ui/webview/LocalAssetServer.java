package com.oc.ide.ui.webview;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.webkit.WebResourceResponse;

import com.oc.ide.core.CoreReadyProbe;
import com.oc.ide.core.CoreRuntimeState;
import com.oc.ide.util.IoUtil;
import com.oc.ide.util.LoopbackHost;
import com.oc.ide.util.MimeTypes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid loopback asset & API resilience layer for OpenCode v2 on Android:
 *  1. Neutralizes Workbox PWA ServiceWorker so fresh native boot scripts always run.
 *  2. Serves Android helper assets (`/__oc_android_boot.js`, `/__oc_plugins_catalog.json`)
 *     directly from `filesDir/web/`.
 *  3. Synthesizes virtual directory listings on `GET /api/fs/list` ONLY for Android SELinux
 *     unreadable container paths (`/`, `/storage`, `/storage/emulated`, `/data`, `/data/user`,
 *     `/data/user/0`) so OpenCode's DirectoryPickerDialog never triggers HTTP 500 EACCES when
 *     navigating to `/storage/emulated/0` or `$HOME`.
 *  4. Reports accurate `persistentPty: false` on `GET /api/info` when `opencode-pty` is absent.
 *  5. Injects `<script src="/__oc_android_boot.js"></script>` into main-frame SPA HTML.
 */
public final class LocalAssetServer {

    private static final String TAG = "WebViewHost";
    private static volatile String hostThemeHint = "system";

    public static void setHostThemeHint(String mode) {
        if (mode != null && !mode.isEmpty()) {
            hostThemeHint = mode;
        }
    }

    public WebResourceResponse intercept(Context context, Uri uri, String method,
                                         int enginePort, boolean isForMainFrame,
                                         String bridgeAuthToken, String rawBindingName) {
        if (uri == null || !"GET".equalsIgnoreCase(method)) {
            return null;
        }
        if (!LoopbackHost.isCoreOrigin(uri, enginePort)) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        // 1. Neutralize PWA ServiceWorker so WebView always routes main-frame HTML through LocalAssetServer
        if ("/sw.js".equals(path)) {
            String unregSw = "self.addEventListener('install',function(){self.skipWaiting();});"
                    + "self.addEventListener('activate',function(e){e.waitUntil("
                    + "caches.keys().then(function(k){return Promise.all(k.map(function(n){return caches.delete(n);}));})"
                    + ".then(function(){return self.registration.unregister();}));});";
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                    headers, new ByteArrayInputStream(unregSw.getBytes(StandardCharsets.UTF_8)));
        }
        if ("/registerSW.js".equals(path)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                    headers, new ByteArrayInputStream("/* native host */".getBytes(StandardCharsets.UTF_8)));
        }

        // 1b. Serve custom Android bridge assets under /__oc_* (same-origin only, no wildcard CORS)
        if (path.startsWith("/__oc_")) {
            String relPath = path.substring(1);
            File webDir = new File(context.getFilesDir(), "web");
            File target = new File(webDir, relPath);
            try {
                String canonicalWeb = webDir.getCanonicalPath();
                String canonicalTarget = target.getCanonicalPath();
                if (!canonicalTarget.startsWith(canonicalWeb + File.separator)) {
                    return null;
                }
                if (target.isFile() && target.canRead()) {
                    String mime = MimeTypes.getMimeType(target.getName());
                    String encoding = MimeTypes.isTextMime(mime) ? "UTF-8" : null;
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    headers.put("X-Content-Type-Options", "nosniff");
                    InputStream bis = new BufferedInputStream(new FileInputStream(target), 32 * 1024);
                    return new WebResourceResponse(mime, encoding, 200, "OK", headers, bis);
                }
            } catch (Exception e) {
                Log.w(TAG, "failed serving " + path, e);
            }
            return null;
        }

        // 2a. Prevent HTTP 500 EACCES when OpenCode lists unreadable Android SELinux ancestor dirs
        if ("/api/fs/list".equals(path)) {
            WebResourceResponse virtualFs = maybeInterceptProtectedFsList(context, uri);
            if (virtualFs != null) {
                return virtualFs;
            }
            return null;
        }

        // 2b. Ensure /api/info reports accurate persistentPty capability when opencode-pty is absent
        if ("/api/info".equals(path) && !hasPtyDaemonBinary(context)) {
            WebResourceResponse patchedInfo = fetchPatchedApiInfo(uri, enginePort);
            if (patchedInfo != null) {
                return patchedInfo;
            }
            return null;
        }

        // 3. Pass all static bundles, API, auth, SSE, and PTY endpoints directly to Bun server
        if (path.startsWith("/_assets/") || path.startsWith("/assets/")
                || path.startsWith("/api/") || path.startsWith("/auth/")
                || path.startsWith("/event") || path.startsWith("/global/")
                || path.startsWith("/session") || path.startsWith("/project")
                || path.startsWith("/pty") || path.startsWith("/mcp")
                || path.startsWith("/provider") || path.startsWith("/config")
                || path.startsWith("/file") || path.startsWith("/find")
                || path.startsWith("/icons/") || path.endsWith(".js")
                || path.endsWith(".css") || path.endsWith(".woff2")
                || path.endsWith(".svg") || path.endsWith(".png")
                || path.endsWith(".ico") || path.endsWith(".webmanifest")) {
            return null;
        }

        // 4. Intercept main-frame SPA document to inject __oc_android_boot.js + capability token cleanly
        if (isForMainFrame) {
            return fetchAndInjectIndexHtml(uri, enginePort, bridgeAuthToken, rawBindingName);
        }

        return null;
    }

    private static boolean hasPtyDaemonBinary(Context context) {
        File files = context.getFilesDir();
        return new File(files, "usr/bin/opencode-pty").canExecute()
                || new File(files, "runtime/bin/opencode-pty").canExecute();
    }

    private WebResourceResponse fetchPatchedApiInfo(Uri uri, int port) {
        HttpURLConnection conn = null;
        try {
            conn = openAuthenticatedLoopbackGet(uri.toString(), port, "application/json");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String body;
            try (InputStream in = conn.getInputStream()) {
                body = IoUtil.readAll(in, "UTF-8");
            }
            String patched = body.replace("\"persistentPty\":true", "\"persistentPty\":false");
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("application/json", "UTF-8", 200, "OK",
                    headers, new ByteArrayInputStream(patched.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private WebResourceResponse maybeInterceptProtectedFsList(Context context, Uri uri) {
        try {
            String defaultLoc = context.getFilesDir().getAbsolutePath();
            String locDir = uri.getQueryParameter("location[directory]");
            if (locDir == null || locDir.trim().isEmpty()) {
                locDir = uri.getQueryParameter("directory");
            }
            if (locDir == null || locDir.trim().isEmpty()) {
                locDir = defaultLoc;
            }
            locDir = normalizePosixPath(locDir);

            String reqPath = uri.getQueryParameter("path");
            if (reqPath == null || reqPath.trim().isEmpty()) {
                reqPath = ".";
            }
            String target = reqPath.startsWith("/")
                    ? normalizePosixPath(reqPath)
                    : normalizePosixPath(locDir + "/" + reqPath);

            // Check if target is actually readable by the OS; if so, let OpenCode's native server handle it
            File targetFile = new File(target);
            if (!isAndroidProtectedContainer(target) && targetFile.canRead() && targetFile.list() != null) {
                return null;
            }

            List<String> virtualChildren = getVirtualAndroidChildren(context, target);
            if (virtualChildren == null) {
                return null;
            }

            Collections.sort(virtualChildren);
            JSONArray data = new JSONArray();
            for (String childAbs : virtualChildren) {
                String rel = relativizePosix(locDir, childAbs);
                String entryPath = ".".equals(rel) ? "./" : (rel.endsWith("/") ? rel : rel + "/");
                JSONObject item = new JSONObject();
                item.put("path", entryPath);
                item.put("type", "directory");
                data.put(item);
            }

            JSONObject location = new JSONObject();
            location.put("directory", locDir);
            JSONObject root = new JSONObject();
            root.put("location", location);
            root.put("data", data);

            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("application/json", "UTF-8", 200, "OK",
                    headers, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Log.w(TAG, "maybeInterceptProtectedFsList fallback: " + e.getMessage());
            return null;
        }
    }

    private static boolean isAndroidProtectedContainer(String normalizedPath) {
        return "/".equals(normalizedPath)
                || "/storage".equals(normalizedPath)
                || "/storage/emulated".equals(normalizedPath)
                || "/data".equals(normalizedPath)
                || "/data/user".equals(normalizedPath)
                || "/data/user/0".equals(normalizedPath);
    }

    private static List<String> getVirtualAndroidChildren(Context context, String target) {
        List<String> out = new ArrayList<>();
        if ("/".equals(target)) {
            out.add("/storage");
            if (new File("/sdcard").exists()) {
                out.add("/sdcard");
            }
            out.add("/data");
            return out;
        }
        if ("/storage".equals(target)) {
            out.add("/storage/emulated");
            try {
                File ext = Environment.getExternalStorageDirectory();
                if (ext != null) {
                    String p = normalizePosixPath(ext.getAbsolutePath());
                    if (p.startsWith("/storage/") && !p.startsWith("/storage/emulated")) {
                        int nextSlash = p.indexOf('/', "/storage/".length());
                        out.add(nextSlash > 0 ? p.substring(0, nextSlash) : p);
                    }
                }
            } catch (Throwable ignored) {
            }
            return out;
        }
        if ("/storage/emulated".equals(target)) {
            out.add("/storage/emulated/0");
            return out;
        }
        if ("/data".equals(target)) {
            out.add("/data/user");
            return out;
        }
        if ("/data/user".equals(target)) {
            out.add("/data/user/0");
            return out;
        }
        if ("/data/user/0".equals(target)) {
            out.add("/data/user/0/" + context.getPackageName());
            return out;
        }
        String pkgRoot = "/data/user/0/" + context.getPackageName();
        if (pkgRoot.equals(target)) {
            out.add(pkgRoot + "/files");
            return out;
        }
        return null;
    }

    private static String normalizePosixPath(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "/";
        }
        String s = raw.replace('\\', '/');
        String[] parts = s.split("/");
        List<String> stack = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p)) {
                continue;
            }
            if ("..".equals(p)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else {
                stack.add(p);
            }
        }
        if (stack.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : stack) {
            sb.append('/').append(seg);
        }
        return sb.toString();
    }

    private static String relativizePosix(String fromDir, String toPath) {
        String[] from = "/".equals(fromDir) ? new String[0] : fromDir.substring(1).split("/");
        String[] to = "/".equals(toPath) ? new String[0] : toPath.substring(1).split("/");
        int common = 0;
        while (common < from.length && common < to.length && from[common].equals(to[common])) {
            common++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < from.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append("..");
        }
        for (int i = common; i < to.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(to[i]);
        }
        return sb.length() == 0 ? "." : sb.toString();
    }

    private static HttpURLConnection openAuthenticatedLoopbackGet(String urlStr, int port, String accept) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2500);
        conn.setReadTimeout(4000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", accept);
        String token = CoreRuntimeState.sessionToken;
        String csrf = CoreRuntimeState.csrfToken;
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Cookie", "opencode_session_" + port + "=" + token);
        } else if (csrf != null && !csrf.isEmpty()) {
            conn.setRequestProperty("Authorization", CoreReadyProbe.basicAuthHeader(csrf));
        }
        return conn;
    }

    public static String buildBridgeFacadeInitJs(String bridgeAuthToken, String rawBindingName) {
        String tokenInit = (bridgeAuthToken != null && !bridgeAuthToken.isEmpty())
                ? "if(!window.__OC_ANDROID_BOOTED__){window.__OC_BRIDGE_TOKEN__=" + JSONObject.quote(bridgeAuthToken) + ";}"
                : "";
        String rawBinding = (rawBindingName != null && !rawBindingName.isEmpty())
                ? JSONObject.quote(rawBindingName)
                : "\"Android\"";
        return "(function(){"
                + "window.__OC_HOST_THEME__=" + JSONObject.quote(hostThemeHint) + ";"
                + tokenInit
                + "try{"
                + "if(navigator.serviceWorker){"
                + "if(typeof navigator.serviceWorker.getRegistrations==='function'){"
                + "navigator.serviceWorker.getRegistrations().then(function(rs){rs.forEach(function(r){r.unregister();});}).catch(function(){});"
                + "}"
                + "navigator.serviceWorker.register=function(){"
                + "return Promise.resolve({scope:'/',active:null,installing:null,waiting:null,"
                + "unregister:function(){return Promise.resolve(true);},"
                + "update:function(){return Promise.resolve();},"
                + "addEventListener:function(){},removeEventListener:function(){}});"
                + "};"
                + "}"
                + "}catch(e){}"
                + "var rn=" + rawBinding + ";"
                + "var raw=window[rn];"
                + "if(raw){"
                + "try{delete window[rn];}catch(e){window[rn]=undefined;}"
                + "var facade={};"
                + "for(var k in raw){"
                + "(function(m){if(typeof raw[m]==='function'){facade[m]=function(){return raw[m].apply(raw,arguments);};}})(k);"
                + "}"
                + "try{"
                + "Object.freeze(facade);"
                + "Object.defineProperty(window,'Android',{value:facade,writable:false,configurable:false,enumerable:true});"
                + "Object.defineProperty(window,'AndroidBridge',{value:facade,writable:false,configurable:false,enumerable:true});"
                + "}catch(e){window.Android=facade;window.AndroidBridge=facade;}"
                + "}"
                + "})();";
    }

    private WebResourceResponse fetchAndInjectIndexHtml(Uri uri, int port, String bridgeAuthToken, String rawBindingName) {
        HttpURLConnection conn = null;
        try {
            conn = openAuthenticatedLoopbackGet(uri.toString(), port, "text/html,application/xhtml+xml");
            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            if (status != 200 || contentType == null || !contentType.toLowerCase().contains("text/html")) {
                return null;
            }
            String html;
            try (InputStream in = conn.getInputStream()) {
                html = IoUtil.readAll(in, "UTF-8");
            }
            String bootTag = "\n    <script>"
                    + buildBridgeFacadeInitJs(bridgeAuthToken, rawBindingName)
                    + "</script>\n    <script src=\"/__oc_android_boot.js\"></script>\n";
            int headIdx = html.indexOf("<head>");
            if (headIdx >= 0) {
                html = html.substring(0, headIdx + 6) + bootTag + html.substring(headIdx + 6);
            } else {
                html = bootTag + html;
            }

            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            return new WebResourceResponse(
                    "text/html", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Log.w(TAG, "fetchAndInjectIndexHtml fallback: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
