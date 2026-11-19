package com.oc.ide.util;

/** Loopback URL builders for the local OpenCode v2 HTTP server and URL redaction helpers. */
public final class CoreUrl {

    private CoreUrl() {
    }

    public static String baseUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    public static String startUrl(int port) {
        return baseUrl(port) + "/";
    }

    /**
     * Strips query strings (?...) and fragments (#...) to prevent logging
     * sensitive capability tokens, session IDs, or OAuth secrets into logcat.
     */
    public static String stripUrlSecrets(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int cut = -1;
        if (q != -1 && h != -1) {
            cut = Math.min(q, h);
        } else if (q != -1) {
            cut = q;
        } else if (h != -1) {
            cut = h;
        }
        return cut != -1 ? (url.substring(0, cut) + "[redacted]") : url;
    }

    public static String stripUrlSecrets(Object uriOrUrl) {
        if (uriOrUrl == null) return "";
        return stripUrlSecrets(uriOrUrl.toString());
    }
}

