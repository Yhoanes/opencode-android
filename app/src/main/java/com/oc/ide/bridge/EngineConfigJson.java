package com.oc.ide.bridge;

import android.content.Context;

import com.oc.ide.runtime.RuntimeConfig;
import com.oc.ide.runtime.RuntimeManager;
import com.oc.ide.shell.ShizukuBridge;

import org.json.JSONObject;

/** Serializes the host configuration object exposed via Android.getConfig(). */
public final class EngineConfigJson {

    private final Context appContext;
    private final RuntimeManager runtime;
    private final EngineBridge engine;
    private final ShizukuBridge shizuku;
    private final ThemeBridge theme;

    public EngineConfigJson(Context appContext, RuntimeManager runtime, EngineBridge engine,
                            ShizukuBridge shizuku, ThemeBridge theme) {
        this.appContext = appContext;
        this.runtime = runtime;
        this.engine = engine;
        this.shizuku = shizuku;
        this.theme = theme;
    }

    public String get() {
        try {
            JSONObject o = new JSONObject();
            o.put("productName", "opencode-android");
            o.put("packageName", appContext.getPackageName());
            o.put("csrfToken", engine.getCsrfToken());
            o.put("appVersion", RuntimeConfig.CORE_VERSION);
            o.put("releaseChannel", "stable");
            o.put("devMode", appContext.getPackageName().endsWith(".dev"));
            o.put("enginePort", runtime.getHttpsPort());
            o.put("engineBaseUrl", engine.getBaseUrl());
            o.put("filesDir", appContext.getFilesDir().getAbsolutePath());
            o.put("runtimeDir", runtime.getRuntimeDir().getAbsolutePath());
            o.put("home", appContext.getFilesDir().getAbsolutePath());
            o.put("prefix", new java.io.File(appContext.getFilesDir(), "usr").getAbsolutePath());
            o.put("ocConfigDir", runtime.getOcConfigDir().getAbsolutePath());
            o.put("ocDataDir", runtime.getOcDataDir().getAbsolutePath());
            o.put("workspaceDir", runtime.getDefaultWorkspaceDir().getAbsolutePath());
            o.put("shizukuAvailable", shizuku.isAvailable());
            o.put("theme", theme.getTheme());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
