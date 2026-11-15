package com.oc.ide.core;

import android.content.Context;
import android.util.Log;

import com.oc.ide.runtime.RuntimeManager;
import com.oc.ide.util.CoreUrl;
import com.oc.ide.util.ErrorText;
import com.oc.ide.util.ProcessUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Supervises the native Bionic OpenCode server for its whole life. Installs/verifies,
 * launches, probes /api/info, mints the WebView session cookie token, and auto-restarts
 * on unexpected exits with exponential backoff.
 */
public final class CoreProcessSupervisor {

    private static final String TAG = "CoreProcessSupervisor";

    private final Context appContext;
    private final RuntimeManager runtime;
    private final OpenUrlWatcher openUrlWatcher;

    private volatile Process process;
    private volatile boolean stopping = false;
    private Thread worker;

    public CoreProcessSupervisor(Context context, RuntimeManager runtime,
                                 OpenUrlWatcher openUrlWatcher) {
        this.appContext = context.getApplicationContext();
        this.runtime = runtime;
        this.openUrlWatcher = openUrlWatcher;
    }

    public void start() {
        stopping = false;
        worker = new Thread(this::runCore, "CoreServerThread");
        worker.start();
    }

    public void stop() {
        stopping = true;
        destroy(process);
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** Triggers a clean supervised restart of the OpenCode process. */
    public void restartProcess() {
        Process p = process;
        if (p != null) {
            destroy(p);
        }
    }

    private void runCore() {
        int attempt = 0;
        while (!stopping) {
            Writer log = CoreLogWriter.open(appContext.getFilesDir());
            try {
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                Process proc = CoreLauncher.launch(runtime, appContext.getFilesDir());
                process = proc;

                final Writer streamWriter = log;
                Thread streamer = new Thread(() -> streamLog(proc, streamWriter), "CoreStreamThread");
                streamer.setDaemon(true);
                streamer.start();

                if (!waitForReady(proc, log)) {
                    if (stopping) {
                        break;
                    }
                    if (CoreRuntimeState.lastError == null) {
                        CoreRuntimeState.lastError =
                                "OpenCode server did not become ready within " + CoreReadyProbe.READY_TIMEOUT_MS + "ms";
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    }
                    destroy(proc);
                    break;
                }

                long readyAt = System.currentTimeMillis();
                try {
                    proc.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (stopping) {
                    break;
                }

                int code = ProcessUtils.exitCode(proc);
                if (System.currentTimeMillis() - readyAt >= CoreRestartPolicy.SUSTAINED_READY_MS) {
                    attempt = 0;
                }
                CoreRuntimeState.serverUrl = null;
                attempt++;
                String tail = getLastLinesFromLog(appContext.getFilesDir(), 15);
                if (attempt > CoreRestartPolicy.MAX_RESTARTS) {
                    CoreRuntimeState.restarting = false;
                    CoreRuntimeState.lastError = "OpenCode exited " + CoreRestartPolicy.MAX_RESTARTS
                            + " times; giving up (last exit code " + code + ")"
                            + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    break;
                }
                long delay = CoreRestartPolicy.delayFor(attempt);
                CoreRuntimeState.restarting = true;
                CoreRuntimeState.lastError = "OpenCode restarting in " + (delay / 1000) + "s (attempt "
                        + attempt + "/" + CoreRestartPolicy.MAX_RESTARTS + ", code " + code + ")";
                Log.w(TAG, CoreRuntimeState.lastError);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                CoreLogWriter.closeQuietly(log);
                log = null;
                sleepInterruptible(delay);
            } catch (Exception e) {
                if (stopping) {
                    break;
                }
                Log.e(TAG, "OpenCode start failed", e);
                CoreRuntimeState.lastError = ErrorText.describe(e);
                CoreLogWriter.writeLine(log, "OpenCode start failed: " + CoreRuntimeState.lastError);
                break;
            } finally {
                CoreLogWriter.closeQuietly(log);
            }
        }
    }

    private boolean waitForReady(Process proc, Writer log) {
        long start = System.currentTimeMillis();
        String csrf = runtime.getCsrfToken();
        int port = CoreRuntimeState.httpsPort;
        while (System.currentTimeMillis() - start < CoreReadyProbe.READY_TIMEOUT_MS) {
            if (stopping) {
                return false;
            }
            if (!ProcessUtils.isAlive(proc)) {
                int code = ProcessUtils.exitCode(proc);
                ProcessUtils.sleep(200);
                String lastLogs = getLastLinesFromLog(appContext.getFilesDir(), 12);
                CoreRuntimeState.lastError = "OpenCode exited before becoming ready (exit code " + code + ")"
                        + (lastLogs.isEmpty() ? "" : "\n--- core.log tail ---\n" + lastLogs);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                return false;
            }
            if (CoreReadyProbe.probeOnce(port, csrf)) {
                CoreReadyProbe.warmDefaultWorkspace(port, csrf, runtime.getDefaultWorkspaceDir());
                String sessionToken = CoreReadyProbe.mintSessionToken(port, csrf);
                CoreRuntimeState.sessionToken = sessionToken;
                CoreRuntimeState.serverUrl = CoreUrl.startUrl(port, csrf);
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                CoreRuntimeState.readyGeneration++;
                Log.i(TAG, "OpenCode ready: " + CoreRuntimeState.serverUrl
                        + " (sessionAuth=" + (sessionToken != null) + ")");
                CoreLogWriter.writeLine(log, "OpenCode ready: " + CoreRuntimeState.serverUrl);
                return true;
            }
        }
        return false;
    }

    private void streamLog(Process proc, Writer log) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, line);
                CoreLogWriter.writeLine(log, line);
                openUrlWatcher.handleStreamLine(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "log stream ended", e);
        }
    }

    private void sleepInterruptible(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stopping) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            ProcessUtils.sleep(Math.min(left, 250));
        }
    }

    private static void destroy(Process proc) {
        if (proc != null) {
            proc.destroy();
        }
    }

    private static String getLastLinesFromLog(File filesDir, int maxLines) {
        File logFile = new File(filesDir, "logs/core.log");
        if (!logFile.isFile()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
