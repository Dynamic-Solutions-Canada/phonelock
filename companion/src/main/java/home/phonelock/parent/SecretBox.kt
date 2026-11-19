package home.phonelock.parent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps this app's secrets encrypted at rest (SEC-2): the devices' pairing secrets and this phone's adb identity.
 * They are sealed with an AES-256-GCM key that the phone's keystore holds and never hands out. The secrets
 * themselves must stay readable, because sharing a device and setting one up hand them over, so they are wrapped
 * by this key rather than generated inside the keystore. A copy of the app's files on another phone can't be
 * opened there.
 *
 * Stored format: base64url(iv[12] || ciphertext+tag) with a fixed associated label, the same as the lock app's
 * SecretBox.java.
 */
internal object SecretBox {
    /** The keystore alias of the wrapping key; changing it orphans every stored secret. */
    private const val ALIAS = "pl-wrap"
    private const val KEYSTORE = "AndroidKeyStore"
    private val LABEL = "phonelock/secretbox/v1".toByteArray()
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** A stored secret can never be opened on this phone: its key is gone, or it was sealed on another phone. */
    class LostKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Seals [plain] with the keystore key, creating the key the first time. */
    fun seal(plain: ByteArray): String = seal(keystoreKey(create = true)!!, plain)

    /**
     * Opens a value from [seal]. Throws [LostKeyException] when it can never be opened on this phone, and another
     * exception (keystore busy or failing) when trying again later might work.
     */
    fun open(sealed: String): ByteArray {
        val key = keystoreKey(create = false) ?: throw LostKeyException("no wrapping key on this phone")
        return try {
            open(key, sealed)
        } catch (e: AEADBadTagException) {
            throw LostKeyException("stored secret can't be opened with this phone's key", e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw LostKeyException("this phone's key was invalidated", e)
        } catch (e: IllegalArgumentException) {
            throw LostKeyException("stored value isn't a sealed secret", e)
        }
    }

    /** Seals [plain] with [key]; the cipher picks a fresh random IV each time. */
    fun seal(key: SecretKey, plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(LABEL)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(c.iv + c.doFinal(plain))
    }

    /** Opens a value sealed with [key]. Throws IllegalArgumentException for a value that isn't one. */
    fun open(key: SecretKey, sealed: String): ByteArray {
        val all = Base64.getUrlDecoder().decode(sealed.trim())
        require(all.size >= IV_BYTES + TAG_BITS / 8) { "too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
        c.updateAAD(LABEL)
        return c.doFinal(all, IV_BYTES, all.size - IV_BYTES)
    }

    /** The wrapping key, or null when it doesn't exist and [create] is false. */
    private fun keystoreKey(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE)
        try {
            ks.load(null)
        } catch (e: java.io.IOException) {
            throw GeneralSecurityException("keystore unavailable", e)
        }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null

        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }
}
