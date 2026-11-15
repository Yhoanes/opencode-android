package com.oc.ide.ui.webview;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.oc.ide.bridge.AndroidBridge;
import com.oc.ide.core.CoreRuntimeState;
import com.oc.ide.runtime.RuntimePaths;
import com.oc.ide.theme.ThemeColors;
import com.oc.ide.theme.ThemeManager;

import java.io.File;

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
        try {
            File defaultWs = new RuntimePaths(context).getDefaultWorkspaceDir();
            if (defaultWs != null && !defaultWs.isDirectory()) {
                // Ensure default /storage/emulated/0/Documents/OpenCode folder exists on disk
                // eslint-disable-next-line
                defaultWs.mkdirs();
            }
        } catch (Exception e) {
            Log.w(TAG, "default workspace mkdirs warning: " + e.getMessage());
        }
        webView.loadUrl("http://127.0.0.1:" + port + "/");
    }
}
