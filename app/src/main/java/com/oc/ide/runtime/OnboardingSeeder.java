package com.oc.ide.runtime;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.oc.ide.util.AtomicFileWriter;
import com.oc.ide.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Seeds OpenCode v2 configuration, service-stable.json password registration,
 * Android AGENTS.md rules, default workspace, and safe Git configuration.
 */
public final class OnboardingSeeder {

    private static final String TAG = "OnboardingSeeder";

    private OnboardingSeeder() {
    }

    public static void seed(Context appContext, File ocConfigDir, File projectsDir) {
        seed(appContext, ocConfigDir, projectsDir, RuntimeConfig.httpsPort(appContext), "");
    }

    public static void seed(Context appContext, File ocConfigDir, File projectsDir,
                            int port, String serverPassword) {
        try {
            File filesDir = appContext.getFilesDir();
            mkdirsQuiet(ocConfigDir);
            mkdirsQuiet(new File(ocConfigDir, "skills"));
            mkdirsQuiet(new File(ocConfigDir, "rules"));
            mkdirsQuiet(new File(ocConfigDir, "plugins"));
            mkdirsQuiet(new File(filesDir, ".local/share/opencode"));
            mkdirsQuiet(new File(filesDir, ".local/state/opencode"));
            mkdirsQuiet(new File(filesDir, ".cache/opencode"));
            mkdirsQuiet(new File(filesDir, "usr/tmp"));
            mkdirsQuiet(new File(filesDir, "workspace"));
            mkdirsQuiet(projectsDir);

            try {
                File extWs = new File(Environment.getExternalStorageDirectory(), "Documents/OpenCode");
                mkdirsQuiet(extWs);
            } catch (Throwable ignored) {
            }

            // 1. Seed deterministic service-stable.json so `opencode serve --service`
            //    uses our exact serverPassword AND CLI commands (`opencode reload`) auto-connect.
            if (serverPassword != null && !serverPassword.isEmpty()) {
                JSONObject svc = new JSONObject();
                svc.put("hostname", "127.0.0.1");
                svc.put("port", port);
                svc.put("password", serverPassword);
                AtomicFileWriter.writeText(new File(ocConfigDir, "service-stable.json"), svc.toString(2) + "\n");
            }

            // 2. Seed bundled Android AGENTS.md into ~/.config/opencode/AGENTS.md
            File agentsFile = new File(ocConfigDir, "AGENTS.md");
            seedBundledAgentsMd(appContext, agentsFile);

            // 3. Ensure ~/.config/opencode/opencode.json loads AGENTS.md + rules/*.md
            ensureOpenCodeConfigJson(ocConfigDir, agentsFile);

            // 4. Ensure ~/.gitconfig trusts Android shared storage (/sdcard/*)
            ensureGitSafeDirectory(filesDir);
        } catch (Exception e) {
            Log.w(TAG, "OnboardingSeeder notice: " + e.getMessage());
        }
    }

    private static void seedBundledAgentsMd(Context ctx, File dst) {
        try (InputStream in = ctx.getAssets().open(RuntimePaths.SEED_ASSET + "/AGENTS.md")) {
            String raw = IoUtil.readAll(in, "UTF-8");
            String pkg = ctx.getPackageName();
            if (!"com.oc.ide".equals(pkg)) {
                raw = raw.replace("com.oc.ide", pkg);
            }
            if (!dst.isFile() || dst.length() == 0) {
                AtomicFileWriter.writeText(dst, raw);
                return;
            }
            try (FileInputStream fis = new FileInputStream(dst)) {
                String existing = IoUtil.readAll(fis, "UTF-8");
                if (existing.contains("OpenCode Android Agent Rules") && !existing.equals(raw)) {
                    AtomicFileWriter.writeText(dst, raw);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot seed AGENTS.md: " + e.getMessage());
        }
    }

    private static void ensureOpenCodeConfigJson(File ocConfigDir, File agentsFile) {
        try {
            File cfgFile = new File(ocConfigDir, "opencode.json");
            JSONObject root = new JSONObject();
            if (cfgFile.isFile() && cfgFile.length() > 0) {
                try (FileInputStream fis = new FileInputStream(cfgFile)) {
                    String txt = IoUtil.readAll(fis, "UTF-8").trim();
                    if (!txt.isEmpty()) {
                        root = new JSONObject(txt);
                    }
                } catch (Exception ignored) {
                }
            }
            boolean dirty = false;
            if (!root.has("$schema")) {
                root.put("$schema", "https://opencode.ai/config.json");
                dirty = true;
            }
            JSONArray instructions = root.optJSONArray("instructions");
            if (instructions == null) {
                instructions = new JSONArray();
                root.put("instructions", instructions);
                dirty = true;
            }
            String agentsPath = agentsFile.getAbsolutePath();
            String rulesGlob = new File(ocConfigDir, "rules/*.md").getAbsolutePath();
            if (!jsonArrayContains(instructions, agentsPath)) {
                instructions.put(agentsPath);
                dirty = true;
            }
            if (!jsonArrayContains(instructions, rulesGlob)) {
                instructions.put(rulesGlob);
                dirty = true;
            }
            String skillsDirPath = new File(ocConfigDir, "skills").getAbsolutePath();
            JSONObject skillsObj = root.optJSONObject("skills");
            if (skillsObj == null) {
                JSONArray legacyArr = root.optJSONArray("skills");
                skillsObj = new JSONObject();
                JSONArray paths = legacyArr != null ? legacyArr : new JSONArray();
                skillsObj.put("paths", paths);
                root.put("skills", skillsObj);
                dirty = true;
            }
            JSONArray skillPaths = skillsObj.optJSONArray("paths");
            if (skillPaths == null) {
                skillPaths = new JSONArray();
                skillsObj.put("paths", skillPaths);
                dirty = true;
            }
            if (!jsonArrayContains(skillPaths, skillsDirPath)) {
                skillPaths.put(skillsDirPath);
                dirty = true;
            }
            if (dirty || !cfgFile.isFile()) {
                AtomicFileWriter.writeText(cfgFile, root.toString(2) + "\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureOpenCodeConfigJson notice: " + e.getMessage());
        }
    }

    private static boolean jsonArrayContains(JSONArray arr, String target) {
        for (int i = 0; i < arr.length(); i++) {
            if (target.equals(arr.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static void ensureGitSafeDirectory(File filesDir) {
        try {
            File gitConfig = new File(filesDir, ".gitconfig");
            if (!gitConfig.isFile()) {
                AtomicFileWriter.writeText(gitConfig, "[safe]\n\tdirectory = *\n");
            }
        } catch (Exception ignored) {
        }
    }

    private static void mkdirsQuiet(File dir) {
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
    }
}
