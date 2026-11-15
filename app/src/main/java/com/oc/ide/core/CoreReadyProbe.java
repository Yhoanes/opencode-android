package com.oc.ide.core;

import android.util.Base64;
import android.util.Log;

import com.oc.ide.util.IoUtil;
import com.oc.ide.util.ProcessUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Probes OpenCode v2's local HTTP server (/api/info) and mints a 30-day
 * WebView session cookie token via /api/pair -> /auth/connect/:code.
 */
public final class CoreReadyProbe {

    private static final String TAG = "CoreReadyProbe";
    public static final long READY_TIMEOUT_MS = 35000;
    private static final long RETRY_SLEEP_MS = 80;

    private CoreReadyProbe() {
    }

    public static String basicAuthHeader(String password) {
        String cred = "opencode:" + (password == null ? "" : password);
        return "Basic " + Base64.encodeToString(cred.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public static boolean probeOnce(int port, String password) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/api/info");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(350);
            conn.setReadTimeout(750);
            conn.setInstanceFollowRedirects(false);
            if (password != null && !password.isEmpty()) {
                conn.setRequestProperty("Authorization", basicAuthHeader(password));
            }
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status == 200) {
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        ProcessUtils.sleep(RETRY_SLEEP_MS);
        return false;
    }

    /**
     * Warms OpenCode's default external workspace (/storage/emulated/0/Documents/OpenCode)
     * so it is registered as an active location before the SolidJS UI opens.
     */
    public static void warmDefaultWorkspace(int port, String password, File workspaceDir) {
        if (workspaceDir == null) return;
        HttpURLConnection conn = null;
        try {
            if (!workspaceDir.isDirectory()) {
                workspaceDir.mkdirs();
            }
            String q = URLEncoder.encode(workspaceDir.getAbsolutePath(), "UTF-8");
            URL url = new URL("http://127.0.0.1:" + port + "/api/project?directory=" + q);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(2000);
            if (password != null && !password.isEmpty()) {
                conn.setRequestProperty("Authorization", basicAuthHeader(password));
            }
            conn.setRequestProperty("Accept", "application/json");
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Exchanges the deterministic Basic-Auth password for an HttpOnly WebView session
     * cookie token so the SolidJS UI opens immediately with zero password modal.
     */
    public static String mintSessionToken(int port, String password) {
        HttpURLConnection pairConn = null;
        HttpURLConnection connectConn = null;
        try {
            URL pairUrl = new URL("http://127.0.0.1:" + port + "/api/pair");
            pairConn = (HttpURLConnection) pairUrl.openConnection();
            pairConn.setRequestMethod("POST");
            pairConn.setConnectTimeout(1500);
            pairConn.setReadTimeout(2500);
            pairConn.setDoOutput(true);
            if (password != null && !password.isEmpty()) {
                pairConn.setRequestProperty("Authorization", basicAuthHeader(password));
            }
            pairConn.setRequestProperty("Accept", "application/json");
            pairConn.setFixedLengthStreamingMode(0);
            if (pairConn.getResponseCode() != 200) {
                Log.w(TAG, "/api/pair returned HTTP " + pairConn.getResponseCode());
                return null;
            }
            String pairBody;
            try (InputStream in = pairConn.getInputStream()) {
                pairBody = IoUtil.readAll(in, "UTF-8");
            }
            String code = new JSONObject(pairBody).optString("code", "");
            if (code.isEmpty()) {
                return null;
            }

            URL connectUrl = new URL("http://127.0.0.1:" + port + "/auth/connect/" + code);
            connectConn = (HttpURLConnection) connectUrl.openConnection();
            connectConn.setRequestMethod("GET");
            connectConn.setConnectTimeout(1500);
            connectConn.setReadTimeout(2500);
            connectConn.setInstanceFollowRedirects(false);
            connectConn.setRequestProperty("Accept", "application/json");
            int codeStatus = connectConn.getResponseCode();
            if (codeStatus == 200) {
                try (InputStream in = connectConn.getInputStream()) {
                    String tokenBody = IoUtil.readAll(in, "UTF-8");
                    String token = new JSONObject(tokenBody).optString("token", "");
                    if (!token.isEmpty()) {
                        return token;
                    }
                }
            }
            String setCookie = connectConn.getHeaderField("Set-Cookie");
            if (setCookie != null) {
                String prefix = "opencode_session_" + port + "=";
                int idx = setCookie.indexOf(prefix);
                if (idx >= 0) {
                    int start = idx + prefix.length();
                    int semi = setCookie.indexOf(';', start);
                    return semi > start ? setCookie.substring(start, semi) : setCookie.substring(start);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "mintSessionToken failed: " + e.getMessage());
        } finally {
            if (pairConn != null) pairConn.disconnect();
            if (connectConn != null) connectConn.disconnect();
        }
        return null;
    }
}
