package com.oc.ide.runtime;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/** Resolves the app-private runtime/web/user-state directories and asset names for OpenCode. */
public final class RuntimePaths {

    public static final String RUNTIME_ASSET = "runtime";
    public static final String WEB_ASSET = "web";
    public static final String MANIFEST_ASSET = RUNTIME_ASSET + "/manifest.json";
    public static final String SEED_ASSET = RUNTIME_ASSET + "/seed";
    /** Written after runtime extraction + manifest verification. Skips re-extract. */
    public static final String RUNTIME_MARKER = ".installed";
    public static final String WEB_MARKER = ".web_installed";

    public static final String BIN_DIR = "runtime/bin";
    public static final String LIB_DIR = "runtime/lib";
    public static final String CERT_REL = "runtime/certs/ca-certificates.crt";
    public static final String CORE_NAME = "opencode";

    public static final String OC_CONFIG_REL = ".config/opencode";
    public static final String OC_DATA_REL = ".local/share/opencode";
    public static final String GEMINI_DIR = ".gemini";
    public static final String APP_DATA_DIR_NAME = "opencode-app";
    public static final String CONFIG_PROJECTS = "projects";

    private final Context appContext;

    public RuntimePaths(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public File getRuntimeDir() {
        return new File(appContext.getFilesDir(), RUNTIME_ASSET);
    }

    public File getWebDir() {
        return new File(appContext.getFilesDir(), WEB_ASSET);
    }

    public File getBinDir() {
        return new File(appContext.getFilesDir(), BIN_DIR);
    }

    public File getLibDir() {
        return new File(appContext.getFilesDir(), LIB_DIR);
    }

    public File getCertFile() {
        return new File(appContext.getFilesDir(), CERT_REL);
    }

    public File getOcConfigDir() {
        return new File(appContext.getFilesDir(), OC_CONFIG_REL);
    }

    public File getOcDataDir() {
        return new File(appContext.getFilesDir(), OC_DATA_REL);
    }

    public File getGeminiDir() {
        return new File(appContext.getFilesDir(), GEMINI_DIR);
    }

    public File getAppDataDir() {
        return getOcDataDir();
    }

    public File getProjectsDir() {
        return new File(getOcConfigDir(), CONFIG_PROJECTS);
    }

    public File getDefaultWorkspaceDir() {
        try {
            File extDocs = new File(Environment.getExternalStorageDirectory(), "Documents/OpenCode");
            if ((extDocs.isDirectory() || extDocs.mkdirs()) && extDocs.canWrite()) {
                return extDocs;
            }
        } catch (Throwable ignored) {
        }
        File internalWs = new File(appContext.getFilesDir(), "workspace");
        if (!internalWs.isDirectory()) {
            internalWs.mkdirs();
        }
        return internalWs;
    }
}
