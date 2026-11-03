package uz.saydo.app.kernel.data;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.KeyGenerator;

/**
 * Dedicated StrongBox Keymaster Vault for devices equipped with tamper-resistant hardware chips (Android 9+).
 */
public final class StrongBoxVault {

    private static final String STRONGBOX_KEY_ALIAS = "saydo_strongbox_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final Context context;
    private final boolean strongBoxSupported;

    public StrongBoxVault(Context context) {
        this.context = (context != null) ? context.getApplicationContext() : null;
        this.strongBoxSupported = checkStrongBoxSupport();
        if (strongBoxSupported) {
            initStrongBoxKey();
        }
    }

    private boolean checkStrongBoxSupport() {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        try {
            return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isStrongBoxSupported() {
        return strongBoxSupported;
    }

    private void initStrongBoxKey() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(STRONGBOX_KEY_ALIAS)) {
                KeyGenerator keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                        STRONGBOX_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setIsStrongBoxBacked(true);

                keyGen.init(builder.build());
                keyGen.generateKey();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void secureZeroScrub(byte[] buffer) {
        if (buffer != null) {
            Arrays.fill(buffer, (byte) 0);
        }
    }
}
