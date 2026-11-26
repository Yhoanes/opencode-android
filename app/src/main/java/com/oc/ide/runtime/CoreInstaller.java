package com.oc.ide.runtime;

import android.content.Context;
import android.util.Log;

import com.oc.ide.install.EnvInstaller;
import com.oc.ide.util.AssetUnpacker;
import com.oc.ide.util.AtomicFileWriter;
import com.oc.ide.util.FileModeUtil;
import com.oc.ide.util.IoUtil;
import com.oc.ide.util.Sha256;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Phase 1: extract + verify the native Bionic OpenCode core runtime and Android web bridge. */
public final class CoreInstaller {

    private static final String TAG = "CoreInstaller";

    private CoreInstaller() {
    }

    public static void installCore(RuntimeManager rm) {
        Context ctx = rm.appContext();
        File runtimeDir = rm.getRuntimeDir();
        String expectedHash = getAssetManifestHash(ctx);
        boolean upToDate = isRuntimeUpToDate(rm, expectedHash);

        if (!upToDate) {
            Log.i(TAG, "extracting assets/runtime (first launch or runtime update)");
            try {
                wipe(rm, runtimeDir);
                AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.RUNTIME_ASSET, runtimeDir);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot unpack assets/runtime into " + runtimeDir + ": " + e.getMessage(), e);
            }
            FileModeUtil.recursiveSetReadableExecutable(rm.getBinDir());
            if (rm.getLibDir().isDirectory()) {
                FileModeUtil.recursiveSetReadableExecutable(rm.getLibDir());
            }
            String manifestError = ManifestVerifier.verifyError(ctx, runtimeDir);
            if (manifestError != null) {
                throw new IllegalStateException("runtime integrity check failed: " + manifestError);
            }
            writeMarker(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER, expectedHash);
        } else {
            Log.i(TAG, "runtime up to date (manifest hash matched); skipping extract");
        }

        try {
            AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.WEB_ASSET, rm.getWebDir());
            writeMarker(rm.getWebDir(), RuntimePaths.WEB_MARKER, "2");
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot unpack assets/web into " + rm.getWebDir() + ": " + e.getMessage(), e);
        }

        rm.writeNetworkConfig();
        rm.startNetworkMonitor();
        OnboardingSeeder.seed(ctx, rm.getOcConfigDir(), rm.getProjectsDir(), rm.getHttpsPort(), rm.getCsrfToken());
        ShellBridgeInstaller.installFallbackBridges(rm.getBinDir(), ctx);
        ShellBridgeInstaller.installOpenUrlShims(rm.getBinDir(), ctx);
        if (EnvInstaller.isInstalled(ctx)) {
            ShellBridgeInstaller.install(rm);
        }
    }

    private static boolean isRuntimeUpToDate(RuntimeManager rm, String expectedHash) {
        if (expectedHash == null || expectedHash.isEmpty()) {
            return false;
        }
        File marker = new File(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER);
        File core = new File(rm.getBinDir(), RuntimePaths.CORE_NAME);
        if (!marker.isFile() || !core.isFile()) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(marker)) {
            String actual = IoUtil.readAll(fis, "UTF-8").trim();
            return expectedHash.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getAssetManifestHash(Context ctx) {
        try (InputStream in = ctx.getAssets().open(RuntimePaths.MANIFEST_ASSET)) {
            return Sha256.of(in);
        } catch (Exception e) {
            Log.w(TAG, "cannot hash asset manifest: " + e.getMessage());
            return "";
        }
    }

    private static void writeMarker(File dir, String name, String content) {
        try {
            AtomicFileWriter.writeText(new File(dir, name), content + "\n");
        } catch (Exception e) {
            Log.w(TAG, "cannot write marker " + name + ": " + e.getMessage());
        }
    }

    private static void wipe(RuntimeManager rm, File dir) throws IOException {
        assertSafeToWipe(rm, dir);
        if (!dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                wipe(rm, child);
            }
        }
        if (!dir.delete()) {
            throw new IOException("cannot delete " + dir);
        }
    }

    private static void assertSafeToWipe(RuntimeManager rm, File dir) throws IOException {
        File files = rm.appContext().getFilesDir();
        String filesPath = files.getCanonicalPath();
        String target = dir.getCanonicalPath();
        if (!target.startsWith(filesPath + File.separator)) {
            throw new IOException("refusing to wipe outside filesDir: " + dir);
        }
        for (String protectedRel : new String[]{".config", ".local", ".gemini", "workspace"}) {
            String protPath = new File(files, protectedRel).getCanonicalPath();
            if (target.equals(protPath)
                    || target.startsWith(protPath + File.separator)
                    || protPath.startsWith(target + File.separator)) {
                throw new IOException("refusing to wipe user data under " + protectedRel + ": " + dir);
            }
        }
    }
}
