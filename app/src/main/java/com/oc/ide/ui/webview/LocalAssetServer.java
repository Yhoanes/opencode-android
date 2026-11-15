package com.oc.ide.ui.webview;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import com.oc.ide.core.CoreReadyProbe;
import com.oc.ide.core.CoreRuntimeState;
import com.oc.ide.util.IoUtil;
import com.oc.ide.util.LoopbackHost;
import com.oc.ide.util.MimeTypes;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Hybrid loopback asset & HTML boot injector for OpenCode v2:
 *  1. Serves Android helper assets (`/__oc_android_boot.js`, `/__oc_plugins_catalog.json`)
 *     directly from `filesDir/web/`.
 *  2. Intercepts main-frame HTML navigation (`GET /` or SPA routes) to inject
 *     `<script src="/__oc_android_boot.js"></script>` and strip the strict CSP header
 *     that would otherwise block mobile bridge enhancements.
 *  3. Passes all `/_assets/*`, `/api/*`, `/event`, `/pty`, `/auth/*` straight through
 *     to OpenCode's native Bun server at full native speed.
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
                                         int enginePort, boolean isForMainFrame) {
        if (uri == null || !"GET".equalsIgnoreCase(method)) {
            return null;
        }
        String host = uri.getHost();
        if (!LoopbackHost.isLoopback(host) || uri.getPort() != enginePort) {
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

        // 1b. Serve custom Android bridge assets under /__oc_*
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
                    headers.put("Access-Control-Allow-Origin", "*");
                    headers.put("Cache-Control", "no-store");
                    InputStream bis = new BufferedInputStream(new FileInputStream(target), 32 * 1024);
                    return new WebResourceResponse(mime, encoding, 200, "OK", headers, bis);
                }
            } catch (Exception e) {
                Log.w(TAG, "failed serving " + path, e);
            }
            return null;
        }

        // 2. Never intercept static bundles, API, auth, SSE, or PTY endpoints
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

        // 3. Intercept main-frame SPA document to inject __oc_android_boot.js cleanly
        if (isForMainFrame) {
            return fetchAndInjectIndexHtml(uri, enginePort);
        }

        return null;
    }

    private WebResourceResponse fetchAndInjectIndexHtml(Uri uri, int port) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(uri.toString());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(4000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");

            String token = CoreRuntimeState.sessionToken;
            String csrf = CoreRuntimeState.csrfToken;
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Cookie", "opencode_session_" + port + "=" + token);
            } else if (csrf != null && !csrf.isEmpty()) {
                conn.setRequestProperty("Authorization", CoreReadyProbe.basicAuthHeader(csrf));
            }

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            if (status != 200 || contentType == null || !contentType.toLowerCase().contains("text/html")) {
                return null;
            }
            String html;
            try (InputStream in = conn.getInputStream()) {
                html = IoUtil.readAll(in, "UTF-8");
            }
            String bootTag = "\n    <script>window.__OC_HOST_THEME__=\"" + hostThemeHint + "\";</script>"
                    + "\n    <script src=\"/__oc_android_boot.js\"></script>\n";
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
