package com.oc.ide.bridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.oc.ide.oauth.OAuthManager;
import com.oc.ide.runtime.RuntimeConfig;
import com.oc.ide.runtime.RuntimeManager;
import com.oc.ide.shell.ShizukuBridge;
import com.oc.ide.theme.ThemeManager;
import com.oc.ide.ui.MainActivity;
import com.oc.ide.ui.download.FileDownloader;
import com.oc.ide.update.AppUpdateManager;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Capability-hardened JS bridge exposed to the WebView as {@code window.Android}.
 *
 * Security model:
 *  - Unauthenticated shell/password methods ({@code exec}, {@code shizuku}, {@code getCsrfToken})
 *    are NEVER exposed via {@code @JavascriptInterface}.
 *  - Every method requires {@code coreOriginActive == true} (verified by {@code AgyWebViewClient}).
 *  - Privileged plugin, config, OTA install, and directory actions additionally require a
 *    per-instance 256-bit capability token ({@code bridgeAuthToken}) captured inside
 *    {@code __oc_android_boot.js}'s private closure and deleted from {@code window} immediately.
 *  - Heavy plugin operations run asynchronously on background worker threads so {@code JavaBridge}
 *    never stalls UI messages.
 */
public final class AndroidBridge {

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final UiBridge ui = new UiBridge();
    private final ThemeBridge themeBridge;
    private final EngineBridge engine;
    private final EngineConfigJson config;
    private final ExternalUrlBridge external;
    private final ShizukuBridge shizuku;
    private final NotificationBridge notification;
    private final String bridgeAuthToken;
    private final String rawInterfaceName;
    private volatile boolean coreOriginActive = false;

    public AndroidBridge(Context context, RuntimeManager runtime,
                         ShizukuBridge shizuku, OAuthManager oauth,
                         ThemeManager theme) {
        Context appContext = context.getApplicationContext();
        this.appContext = appContext;
        this.themeBridge = new ThemeBridge(theme);
        this.engine = new EngineBridge(runtime);
        this.config = new EngineConfigJson(appContext, runtime, engine, shizuku, themeBridge);
        this.external = new ExternalUrlBridge(appContext, oauth);
        this.shizuku = shizuku;
        this.notification = new NotificationBridge(appContext, shizuku);
        this.bridgeAuthToken = generateCapabilityToken();
        this.rawInterfaceName = generateRandomBindingName();
    }

