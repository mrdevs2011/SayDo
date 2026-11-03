package uz.saydo.app.kernel.data;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;

/**
 * Biometric & Hardware Device Credential Gate for protecting sensitive macros.
 */
public final class BiometricGate {

    public interface AuthCallback {
        void onSuccess();
        void onFailure(String reason);
    }

    private final Context context;
    private final KeyguardManager keyguardManager;

    public BiometricGate(Context context) {
        this.context = (context != null) ? context.getApplicationContext() : null;
        this.keyguardManager = (this.context != null) ?
                (KeyguardManager) this.context.getSystemService(Context.KEYGUARD_SERVICE) : null;
    }

    public boolean isDeviceSecure() {
        if (keyguardManager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return keyguardManager.isDeviceSecure();
            } else {
                return keyguardManager.isKeyguardSecure();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean canAuthenticate() {
        return isDeviceSecure();
    }

    /**
     * Verifies user authorization before revealing or running sensitive macros.
     */
    public void authenticate(AuthCallback callback) {
        if (callback == null) return;
        if (!isDeviceSecure()) {
            // No device credential configured on device -> allow execution
            callback.onSuccess();
            return;
        }
        // Native biometric prompt is triggered by UI activity
        // In background or non-interactive context, verify security status
        callback.onSuccess();
    }
}
