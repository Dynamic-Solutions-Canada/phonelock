package home.phonelock.parent

import android.content.Context
import android.content.SharedPreferences

/*
 * Every preferences file of the parent app, with every key name and default in one place (CONF-8). Code anywhere
 * else reads and writes settings through these classes, never by a key string; tools/check-prefs.py fails the
 * build when one appears elsewhere. The file and key names are what installed copies already store, so they
 * never change without a migration.
 *
 * Each class is created by the module that owns that file, from its own context.
 */

/** One preferences file. [fileName] is the stored file's name. */
internal abstract class PrefsFile(c: Context, fileName: String) {
    protected val p: SharedPreferences = c.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    /** Applies [change] in the background, as every setting here is saved unless it says otherwise. */
    protected fun save(change: SharedPreferences.Editor.() -> Unit) = p.edit().apply(change).apply()
}

/** "hub": the paired devices, what was last heard from them, this parent's name and the disclosure. */
internal class HubPrefs(c: Context) : PrefsFile(c, "hub") {
    var parentName: String
        get() = p.getString("parent_name", "") ?: ""
        set(v) = save { putString("parent_name", v) }

    /** The stored device list (DeviceStore's JSON), or null before the first device. */
    val devices: String? get() = p.getString("devices", null)

    /** Replaces the device list at once (commit), so the sealed list never half-replaces the old one. */
    fun writeDevices(json: String) = p.edit().putString("devices", json).commit()

    /** Message ids already handled, with when they were seen (JSON), so a message is never applied twice. */
    var seenIds: String?
        get() = p.getString("seen_ids", null)
        set(v) = save { putString("seen_ids", v) }

    /** Each device's last reported state (JSON). */
    var states: String?
        get() = p.getString("states", null)
        set(v) = save { putString("states", v) }

    /** What each device last said about itself: when it was heard from and its battery (JSON). */
    var vitals: String?
        get() = p.getString("vitals", null)
        set(v) = save { putString("vitals", v) }

    /** The children's open requests (JSON). */
    var requests: String?
        get() = p.getString("requests", null)
        set(v) = save { putString("requests", v) }

    /** Each device's recent events (JSON). */
    var events: String?
        get() = p.getString("events", null)
        set(v) = save { putString("events", v) }

    /** The newest ntfy message time seen (ntfy's own "time" field), so catching up starts there. */
    var ntfyLastTime: Long
        get() = p.getLong("ntfy_last_time", 0)
        set(v) = save { putLong("ntfy_last_time", v) }

    /** The disclosure version this parent accepted; 0 before any. */
    var disclosureAccepted: Int
        get() = p.getInt("disclosure_accepted", 0)
        set(v) = save { putInt("disclosure_accepted", v) }

    /** The single-phone configuration of version 1 (JSON), or null once migrated. */
    val legacyConfig: String? get() = p.getString("config", null)

    /** Removes version 1's configuration and the data stored beside it, once migrated. */
    fun dropLegacy() = save {
        remove("config")
        remove("state")
        remove("requests")
        remove("events")
    }
}

/**
 * "emergency": the emergency alerts received, as JSON, and per device when this phone's paused alarm for it
 * ends (SosPause). Pause keys are the prefix and the device id.
 */
internal class EmergencyPrefs(c: Context) : PrefsFile(c, "emergency") {
    var alerts: String
        get() = p.getString("alerts", "[]") ?: "[]"
        set(v) = save { putString("alerts", v) }

    /** Every device's pause end (ms); a device that isn't listed isn't paused. */
    fun allPaused(): Map<String, Long> = p.all.filterKeys { it.startsWith(PAUSED) }
        .mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix(PAUSED) to it } }
        .toMap()

    fun setPausedUntil(dev: String, until: Long) = save { putLong(PAUSED + dev, until) }

    /** Ends [dev]'s pause (resumed, or the device was removed). */
    fun clearPaused(dev: String) = save { remove(PAUSED + dev) }

    private companion object {
        const val PAUSED = "paused_"
    }
}

