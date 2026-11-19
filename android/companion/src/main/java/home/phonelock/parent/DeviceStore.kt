package home.phonelock.parent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The stored list of paired devices, with each device's pairing secret sealed (SEC-2). Encodes and decodes the
 * JSON that Hub keeps under "devices"; Hub owns when it is read and written.
 *
 * A device whose secret can't be opened on this phone is never dropped: its stored entry is carried through every
 * save untouched, so a passing keystore failure can't erase it, and a parent decides whether to forget it.
 *
 * [sealer] is SecretBox on a phone and a stand-in in tests.
 */
internal class DeviceStore(private val sealer: Sealer = KeystoreSealer) {
    /** Seals and opens secrets; [open] throws [SecretBox.LostKeyException] for one that can never be opened. */
    interface Sealer {
        fun seal(plain: ByteArray): String
        fun open(sealed: String): ByteArray
    }

    private object KeystoreSealer : Sealer {
        override fun seal(plain: ByteArray) = SecretBox.seal(plain)
        override fun open(sealed: String) = SecretBox.open(sealed)
    }

    /**
     * What [decode] found: the [devices] that can be used; [unreadable] stored entries to carry through saves;
     * [lost] those of them whose secret can never be opened here (shown to the parent); and [needsSealing],
     * true when a plain secret from an older version was read and should be sealed by saving.
     */
    data class Loaded(
        val devices: List<Device>,
        val unreadable: List<JSONObject>,
        val lost: List<JSONObject>,
        val needsSealing: Boolean,
    )

    /** Reads the stored list. Never throws for one bad entry; a damaged list as a whole throws. */
    fun decode(json: String): Loaded {
        val a = JSONArray(json)
        val devices = mutableListOf<Device>()
        val unreadable = mutableListOf<JSONObject>()
        val lost = mutableListOf<JSONObject>()
        var needsSealing = false

        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val name = o.optString("name")
            val sealed = o.optString("secret_enc").takeIf { it.isNotEmpty() }
            val secret = if (sealed == null) {
                // Written by an older version: usable now, sealed on the next save.
                needsSealing = true
                o.getString("secret")
            } else {
                try {
                    String(sealer.open(sealed))
                } catch (e: SecretBox.LostKeyException) {
                    android.util.Log.w(TAG, "can't read the pairing secret of a device", e)
                    unreadable += o
                    lost += o
                    continue
                } catch (e: Exception) {
                    // Possibly passing: kept, not shown as lost, and tried again on the next start.
                    android.util.Log.w(TAG, "pairing secret of a device not readable now", e)
                    unreadable += o
                    continue
                }
            }
            devices += Device(o.getString("server"), o.getString("topic"), secret, name)
        }
        return Loaded(devices, unreadable, lost, needsSealing)
    }

    /**
     * The stored form of [devices] plus the [unreadable] entries as they were. A secret the keystore can't seal
     * right now is kept plain so the device stays reachable, and sealed on a later save.
     */
    fun encode(devices: List<Device>, unreadable: List<JSONObject>): String {
        val a = JSONArray()
        devices.forEach { d ->
            val o = JSONObject().put("server", d.server).put("topic", d.topic).put("name", d.name)
            val sealed = try {
                sealer.seal(d.secret.toByteArray()).takeIf { String(sealer.open(it)) == d.secret }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "pairing secret stored unsealed for now", e)
                null
            }
            if (sealed != null) o.put("secret_enc", sealed) else o.put("secret", d.secret)
            a.put(o)
        }
        unreadable.forEach { a.put(it) }
        return a.toString()
    }
}
