package uz.saydo.app;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.os.StrictMode;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SayDo Application foundation class.
 * Provides global crash recording, memory pressure lifecycle hooks,
 * and debug-only StrictMode policies.
 */
public class SayDoApp extends Application implements ComponentCallbacks2 {

    private static final String TAG = "SayDoApp";
    private static final String CRASH_FILE_NAME = "app_crash.log";
    private static final long MAX_CRASH_LOG_BYTES = 128 * 1024; // 128 KB cap
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile SayDoApp sInstance;
    private final List<MemoryTrimCallback> memoryTrimCallbacks = new CopyOnWriteArrayList<>();
    private Thread.UncaughtExceptionHandler defaultHandler;

    public interface MemoryTrimCallback {
        void onTrimMemory(int level);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        installCrashHandler();
        setupStrictMode();
        uz.saydo.app.metrics.Metrics.init(this);
        uz.saydo.app.kernel.SayDoKernel.init(this);
    }

    public static SayDoApp getInstance() {
        return sInstance;
    }

    /**
     * Registers a callback for system memory pressure notifications.
     */
    public void registerMemoryTrimCallback(MemoryTrimCallback callback) {
        if (callback != null && !memoryTrimCallbacks.contains(callback)) {
            memoryTrimCallbacks.add(callback);
        }
    }

    /**
     * Unregisters a callback for system memory pressure notifications.
     */
    public void unregisterMemoryTrimCallback(MemoryTrimCallback callback) {
        if (callback != null) {
            memoryTrimCallbacks.remove(callback);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        for (MemoryTrimCallback cb : memoryTrimCallbacks) {
            try {
                cb.onTrimMemory(level);
            } catch (Throwable t) {
                Log.w(TAG, "Error notifying memory trim callback", t);
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onLowMemory() {
        super.onLowMemory();
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
    }

    /**
     * Installs the global uncaught exception handler.
     */
    private void installCrashHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                handleUncaughtException(thread, throwable);
            }
        });
    }

    /**
     * Logs structured crash report to app_crash.log and chains to default handler.
     */
    private void handleUncaughtException(Thread thread, Throwable throwable) {
        try {
            writeCrashEntry(thread, throwable);
        } catch (Throwable logError) {
            Log.e(TAG, "Failed to write crash entry", logError);
        } finally {
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
    }

    /**
     * Writes structured crash info to the circular crash log file.
     */
    public synchronized void writeCrashEntry(Thread thread, Throwable throwable) {
        try {
            File logFile = new File(getFilesDir(), CRASH_FILE_NAME);
            maintainLogFileSize(logFile);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("==================================================");
            pw.println("TIME: " + DATE_FORMAT.format(new Date()));
            pw.println("THREAD: " + (thread != null ? thread.getName() + " (id=" + thread.getId() + ")" : "unknown"));
            pw.println("DEVICE: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
            pw.println("OS_VERSION: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            pw.println("APP_VERSION: 2.9.0");
            Runtime rt = Runtime.getRuntime();
            long freeMem = rt.freeMemory();
            long totalMem = rt.totalMemory();
            long maxMem = rt.maxMemory();
            pw.println("MEMORY_FREE: " + (freeMem / 1024) + " KB, TOTAL: " + (totalMem / 1024) + " KB, MAX: " + (maxMem / 1024) + " KB");
            pw.println("EXCEPTION: " + (throwable != null ? throwable.getClass().getName() : "null"));
            pw.println("MESSAGE: " + (throwable != null ? throwable.getMessage() : "null"));
            pw.println("STACKTRACE:");
            if (throwable != null) {
                throwable.printStackTrace(pw);
            }
            pw.println("==================================================");
            pw.flush();

            byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(bytes);
                fos.flush();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error writing crash log", t);
        }
    }

    /**
     * Manages circular file trimming so crash log never exceeds MAX_CRASH_LOG_BYTES.
     */
    private void maintainLogFileSize(File logFile) {
        if (!logFile.exists() || logFile.length() < MAX_CRASH_LOG_BYTES) {
            return;
        }
        try {
            int len = (int) logFile.length();
            byte[] data = new byte[len];
            try (FileInputStream fis = new FileInputStream(logFile)) {
                int read = fis.read(data);
                if (read <= 0) return;
            }
            // Retain the last 64 KB of logs
            int keepBytes = (int) (MAX_CRASH_LOG_BYTES / 2);
            int startOffset = Math.max(0, len - keepBytes);
            try (FileOutputStream fos = new FileOutputStream(logFile, false)) {
                fos.write(data, startOffset, len - startOffset);
                fos.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Retrieves the entire crash log content.
     */
    public synchronized String getCrashLog() {
        File logFile = new File(getFilesDir(), CRASH_FILE_NAME);
        if (!logFile.exists() || logFile.length() == 0) {
            return "";
        }
        try (FileInputStream fis = new FileInputStream(logFile)) {
            byte[] data = new byte[(int) logFile.length()];
            int read = fis.read(data);
            if (read > 0) {
                return new String(data, 0, read, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error reading crash log", t);
        }
        return "";
    }

    /**
     * Clears the crash log.
     */
    public synchronized void clearCrashLog() {
        File logFile = new File(getFilesDir(), CRASH_FILE_NAME);
        if (logFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
        }
    }

    /**
     * Records a handled non-fatal error for diagnostics.
     */
    public static void logNonFatal(Throwable t) {
        if (sInstance != null && t != null) {
            sInstance.writeCrashEntry(Thread.currentThread(), t);
        }
    }

    /**
     * Sets up StrictMode policies exclusively for debuggable builds.
     */
    private void setupStrictMode() {
        boolean isDebug = false;
        try {
            ApplicationInfo info = getApplicationInfo();
            isDebug = (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable ignored) {
        }

        if (isDebug) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build());

            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build());
            Log.i(TAG, "StrictMode initialized for debug build");
        }
    }
}