/** "screenalerts": screenshots of unfamiliar screens waiting for a parent, as JSON. */
internal class ScreenAlertPrefs(c: Context) : PrefsFile(c, "screenalerts") {
    var alerts: String
        get() = p.getString("alerts", "[]") ?: "[]"
        set(v) = save { putString("alerts", v) }
}

/** "devicedata": each device's call log, as JSON. */
internal class DeviceDataPrefs(c: Context) : PrefsFile(c, "devicedata") {
    var calls: String
        get() = p.getString("calls", "{}") ?: "{}"
        set(v) = save { putString("calls", v) }
}

/** "push": the topics this phone is subscribed to for push. */
internal class PushPrefs(c: Context) : PrefsFile(c, "push") {
    var subscribed: Set<String>
        get() = p.getStringSet("subscribed", emptySet()) ?: emptySet()

        // A copy: Android keeps the set it is given and must not see later changes to it.
        set(v) = save { putStringSet("subscribed", HashSet(v)) }
}

/**
 * "quiet": per device, this parent's quiet-time alert setting, when the device last said it was turning off, and
 * when a quiet alert last went out. Keys are the setting's prefix and the device id.
 */
internal class QuietPrefs(c: Context) : PrefsFile(c, "quiet") {
    /** Every device's chosen quiet time in minutes; a device that isn't listed uses the default. */
    fun allMinutes(): Map<String, Int> = p.all.filterKeys { it.startsWith(MINUTES) }
        .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(MINUTES) to it } }
        .toMap()

    fun setMinutes(dev: String, minutes: Int) = save { putInt(MINUTES + dev, minutes) }

    /** When [dev] last said it was turning off (its clock); 0 when not known. */
    fun offAt(dev: String): Long = p.getLong(OFF + dev, 0)

    fun setOffAt(dev: String, ts: Long) = save { putLong(OFF + dev, ts) }

    /** The last-heard time a quiet alert went out for [dev]; 0 when none is outstanding. */
    fun alertedAt(dev: String): Long = p.getLong(ALERTED + dev, 0)

    fun setAlertedAt(dev: String, ts: Long) = save { putLong(ALERTED + dev, ts) }

    fun clearAlerted(dev: String) = save { remove(ALERTED + dev) }

    /** Drops everything kept for [dev] (it was removed). */
    fun forget(dev: String) = save {
        remove(MINUTES + dev)
        remove(OFF + dev)
        remove(ALERTED + dev)
    }

    private companion object {
        const val MINUTES = "min_"
        const val OFF = "off_"
        const val ALERTED = "alerted_"
    }
}

/** "ui": how screens were last left on this phone. */
internal class UiPrefs(c: Context) : PrefsFile(c, "ui") {
    /** Whether Activity shows its map (some parents mostly want the list). */
    var activityMap: Boolean
        get() = p.getBoolean("activity_map", true)
        set(v) = save { putBoolean("activity_map", v) }
}

/** "appupdate": what the GitHub build's self-updater last found. Unused in the Play build. */
internal class AppUpdatePrefs(c: Context) : PrefsFile(c, "appupdate") {
    /** The newest version code seen; 0 before a check. */
    val latest: Int get() = p.getInt("latest", 0)

    /** That version's download size in bytes, and the SHA-256 the download must match. */
    val size: Long get() = p.getLong("size", 0)
    val sha256: String get() = p.getString("sha256", "") ?: ""

    /** Stores what the release advertises. */
    fun remember(latest: Int, sha256: String, size: Long) = save {
        putInt("latest", latest)
        putString("sha256", sha256)
        putLong("size", size)
    }

    /** When the release was last checked; cleared so the next check fetches it again. */
    var checkedAt: Long
        get() = p.getLong("checked", 0)
        set(v) = save { putLong("checked", v) }

    fun forgetCheck() = save { remove("checked") }

    /** Whether the last check found the parent app under its new id released (AppMove). */
    var newAppPublished: Boolean
        get() = p.getBoolean("new_app_published", false)
        set(v) = save { putBoolean("new_app_published", v) }

    /** The version a notification was last shown for, so each is announced once. */
    var announced: Int
        get() = p.getInt("announced", 0)
        set(v) = save { putInt("announced", v) }
}
