package uz.saydo.app.kernel.data;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Enterprise-grade Hardware-Backed Cryptographic Security Vault.
 * Provides authenticated AES-256-GCM encryption for sensitive macro coordinates and payloads.
 */
public final class SecurityVault {

    private static final String KEY_ALIAS = "saydo_master_vault_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12; // bytes for GCM

    private static SecurityVault sInstance;

    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKey softwareFallbackKey;

    public static synchronized SecurityVault getInstance() {
        if (sInstance == null) {
            sInstance = new SecurityVault();
        }
        return sInstance;
    }

    public SecurityVault() {
        initKey();
    }

    private void initKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(false);

                keyGen.init(builder.build());
                keyGen.generateKey();
            }
        } catch (Throwable t) {
            // JVM Unit test or Keystore unavailable fallback
            generateSoftwareKey();
        }
    }

    private void generateSoftwareKey() {
        if (softwareFallbackKey == null) {
            byte[] keyBytes = new byte[32];
            secureRandom.nextBytes(keyBytes);
            softwareFallbackKey = new SecretKeySpec(keyBytes, "AES");
        }
    }

    private SecretKey getSecretKey() throws Exception {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            }
        } catch (Throwable ignored) {
        }
        if (softwareFallbackKey == null) {
            generateSoftwareKey();
        }
        return softwareFallbackKey;
    }

    /**
     * Encrypts plaintext string into an authenticated AES-256-GCM Base64 envelope: [12-byte IV][Ciphertext + Tag].
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        try {
            SecretKey secretKey = getSecretKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);

            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new SecurityException("Failed to encrypt sensitive data", e);
        }
    }

    /**
     * Decrypts an authenticated AES-256-GCM Base64 envelope back to plaintext string.
     */
    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) return "";
        try {
            byte[] combined = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            if (combined.length < IV_LENGTH + 16) {
                throw new IllegalArgumentException("Invalid encrypted payload length");
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH];
            byteBuffer.get(iv);

            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            SecretKey secretKey = getSecretKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecurityException("Failed to decrypt or authenticate sensitive data", e);
        }
    }

    /**
     * Securely scrubs memory buffer with zeroes to eliminate residual heap traces.
     */
    public static void scrub(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    /**
     * Securely scrubs char buffer with zeroes to eliminate residual heap traces.
     */
    public static void scrub(char[] data) {
        if (data != null) {
            Arrays.fill(data, '\0');
        }
    }
}
