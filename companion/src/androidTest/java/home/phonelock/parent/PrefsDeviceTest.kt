package home.phonelock.parent

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The typed preferences read what installed copies already store: each value is written the way the app wrote it
 * before Prefs.kt (by file and key name), then read back through the typed class. Also the defaults on an empty
 * file, and a typed write read back. Runs on the test app's own preference files, cleared first.
 */
@RunWith(AndroidJUnit4::class)
class PrefsDeviceTest {
    private val c: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = listOf("hub", "emergency", "screenalerts", "devicedata", "push", "quiet", "ui", "appupdate")

    private fun raw(file: String) = c.getSharedPreferences(file, Context.MODE_PRIVATE)

    @Before
    fun clear() = files.forEach { raw(it).edit().clear().commit() }

    @Test
    fun emptyFilesGiveTheDefaults() {
        val hub = HubPrefs(c)
        assertEquals("", hub.parentName)
        assertNull(hub.devices)
        assertEquals(0L, hub.ntfyLastTime)
        assertEquals(0, hub.disclosureAccepted)
        assertEquals("[]", EmergencyPrefs(c).alerts)
        assertEquals("[]", ScreenAlertPrefs(c).alerts)
        assertEquals("{}", DeviceDataPrefs(c).calls)
        assertEquals(emptySet<String>(), PushPrefs(c).subscribed)
        assertEquals(emptyMap<String, Int>(), QuietPrefs(c).allMinutes())
        assertTrue(UiPrefs(c).activityMap)
        assertEquals(0, AppUpdatePrefs(c).latest)
    }

    @Test
    fun readsWhatAnInstalledCopyStored() {
        raw("hub").edit().putString("parent_name", "Sam").putString("devices", "[{}]").putString("seen_ids", "{}")
            .putString("states", "{\"s\":1}").putString("vitals", "{\"v\":1}").putString("requests", "[1]")
            .putString("events", "{\"e\":1}").putLong("ntfy_last_time", 42).putInt("disclosure_accepted", 2)
            .putString("config", "{}").commit()
        raw("emergency").edit().putString("alerts", "[\"a\"]").commit()
        raw("screenalerts").edit().putString("alerts", "[\"s\"]").commit()
        raw("devicedata").edit().putString("calls", "{\"c\":1}").commit()
        raw("push").edit().putStringSet("subscribed", setOf("t1", "t2")).commit()
        raw("quiet").edit().putInt("min_dev1", 90).putLong("off_dev1", 7).putLong("alerted_dev1", 8).commit()
        raw("ui").edit().putBoolean("activity_map", false).commit()
        raw("appupdate").edit().putInt("latest", 120).putLong("size", 5).putString("sha256", "ab")
            .putLong("checked", 9).putInt("announced", 119).commit()

        val hub = HubPrefs(c)
        assertEquals("Sam", hub.parentName)
        assertEquals("[{}]", hub.devices)
        assertEquals("{}", hub.seenIds)
        assertEquals("{\"s\":1}", hub.states)
        assertEquals("{\"v\":1}", hub.vitals)
        assertEquals("[1]", hub.requests)
        assertEquals("{\"e\":1}", hub.events)
        assertEquals(42L, hub.ntfyLastTime)
        assertEquals(2, hub.disclosureAccepted)
        assertEquals("{}", hub.legacyConfig)
        assertEquals("[\"a\"]", EmergencyPrefs(c).alerts)
        assertEquals("[\"s\"]", ScreenAlertPrefs(c).alerts)
        assertEquals("{\"c\":1}", DeviceDataPrefs(c).calls)
        assertEquals(setOf("t1", "t2"), PushPrefs(c).subscribed)
        val quiet = QuietPrefs(c)
        assertEquals(mapOf("dev1" to 90), quiet.allMinutes())
        assertEquals(7L, quiet.offAt("dev1"))
        assertEquals(8L, quiet.alertedAt("dev1"))
        assertFalse(UiPrefs(c).activityMap)
        val update = AppUpdatePrefs(c)
        assertEquals(120, update.latest)
        assertEquals(5L, update.size)
        assertEquals("ab", update.sha256)
        assertEquals(9L, update.checkedAt)
        assertEquals(119, update.announced)
    }

    @Test
    fun typedWritesLandUnderTheStoredNames() {
        HubPrefs(c).writeDevices("[\"d\"]")
        assertEquals("[\"d\"]", raw("hub").getString("devices", null))

        val quiet = QuietPrefs(c)
        quiet.setMinutes("dev2", 30)
        quiet.setAlertedAt("dev2", 3)
        quiet.forget("dev2")
        assertFalse(raw("quiet").contains("min_dev2"))
        assertFalse(raw("quiet").contains("alerted_dev2"))

        // Background saves: wait for them through a commit on the same file.
        PushPrefs(c).subscribed = setOf("x")
        raw("push").edit().commit()
        assertEquals(setOf("x"), raw("push").getStringSet("subscribed", null))
    }
}
