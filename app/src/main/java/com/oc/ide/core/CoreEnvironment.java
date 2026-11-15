package com.oc.ide.core;

import com.oc.ide.install.EnvInstaller;
import com.oc.ide.runtime.RuntimeManager;

import java.io.File;
import java.util.Map;

/** Builds the clean native Bionic ProcessBuilder environment for OpenCode v2. */
public final class CoreEnvironment {

    private CoreEnvironment() {
    }

    public static void apply(RuntimeManager runtime, ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        File filesDir = runtime.appContext().getFilesDir();
        File bin = runtime.getBinDir();
        File cert = runtime.getCertFile();
        File ocConfig = runtime.getOcConfigDir();
        File ocData = runtime.getOcDataDir();
        File prefix = EnvInstaller.prefix(runtime.appContext());
        File prefixBin = new File(prefix, "bin");
        File tmpDir = new File(prefix, "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
        }
        File localBin = new File(filesDir, ".local/bin");
        if (!localBin.isDirectory()) {
            localBin.mkdirs();
        }

        env.put("HOME", filesDir.getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("XDG_CONFIG_HOME", new File(filesDir, ".config").getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(filesDir, ".local/share").getAbsolutePath());
        env.put("XDG_STATE_HOME", new File(filesDir, ".local/state").getAbsolutePath());
        env.put("XDG_CACHE_HOME", new File(filesDir, ".cache").getAbsolutePath());
        env.put("OPENCODE_CONFIG_DIR", ocConfig.getAbsolutePath());
        env.put("OPENCODE_SERVER_USERNAME", "opencode");
        env.put("OPENCODE_SERVER_PASSWORD", runtime.getCsrfToken());
        env.put("OPENCODE_FILEWATCHER_DISABLE", "1");
        env.put("OPENCODE_DISABLE_FILEWATCHER", "1");
        env.put("OPENCODE_DISABLE_AUTOUPDATE", "1");
        env.put("GHIDRA_HEADLESS_MAXMEM", "768M");
        env.put("ANTIGRAVITY_APP_DATA_DIR", ocData.getAbsolutePath());

        env.put("SSL_CERT_FILE", cert.getAbsolutePath());
        env.put("SSL_CERT_DIR", cert.getParentFile().getAbsolutePath());
        env.put("CURL_CA_BUNDLE", cert.getAbsolutePath());
        env.put("GIT_SSL_CAINFO", cert.getAbsolutePath());
        env.put("NODE_EXTRA_CA_CERTS", cert.getAbsolutePath());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        File gitCore = new File(prefix, "libexec/git-core");
        if (gitCore.isDirectory()) {
            env.put("GIT_EXEC_PATH", gitCore.getAbsolutePath());
        }

        File opensslCnf = new File(prefix, "etc/tls/openssl.cnf");
        if (opensslCnf.isFile()) {
            env.put("OPENSSL_CONF", opensslCnf.getAbsolutePath());
        }

        String sysPath = env.get("PATH");
        String basePath = prefixBin.getAbsolutePath()
                + File.pathSeparator + bin.getAbsolutePath()
                + File.pathSeparator + localBin.getAbsolutePath();
        env.put("PATH", (sysPath == null || sysPath.isEmpty())
                ? basePath + ":/system/bin"
                : basePath + File.pathSeparator + sysPath);

        env.put("SHELL", new File(bin, "bash").getAbsolutePath());
        env.put("BROWSER", new File(bin, "xdg-open").getAbsolutePath());
        String chromeShim = new File(bin, "chrome").getAbsolutePath();
        env.put("CHROME_PATH", chromeShim);
        env.put("PUPPETEER_EXECUTABLE_PATH", chromeShim);
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("JDK_JAVA_OPTIONS",
                "-Duser.home=" + filesDir.getAbsolutePath() + " -Djava.io.tmpdir=" + tmpDir.getAbsolutePath());

        // Keep OpenCode's own process free of Termux LD_PRELOAD/LD_LIBRARY_PATH so Bun's
        // embedded opencode-pty and /system/bin utilities run cleanly; ShellBridgeInstaller
        // configures LD_LIBRARY_PATH/LD_PRELOAD per-tool inside $PREFIX/bin bridges.
        env.remove("LD_LIBRARY_PATH");
        env.remove("LD_PRELOAD");
    }
}
