package com.oc.ide.ui.webview;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.oc.ide.bridge.AndroidBridge;
import com.oc.ide.oauth.OAuthManager;
import com.oc.ide.theme.ThemeManager;
import com.oc.ide.util.LoopbackHost;

/**
 * Builds and configures the single WebView that hosts the OpenCode UI
 * on its local origin, wired with capability-hardened native bindings
 * and ServiceWorker interception so Workbox never hijacks loopback navigation.
 */
public final class WebViewHost {

    private static final String TAG = "WebViewHost";

    public interface Listener {
        void onPageFinished(String url);
        void onMainFrameLoadError(String url, String description);
    }

    private final Context context;
    private final ThemeManager theme;
    private final AndroidBridge bridge;
    private final WebView webView;
    private Listener listener;

    public WebViewHost(Context context, ThemeManager theme, OAuthManager oauth,
                       AndroidBridge bridge, AgyWebChromeClient.FileChooserCallback fileChooserCallback) {
        this.context = context;
        this.theme = theme;
        this.bridge = bridge;
        final LocalAssetServer assetServer = new LocalAssetServer();
        installServiceWorkerInterceptor(context.getApplicationContext(), assetServer, bridge);
        this.webView = WebViewFactory.create(context, bridge, theme);
        this.webView.setWebChromeClient(new AgyWebChromeClient(
                new PopupWindowHandler(context, oauth), fileChooserCallback, bridge));
        this.webView.setWebViewClient(new AgyWebViewClient(
                context, oauth, assetServer, bridge, new Listener() {
            @Override
            public void onPageFinished(String url) {
                if (WebViewHost.this.listener != null) {
                    WebViewHost.this.listener.onPageFinished(url);
                }
            }

            @Override
            public void onMainFrameLoadError(String url, String description) {
                if (WebViewHost.this.listener != null) {
                    WebViewHost.this.listener.onMainFrameLoadError(url, description);
                }
            }
        }));
        this.webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                if (com.oc.ide.ui.MainActivity.activeActivity != null) {
                    com.oc.ide.ui.download.FileDownloader.handleDownload(
                            com.oc.ide.ui.MainActivity.activeActivity, webView, url,
                            userAgent, contentDisposition, mimetype, contentLength);
                }
            }
        });
    }

    private static void installServiceWorkerInterceptor(final Context appContext,
                                                        final LocalAssetServer assetServer,
                                                        final AndroidBridge bridge) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            ServiceWorkerController swc = ServiceWorkerController.getInstance();
            ServiceWorkerWebSettings swSettings = swc.getServiceWorkerWebSettings();
            if (swSettings != null) {
                swSettings.setAllowContentAccess(false);
                swSettings.setAllowFileAccess(false);
                swSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            }
            swc.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    if (request == null || request.getUrl() == null) {
                        return null;
                    }
                    Uri reqUri = request.getUrl();
                    int port = bridge.getEnginePort();
                    if (!LoopbackHost.isCoreOrigin(reqUri, port)) {
                        if (LoopbackHost.isLoopback(reqUri)) {
                            return AgyWebViewClient.blockedResponse();
                        }
                        return null;
                    }
                    return assetServer.intercept(
                            appContext,
                            reqUri,
                            request.getMethod(),
                            port,
                            request.isForMainFrame(),
                            bridge.getBridgeAuthToken(),
                            bridge.getRawInterfaceName());
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "ServiceWorkerController setup notice: " + t.getMessage());
        }
    }

    public WebView getWebView() {
        return webView;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isDesktopMode() {
        return DesktopModePrefs.isDesktopMode(context);
    }

    public void setDesktopMode(boolean enabled) {
        DesktopModePrefs.setDesktopMode(context, enabled);
        webView.post(new Runnable() {
            @Override
            public void run() {
                WebSettingsFactory.applyDesktopMode(webView, enabled);
                webView.reload();
            }
        });
    }

    /** Loads the local OpenCode origin with session cookie and host theme hint. */
    public void loadApp() {
        AppLoader.load(context, webView, theme, bridge);
    }

    /** Detaches JS interfaces, callbacks, parent view, and destroys the underlying WebView cleanly. */
    public void destroy() {
        this.listener = null;
        try {
            webView.stopLoading();
        } catch (Throwable ignored) {
        }
        try {
            webView.setDownloadListener(null);
            webView.setWebChromeClient(null);
        } catch (Throwable ignored) {
        }
        try {
            webView.removeJavascriptInterface(bridge.getRawInterfaceName());
        } catch (Throwable ignored) {
        }
        try {
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
        } catch (Throwable ignored) {
        }
        try {
            webView.destroy();
        } catch (Throwable ignored) {
        }
    }
}
