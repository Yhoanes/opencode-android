package com.oc.ide.oauth;

import android.net.Uri;

import java.util.Locale;

/** Recognizes provider OAuth URLs that must open in Chrome Custom Tabs instead of WebView. */
public final class AuthUrlMatcher {

    public boolean isAuthUrl(String url) {
        if (url == null) {
            return false;
        }
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.US);
        String path = uri.getPath();
        if (path == null) {
            path = "";
        }

        if (host.equals("accounts.google.com") || host.endsWith(".accounts.google.com")
                || host.equals("accounts.googleusercontent.com")
                || host.equals("oauth2.googleapis.com")
                || host.equals("auth.openai.com")
                || host.equals("login.microsoftonline.com")) {
            return true;
        }
        if ((host.equals("google.com") || host.endsWith(".google.com"))
                && path.contains("/o/oauth2")) {
            return true;
        }
        if ((host.equals("claude.ai") || host.equals("console.anthropic.com"))
                && path.contains("/oauth")) {
            return true;
        }
        if (host.equals("github.com") && path.startsWith("/login/")) {
            return true;
        }
        if ((host.equals("opencode.ai") || host.endsWith(".opencode.ai"))
                && path.contains("/auth")) {
            return true;
        }
        return false;
    }
}
