package uz.saydo.app;

import org.junit.Test;

import uz.saydo.app.kernel.data.SecurityVault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustive Unit Test Suite for Hardware Keystore Security Vault (Pillar 5).
 */
public class SecurityVaultTest {

    @Test
    public void testEncryptionAndDecryptionRoundtrip() {
        SecurityVault vault = new SecurityVault();
        String sensitivePayload = "PIN_CODE_1234_GESTURE_PATH_SECRET";

        String encrypted = vault.encrypt(sensitivePayload);
        assertNotNull(encrypted);
        assertFalse(encrypted.isEmpty());
        assertFalse("Ciphertext must not contain plaintext", encrypted.contains("1234"));

        String decrypted = vault.decrypt(encrypted);
        assertEquals(sensitivePayload, decrypted);
    }

    @Test
    public void testUniqueIvRandomness() {
        SecurityVault vault = new SecurityVault();
        String payload = "SAME_PAYLOAD_EVERY_TIME";

        String enc1 = vault.encrypt(payload);
        String enc2 = vault.encrypt(payload);

        // Even with identical plaintext, AES-GCM must produce unique ciphertexts due to random 12-byte IVs
        assertFalse("Unique IVs must yield distinct ciphertexts", enc1.equals(enc2));
        assertEquals(payload, vault.decrypt(enc1));
        assertEquals(payload, vault.decrypt(enc2));
    }

    @Test
    public void testEmptyAndNullSafety() {
        SecurityVault vault = new SecurityVault();
        assertEquals("", vault.encrypt(""));
        assertEquals("", vault.encrypt(null));
        assertEquals("", vault.decrypt(""));
        assertEquals("", vault.decrypt(null));
    }

    @Test(expected = Exception.class)
    public void testTamperedCiphertextRejection() {
        SecurityVault vault = new SecurityVault();
        String enc = vault.encrypt("SECURE_TOKEN");
        // Tamper with the base64 string
        String tampered = enc.substring(0, enc.length() - 4) + "AAAA";
        vault.decrypt(tampered);
    }

    @Test
    public void testZeroScrubbing() {
        byte[] sensitiveBytes = new byte[]{1, 2, 3, 4, 5};
        SecurityVault.scrub(sensitiveBytes);
        for (byte b : sensitiveBytes) {
            assertEquals((byte) 0, b);
        }

        char[] sensitiveChars = new char[]{'s', 'e', 'c', 'r', 'e', 't'};
        SecurityVault.scrub(sensitiveChars);
        for (char c : sensitiveChars) {
            assertEquals('\0', c);
        }
    }
}
