package com.oc.ide.util;

import android.net.Uri;

/** Loopback host & strict core origin verification for the local OpenCode engine. */
public final class LoopbackHost {

    private LoopbackHost() {
    }

    public static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.trim().toLowerCase();
        return "127.0.0.1".equals(lower) || "localhost".equals(lower);
    }

    public static boolean isLoopback(Uri uri) {
        return uri != null && isLoopback(uri.getHost());
    }

    /**
     * Returns true ONLY when {@code uri} targets the exact OpenCode loopback origin
     * ({@code http://127.0.0.1:<enginePort>} or {@code http://localhost:<enginePort>}).
     * Other local ports (e.g. user dev servers on :3000/:5173/:8080) return false.
     */
    public static boolean isCoreOrigin(Uri uri, int enginePort) {
        if (uri == null || enginePort <= 0) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        return isLoopback(uri.getHost()) && uri.getPort() == enginePort;
    }

    public static boolean isCoreOriginUrl(String url, int enginePort) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            return isCoreOrigin(Uri.parse(url), enginePort);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates {@code host} passed to {@code WebViewClient.onReceivedHttpAuthRequest},
     * which Chromium supplies either as {@code "127.0.0.1"} or {@code "127.0.0.1:<port>"}.
     */
    public static boolean isCoreAuthHost(String host, int enginePort) {
        if (host == null || host.isEmpty() || enginePort <= 0) {
            return false;
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            String h = host.substring(0, colon);
            String p = host.substring(colon + 1);
            try {
                return isLoopback(h) && Integer.parseInt(p) == enginePort;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return isLoopback(host);
    }
}
