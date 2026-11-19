package home.phonelock.parent

import android.content.Context
import android.os.Build
import android.util.Log
import home.phonelock.parent.adb.AdbConnection
import home.phonelock.parent.adb.AdbDiscovery
import home.phonelock.parent.adb.AdbEndpoint
import home.phonelock.parent.adb.AdbIdentity
import home.phonelock.parent.adb.AdbPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

enum class StepState { WAITING, RUNNING, DONE, FAILED, SKIPPED }

/** One line of the setup progress list; [detail] explains a failure or what was skipped. */
data class SetupStep(val label: String, val state: StepState = StepState.WAITING, val detail: String? = null)

/**
 * A setup in progress or finished. [deviceId] is the device added to this app (null when none was, e.g.
 * the device already belonged to another parent). [outcome] is the closing message.
 */
data class SetupRun(
    val steps: List<SetupStep>,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val deviceId: String? = null,
    val outcome: String? = null,
    /** Whether the last step really switched debugging off (for the closing message). */
    val debuggingOff: Boolean = false,
)

/**
 * Sets up a child's device from this phone over Android's Wireless debugging, doing what
 * `tools/configure-lock.sh` and the adb steps in docs/development.md do from a computer: install the
 * lock app, make it device owner, pair it with this app, turn on the features that need a one-time grant,
 * then switch debugging off. Works without a factory reset as long as no accounts are signed in on the device.
 *
 * A device that already runs PhoneLock as device owner is "repaired" instead: only the one-time grants are
 * re-applied (e.g. after an update lost one), and its pairing is left alone.
 */
object ChildSetup {
    private const val LOCK_PACKAGE = "home.phonelock"
    private const val ADMIN = "home.phonelock/.AdminReceiver"
    private const val CONFIG = "-n home.phonelock/.ConfigReceiver -a home.phonelock.CONFIG"

    /** The lock app needs Android 13 (its minSdk); Wireless debugging itself arrived in 11. */
    private const val MIN_SDK = 33

    /** How long to look for the device's debugging port on the network after pairing. */
    private const val FIND_CONNECT_PORT_MS = 20_000L

    /** How long to wait for a newly paired device's first message through the hub. */
    private const val FIRST_REPLY_MS = 60_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _run = MutableStateFlow<SetupRun?>(null)
    val run: StateFlow<SetupRun?> = _run.asStateFlow()

    /**
     * What the parent entered. [existing] is set when fixing a device already in this app. [connectPort] is
     * the device's debugging port if typed in (otherwise found on the network). [turnOffDebugging] ends
     * setup by locking adb away; it only happens once the device is confirmed reachable through the hub.
     */
    data class Request(
        val host: String,
        val pairPort: Int,
        val code: String,
        val childName: String,
        val parentName: String,
        val pin: String,
        val existing: Device? = null,
        val connectPort: Int? = null,
        val turnOffDebugging: Boolean = true,
    )

    /** The features that need a one-time grant over adb, as (what it's for, shell command). */
    private val GRANTS = listOf<Pair<Int, String>>(
        // Added to whatever accessibility services are on (e.g. TalkBack), not replacing them.
        R.string.setup_grant_screen_view to "s=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$s\" in *$LOCK_PACKAGE/*) ;; ''|null) settings put secure enabled_accessibility_services $LOCK_PACKAGE/$LOCK_PACKAGE.ScreenService ;; " +
            "*) settings put secure enabled_accessibility_services \"\$s:$LOCK_PACKAGE/$LOCK_PACKAGE.ScreenService\" ;; esac" +
            " && settings put secure accessibility_enabled 1",
        R.string.setup_grant_keep_screen_view to "pm grant $LOCK_PACKAGE android.permission.WRITE_SECURE_SETTINGS",
        R.string.setup_grant_dnd to "cmd notification allow_dnd $LOCK_PACKAGE",
        R.string.setup_grant_screen_calls to "cmd role add-role-holder android.app.role.CALL_SCREENING $LOCK_PACKAGE",
        R.string.setup_grant_limit_calls to "cmd role add-role-holder android.app.role.CALL_REDIRECTION $LOCK_PACKAGE",
        R.string.setup_grant_call_log to "pm grant $LOCK_PACKAGE android.permission.READ_CALL_LOG",
        R.string.setup_grant_app_use to "appops set $LOCK_PACKAGE GET_USAGE_STATS allow",
        R.string.setup_grant_files to "appops set $LOCK_PACKAGE MANAGE_EXTERNAL_STORAGE allow",
        R.string.setup_grant_idle to "dumpsys deviceidle whitelist +$LOCK_PACKAGE",
    )

