package com.oc.ide.ui.webview;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import com.oc.ide.util.AtomicFileWriter;
import com.oc.ide.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;

/**
 * Purges stale Chromium PWA ServiceWorker & Workbox CacheStorage directories on disk
 * BEFORE libwebviewchromium.so opens its profile locks in MainActivity.onCreate().
 *
 * Why this is essential:
 *  - OpenCode's upstream web bundle includes a 338 KB Workbox PWA ServiceWorker (/sw.js)
 *    with a NavigationRoute bound to precached /index.html.
 *  - Across APK/OTA updates (or when CacheStorage is invalidated), a lingering ServiceWorker
 *    intercepts http://127.0.0.1:<port>/ before WebViewClient.shouldInterceptRequest and
 *    rejects FetchEvent.respondWith() with non-precached-url -> Chromium net::ERR_FAILED.
 *  - Deleting ONLY Service Worker + CacheStorage directories (while preserving Local Storage,
 *    IndexedDB, and Cookies) guarantees zero data loss and 100% deterministic boot on first launch.
 */
public final class WebViewServiceWorkerSanitizer {

    private static final String TAG = "WebViewHost";
    private static final String VERSION_MARKER = ".webview_sw_sanitized_version";
    private static volatile boolean sanitizedThisProcess = false;

    private WebViewServiceWorkerSanitizer() {
    }

    public static synchronized void sanitizeBeforeWebViewInit(Context context) {
        if (sanitizedThisProcess || context == null) {
            return;
        }
        sanitizedThisProcess = true;
        try {
            Context appCtx = context.getApplicationContext();
            String dataDirPath = appCtx.getApplicationInfo().dataDir;
            if (dataDirPath != null && !dataDirPath.isEmpty()) {
                File appWebView = new File(dataDirPath, "app_webview");
                if (appWebView.isDirectory()) {
                    purgeServiceWorkerDirs(appWebView);
                    File defaultProfile = new File(appWebView, "Default");
                    if (defaultProfile.isDirectory()) {
                        purgeServiceWorkerDirs(defaultProfile);
                        purgeBucketedWebStorage(new File(defaultProfile, "WebStorage"));
                    }
                }
            }

            // Clear Chromium HTTP disk cache once whenever APK versionCode changes across updates
            long curVersion = getInstalledVersionCode(appCtx);
            File marker = new File(appCtx.getFilesDir(), VERSION_MARKER);
            long prevVersion = readVersionMarker(marker);
            if (curVersion > 0 && curVersion != prevVersion) {
                File webViewCache = new File(appCtx.getCacheDir(), "WebView");
                if (webViewCache.isDirectory()) {
                    deleteRecursive(new File(webViewCache, "HTTP Cache"));
                    deleteRecursive(new File(new File(webViewCache, "Default"), "HTTP Cache"));
                }
                AtomicFileWriter.writeText(marker, curVersion + "\n");
                Log.i(TAG, "Cleared stale WebView HTTP cache for updated versionCode=" + curVersion);
            }
        } catch (Throwable t) {
            Log.w(TAG, "WebView pre-init sanitizer warning: " + t.getMessage());
        }
    }

    private static void purgeServiceWorkerDirs(File profileDir) {
        File swDir = new File(profileDir, "Service Worker");
        if (swDir.exists()) {
            deleteRecursive(swDir);
            Log.i(TAG, "Removed stale WebView Service Worker dir: " + swDir.getAbsolutePath());
        }
        File cacheStorageDir = new File(profileDir, "CacheStorage");
        if (cacheStorageDir.exists()) {
            deleteRecursive(cacheStorageDir);
            Log.i(TAG, "Removed stale WebView CacheStorage dir: " + cacheStorageDir.getAbsolutePath());
        }
    }

    private static void purgeBucketedWebStorage(File webStorageRoot) {
        if (!webStorageRoot.isDirectory()) {
            return;
        }
        File[] buckets = webStorageRoot.listFiles();
        if (buckets == null) {
            return;
        }
        for (File bucket : buckets) {
            if (bucket != null && bucket.isDirectory()) {
                // Only remove Service Worker & CacheStorage inside storage buckets; preserve IndexedDB
                purgeServiceWorkerDirs(bucket);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static long getInstalledVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (pi == null) {
                return 0L;
            }
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode()
                    : pi.versionCode;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long readVersionMarker(File marker) {
        if (!marker.isFile()) {
            return -1L;
        }
        try (FileInputStream fis = new FileInputStream(marker)) {
            return Long.parseLong(IoUtil.readAll(fis, "UTF-8").trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static void deleteRecursive(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        target.delete();
    }
}
