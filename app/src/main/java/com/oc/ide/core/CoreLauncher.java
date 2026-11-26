package com.oc.ide.core;

import com.oc.ide.runtime.RuntimeManager;

import java.io.File;
import java.io.IOException;

/** Installs (phase 1 + phase 2) then launches the native Bionic OpenCode process. */
public final class CoreLauncher {

    private CoreLauncher() {
    }

    public static Process launch(RuntimeManager runtime, File filesDir) throws IOException {
        runtime.installCore();
        runtime.installEnvInBackground();
        File launchDir = runtime.getDefaultWorkspaceDir();
        if (launchDir == null || !launchDir.isDirectory()) {
            launchDir = filesDir;
        }
        ProcessBuilder pb = new ProcessBuilder(runtime.coreCommand());
        pb.directory(launchDir);
        pb.redirectErrorStream(true);
        runtime.applyEnvironment(pb);
        return pb.start();
    }
}