    /** This phone's adb key and certificate, kept so a later "Fix setup" on the same device may skip pairing. */
    private val identity by lazy { AdbIdentity.load(app) }

    fun init(c: Context) {
        app = c.applicationContext
    }

    /** Clears a finished run so the screen starts fresh. */
    fun reset() {
        if (_run.value?.finished != false) _run.value = null
    }

    /** Starts a setup; progress appears in [run]. Ignored while one is already running. */
    fun start(req: Request) {
        if (_run.value?.let { !it.finished } == true) return
        _run.value = SetupRun(STEPS.map { SetupStep(Strings.get(it)) })
        scope.launch {
            try {
                perform(req)
            } catch (e: Exception) {
                Log.w(TAG, "setup: $e")
                failRunning(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---------- the steps ----------

    private val STEPS = listOf(
        R.string.setup_step_pair,
        R.string.setup_step_connect,
        R.string.setup_step_check,
        R.string.setup_step_download,
        R.string.setup_step_install,
        R.string.setup_step_owner,
        R.string.setup_step_connect_app,
        R.string.setup_step_features,
        R.string.setup_step_debugging_off,
    )

    private suspend fun perform(req: Request) {
        // 1. Pair (Android shows the code and a pairing port while "Pair device with pairing code" is open).
        step(0) {
            try {
                AdbPairing.pair(identity, req.host, req.pairPort, req.code.trim(), "phonelock@" + Build.MODEL.replace(' ', '-'))
            } catch (e: AdbPairing.PairingRefused) {
                throw SetupError(e.message ?: Strings.get(R.string.setup_pairing_refused))
            }
            null
        }

        // 2. Connect on the device's debugging port, which differs from the pairing port.
        lateinit var adb: AdbConnection
        step(1) {
            val port = req.connectPort ?: findConnectPort(req.host)
                ?: throw SetupError(Strings.get(R.string.setup_no_debugging_port))
            adb = AdbConnection.connect(identity, req.host, port)
            val ok = runCatching { adb.shell("echo ok").output.trim() == "ok" }.getOrDefault(false)
            if (!ok) {
                adb.close()
                throw SetupError(Strings.get(R.string.setup_device_no_answer))
            }
            null
        }

        adb.use { device ->
            // 3. What state is it in? A fresh device gets everything. One PhoneLock already manages is either
            // this app's (an earlier run stopped part way, or Fix setup: carry on), another parent's (only its
            // features are checked), or not paired yet (pairing continues).
            var owned = false
            var mine: Device? = null
            var othersDevice = false
            // This app's device, but it holds no readable pairing key (it lost it): it is sent again below.
            var needsKey = false
            step(2) {
                val sdk = device.shell("getprop ro.build.version.sdk").output.trim().toIntOrNull() ?: 0
                if (sdk < MIN_SDK) throw SetupError(Strings.get(R.string.setup_android_too_old))
                when (deviceOwner(device)) {
                    LOCK_PACKAGE -> {
                        owned = true
                        // Fail closed: a missing reply must not be read as "not paired" and overwrite a pairing.
                        val st = status(device)
                        if (!st.contains("owner=true") || !st.contains("paired=")) {
                            throw SetupError(Strings.get(R.string.setup_lock_no_answer))
                        }
                        val hash = Regex("topic=(\\w+)").find(st)?.groupValues?.get(1)
                        mine = Hub.devices.value.firstOrNull { topicHash(it.topic) == hash }
                        val paired = st.contains("paired=true")
                        if (req.existing != null && mine?.id != req.existing.id) {
                            throw SetupError(Strings.get(R.string.setup_wrong_device, req.existing.name))
                        }
                        othersDevice = paired && mine == null
                        needsKey = mine != null && !paired
                        when {
                            req.existing != null -> Strings.get(R.string.setup_note_checking_features)
                            mine != null -> Strings.get(R.string.setup_note_already_mine)
                            othersDevice -> Strings.get(R.string.setup_note_other_parent)
                            else -> Strings.get(R.string.setup_note_not_connected)
                        }
                    }

                    null -> {
                        if (req.existing != null) throw SetupError(Strings.get(R.string.setup_not_set_up))
                        val accounts = Regex("Accounts: (\\d+)").find(device.shell("dumpsys account").output)
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (accounts > 0) throw SetupError(Strings.get(R.string.setup_remove_accounts_first))
                        val users = device.shell("pm list users").output.lines().count { it.contains("UserInfo{") }
                        if (users > 1) throw SetupError(Strings.get(R.string.setup_remove_users_first))
                        null
                    }

                    else -> throw SetupError(Strings.get(R.string.setup_other_owner))
                }
            }

            // 4 to 6. Install and take ownership: only for a device PhoneLock doesn't manage yet.
            if (owned) {
                listOf(3, 4, 5).forEach { skip(it, Strings.get(R.string.setup_already_done)) }
            } else {
                lateinit var apk: File
                step(3) {
                    apk = downloadLockApp()
                    null
                }
                step(4) {
                    device.install(apk)
                    null
                }
                step(5) {
                    val out = device.shell("dpm set-device-owner $ADMIN")
                    if (!out.allOutput.contains("Success")) throw SetupError(out.allOutput.trim().lines().lastOrNull() ?: Strings.get(R.string.setup_refused))
                    null
                }
            }

            // 7. Connect it to this app (unless it already is, or belongs to another parent), then confirm it
            // really answers through the hub: nothing below may lock adb away from a device nobody can reach.
            val dev: String? = req.existing?.id ?: mine?.id
            var reachable = false
            when {
                dev != null -> {
                    set(6, StepState.RUNNING, Strings.get(R.string.setup_checking_answers))
                    mine?.takeIf { needsKey }?.let { resendKey(device, it) }
                    reachable = awaitReply(dev)
                    if (reachable) {
                        set(6, StepState.DONE, Strings.get(R.string.setup_already_connected))
                    } else {
                        set(6, StepState.FAILED, Strings.get(NOT_ANSWERING))
                    }
                    _run.update { it?.copy(deviceId = dev) }
                }

                othersDevice -> skip(6, Strings.get(R.string.setup_connected_other_parent))

                else -> {
                    set(6, StepState.RUNNING, null)
                    val id = pair(device, req)
                    reachable = awaitReply(id)
                    if (reachable) set(6, StepState.DONE, null) else set(6, StepState.FAILED, Strings.get(NOT_ANSWERING))
                }
            }

            // 8. One-time grants. A refused one is reported but doesn't stop setup (the rest still work).
            set(7, StepState.RUNNING, null)
            val refused = GRANTS.filter { (_, cmd) -> device.shell(cmd).exitCode != 0 }.map { Strings.get(it.first) }
            if (refused.isEmpty()) {
                set(7, StepState.DONE, null)
            } else {
                set(7, StepState.FAILED, Strings.get(R.string.setup_not_turned_on, refused.joinToString(", ")))
            }

            // 9. Lock adb away again (this ends the connection), only once the device has answered this app
            // through the hub, and never on another parent's device.
            when {
                !req.turnOffDebugging -> skip(8, Strings.get(R.string.setup_debugging_left_on_chosen))

                othersDevice -> skip(8, Strings.get(R.string.setup_debugging_left_on_other))

                !reachable -> skip(8, Strings.get(R.string.setup_debugging_left_on_unreachable))

                else -> {
                    set(8, StepState.RUNNING, null)
                    // Either the receiver confirms, or the connection drops as adb switches off: both mean done.
                    val reply = runCatching {
                        broadcastReply(device.shell("am broadcast $CONFIG --es cmd debug --es value off").output)
                    }
                    if (reply.isFailure || reply.getOrNull()?.startsWith("allow_debugging=false") == true) {
                        set(8, StepState.DONE, null)
                    } else {
                        set(8, StepState.FAILED, Strings.get(R.string.setup_debugging_not_confirmed))
                    }
                }
            }
        }

        val added = _run.value?.deviceId
        val r = _run.value
        val debugOff = r?.steps?.getOrNull(8)?.state == StepState.DONE
        val grantsFailed = r?.steps?.getOrNull(7)?.state == StepState.FAILED
        val outcome = when {
            req.existing != null -> Strings.get(R.string.setup_outcome_fixed)
            added == null -> Strings.get(R.string.setup_outcome_other_parent)
            else -> Strings.get(R.string.setup_outcome_done, req.childName)
        }
        _run.update {
            it?.copy(
                finished = true,
                failed = false,
                deviceId = added,
                debuggingOff = debugOff,
                outcome = if (grantsFailed) Strings.get(R.string.setup_outcome_some_features_failed, outcome) else outcome,
            )
        }
    }

    private val NOT_ANSWERING = R.string.setup_not_answering

    /**
     * Gives [d]'s pairing key back to a device this app set up that can no longer read its own (its keystore key
     * was lost). The topic already matches, so only the key is sent; the device reconnects by itself.
     */
    private fun resendKey(device: AdbConnection, d: Device) {
        val reply = runCatching {
            broadcastReply(device.shell("am broadcast $CONFIG --es cmd secret --es value ${quote(d.secret)}").output)
        }.getOrNull()
        Log.i(TAG, "pairing key sent again: ${reply ?: "no reply"}")
    }

    /**
     * Hands the new device its pairing and parent PIN in one go, after first saving it in this app, so the
     * secret is never on the device without a parent holding it too. Returns its id.
     */
    private fun pair(device: AdbConnection, req: Request): String {
        val d = Setup.newDevice(req.childName)
        Hub.setParentName(req.parentName)
        Hub.addDevice(d)
        _run.update { it?.copy(deviceId = d.id) }
        val pairing = org.json.JSONObject().put("server", d.server).put("topic", d.topic).put("secret", d.secret)
            .put("name", d.name).put("pin", req.pin)
            // The child's phone starts in this app's language (parents and child are assumed to share one);
            // a parent can change it later in that device's Settings.
            .put("lang", AppLanguage.currentTag(app))
        val reply = runCatching {
            broadcastReply(device.shell("am broadcast $CONFIG --es cmd pair --es value ${quote(pairing.toString())}").output)
        }.getOrNull()
        if (reply?.startsWith("paired") != true) {
            // Refused outright: nothing was stored on the device, so take it back out of this app.
            if (reply != null) {
                Hub.removeDevice(d.id)
                _run.update { it?.copy(deviceId = null) }
            }
            throw SetupError(
                if (reply != null) {
                    Strings.get(R.string.setup_pairing_not_accepted, reply)
                } else {
                    Strings.get(R.string.setup_pairing_no_answer)
                },
            )
        }
        return d.id
    }

    /**
     * Asks [dev] for its state and waits for an answer through the hub, judged by this phone's clock when it
     * arrives (an answer from before now doesn't count).
     */
    private suspend fun awaitReply(dev: String): Boolean {
        val since = System.currentTimeMillis()
        Hub.refresh(dev)
        return withTimeoutOrNull(FIRST_REPLY_MS) {
            Hub.vitals.first { (it[dev]?.heard ?: 0L) >= since }
            true
        } ?: false
    }

    /** The lock app's `status` line ("owner=true paired=false topic=…"), or "" if it didn't answer. */
    private fun status(device: AdbConnection): String = broadcastReply(device.shell("am broadcast $CONFIG --es cmd status").output) ?: ""

    /** The same short fingerprint of a pairing topic as the lock app's ConfigReceiver.topicHash. */
    private fun topicHash(topic: String): String = MessageDigest.getInstance("SHA-256").digest(topic.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** The receiver's reply from `am broadcast` output: `Broadcast completed: result=1, data="…"`. */
    private fun broadcastReply(out: String): String? = Regex("result=1, data=\"(.*)\"", RegexOption.DOT_MATCHES_ALL).find(out)?.groupValues?.get(1)

    /** The package of the device owner, or null when there is none. */
    private fun deviceOwner(device: AdbConnection): String? {
        val dump = device.shell("dumpsys device_policy").output
        val section = dump.substringAfter("Device Owner:", "").takeIf { it.isNotEmpty() } ?: return null
        return Regex("package=([\\w.]+)").find(section.take(600))?.groupValues?.get(1)
    }

    /** The device's debugging (connect) port on [host], found on the local network. */
    private suspend fun findConnectPort(host: String): Int? {
        val discovery = AdbDiscovery(app)
        discovery.start()
        try {
            // Compare addresses, not strings ("192.168.1.20" typed vs resolved forms of the same host).
            val target = runCatching { java.net.InetAddress.getByName(host) }.getOrNull()
            fun same(e: AdbEndpoint) = !e.pairing && (
                e.host == host ||
                    (target != null && runCatching { java.net.InetAddress.getByName(e.host) == target }.getOrDefault(false))
                )
            return withTimeoutOrNull(FIND_CONNECT_PORT_MS) {
                discovery.found.first { l -> l.any(::same) }.first(::same).port
            }
        } finally {
            discovery.close()
        }
    }

    /**
     * Downloads the latest lock app release and checks it before it goes anywhere near the child's device:
     * it must be the lock app and signed with the PhoneLock key.
     */
    private fun downloadLockApp(): File {
        val f = File(app.cacheDir, "lock-release.apk")
        try {
            Releases.download(Setup.LOCK_APK_URL, f, maxBytes = 50L * 1024 * 1024)
        } catch (e: java.io.FileNotFoundException) {
            throw SetupError(Strings.get(R.string.setup_download_404))
        }
        val info = Releases.inspect(app.packageManager, f)
        if (info?.packageName != LOCK_PACKAGE || info.signer != Setup.LOCK_SIGNATURE_SHA256) {
            f.delete()
            throw SetupError(Strings.get(R.string.setup_not_genuine))
        }
        return f
    }

    // ---------- progress ----------

    /** A failure with a message meant for the parent. */
    private class SetupError(message: String) : Exception(message)

    /** Runs step [i]; its result, if any, becomes the step's note. A thrown error fails it and the run. */
    private inline fun step(i: Int, block: () -> String?) {
        set(i, StepState.RUNNING, null)
        val note = block()
        set(i, StepState.DONE, note)
    }

    private fun skip(i: Int, why: String) = set(i, StepState.SKIPPED, why)

    private fun set(i: Int, state: StepState, detail: String?) = _run.update { r ->
        r?.copy(steps = r.steps.mapIndexed { k, s -> if (k == i) s.copy(state = state, detail = detail) else s })
    }

    /** Marks the running step failed with [message] and ends the run. */
    private fun failRunning(message: String) = _run.update { r ->
        r?.copy(
            steps = r.steps.map { if (it.state == StepState.RUNNING) it.copy(state = StepState.FAILED, detail = message) else it },
            finished = true,
            failed = true,
            outcome = message,
        )
    }

    /** [s] as one single-quoted shell word. */
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
