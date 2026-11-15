package com.oc.ide.ui.webview;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.oc.ide.bridge.AndroidBridge;
import com.oc.ide.core.CoreRuntimeState;
import com.oc.ide.runtime.RuntimePaths;
import com.oc.ide.theme.ThemeColors;
import com.oc.ide.theme.ThemeManager;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Injects the pre-minted OpenCode session cookie into WebView's CookieManager
 * and loads the real loopback origin (http://127.0.0.1:<port>/) so SolidJS,
 * SSE (/event), and WebSocket (/pty) run with zero password prompt.
 */
public final class AppLoader {

    private static final String TAG = "WebViewHost";

    private AppLoader() {
    }

    public static void load(Context context, WebView webView, ThemeManager theme,
                            AndroidBridge bridge) {
        int port = bridge.getEnginePort();
        if (theme != null) {
            theme.applyToWebView(webView);
        }

        String sessionToken = CoreRuntimeState.sessionToken;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
            if (sessionToken != null && !sessionToken.isEmpty()) {
                String cookie = "opencode_session_" + port + "=" + sessionToken
                        + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax";
                cm.setCookie("http://127.0.0.1:" + port, cookie);
                cm.setCookie("http://localhost:" + port, cookie);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.flush();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cookie injection warning: " + e.getMessage());
        }

        String hostTheme;
        if (theme != null && theme.mode() == ThemeColors.MODE_DARK) {
            hostTheme = "dark";
        } else if (theme != null && theme.mode() == ThemeColors.MODE_LIGHT) {
            hostTheme = "light";
        } else {
            hostTheme = "system";
        }

        LocalAssetServer.setHostThemeHint(hostTheme);
        bridge.setCoreOriginActive(true);
        String startUrl = "http://127.0.0.1:" + port + "/";
        try {
            SharedPreferences prefs = context.getSharedPreferences("oc_workspace_boot", Context.MODE_PRIVATE);
            if (!prefs.getBoolean("opened_default_ws_v2", false)) {
                File defaultWs = new RuntimePaths(context).getDefaultWorkspaceDir();
                if (defaultWs != null && (defaultWs.isDirectory() || defaultWs.mkdirs())) {
                    String encodedDir = Base64.encodeToString(
                            defaultWs.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    startUrl = "http://127.0.0.1:" + port + "/" + encodedDir + "/session";
                    prefs.edit().putBoolean("opened_default_ws_v2", true).apply();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "workspace startUrl fallback: " + e.getMessage());
        }
        webView.loadUrl(startUrl);
    }
}
