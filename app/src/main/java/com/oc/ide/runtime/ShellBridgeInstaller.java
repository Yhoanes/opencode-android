package com.oc.ide.runtime;

import android.content.Context;
import android.util.Log;

import com.oc.ide.install.EnvInstaller;
import com.oc.ide.util.ShellQuote;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Materializes the native Bionic bridges in getFilesDir()/runtime/bin for OpenCode.
 * Rewrites 31-byte "/data/data/com.termux/files/usr" ELF paths in-place to
 * "/data/data/com.oc.ide/files/usr" (or "/data/data/com.oc.dev/files/usr").
 */
public final class ShellBridgeInstaller {

    private static final String TAG = "ShellBridgeInstaller";

    private static final String[] OPEN_URL_SHIMS = {
            "xdg-open", "open", "open_helper", "sensible-browser",
            "x-www-browser", "gnome-open", "termux-open", "termux-open-url"
    };

    private static final String[] BROWSER_SHIMS = {
            "google-chrome", "chrome", "chromium", "chromium-browser"
    };

    private static final String[] SPECIAL_SHIMS = {
            "agy-node", "oc-node"
    };

    private static final String BRIDGE_MARKER = ".oc-bridges";
    private static final int BRIDGE_FORMAT = 14;

    private ShellBridgeInstaller() {
    }

    private static String buildLdPreloadSnippet() {
        return "if [ -f \"$PREFIX/lib/libagy-go-dns.so\" ] && [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n"
                + "  export LD_PRELOAD=\"$PREFIX/lib/libagy-go-dns.so:$PREFIX/lib/libtermux-exec.so\"\n"
                + "elif [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n"
                + "  export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n"
                + "elif [ -f \"$PREFIX/lib/libagy-go-dns.so\" ]; then\n"
                + "  export LD_PRELOAD=\"$PREFIX/lib/libagy-go-dns.so\"\n"
                + "else\n"
                + "  unset LD_PRELOAD\n"
                + "fi\n";
    }

    public static synchronized void installFallbackBridges(File bin) {
        installFallbackBridges(bin, null);
    }

