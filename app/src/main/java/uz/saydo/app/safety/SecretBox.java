package uz.saydo.app.safety;

import java.security.GeneralSecurityException;

/** Encrypts and decrypts small byte arrays. The phone uses Android Keystore (KeystoreBox); tests use a plain AES key. */
public interface SecretBox {
    byte[] encrypt(byte[] plain) throws GeneralSecurityException;

    byte[] decrypt(byte[] sealed) throws GeneralSecurityException;
}