    private static String generateCapabilityToken() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : raw) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String generateRandomBindingName() {
        byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder("__oc_nb_");
        for (byte b : raw) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** Java-only accessor (never exposed to JS). */
    public String getBridgeAuthToken() {
        return bridgeAuthToken;
    }

    /** Java-only accessor (never exposed to JS). */
    public String getRawInterfaceName() {
        return rawInterfaceName;
    }

    /** Java-only accessor (never exposed to JS). */
    public void setCoreOriginActive(boolean active) {
        this.coreOriginActive = active;
    }

    private boolean isAuthorized(String token) {
        if (!coreOriginActive || token == null || token.length() != bridgeAuthToken.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                bridgeAuthToken.getBytes(StandardCharsets.UTF_8));
    }

    public void setActivity(MainActivity activity) {
        ui.setActivity(activity);
    }

    @JavascriptInterface
    public void onUiReady() {
        if (!coreOriginActive) return;
        ui.onUiReady();
    }

    @JavascriptInterface
    public void onUiError(final String errorMessage) {
        if (!coreOriginActive) return;
        ui.onUiError(errorMessage);
    }

    @JavascriptInterface
    public boolean setTheme(String mode) {
        if (!coreOriginActive) return false;
        return themeBridge.setTheme(mode);
    }

    @JavascriptInterface
    public boolean setThemeByUser(final String mode) {
        if (!coreOriginActive) return false;
        return themeBridge.setThemeByUser(mode);
    }

    @JavascriptInterface
    public void onVisualThemeChanged(String visual) {
        if (!coreOriginActive) return;
        themeBridge.onVisualThemeChanged(visual);
    }

    @JavascriptInterface
    public String getTheme() {
        if (!coreOriginActive) return "system";
        return themeBridge.getTheme();
    }

    @JavascriptInterface
    public boolean isDesktopMode() {
        if (!coreOriginActive) return false;
        return ui.isDesktopMode();
    }

    @JavascriptInterface
    public void setDesktopMode(boolean enabled) {
        if (!coreOriginActive) return;
        ui.setDesktopMode(enabled);
    }

    @JavascriptInterface
    public boolean isEngineReady() {
        if (!coreOriginActive) return false;
        return engine.isReady();
    }

    public int getEnginePort() {
        return engine.getPort();
    }

    @JavascriptInterface
    public String getConfigAuth(String authToken) {
        if (!isAuthorized(authToken)) {
            return "{}";
        }
        return config.get();
    }

    @JavascriptInterface
    public void openUrl(String url) {
        if (!coreOriginActive) return;
        external.open(url);
    }

    @JavascriptInterface
    public boolean shizukuAvailable() {
        if (!coreOriginActive) return false;
        return shizuku.isAvailable();
    }

    @JavascriptInterface
    public boolean postNotification(String title, String message, String tag) {
        if (!coreOriginActive) return false;
        return notification.post(title, message, tag);
    }

    @JavascriptInterface
    public void saveBlob(String base64Data, String filename, String mimeType) {
        if (!coreOriginActive) return;
        if (MainActivity.activeActivity != null) {
            FileDownloader.handleBlobData(
                    MainActivity.activeActivity, base64Data, filename, mimeType);
        }
    }

    @JavascriptInterface
    public String getPluginsCatalogJson() {
        if (!coreOriginActive) return "{\"plugins\":[]}";
        return engine.getPluginsCatalogJson();
    }

    @JavascriptInterface
    public String listAndroidPluginsAuth(String authToken) {
        if (!isAuthorized(authToken)) {
            return "{}";
        }
        return engine.listAndroidPlugins();
    }

    private void emitPluginCallback(final String callbackId, final String resultJson) {
        if (callbackId == null || callbackId.isEmpty()) {
            return;
        }
        final String safeCb = JSONObject.quote(callbackId);
        final String safeRes = JSONObject.quote(resultJson != null ? resultJson : "{}");
        mainHandler.post(() -> {
            MainActivity act = MainActivity.activeActivity;
            WebView wv = act != null ? act.getWebView() : null;
            if (wv != null) {
                try {
                    wv.evaluateJavascript(
                            "window.__ocOnPluginOpDone&&window.__ocOnPluginOpDone("
                                    + safeCb + "," + safeRes + ");",
                            null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @JavascriptInterface
    public void installMarketplacePluginAsync(final String authToken,
                                              final String callbackId,
                                              final String pluginName,
                                              final String archiveUrl,
                                              final String catalogId,
                                              final String marketplace,
                                              final String version) {
        if (!isAuthorized(authToken)) {
            emitPluginCallback(callbackId, "{\"ok\":false,\"error\":\"Unauthorized bridge request\"}");
            return;
        }
        Thread worker = new Thread(() -> {
            String raw = engine.installMarketplacePlugin(pluginName, archiveUrl, catalogId, marketplace, version);
            emitPluginCallback(callbackId, raw);
        }, "OcPluginInstall");
        worker.setDaemon(true);
        worker.start();
    }

    @JavascriptInterface
    public void toggleAndroidPluginAsync(final String authToken,
                                         final String callbackId,
                                         final String pluginName,
                                         final boolean enabled) {
        if (!isAuthorized(authToken)) {
            emitPluginCallback(callbackId, "{\"ok\":false,\"error\":\"Unauthorized bridge request\"}");
            return;
        }
        Thread worker = new Thread(() -> {
            String raw = engine.toggleAndroidPlugin(pluginName, enabled);
            emitPluginCallback(callbackId, raw);
        }, "OcPluginToggle");
        worker.setDaemon(true);
        worker.start();
    }

    @JavascriptInterface
    public void uninstallMarketplacePluginAsync(final String authToken,
                                                final String callbackId,
                                                final String pluginName) {
        if (!isAuthorized(authToken)) {
            emitPluginCallback(callbackId, "{\"ok\":false,\"error\":\"Unauthorized bridge request\"}");
            return;
        }
        Thread worker = new Thread(() -> {
            boolean ok = engine.uninstallMarketplacePlugin(pluginName);
            emitPluginCallback(callbackId, "{\"ok\":" + ok + "}");
        }, "OcPluginUninstall");
        worker.setDaemon(true);
        worker.start();
    }

    @JavascriptInterface
    public String getMicAccessStatus() {
        if (!coreOriginActive) return "denied";
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                return "granted";
            }
            MainActivity act = MainActivity.activeActivity;
            Context ctx = act != null ? act : appContext;
            if (ctx != null && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return "granted";
            }
            return "not-determined";
        } catch (Throwable t) {
            return "not-determined";
        }
    }

    @JavascriptInterface
    public void requestMicAccess(final String callbackId) {
        if (!coreOriginActive) return;
        MainActivity act = MainActivity.activeActivity;
        if (act != null) {
            act.requestMicAccessFromJs(callbackId);
        }
    }

    @JavascriptInterface
    public void openAppSettings() {
        if (!coreOriginActive) return;
        try {
            MainActivity act = MainActivity.activeActivity;
            Context ctx = act != null ? act : appContext;
            if (ctx != null) {
                android.content.Intent intent = new android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public String getAppVersion() {
        if (!coreOriginActive) return RuntimeConfig.CORE_VERSION;
        try {
            return AppUpdateManager.getInstance(appContext).getInstalledVersion();
        } catch (Throwable t) {
            return RuntimeConfig.CORE_VERSION;
        }
    }

    @JavascriptInterface
    public String getUpdateStateJson() {
        if (!coreOriginActive) return "{\"type\":\"idle\"}";
        try {
            return AppUpdateManager.getInstance(appContext).getStateJson();
        } catch (Throwable t) {
            return "{\"type\":\"idle\"}";
        }
    }

    @JavascriptInterface
    public void checkForAppUpdates(boolean manual) {
        if (!coreOriginActive) return;
        try {
            AppUpdateManager.getInstance(appContext).checkForUpdates(manual);
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public void applyAppUpdateAuth(String authToken) {
        if (!isAuthorized(authToken)) return;
        try {
            AppUpdateManager.getInstance(appContext).applyUpdate();
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public String getExternalStoragePath() {
        if (!coreOriginActive) return "/storage/emulated/0";
        try {
            java.io.File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null) {
                return ext.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return "/storage/emulated/0";
    }

    @JavascriptInterface
    public void pickProjectDirectoryAuth(final String authToken, final String callbackId) {
        if (!isAuthorized(authToken)) {
            return;
        }
        MainActivity act = MainActivity.activeActivity;
        if (act != null) {
            act.pickProjectDirectoryFromJs(callbackId);
        }
    }

    @JavascriptInterface
    public String createProjectDirectoryAuth(String authToken, String parentPath, String folderName) {
        if (!isAuthorized(authToken)) {
            return "{\"ok\":false,\"error\":\"Unauthorized bridge request\"}";
        }
        try {
            if (folderName == null || folderName.trim().isEmpty()) {
                return "{\"ok\":false,\"error\":\"Empty folder name\"}";
            }
            String cleanName = folderName.trim().replace('\\', '/');
            if (cleanName.contains("..") || cleanName.startsWith("/")) {
                return "{\"ok\":false,\"error\":\"Invalid folder name\"}";
            }
            String base = (parentPath == null || parentPath.trim().isEmpty())
                    ? getExternalStoragePath() + "/Documents"
                    : parentPath.trim();
            java.io.File target = new java.io.File(base, cleanName);
            if (!target.exists() && !target.mkdirs()) {
                return "{\"ok\":false,\"error\":\"Cannot create directory: " + target.getAbsolutePath() + "\"}";
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("path", target.getAbsolutePath());
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }
}
