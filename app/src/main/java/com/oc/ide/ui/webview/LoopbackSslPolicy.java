package com.oc.ide.ui.webview;

import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;

import com.oc.ide.util.LoopbackHost;

/** The core serves a self-signed cert on loopback; proceed only on the designated core port. */
public final class LoopbackSslPolicy {

    private static final String TAG = "WebViewHost";

    private LoopbackSslPolicy() {
    }

    public static boolean proceed(SslError error, int expectedPort) {
        if (error == null) return false;
        try {
            String errorUrl = error.getUrl();
            if (errorUrl == null) return false;
            Uri uri = Uri.parse(errorUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            if (LoopbackHost.isLoopback(host)) {
                return expectedPort <= 0 || port == expectedPort;
            }
        } catch (Exception e) {
            Log.w(TAG, "ssl url parse failed", e);
        }
        return false;
    }

    public static boolean proceed(SslError error) {
        return proceed(error, -1);
    }
}
