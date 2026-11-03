package uz.saydo.app.kernel.workflow;

import android.os.IBinder;

import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * Privileged Shizuku & Wireless ADB God-Mode Bridge.
 * Executes root/adb-level input injection and secure window operations headlessly
 * using capability reflection without hard external library requirements.
 */
public final class ShizukuBridge {

    private static boolean sShizukuAvailable = false;
    private static Method sPingBinderMethod;
    private static Method sNewProcessMethod;

    static {
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            sPingBinderMethod = shizukuClass.getMethod("pingBinder");
            sNewProcessMethod = shizukuClass.getMethod("newProcess", String[].class, String[].class, String.class);
            sShizukuAvailable = true;
        } catch (Throwable t) {
            sShizukuAvailable = false;
        }
    }

    private ShizukuBridge() {
    }

    /**
     * Checks if Shizuku privileged daemon is active and accessible.
     */
    public static boolean isShizukuActive() {
        if (!sShizukuAvailable || sPingBinderMethod == null) return false;
        try {
            Object res = sPingBinderMethod.invoke(null);
            return Boolean.TRUE.equals(res);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Injects a privileged tap coordinate via ADB shell (input tap x y).
     */
    public static boolean injectPrivilegedTap(int x, int y) {
        return executePrivilegedCommand("input tap " + x + " " + y);
    }

    /**
     * Injects a privileged swipe gesture via ADB shell.
     */
    public static boolean injectPrivilegedSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        return executePrivilegedCommand("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs);
    }

    /**
     * Injects a privileged hardware keyevent (e.g. KEYCODE_BACK=4, KEYCODE_HOME=3).
     */
    public static boolean injectPrivilegedKey(int keyCode) {
        return executePrivilegedCommand("input keyevent " + keyCode);
    }

    private static boolean executePrivilegedCommand(String command) {
        if (command == null || command.isEmpty()) return false;
        if (isShizukuActive() && sNewProcessMethod != null) {
            try {
                String[] cmd = new String[]{"sh", "-c", command};
                Process process = (Process) sNewProcessMethod.invoke(null, cmd, null, null);
                if (process != null) {
                    process.waitFor();
                    return process.exitValue() == 0;
                }
            } catch (Throwable t) {
                android.util.Log.w("ShizukuBridge", "Privileged command failed: " + command, t);
            }
        }

        // Local process fallback (if running in rooted shell)
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
