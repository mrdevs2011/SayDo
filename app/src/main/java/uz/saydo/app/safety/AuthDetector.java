package uz.saydo.app.safety;

import java.util.Locale;

/**
 * Decides from a window-change event whether the window is a fingerprint / face / lock-screen /
 * credential prompt (pure Java, unit tested). Looks only at package, class name and window title,
 * never at screen content, so it also works on FLAG_SECURE screens.
 *
 * The class and package names are educated guesses from AOSP and Samsung One UI; they are NOT yet
 * verified on a phone. Where a phone uses other names the recorder stores no wait step and replay
 * does not wait; it never taps anything because of this.
 */
public final class AuthDetector {
    private AuthDetector() {}

    private static final String[] AUTH_PACKAGES = {
            "com.samsung.android.biometrics",
            "com.samsung.android.bio.face",
            "com.samsung.android.authfw",
            "com.android.settings.biometrics",
    };

    /** Fragments in the class name that mean "credential or biometric prompt". */
    private static final String[] AUTH_CLASS = {
            "biometricprompt", "biometric", "fingerprint", "faceauth", "keyguardbouncer", "bouncer",
            "confirmlockpassword", "confirmlockpattern", "confirmlockpin", "confirmdevicecredential",
            "confirmcredential", "credentialconfirm", "keyguardpin", "keyguardpassword", "keyguardpattern",
            "authcontainer", "authdialog", "keyguardhost", "lockscreen"
    };

    /** Fragments in the window title, in the languages the owner uses. */
    private static final String[] AUTH_TITLE = {
            "fingerprint", "biometric", "face unlock", "unlock", "verify it", "verify your identity",
            "barmoq", "yuz", "отпечат", "биометр", "разблокир", "подтвердите"
    };

    public static boolean isAuthWindow(String pkg, String cls, String title) {
        String p = lower(pkg), c = lower(cls), t = lower(title);
        if (p.isEmpty()) return false;
        for (String a : AUTH_PACKAGES) if (p.startsWith(a)) return true;
        boolean systemUi = p.equals("com.android.systemui") || p.equals("android")
                || p.startsWith("com.samsung.android.systemui");
        boolean settings = p.equals("com.android.settings") || p.equals("com.samsung.android.settings");
        if (systemUi || settings) {
            for (String a : AUTH_CLASS) if (c.contains(a)) return true;
        }
        // The title alone is only trusted in system UI (an app can name its own screen "Unlock").
        if (systemUi && !t.isEmpty()) {
            for (String a : AUTH_TITLE) if (t.contains(a)) return true;
        }
        return false;
    }

    /**
     * Windows that show up while a prompt is open without meaning that the prompt is over: other system
     * UI windows (the prompt itself often reports a plain class name), keyboards, toasts.
     */
    public static boolean isNeutralDuringAuth(String pkg, String cls) {
        String p = lower(pkg), c = lower(cls);
        if (p.isEmpty()) return true;
        if (c.startsWith("android.widget.toast") || c.startsWith("android.inputmethodservice")) return true;
        return p.equals("com.android.systemui") || p.equals("android") || p.startsWith("com.samsung.android.systemui")
                || p.contains("inputmethod") || p.contains("keyboard");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
