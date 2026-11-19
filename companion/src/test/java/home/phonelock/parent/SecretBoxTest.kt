package home.phonelock.parent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

/**
 * The sealed format, with a software key standing in for the keystore's (checked on a device): a secret comes
 * back as it went in, each seal looks different, and a value sealed with another key or changed never opens.
 */
class SecretBoxTest {
    private val secret = "sndwiKxES8Ik8bBOiB9nlstt7reuBg2EURYMu7faX0I".toByteArray()

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun opensWhatItSealed() {
        val key = newKey()
        assertArrayEquals(secret, SecretBox.open(key, SecretBox.seal(key, secret)))
    }

    @Test
    fun eachSealUsesAFreshIv() {
        val key = newKey()
        assertNotEquals(SecretBox.seal(key, secret), SecretBox.seal(key, secret))
    }

    @Test
    fun anotherKeyCantOpenIt() {
        val sealed = SecretBox.seal(newKey(), secret)
        assertThrows(AEADBadTagException::class.java) { SecretBox.open(newKey(), sealed) }
    }

    @Test
    fun somethingThatIsntSealedIsRejected() {
        val key = newKey()
        assertThrows(IllegalArgumentException::class.java) { SecretBox.open(key, "not sealed!") }
        assertThrows(IllegalArgumentException::class.java) { SecretBox.open(key, "c2hvcnQ") }
    }
}
