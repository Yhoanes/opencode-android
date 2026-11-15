package com.oc.ide.oauth;

import com.oc.ide.BuildConfig;

/** OAuth/Custom-Tab constants for OpenCode Android (no androidx.browser dependency). */
public final class OAuthConstants {

    public static final String TAG = "OAuthManager";

    public static final String CHROME_PACKAGE = "com.android.chrome";

    public static final String EXTRA_SESSION = "android.support.customtabs.extra.SESSION";
    public static final String EXTRA_TOOLBAR_COLOR =
            "android.support.customtabs.extra.TOOLBAR_COLOR";
    public static final String EXTRA_APP_ID = "com.android.browser.application_id";
    public static final int TOOLBAR_COLOR_DARK = 0xFF080808;

    public static final String CALLBACK_SCHEME = BuildConfig.OAUTH_SCHEME;
    public static final String CALLBACK_HOST = "oauth-success";
    public static final String CALLBACK_HOST_COMPAT = "auth-success";

    /** OpenCode persists provider credentials in <filesDir>/.local/share/opencode/auth.json. */
    public static final String TOKEN_DIR = ".local/share/opencode";
    public static final String TOKEN_NAME = "auth.json";

    private OAuthConstants() {
    }
}
