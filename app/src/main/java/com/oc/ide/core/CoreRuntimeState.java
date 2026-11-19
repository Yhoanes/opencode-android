package com.oc.ide.core;

import com.oc.ide.runtime.RuntimeConfig;

/** Cross-component state shared by the foreground service, the JS bridge, and the WebView UI. */
public final class CoreRuntimeState {

    private CoreRuntimeState() {
    }

    /** Published once the OpenCode HTTP server answers on its loopback port and mints a session cookie. */
    public static volatile String serverUrl = null;
    public static volatile int httpsPort = RuntimeConfig.DEFAULT_HTTPS_PORT;
    public static volatile String csrfToken = null;
    /** 30-day session cookie token minted via /api/pair -> /auth/connect/:code for zero-prompt WebView auth. */
    public static volatile String sessionToken = null;

    /** Fail-closed startup error, surfaced to the UI instead of a hung splash. */
    public static volatile String lastError = null;
    /** True while an unexpected exit is being auto-restarted (transient, not fatal). */
    public static volatile boolean restarting = false;
    /** Bumped on every successful ready; the UI reloads the app when it changes. */
    public static volatile int readyGeneration = 0;
}
