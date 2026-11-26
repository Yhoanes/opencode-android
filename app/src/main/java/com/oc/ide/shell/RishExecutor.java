package com.oc.ide.shell;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a shell command through the bundled rish bridge. */
public final class RishExecutor {

    private final Context appContext;
    private final RishLocator locator;

    public RishExecutor(Context context) {
        this.appContext = context.getApplicationContext();
        this.locator = new RishLocator(context);
    }

    /** Runs `cmd` as shell via rish. Returns combined stdout/stderr; errors are text. */
    public String exec(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return "error: empty command";
        }
        File rish = locator.rishBinary();
        if (!rish.isFile()) {
            return "error: bundled rish not found at " + rish.getAbsolutePath();
        }
        if (!rish.canExecute()) {
            return "error: rish not executable: " + rish.getAbsolutePath()
                    + " (Shizuku may be unavailable)";
        }
        ProcessBuilder pb = new ProcessBuilder(
                rish.getAbsolutePath(),
                "-c",
                "env -u LD_LIBRARY_PATH " + cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.remove("LD_LIBRARY_PATH");
        env.put("RISH_APPLICATION_ID", appContext.getPackageName());
        pb.directory(appContext.getFilesDir());

        final Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }

        final StringBuilder out = new StringBuilder();
        Thread readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "RishStreamDrainer");
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            boolean finished = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                finished = process.waitFor(5, TimeUnit.SECONDS);
            } else {
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        process.exitValue();
                        finished = true;
                        break;
                    } catch (IllegalThreadStateException notYet) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            if (!finished) {
                destroyProc(process);
                return "error: rish command timed out after 5s";
            }

            try {
                readerThread.join(500);
            } catch (InterruptedException ignored) {
            }

            int code = process.exitValue();
            String text;
            synchronized (out) {
                text = out.toString().trim();
            }
            if (code != 0 && text.isEmpty()) {
                return "error: rish exited " + code;
            }
            return text;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        } finally {
            destroyProc(process);
        }
    }

    private static void destroyProc(Process p) {
        if (p == null) return;
        try {
            p.destroy();
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
    }
}
