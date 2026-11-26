package com.oc.ide.ui.webview;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.oc.ide.bridge.AndroidBridge;
import com.oc.ide.ui.MainActivity;
import com.oc.ide.util.LoopbackHost;

/** Routes console output to logcat, popups to PopupWindowHandler, file chooser, and origin-checked mic permissions. */
public final class AgyWebChromeClient extends WebChromeClient {

    private static final String TAG = "WebViewHost";

    public interface FileChooserCallback {
        boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                  FileChooserParams fileChooserParams);
    }

    private final PopupWindowHandler popupHandler;
    private final FileChooserCallback fileChooserCallback;
    private final AndroidBridge bridge;

    public AgyWebChromeClient(PopupWindowHandler popupHandler, FileChooserCallback fileChooserCallback,
                              AndroidBridge bridge) {
        this.popupHandler = popupHandler;
        this.fileChooserCallback = fileChooserCallback;
        this.bridge = bridge;
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler, FileChooserCallback fileChooserCallback) {
        this(popupHandler, fileChooserCallback, null);
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler) {
        this(popupHandler, null, null);
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage cm) {
        if (cm != null) {
            String msg = cm.message();
            String src = cm.sourceId();
            int line = cm.lineNumber();
            ConsoleMessage.MessageLevel lvl = cm.messageLevel();
            String tag = "AGY_JS";
            if (lvl == ConsoleMessage.MessageLevel.ERROR) {
                Log.e(tag, "[" + src + ":" + line + "] " + msg);
            } else if (lvl == ConsoleMessage.MessageLevel.WARNING) {
                Log.w(tag, "[" + src + ":" + line + "] " + msg);
            } else {
                Log.i(tag, "[" + src + ":" + line + "] " + msg);
            }
        }
        return true;
    }

    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        if (request == null) {
            return;
        }
        int enginePort = bridge != null ? bridge.getEnginePort() : -1;
        if (!LoopbackHost.isCoreOrigin(request.getOrigin(), enginePort)) {
            Log.w(TAG, "Denying WebView permission from non-core origin: " + request.getOrigin());
            request.deny();
            return;
        }
        boolean wantsAudio = false;
        String[] resources = request.getResources();
        if (resources != null) {
            for (String r : resources) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                    wantsAudio = true;
                    break;
                }
            }
        }
        if (!wantsAudio) {
            request.deny();
            return;
        }
        MainActivity act = MainActivity.activeActivity;
        if (act != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            act.requestWebViewAudioPermission(request);
            return;
        }
        try {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } catch (Throwable t) {
            Log.w(TAG, "onPermissionRequest grant failed: " + t.getMessage());
        }
    }

    @Override
    public boolean onCreateWindow(WebView parent, boolean isDialog, boolean isUserGesture,
                                  Message resultMsg) {
        return popupHandler.handle(parent, isDialog, isUserGesture, resultMsg);
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                      FileChooserParams fileChooserParams) {
        if (fileChooserCallback != null) {
            return fileChooserCallback.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }
        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
    }
}
