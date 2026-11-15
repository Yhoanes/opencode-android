package com.oc.ide.util;

/** Loopback URL builders for the local OpenCode v2 HTTP server. */
public final class CoreUrl {

    private CoreUrl() {
    }

    public static String baseUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    public static String startUrl(int port) {
        return baseUrl(port) + "/";
    }

    public static String startUrl(int port, String csrfToken) {
        return baseUrl(port) + "/";
    }
}
