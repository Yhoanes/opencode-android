package com.oc.ide.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.util.Base64;
import android.util.Log;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.oc.ide.bridge.AndroidBridge;
import com.oc.ide.core.CoreRuntimeState;
import com.oc.ide.oauth.OAuthManager;
import com.oc.ide.util.LoopbackHost;

/** WebView client: loopback session/HTTP-Basic auth, OAuth interception, and boot script injection. */
public final class AgyWebViewClient extends WebViewClient {

    private static final String TAG = "WebViewHost";

    private final Context context;
    private final OAuthManager oauth;
    private final LocalAssetServer assetServer;
    private final AndroidBridge bridge;
    private final WebViewHost.Listener listener;

    public AgyWebViewClient(Context context, OAuthManager oauth, LocalAssetServer assetServer,
                            AndroidBridge bridge, WebViewHost.Listener listener) {
        this.context = context;
        this.oauth = oauth;
        this.assetServer = assetServer;
        this.bridge = bridge;
        this.listener = listener;
    }

    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
        if (LoopbackHost.isLoopback(host)) {
            String pwd = CoreRuntimeState.csrfToken;
            if (pwd != null && !pwd.isEmpty()) {
                handler.proceed("opencode", pwd);
                return;
            }
        }
        super.onReceivedHttpAuthRequest(view, handler, host, realm);
    }

    @Override
    public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
        if (req != null && req.getUrl() != null) {
            Log.w(TAG, "Resource load error: " + req.getUrl() + " err="
                    + (err != null ? err.getDescription() : "unknown"));
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
        String url = request != null && request.getUrl() != null
                ? request.getUrl().toString() : null;
        return handleNavigation(v, url);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView v, String url) {
        return handleNavigation(v, url);
    }

    private boolean handleNavigation(WebView v, String url) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            if (!LoopbackHost.isLoopback(uri)) {
                Log.i(TAG, "non-loopback navigation -> external browser: " + url);
                return oauth.openInBrowser(context, url);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot parse uri: " + url, e);
        }
        return oauth.intercept(context, url);
    }

    @Override
    public void onPageStarted(WebView v, String url, Bitmap favicon) {
        if (url != null && !url.equals("about:blank")) {
            try {
                Uri uri = Uri.parse(url);
                if (!LoopbackHost.isLoopback(uri)) {
                    v.stopLoading();
                    oauth.openInBrowser(context, url);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        if (oauth.intercept(context, url)) {
            v.stopLoading();
        }
    }

    @Override
    public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
        if (LoopbackSslPolicy.proceed(error)) {
            handler.proceed();
        } else {
            handler.cancel();
        }
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        return assetServer.intercept(
                context,
                request.getUrl(),
                request.getMethod(),
                bridge.getEnginePort(),
                request.isForMainFrame());
    }

    @Override
    @SuppressWarnings("deprecation")
    public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
        if (url == null) {
            return null;
        }
        try {
            Uri u = Uri.parse(url);
            boolean isMain = "/".equals(u.getPath()) || u.getPath() == null || u.getPath().isEmpty();
            return assetServer.intercept(context, u, "GET", bridge.getEnginePort(), isMain);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onPageFinished(WebView v, String url) {
        if (listener != null) {
            listener.onPageFinished(url);
        }
    }
}
