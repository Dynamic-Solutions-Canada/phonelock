package home.phonelock.parent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Sealed storage on Android itself: the keystore key round trip, and the stored device list, where a plain
 * secret from an older version is read and sealed, and an entry that can't be opened is reported and carried
 * through saves instead of being dropped.
 */
@RunWith(AndroidJUnit4::class)
class SecretStorageDeviceTest {
    private val secret = "sndwiKxES8Ik8bBOiB9nlstt7reuBg2EURYMu7faX0I"

    /** Stands in for the keystore: "sealed:" plus base64, and anything else is a lost key. */
    private object FakeSealer : DeviceStore.Sealer {
        override fun seal(plain: ByteArray) = "sealed:" + Base64.getEncoder().encodeToString(plain)
        override fun open(sealed: String): ByteArray {
            if (!sealed.startsWith("sealed:")) throw SecretBox.LostKeyException("not ours")
            return Base64.getDecoder().decode(sealed.removePrefix("sealed:"))
        }
    }

    private fun entry(name: String, field: String, value: String) = JSONObject().put("server", "https://ntfy.sh").put("topic", "pl-$name").put("name", name).put(field, value)

    @Test
    fun theKeystoreKeyOpensWhatItSealed() {
        val sealed = SecretBox.seal(secret.toByteArray())
        assertArrayEquals(secret.toByteArray(), SecretBox.open(sealed))
    }

    @Test
    fun aPlainSecretFromAnOlderVersionIsReadAndSealedOnSave() {
        val store = DeviceStore(FakeSealer)
        val loaded = store.decode(JSONArray().put(entry("Emma", "secret", secret)).toString())
        assertTrue(loaded.needsSealing)
        assertEquals(secret, loaded.devices.single().secret)

        val saved = JSONArray(store.encode(loaded.devices, loaded.unreadable)).getJSONObject(0)
        assertFalse(saved.has("secret"))
        assertEquals(secret, String(FakeSealer.open(saved.getString("secret_enc"))))
        assertFalse(store.decode(JSONArray().put(saved).toString()).needsSealing)
    }

    @Test
    fun anUnreadableDeviceIsReportedAndKeptThroughSaves() {
        val store = DeviceStore(FakeSealer)
        val good = entry("Emma", "secret_enc", FakeSealer.seal(secret.toByteArray()))
        val bad = entry("Noah", "secret_enc", "from-another-phone")
        val loaded = store.decode(JSONArray().put(good).put(bad).toString())

        assertEquals(listOf("Emma"), loaded.devices.map { it.name })
        assertEquals(listOf("Noah"), loaded.lost.map { it.optString("name") })

        val saved = JSONArray(store.encode(loaded.devices, loaded.unreadable))
        assertEquals(2, saved.length())
        assertEquals("from-another-phone", saved.getJSONObject(1).getString("secret_enc"))
    }
}
