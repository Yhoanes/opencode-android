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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** WebView client: strict core-origin isolation, HTTP-Basic auth guard, OAuth interception, and boot injection. */
public final class AgyWebViewClient extends WebViewClient {

    private static final String TAG = "WebViewHost";

    private final Context context;
    private final OAuthManager oauth;
    private final LocalAssetServer assetServer;
    private final AndroidBridge bridge;
    private final WebViewHost.Listener listener;
    private volatile boolean mainFrameOnCoreOrigin = false;

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
        int port = bridge.getEnginePort();
        String currentUrl = view != null ? view.getUrl() : null;
        if (mainFrameOnCoreOrigin
                && LoopbackHost.isCoreOriginUrl(currentUrl, port)
                && LoopbackHost.isCoreAuthHost(host, port)) {
            String pwd = CoreRuntimeState.csrfToken;
            if (pwd != null && !pwd.isEmpty()) {
                handler.proceed("opencode", pwd);
                return;
            }
        }
        Log.w(TAG, "Blocked HTTP auth challenge for non-core target: host=" + host + " viewUrl=" + currentUrl);
        if (handler != null) {
            handler.cancel();
        }
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
            if (!LoopbackHost.isCoreOrigin(uri, bridge.getEnginePort())) {
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    Log.i(TAG, "non-core navigation -> external browser: " + url);
                    return oauth.openInBrowser(context, url);
                }
                Log.w(TAG, "blocked non-http navigation scheme inside WebView: " + url);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot parse uri: " + url, e);
            return true;
        }
        return oauth.intercept(context, url);
    }

    @Override
    public void onPageStarted(WebView v, String url, Bitmap favicon) {
        if (url == null || url.equals("about:blank")) {
            mainFrameOnCoreOrigin = false;
            bridge.setCoreOriginActive(false);
            return;
        }
        boolean isCore = LoopbackHost.isCoreOriginUrl(url, bridge.getEnginePort());
        mainFrameOnCoreOrigin = isCore;
        bridge.setCoreOriginActive(isCore);
        if (!isCore) {
            v.stopLoading();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                oauth.openInBrowser(context, url);
            }
            return;
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

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Forbidden",
                Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream("Forbidden".getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean isBlockedCrossOriginDest(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && "sec-fetch-dest".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
                String dest = e.getValue().trim().toLowerCase(Locale.ROOT);
                return "iframe".equals(dest)
                        || "frame".equals(dest)
                        || "fencedframe".equals(dest)
                        || "embed".equals(dest)
                        || "object".equals(dest)
                        || "script".equals(dest)
                        || "worker".equals(dest)
                        || "sharedworker".equals(dest)
                        || "serviceworker".equals(dest);
            }
        }
        return false;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        Uri reqUri = request.getUrl();
        int port = bridge.getEnginePort();
        boolean isCore = LoopbackHost.isCoreOrigin(reqUri, port);

        if (request.isForMainFrame()) {
            if (!isCore && !"about:blank".equals(reqUri.toString())) {
                Log.w(TAG, "Blocked non-core main-frame request: " + reqUri);
                return blockedResponse();
            }
            if (isCore) {
                mainFrameOnCoreOrigin = true;
                bridge.setCoreOriginActive(true);
            }
        } else if (!isCore) {
            // Never allow subresources or iframes targeting any other local port (localhost:3000, etc.)
            if (LoopbackHost.isLoopback(reqUri)) {
                Log.w(TAG, "Blocked cross-port loopback request: " + reqUri);
                return blockedResponse();
            }
            // Never allow cross-origin iframes, workers, or scripts inside the privileged WebView
            if (isBlockedCrossOriginDest(request.getRequestHeaders())) {
                Log.w(TAG, "Blocked cross-origin active resource: " + reqUri);
                return blockedResponse();
            }
            return null;
        }

        return assetServer.intercept(
                context,
                reqUri,
                request.getMethod(),
                port,
                request.isForMainFrame(),
                bridge.getBridgeAuthToken());
    }

    @Override
    @SuppressWarnings("deprecation")
    public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
        if (url == null) {
            return null;
        }
        try {
            Uri u = Uri.parse(url);
            int port = bridge.getEnginePort();
            if (!LoopbackHost.isCoreOrigin(u, port)) {
                if (LoopbackHost.isLoopback(u)) {
                    return blockedResponse();
                }
                return null;
            }
            boolean isMain = "/".equals(u.getPath()) || u.getPath() == null || u.getPath().isEmpty();
            return assetServer.intercept(context, u, "GET", port, isMain, bridge.getBridgeAuthToken());
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
