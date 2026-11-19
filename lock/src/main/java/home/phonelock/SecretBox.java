package home.phonelock;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the pairing secret encrypted at rest (SEC-2), with an AES-256-GCM key that the phone's keystore holds and
 * never hands out. The secret itself must stay readable, because the parent app gives it to the lock app at setup,
 * so it is wrapped by this key rather than generated inside the keystore.
 *
 * The key needs no screen unlock, so the lock app can read its secret after a reboot, before the child unlocks
 * the phone. A copy of the app's files on another phone can't be decrypted there.
 *
 * Stored format: base64url(iv[12] || ciphertext+tag), with a fixed associated label so a value sealed here can't
 * be mistaken for a message. Must stay byte-compatible with the parent app's SecretBox.kt.
 */
final class SecretBox {
    /** The keystore alias of the wrapping key; changing it orphans every stored secret. */
    static final String ALIAS = "pl-wrap";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final byte[] LABEL = "phonelock/secretbox/v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** A stored secret can't be decrypted on this phone any more: its key is gone, or it was sealed elsewhere. */
    static final class LostKeyException extends Exception {
        LostKeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private SecretBox() {}

    /** Seals [plain] with the keystore key, creating the key the first time. */
    static String seal(String plain) throws GeneralSecurityException {
        return seal(keystoreKey(true), plain);
    }

    /**
     * Opens a value from {@link #seal(String)}. Throws {@link LostKeyException} when it can never be opened on this
     * phone, and another exception (keystore busy or failing) when trying again later might work.
     */
    static String open(String sealed) throws GeneralSecurityException, LostKeyException {
        SecretKey key = keystoreKey(false);
        if (key == null) throw new LostKeyException("no wrapping key on this phone", null);
        try {
            return open(key, sealed);
        } catch (AEADBadTagException | KeyPermanentlyInvalidatedException | IllegalArgumentException e) {
            // A wrong key, a changed or damaged value, or one that isn't base64: none will ever open.
            throw new LostKeyException("stored secret can't be opened with this phone's key", e);
        }
    }

    /** Seals [plain] with [key]; the cipher picks a fresh random IV each time. */
    static String seal(SecretKey key, String plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.updateAAD(LABEL);
        byte[] iv = c.getIV();
        byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all =
                ByteBuffer.allocate(iv.length + body.length).put(iv).put(body).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    /** Opens a value sealed with [key]. Throws IllegalArgumentException for a value that isn't one. */
    static String open(SecretKey key, String sealed) throws GeneralSecurityException {
        byte[] all = Base64.getUrlDecoder().decode(sealed.trim());
        if (all.length < IV_BYTES + TAG_BITS / 8) throw new IllegalArgumentException("too short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(all, 0, IV_BYTES)));
        c.updateAAD(LABEL);
        byte[] plain = c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** The wrapping key, or null when it doesn't exist and [create] is false. */
    private static SecretKey keystoreKey(boolean create) throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        try {
            ks.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("keystore unavailable", e);
        }
        Key existing = ks.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create) return null;

        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