    public static synchronized void installFallbackBridges(File bin, Context context) {
        if (bin == null) return;
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            Log.w(TAG, "cannot create " + bin + "; skipping fallback bridges");
            return;
        }
        String fallbackShell = "#!/system/bin/sh\nexec /system/bin/sh \"$@\"\n";
        File bash = new File(bin, "bash");
        if (!bash.isFile()) {
            writeScript(bash, fallbackShell);
        }
        File sh = new File(bin, "sh");
        if (!sh.isFile()) {
            writeScript(sh, fallbackShell);
        }
        writeNodeShim(bin, context);
        writeTorchShims(bin, context);
    }

    private static void writeNodeShim(File bin, Context context) {
        File filesDir = (context != null) ? context.getFilesDir() : bin.getParentFile().getParentFile();
        File prefix = new File(filesDir, "usr");
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        String h = ShellQuote.quote(filesDir.getAbsolutePath());
        String rbin = bin.getAbsolutePath();
        String script = "#!/system/bin/sh\n"
                + "# OpenCode node wrapper for MCP plugins\n"
                + "PREFIX=" + p + "\n"
                + "export PREFIX\n"
                + "export HOME=" + h + "\n"
                + "export OPENCODE_CONFIG_DIR=\"$HOME/.config/opencode\"\n"
                + "export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.local/share/opencode\"\n"
                + "export TMPDIR=\"$PREFIX/tmp\"\n"
                + buildLdPreloadSnippet()
                + "export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n"
                + "export PATH=\"$PREFIX/bin:" + rbin + ":$HOME/.local/bin:/system/bin\"\n"
                + "export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n"
                + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                + "export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n"
                + "if [ -x \"$PREFIX/bin/node\" ]; then\n"
                + "  exec \"$PREFIX/bin/node\" \"$@\"\n"
                + "fi\n"
                + "echo \"node: nodejs is not installed in Android userspace (run: pkg install nodejs)\" >&2\n"
                + "exit 127\n";
        writeScript(new File(bin, "agy-node"), script);
        writeScript(new File(bin, "oc-node"), script);
    }

    private static void ensureGoDnsPreloadLib(Context context, File prefix) {
        if (context == null || prefix == null) return;
        File libDir = new File(prefix, "lib");
        if (!libDir.isDirectory() && !libDir.mkdirs()) return;
        File dst = new File(libDir, "libagy-go-dns.so");
        try (InputStream in = context.getAssets().open("env/overlay/lib/libagy-go-dns.so")) {
            byte[] buf = new byte[16384];
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            byte[] raw = bos.toByteArray();
            if (raw.length > 0 && (!dst.isFile() || dst.length() != raw.length)) {
                File tmp = new File(libDir, "libagy-go-dns.so.tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(raw);
                }
                tmp.setReadable(true, false);
                tmp.setExecutable(true, false);
                try {
                    android.system.Os.chmod(tmp.getAbsolutePath(), 0755);
                } catch (Throwable ignored) {
                }
                if (!tmp.renameTo(dst)) {
                    dst.delete();
                    tmp.renameTo(dst);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureGoDnsPreloadLib notice: " + t.getMessage());
        }
    }

    public static synchronized void writeTorchShims(File bin, Context context) {
        String pkg = (context != null) ? context.getPackageName() : "com.oc.ide";
        String script = "#!/system/bin/sh\n"
                + "STATE=\"$1\"\n"
                + "[ -z \"$STATE\" ] && STATE=\"on\"\n"
                + "ENABLE=\"true\"\n"
                + "if [ \"$STATE\" = \"off\" ] || [ \"$STATE\" = \"0\" ] || [ \"$STATE\" = \"false\" ]; then\n"
                + "  ENABLE=\"false\"\n"
                + "fi\n"
                + "if [ -x /system/bin/cmd ]; then\n"
                + "  /system/bin/cmd camera set-torch-mode 0 \"$STATE\" >/dev/null 2>&1 || true\n"
                + "fi\n"
                + "if [ -x /system/bin/am ]; then\n"
                + "  /system/bin/am broadcast -a com.oc.ide.ACTION_TORCH -p " + pkg + " --ez enabled \"$ENABLE\" --es state \"$STATE\" >/dev/null 2>&1 || true\n"
                + "fi\n"
                + "echo \"Flashlight set to $STATE\"\n"
                + "exit 0\n";
        writeScript(new File(bin, "torch"), script);
        writeScript(new File(bin, "termux-torch"), script);
        writeScript(new File(bin, "termux-camera-torch"), script);
    }

    public static synchronized void installOpenUrlShims(File bin) {
        installOpenUrlShims(bin, null);
    }

    public static synchronized void installOpenUrlShims(File bin, Context context) {
        if (bin == null) return;
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            return;
        }
        for (String opener : OPEN_URL_SHIMS) {
            writeOpenUrlShim(new File(bin, opener), context);
        }
        for (String browser : BROWSER_SHIMS) {
            writeBrowserShim(new File(bin, browser), context);
        }
        if (context != null) {
            File prefixBin = new File(context.getFilesDir(), "usr/bin");
            if (prefixBin.isDirectory()) {
                for (String opener : OPEN_URL_SHIMS) {
                    File pf = new File(prefixBin, opener);
                    pf.delete();
                    writeOpenUrlShim(pf, context);
                }
            }
        }
    }

    public static synchronized void install(RuntimeManager rm) {
        install(rm, false);
    }

    public static synchronized void install(RuntimeManager rm, boolean force) {
        File bin = rm.getBinDir();
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            return;
        }
        Context ctx = rm.appContext();
        String pkg = ctx.getPackageName();
        File prefix = EnvInstaller.prefix(ctx);
        ensureGoDnsPreloadLib(ctx, prefix);

        installFallbackBridges(bin, ctx);
        installOpenUrlShims(bin, ctx);

        File workspaceDir = new File(prefix.getParentFile(), "workspace");
        if (!workspaceDir.isDirectory()) {
            workspaceDir.mkdirs();
        }
        File prefixBash = new File(prefix, "bin/bash");
        if (!prefixBash.isFile()) {
            return;
        }
        writeOcBashrc(prefix, bin);
        patchTermuxBinaryPrefixInPlace(prefixBash, pkg);
        patchTermuxBinaryPrefixInPlace(new File(prefix, "lib/libtermux-exec.so"), pkg);
        writeBridge(new File(bin, "bash"), prefix, "bash");
        writeBridge(new File(bin, "sh"), prefix, "bash");
        writeNpmShims(bin, prefix);

        File[] prefixTools = new File(prefix, "bin").listFiles();
        int toolCount = countFiles(prefixTools);
        File marker = new File(bin, BRIDGE_MARKER);
        if (!force && markerMatches(marker, prefix, toolCount)) {
            return;
        }
        if (prefixTools != null) {
            for (File toolFile : prefixTools) {
                if (toolFile.isFile()) {
                    String name = toolFile.getName();
                    if ("rish".equals(name) || "sh".equals(name) || "bash".equals(name)
                            || RuntimePaths.CORE_NAME.equals(name) || isSpecialShim(name)) {
                        continue;
                    }
                    if (!"agy-box".equals(name) && !"oc-box".equals(name)) {
                        patchTermuxBinaryPrefixInPlace(toolFile, pkg);
                    }
                    writeBridge(new File(bin, name), prefix, name);
                }
            }
        }
        File[] prefixLibs = new File(prefix, "lib").listFiles();
        if (prefixLibs != null) {
            for (File libFile : prefixLibs) {
                if (libFile.isFile() && libFile.getName().contains(".so")
                        && !"libagy-go-dns.so".equals(libFile.getName())) {
                    patchTermuxBinaryPrefixInPlace(libFile, pkg);
                }
            }
        }
        writeMarker(marker, prefix, toolCount);
    }

    private static int countFiles(File[] files) {
        int n = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) n++;
            }
        }
        return n;
    }

    private static boolean markerMatches(File marker, File prefix, int toolCount) {
        if (!marker.isFile()) return false;
        try (InputStream in = new FileInputStream(marker)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            String content = n > 0 ? new String(buf, 0, n, Charset.forName("UTF-8")) : "";
            return content.equals(markerContent(prefix, toolCount));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMarker(File marker, File prefix, int toolCount) {
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(markerContent(prefix, toolCount).getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            Log.w(TAG, "cannot write bridge marker " + marker + ": " + e.getMessage());
        }
    }

    private static String markerContent(File prefix, int toolCount) {
        return BRIDGE_FORMAT + "\n" + prefix.getAbsolutePath() + "\n" + toolCount + "\n";
    }

    private static boolean isSpecialShim(String name) {
        for (String s : OPEN_URL_SHIMS) if (s.equals(name)) return true;
        for (String s : BROWSER_SHIMS) if (s.equals(name)) return true;
        for (String s : SPECIAL_SHIMS) if (s.equals(name)) return true;
        return false;
    }

    private static void writeOpenUrlShim(File bridge, Context context) {
        String pkg = (context != null) ? context.getPackageName() : "com.oc.ide";
        File filesDir = (context != null) ? context.getFilesDir() : bridge.getParentFile().getParentFile();
        String queuePath = ShellQuote.quote(new File(filesDir, ".open_url_queue").getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("unset LD_PRELOAD\n");
        sb.append("unset LD_LIBRARY_PATH\n");
        sb.append("URL=\"\"\n");
        sb.append("for arg in \"$@\"; do\n");
        sb.append("  case \"$arg\" in\n");
        sb.append("    -*) ;;\n");
        sb.append("    *) URL=\"$arg\" ;;\n");
        sb.append("  esac\n");
        sb.append("done\n");
        sb.append("[ -z \"$URL\" ] && URL=\"$1\"\n");
        sb.append("[ -z \"$URL\" ] && exit 0\n");
        sb.append("echo \"OC_OPEN_URL=$URL\"\n");
        sb.append("printf '%s\\n' \"$URL\" >> ").append(queuePath).append(" 2>/dev/null || true\n");
        sb.append("if [ -x /system/bin/am ]; then\n");
        sb.append("  /system/bin/am broadcast -a com.oc.ide.ACTION_OPEN_URL -p ").append(pkg).append(" --es url \"$URL\" >/dev/null 2>&1 || true\n");
        if (!"com.oc.ide".equals(pkg)) {
            sb.append("  /system/bin/am broadcast -a com.oc.ide.ACTION_OPEN_URL -p com.oc.ide --es url \"$URL\" >/dev/null 2>&1 || true\n");
        }
        sb.append("  /system/bin/am start -a android.intent.action.VIEW -d \"$URL\" >/dev/null 2>&1 || true\n");
        sb.append("fi\n");
        sb.append("exit 0\n");
        writeScript(bridge, sb.toString());
    }

    private static void writeBrowserShim(File bridge, Context context) {
        File filesDir = (context != null) ? context.getFilesDir() : bridge.getParentFile().getParentFile();
        File prefix = new File(filesDir, "usr");
        File runtimeBinDir = bridge.getParentFile();
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        String h = ShellQuote.quote(filesDir.getAbsolutePath());
        String rbin = runtimeBinDir != null ? runtimeBinDir.getAbsolutePath() : "";
        String xdgOpenPath = ShellQuote.quote(new File(runtimeBinDir, "xdg-open").getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("PREFIX=").append(p).append('\n');
        sb.append("HOME=").append(h).append('\n');
        sb.append("WANT_HEADLESS=0\n");
        sb.append("for arg in \"$@\"; do\n");
        sb.append("  case \"$arg\" in\n");
        sb.append("    --headless*|--remote-debugging*|--user-data-dir*|--dump-dom*|--screenshot*|--version|--product-version)\n");
        sb.append("      WANT_HEADLESS=1\n");
        sb.append("      break\n");
        sb.append("      ;;\n");
        sb.append("  esac\n");
        sb.append("done\n");
        sb.append("if [ \"$WANT_HEADLESS\" = \"1\" ]; then\n");
        sb.append("  REAL_CHROME=\"\"\n");
        sb.append("  for cand in \"$PREFIX/bin/chromium\" \"$PREFIX/bin/chromium-browser\" \"$PREFIX/bin/google-chrome\"; do\n");
        sb.append("    if [ -x \"$cand\" ]; then\n");
        sb.append("      REAL_CHROME=\"$cand\"\n");
        sb.append("      break\n");
        sb.append("    fi\n");
        sb.append("  done\n");
        sb.append("  if [ -n \"$REAL_CHROME\" ]; then\n");
        sb.append("    export PREFIX HOME\n");
        sb.append(buildLdPreloadSnippet());
        sb.append("    export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("    export PATH=\"$PREFIX/bin:").append(rbin).append(":$HOME/.local/bin:/system/bin\"\n");
        sb.append("    export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("    export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("    export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("    exec \"$REAL_CHROME\" --no-sandbox --disable-gpu --disable-dev-shm-usage \"$@\"\n");
        sb.append("  fi\n");
        sb.append("  echo \"chrome: headless/CDP requested but Chromium is not installed. Run: pkg update && pkg install chromium\" >&2\n");
        sb.append("  exit 127\n");
        sb.append("fi\n");
        sb.append("exec ").append(xdgOpenPath).append(" \"$@\"\n");
        writeScript(bridge, sb.toString());
    }

    private static void writeOcBashrc(File prefix, File runtimeBinDir) {
        try {
            File etcDir = new File(prefix, "etc");
            if (!etcDir.isDirectory()) etcDir.mkdirs();
            File tmpDir = new File(prefix, "tmp");
            if (!tmpDir.isDirectory()) tmpDir.mkdirs();
            String p = ShellQuote.quote(prefix.getAbsolutePath());
            String rbin = runtimeBinDir != null ? runtimeBinDir.getAbsolutePath() : "";
            String chromePath = runtimeBinDir != null
                    ? ShellQuote.quote(new File(runtimeBinDir, "chrome").getAbsolutePath()) : "\"chrome\"";
            String rc = "# OpenCode Terminal Environment\n"
                    + "export PREFIX=" + p + "\n"
                    + "export OPENCODE_CONFIG_DIR=\"$HOME/.config/opencode\"\n"
                    + "export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.local/share/opencode\"\n"
                    + "export GIT_CONFIG_NOSYSTEM=\"1\"\n"
                    + "export TMPDIR=\"$PREFIX/tmp\"\n"
                    + "export TERM=\"xterm-256color\"\n"
                    + "export COLORTERM=\"truecolor\"\n"
                    + "export LANG=\"C.UTF-8\"\n"
                    + "export LC_ALL=\"C.UTF-8\"\n"
                    + "export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n"
                    + buildLdPreloadSnippet()
                    + "export PATH=\"$PREFIX/bin:" + rbin + ":$HOME/.local/bin:/system/bin\"\n"
                    + "export CHROME_PATH=" + chromePath + "\n"
                    + "export PUPPETEER_EXECUTABLE_PATH=" + chromePath + "\n"
                    + "export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n"
                    + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                    + "export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n"
                    + "export JDK_JAVA_OPTIONS=\"-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp\"\n"
                    + "PS1='\\[\\033[01;32m\\]opencode\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '\n";
            writeScript(new File(etcDir, "oc.bashrc"), rc);
            writeScript(new File(etcDir, "agy.bashrc"), rc);
            writeScript(new File(etcDir, "profile"), rc);
            writeScript(new File(etcDir, "bash.bashrc"), rc);
            File homeDir = prefix.getParentFile();
            if (homeDir != null && homeDir.isDirectory()) {
                writeScript(new File(homeDir, ".bashrc"), rc);
                writeScript(new File(homeDir, ".profile"), rc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "writeOcBashrc notice: " + t.getMessage());
        }
    }

    /**
     * Replaces 31-byte "/data/data/com.termux/files/usr" (or "com.oc.ide" when running dev pkg)
     * in-place inside ELF binaries using an atomic temp-file swap.
     */
    private static void patchTermuxBinaryPrefixInPlace(File file, String pkg) {
        if (file == null || !file.isFile() || pkg == null || pkg.length() > 10) return;
        long len = file.length();
        if (len < 64 || len > 16 * 1024 * 1024) return;
        StringBuilder padded = new StringBuilder(pkg);
        while (padded.length() < 10) {
            padded.append('/');
        }
        String replStr = "/data/data/" + padded + "/files/usr";
        if (replStr.length() != 31) return;

        try {
            byte[] data = new byte[(int) len];
            try (InputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            if (data[0] != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
                return;
            }
            byte[] replacement = replStr.getBytes(Charset.forName("US-ASCII"));
            String[] foreignPrefixes = new String[]{
                    "/data/data/com.termux/files/usr",
                    "/data/data/com.agy////files/usr",
                    !"com.oc.ide".equals(pkg) ? "/data/data/com.oc.ide/files/usr" : null
            };
            boolean modified = false;
            for (String needleStr : foreignPrefixes) {
                if (needleStr == null) continue;
                byte[] needle = needleStr.getBytes(Charset.forName("US-ASCII"));
                int limit = data.length - needle.length;
                for (int i = 0; i <= limit; i++) {
                    if (data[i] == needle[0] && data[i + 11] == 'c') {
                        boolean match = true;
                        for (int j = 0; j < needle.length; j++) {
                            if (data[i + j] != needle[j]) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            System.arraycopy(replacement, 0, data, i, replacement.length);
                            modified = true;
                            i += needle.length - 1;
                        }
                    }
                }
            }
            if (!modified) return;

            File tmp = new File(file.getParentFile(), file.getName() + ".octmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            tmp.setReadable(true, false);
            tmp.setExecutable(true, false);
            try {
                android.system.Os.chmod(tmp.getAbsolutePath(), 0755);
            } catch (Throwable ignored) {
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Throwable t) {
            Log.w(TAG, "patchTermuxBinaryPrefixInPlace notice for " + file.getName() + ": " + t.getMessage());
        }
    }

    private static void writeNpmShims(File runtimeBinDir, File prefix) {
        try {
            File prefixBin = new File(prefix, "bin");
            if (!prefixBin.isDirectory()) prefixBin.mkdirs();
            String p = ShellQuote.quote(prefix.getAbsolutePath());
            File filesDir = prefix.getParentFile();
            String h = ShellQuote.quote(filesDir != null ? filesDir.getAbsolutePath() : prefix.getAbsolutePath());
            String rbin = runtimeBinDir != null ? runtimeBinDir.getAbsolutePath() : "";
            String chromePath = runtimeBinDir != null
                    ? ShellQuote.quote(new File(runtimeBinDir, "chrome").getAbsolutePath()) : "\"chrome\"";

            for (String tool : new String[]{"npm", "npx"}) {
                String cliJs = "npm".equals(tool) ? "npm-cli.js" : "npx-cli.js";
                String script = "#!/system/bin/sh\n"
                        + "PREFIX=" + p + "\n"
                        + "export PREFIX\n"
                        + "export HOME=" + h + "\n"
                        + "export OPENCODE_CONFIG_DIR=\"$HOME/.config/opencode\"\n"
                        + "export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.local/share/opencode\"\n"
                        + "export TMPDIR=\"$PREFIX/tmp\"\n"
                        + buildLdPreloadSnippet()
                        + "export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n"
                        + "export PATH=\"$PREFIX/bin:" + rbin + ":$HOME/.local/bin:/system/bin\"\n"
                        + "export CHROME_PATH=" + chromePath + "\n"
                        + "export PUPPETEER_EXECUTABLE_PATH=" + chromePath + "\n"
                        + "export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n"
                        + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                        + "export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n"
                        + "NPM_CLI=\"$PREFIX/lib/node_modules/npm/bin/" + cliJs + "\"\n"
                        + "if [ ! -f \"$NPM_CLI\" ] && [ -x \"$PREFIX/bin/node\" ] && [ -x \"$PREFIX/bin/curl\" ]; then\n"
                        + "  \"$PREFIX/bin/mkdir\" -p \"$PREFIX/lib/node_modules/npm\" \"$PREFIX/tmp\" 2>/dev/null || true\n"
                        + "  \"$PREFIX/bin/curl\" -fsSL --cacert \"$PREFIX/etc/tls/cert.pem\" \"https://registry.npmjs.org/npm/-/npm-10.9.2.tgz\" -o \"$PREFIX/tmp/npm.tgz\" 2>/dev/null \\\n"
                        + "    && \"$PREFIX/bin/tar\" -xzf \"$PREFIX/tmp/npm.tgz\" -C \"$PREFIX/lib/node_modules/npm\" --strip-components=1 2>/dev/null \\\n"
                        + "    && \"$PREFIX/bin/rm\" -f \"$PREFIX/tmp/npm.tgz\" 2>/dev/null || true\n"
                        + "fi\n"
                        + "if [ -f \"$NPM_CLI\" ] && [ -x \"$PREFIX/bin/node\" ]; then\n"
                        + "  exec \"$PREFIX/bin/node\" \"$NPM_CLI\" \"$@\"\n"
                        + "fi\n"
                        + "echo \"npm: node is not installed yet (run: pkg install nodejs)\" >&2\n"
                        + "exit 127\n";
                writeScript(new File(runtimeBinDir, tool), script);
                writeScript(new File(prefixBin, tool), script);
            }
        } catch (Throwable t) {
            Log.w(TAG, "writeNpmShims notice: " + t.getMessage());
        }
    }

    private static void writeBridge(File bridge, File prefix, String target) {
        if ("npm".equals(target) || "npx".equals(target) || "analyzeHeadless".equals(target)
                || "craft-anything-cli".equals(target) || "craft-ghidra-setup".equals(target)
                || "agy-video-mcp".equals(target) || "agy-video-setup".equals(target)) {
            return;
        }
        if (!"bash".equals(target) && !"sh".equals(bridge.getName())) {
            File agyBox = new File(prefix, "bin/agy-box");
            if (agyBox.isFile()) {
                try {
                    android.system.Os.lstat(bridge.getAbsolutePath());
                    bridge.delete();
                } catch (Throwable ignored) {
                }
                try {
                    android.system.Os.symlink(agyBox.getAbsolutePath(), bridge.getAbsolutePath());
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "agy-box symlink fallback for " + target + ": " + t.getMessage());
                }
            }
        }
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        File filesDir = prefix.getParentFile();
        String h = ShellQuote.quote(filesDir != null ? filesDir.getAbsolutePath() : prefix.getAbsolutePath());
        File runtimeBinDir = bridge.getParentFile();
        String rbin = runtimeBinDir != null ? runtimeBinDir.getAbsolutePath() : "";
        String chromePath = runtimeBinDir != null
                ? ShellQuote.quote(new File(runtimeBinDir, "chrome").getAbsolutePath()) : "\"chrome\"";
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("PREFIX=").append(p).append('\n');
        sb.append("export PREFIX\n");
        sb.append("export HOME=").append(h).append('\n');
        sb.append("export OPENCODE_CONFIG_DIR=\"$HOME/.config/opencode\"\n");
        sb.append("export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.local/share/opencode\"\n");
        sb.append("export GIT_CONFIG_NOSYSTEM=\"1\"\n");
        sb.append("export TMPDIR=\"$PREFIX/tmp\"\n");
        sb.append("export JDK_JAVA_OPTIONS=\"-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp\"\n");
        sb.append("export TERM=\"xterm-256color\"\n");
        sb.append(buildLdPreloadSnippet());
        sb.append("export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("export PATH=\"$PREFIX/bin:").append(rbin).append(":$HOME/.local/bin:/system/bin\"\n");
        sb.append("export CHROME_PATH=").append(chromePath).append('\n');
        sb.append("export PUPPETEER_EXECUTABLE_PATH=").append(chromePath).append('\n');
        sb.append("export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        if ("bash".equals(target)) {
            sb.append("if [ -x \"$PREFIX/bin/bash\" ]; then\n");
            sb.append("  _HAS_RC=0\n");
            sb.append("  _HAS_C=0\n");
            sb.append("  for _a in \"$@\"; do\n");
            sb.append("    case \"$_a\" in\n");
            sb.append("      --rcfile*|--init-file*|--norc) _HAS_RC=1 ;;\n");
            sb.append("      -c|-lc|-cl) _HAS_C=1 ;;\n");
            sb.append("    esac\n");
            sb.append("  done\n");
            sb.append("  for _a in \"$@\"; do\n");
            sb.append("    shift\n");
            sb.append("    case \"$_a\" in\n");
            sb.append("      -l|--login) ;;\n");
            sb.append("      -lc|-cl) set -- \"$@\" \"-c\" ;;\n");
            sb.append("      -il|-li) set -- \"$@\" \"-i\" ;;\n");
            sb.append("      *) set -- \"$@\" \"$_a\" ;;\n");
            sb.append("    esac\n");
            sb.append("  done\n");
            sb.append("  if [ \"$_HAS_C\" = \"1\" ] || [ \"$_HAS_RC\" = \"1\" ]; then\n");
            sb.append("    exec \"$PREFIX/bin/bash\" --noprofile \"$@\"\n");
            sb.append("  else\n");
            sb.append("    exec \"$PREFIX/bin/bash\" --noprofile --rcfile \"$PREFIX/etc/oc.bashrc\" \"$@\"\n");
            sb.append("  fi\n");
            sb.append("else\n");
            sb.append("  exec /system/bin/sh \"$@\"\n");
            sb.append("fi\n");
        } else {
            sb.append("if [ -x \"$PREFIX/bin/").append(target).append("\" ]; then\n");
            sb.append("  exec \"$PREFIX/bin/").append(target).append("\" \"$@\"\n");
            sb.append("else\n");
            sb.append("  exec /system/bin/sh \"$@\"\n");
            sb.append("fi\n");
        }
        writeScript(bridge, sb.toString());
    }

    private static void writeScript(File bridge, String script) {
        try {
            android.system.Os.lstat(bridge.getAbsolutePath());
            bridge.delete();
        } catch (Throwable ignored) {
        }
        try (OutputStream out = new FileOutputStream(bridge)) {
            out.write(script.getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            Log.w(TAG, "cannot write bridge " + bridge + ": " + e.getMessage());
            return;
        }
        bridge.setReadable(true, false);
        bridge.setExecutable(true, false);
        try {
            android.system.Os.chmod(bridge.getAbsolutePath(), 0755);
        } catch (Throwable ignored) {
        }
    }
}
