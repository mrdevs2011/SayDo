package uz.saydo.app.safety;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM with a key that lives in the Android Keystore and cannot be extracted (the app can use
 * it, nobody can read it out, and it is not part of any backup). Output = 12 byte IV + ciphertext + tag.
 * NOT verified on a phone yet.
 */
public final class KeystoreBox implements SecretBox {
    static final String ALIAS = "saydo_sensitive_steps_v1";
    private static final int IV_LEN = 12;

    private final KeyStore ks;

    private KeystoreBox(KeyStore ks) {
        this.ks = ks;
    }

    /** Null when the Keystore is not available (then sensitive macros are not written to disk at all). */
    public static KeystoreBox tryCreate() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build());
                kg.generateKey();
            }
            return new KeystoreBox(ks);
        } catch (Throwable t) {
            return null;
        }
    }

    private Key key() throws GeneralSecurityException {
        Key k = ks.getKey(ALIAS, null);
        if (k == null) throw new GeneralSecurityException("key missing");
        return k;
    }

    @Override
    public byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    @Override
    public byte[] decrypt(byte[] sealed) throws GeneralSecurityException {
        if (sealed == null || sealed.length <= IV_LEN) throw new GeneralSecurityException("too short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 0, IV_LEN)));
        return c.doFinal(sealed, IV_LEN, sealed.length - IV_LEN);
    }
}
