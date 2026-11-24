package com.oc.ide.ui.lifecycle;

import android.app.Activity;
import android.view.View;

import com.oc.ide.theme.ThemeManager;
import com.oc.ide.ui.splash.SplashController;
import com.oc.ide.ui.webview.WebViewHost;

/** Re-applies the resolved theme to the activity, splash and host WebView. */
public final class ThemeRefresher {

    private ThemeRefresher() {
    }

    public static void refresh(Activity activity, ThemeManager theme, View rootLayout,
                               SplashController splash, WebViewHost host) {
        if (theme != null) {
            theme.updateContext(activity);
            theme.applyToActivity(activity);
        }
        if (rootLayout != null) {
            rootLayout.setBackgroundColor(theme != null ? theme.getBackgroundColor() : 0xFF101010);
        }
        if (splash != null) {
            splash.refresh();
        }
        if (host != null && theme != null) {
            theme.applyToWebView(host.getWebView());
        }
    }
}
