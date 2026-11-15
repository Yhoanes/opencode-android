package com.oc.ide.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.oc.ide.runtime.RuntimeManager;
import com.oc.ide.shell.ShizukuBridge;

/**
 * Foreground host for the native Bionic OpenCode server. Holds a wake lock,
 * streams stdout/stderr to files/logs/core.log, and publishes serverUrl + sessionToken once ready.
 */
public final class CoreServerService extends Service {

    private static final String TAG = "CoreServer";
    private static volatile CoreProcessSupervisor activeSupervisor;

    private RuntimeManager runtime;
    private WakeLockHolder wakeLock;
    private OpenUrlWatcher openUrlWatcher;
    private CoreProcessSupervisor supervisor;

    public static boolean requestRestart() {
        CoreProcessSupervisor sup = activeSupervisor;
        if (sup != null) {
            sup.restartProcess();
            return true;
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = new RuntimeManager(this);
        Log.i(TAG, "state paths: HOME=" + getFilesDir().getAbsolutePath()
                + " ocConfig=" + runtime.getOcConfigDir().getAbsolutePath()
                + " ocData=" + runtime.getOcDataDir().getAbsolutePath()
                + " runtime=" + runtime.getRuntimeDir().getAbsolutePath());
        CoreRuntimeState.httpsPort = runtime.getHttpsPort();
        CoreRuntimeState.csrfToken = runtime.getCsrfToken();
        CoreRuntimeState.sessionToken = null;
        CoreRuntimeState.restarting = false;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.serverUrl = null;

        ForegroundNotification.start(this);
        wakeLock = new WakeLockHolder();
        wakeLock.acquire(this);

        openUrlWatcher = new OpenUrlWatcher(this);
        openUrlWatcher.start();

        supervisor = new CoreProcessSupervisor(this, runtime, openUrlWatcher);
        activeSupervisor = supervisor;
        supervisor.start();

        new ShizukuBridge(this).applyBackgroundSurvivabilityTweaksAsync();
    }

    @Override
    public void onDestroy() {
        activeSupervisor = null;
        if (supervisor != null) {
            supervisor.stop();
        }
        if (openUrlWatcher != null) {
            openUrlWatcher.stop();
        }
        if (wakeLock != null) {
            wakeLock.release();
        }
        CoreRuntimeState.serverUrl = null;
        CoreRuntimeState.sessionToken = null;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.restarting = false;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
