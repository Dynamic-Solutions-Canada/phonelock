package home.phonelock.parent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The family chat on this parent's phone (docs/chat.md): one thread per child's phone, shared with the child and
 * every other parent. The child's phone holds the history; this keeps a copy, sends this parent's messages, catches
 * up with `chatsync` and resends its own messages the phone hasn't stored. The thread rules themselves are in
 * [ChatThread].
 *
 * The copy is one file in the app's private storage, sealed with this phone's keystore key (SecretBox), since it
 * holds what the family wrote. A copy that can't be opened is dropped and fetched again from the phone.
 */
object Chat {
    /** The notification channel for new chat messages. */
    const val CHANNEL = "chat"

    /** The fewest milliseconds between background syncs of one phone, to spare ntfy.sh's daily allowance. */
    private const val SYNC_EVERY_MS = 2 * 60_000L

    /** How soon after one sync a noticed gap may send another. */
    private const val GAP_SYNC_MS = 30_000L

    /** How long a sync this app sent is remembered while its answer is awaited. */
    private const val SYNC_FORGET_MS = 3600_000L

    private lateinit var app: Context
    private val file by lazy { File(app.filesDir, "chat.sealed") }

    /** Saves run one at a time, off the caller's thread, each writing the newest threads. */
    private val saver = Executors.newSingleThreadExecutor()

    /** Whether a save is queued and not started yet; further changes ride along with it. */
    private val saveQueued = AtomicBoolean(false)

    private val _threads = MutableStateFlow<Map<String, ChatThread>>(emptyMap())

    /** Device -> its chat thread. */
    val threads: StateFlow<Map<String, ChatThread>> = _threads.asStateFlow()

    /** The device whose chat is on screen now, so its new messages need no notification; null when none is. */
    @Volatile var onScreen: String? = null

    /**
     * A `chatsync` this app sent: to [dev], asking after [after], first sent at [sentAt] (later pages keep it), as a
     * [baseline] first sync from nothing, and [visible] when a screen shows that it is waiting.
     */
    private data class Sync(val dev: String, val after: Long, val sentAt: Long, val baseline: Boolean, val visible: Boolean)

    /** Syncs awaiting their `chatlog`, by request id. */
    private val syncs = ConcurrentHashMap<String, Sync>()

    /** When each device was last synced, for [syncAll]'s spacing. */
    private val lastSync = ConcurrentHashMap<String, Long>()

    fun init(c: Context) {
        app = c.applicationContext
        _threads.value = load()
    }

    /** The name this parent's messages carry, as [Hub.msg] signs them. */
    private fun ownName(): String = Hub.parentName.value.ifBlank { "Parent" }

    /** Who sent a message, for display: the child's device name for the phone, else the parent's name. */
    fun senderName(dev: String, from: String): String = if (from == "phone") Hub.deviceName(dev) else from

    // ---------- sending ----------

    /** Sends [text] to [dev]'s chat as this parent; false (nothing sent) when it is empty or too long. */
    fun send(dev: String, text: String): Boolean {
        val body = ChatThread.sendable(text) ?: return false
        val now = System.currentTimeMillis()
        val m = ChatMessage(Crypto.randomId(), 0, ownName(), body, now, mine = true, shown = true, sentAt = now)
        change(dev) { it.merge(listOf(m)) }
        post(dev, m)
        return true
    }

    /** Sends [m] (one of this parent's own) in a new envelope; a resend keeps its cid, so the phone stores it once. */
    private fun post(dev: String, m: ChatMessage) {
        Hub.send(dev, envelope(m))
    }

    private fun envelope(m: ChatMessage): JSONObject = Hub.msg("chat").put("cid", m.cid).put("text", m.text).put("at", m.at)

    /** How long one of this parent's messages may wait for "Delivered" before a message from the phone prompts a sync. */
    private const val UNDELIVERED_MS = 60_000L

    /**
     * Something arrived from [dev]: if this parent has messages the phone hasn't confirmed for a while (it may have
     * been off, and dropped them as too old), sync, which sends them again. Cheap when nothing is waiting.
     */
    fun heardFromPhone(dev: String) {
        val now = System.currentTimeMillis()
        if (thread(dev).messages.any { it.mine && it.seq <= 0 && now - it.sentAt > UNDELIVERED_MS }) catchUp(dev)
    }

    /**
     * Asks [dev] for the history after what this app holds. [visible] shows the page's progress line until the
     * phone answers (for a chat being opened); background syncs wait silently.
     */
    fun sync(dev: String, visible: Boolean = false) {
        // Until one sync has completed, what the phone sends is the existing history, not news.
        val t = thread(dev)
        request(Sync(dev, t.synced, System.currentTimeMillis(), baseline = !t.caughtUp, visible = visible))
    }

    /** Syncs every phone that has the chat, unless it was synced in the last [SYNC_EVERY_MS]: on resume and reconnect. */
    fun syncAll() {
        val now = System.currentTimeMillis()
        val states = Hub.states.value
        Hub.devices.value.filter { states[it.id]?.canChat == true }.forEach { d ->
            if (now - (lastSync[d.id] ?: 0L) >= SYNC_EVERY_MS) sync(d.id)
        }
    }

    /** Sends the `chatsync` for [s] and remembers it until its answer comes. */
    private fun request(s: Sync) {
        val now = System.currentTimeMillis()
        syncs.entries.removeAll { now - it.value.sentAt > SYNC_FORGET_MS }
        lastSync[s.dev] = now
        val m = Hub.msg("chatsync").put("after", s.after)
        syncs[m.getString("id")] = s
        if (s.visible) Pending.send(s.dev, "chat", m) else Hub.send(s.dev, m)
    }

    // ---------- the chat screen ----------

    /** The chat of [dev] was on screen with everything in it: nothing there is unread, and its notification goes. */
    fun shown(dev: String) {
        if ((_threads.value[dev]?.unread ?: 0) > 0) change(dev) { it.allShown() }
        app.getSystemService(NotificationManager::class.java).cancel(notificationId(dev))
    }

    /** Drops [dev]'s chat (the device was removed). */
    fun forget(dev: String) {
        _threads.update { it - dev }
        lastSync.remove(dev)
        syncs.entries.removeAll { it.value.dev == dev }
        app.getSystemService(NotificationManager::class.java).cancel(notificationId(dev))
        save()
    }

    // ---------- incoming ----------

    /**
     * A chat message on [dev]'s topic: a `chat` from the child or another parent ([from]), or the phone's `chatin`,
     * `chatread` or `chatlog`. A malformed one is dropped and logged, never allowed to stop the caller.
     */
    fun handle(dev: String, m: JSONObject, from: String) {
        try {
            when (m.optString("t")) {
                "chat" -> onChat(dev, m, from)
                "chatin" -> if (from == "phone") onStored(dev, m)
                "chatread" -> if (from == "phone") change(dev) { it.seenUpTo(m.optLong("seq")) }
                "chatlog" -> if (from == "phone") onLog(dev, m)
            }
        } catch (e: Exception) {
            Log.w(TAG, "chat: dropped a malformed ${m.optString("t")}", e)
        }
    }

    /** A live message: the child's carries its seq; another parent's (or this parent's own echo) has none yet. */
    private fun onChat(dev: String, m: JSONObject, from: String) {
        val phone = from == "phone"
        val msg = parse(m, from, phone) ?: return
        if (phone && msg.seq <= 0) return

        val news = thread(dev).newFromOthers(listOf(msg))
        val t = change(dev) { it.merge(listOf(msg)) }
        notifyNew(dev, news)
        // A seq past the complete history means some were missed (this phone was off): fetch them.
        if (msg.seq > t.synced) catchUp(dev)
    }

    /** Syncs [dev] after a gap was noticed, unless a sync went out moments ago (its answer will fill the gap). */
    private fun catchUp(dev: String) {
        if (System.currentTimeMillis() - (lastSync[dev] ?: 0L) < GAP_SYNC_MS) return
        sync(dev)
    }

    /** The phone stored a parent's message: its seq, and "Delivered" for the sender. */
    private fun onStored(dev: String, m: JSONObject) {
        val cid = m.optString("cid").takeIf { it.isNotEmpty() } ?: return
        val seq = m.optLong("seq").takeIf { it > 0 } ?: return
        var known = false
        val updated = change(dev) { t -> t.delivered(cid, seq, m.optLong("at")).also { known = it.second }.first }
        // Another parent's message that never reached this phone, or seqs missed below it: catch up.
        if (!known || seq > updated.synced) catchUp(dev)
    }

    /**
     * A page of the phone's history. One this app asked for may continue (`more`) and, once complete, shows which
     * of this parent's messages the phone doesn't have, which are sent again. Another parent's page is merged
     * only.
     */
    private fun onLog(dev: String, m: JSONObject) {
        val own = syncs.remove(m.optString("req"))?.takeIf { it.dev == dev }
        val a = m.optJSONArray("msgs") ?: JSONArray()
        val page = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> parse(o, o.optString("from"), stored = true) }
        }.filter { it.seq > 0 }

        // Another parent's page counts as history too while this app has never caught up.
        val baseline = own?.baseline ?: !thread(dev).caughtUp
        val news = if (baseline) emptyList() else thread(dev).newFromOthers(page)
        val t = change(dev) { it.withLog(page, own?.after ?: 0, m.optLong("read"), ownRequest = own != null, baseline = baseline) }
        notifyNew(dev, news)
        if (own == null) return

        val last = page.maxOfOrNull { it.seq }
        if (m.optBoolean("more") && last != null) {
            request(own.copy(after = last))
            return
        }
        val resend = t.toResend(own.sentAt)
        if (resend.isEmpty()) return
        change(dev) { it.resent(resend.map { r -> r.cid }, System.currentTimeMillis()) }
        // In order, one after another: the phone numbers messages as they arrive.
        Hub.sendInOrder(dev, resend.sortedBy { it.at }.map(::envelope))
    }

    /**
     * One message from a `chat` or a `chatlog` entry [o], sent by [from]; null when it lacks an id or text. [stored]
     * reads its seq (the phone's messages and history entries carry one; a parent's live message has none).
     */
    private fun parse(o: JSONObject, from: String, stored: Boolean): ChatMessage? {
        val cid = o.optString("cid").takeIf { it.isNotEmpty() } ?: return null
        val text = o.optString("text").takeIf { it.isNotBlank() } ?: return null
        // Only messages this app sent are its own (marked when sent, and kept by the merge). Matching the sender
        // name would hide another parent's messages whenever two parents share a name, or both left it blank.
        val mine = false
        return ChatMessage(
            cid,
            if (stored) o.optLong("seq") else 0,
            from.ifEmpty { "?" },
            text.take(ChatThread.MAX_TEXT),
            o.optLong("at"),
            mine = mine,
            shown = mine,
        )
    }

    // ---------- notifications ----------

    /** The notification id of [dev]'s chat: one per device, so a newer message replaces the last one shown. */
    private fun notificationId(dev: String) = "chat:$dev".hashCode()

    /** Posts the newest of [news] (messages from the child or another parent) for [dev], unless that chat is on screen. */
    private fun notifyNew(dev: String, news: List<ChatMessage>) {
        val last = ChatThread.ordered(news).lastOrNull() ?: return
        if (onScreen == dev) return
        Hub.channels(app)
        // Its own request code: the device's plain "open" intent uses dev.hashCode() and would be reused instead.
        val open = PendingIntent.getActivity(
            app,
            notificationId(dev),
            Intent(app, MainActivity::class.java).putExtra("device", dev).putExtra("page", "chat"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val line = Strings.get(R.string.chat_notification_line, senderName(dev, last.from), last.text)
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Hub.deviceName(dev))
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setNumber(thread(dev).unread)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            app.getSystemService(NotificationManager::class.java).notify(notificationId(dev), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification permission")
        }
    }

    // ---------- storage ----------

    private fun thread(dev: String): ChatThread = _threads.value[dev] ?: ChatThread()

    /** Applies [f] to [dev]'s thread, saves, and returns the new thread. */
    private fun change(dev: String, f: (ChatThread) -> ChatThread): ChatThread {
        var result = ChatThread()
        _threads.update { all ->
            result = f(all[dev] ?: ChatThread())
            all + (dev to result)
        }
        save()
        return result
    }

    /** Writes the threads in the background, sealed; a seal that fails leaves the last file in place, never plain text. */
    private fun save() {
        if (!saveQueued.compareAndSet(false, true)) return
        saver.execute {
            saveQueued.set(false)
            runCatching {
                val sealed = SecretBox.seal(encode(_threads.value).toByteArray())
                val tmp = File(app.filesDir, "chat.sealed.tmp")
                tmp.writeText(sealed)
                tmp.renameTo(file)
            }.onFailure { Log.w(TAG, "chat save: $it") }
        }
    }

    /** Reads the stored threads, dropping messages past 90 days; empty when there are none or they can't be opened. */
    private fun load(): Map<String, ChatThread> {
        if (!file.isFile) return emptyMap()
        return try {
            val cutoff = System.currentTimeMillis() - ChatThread.KEEP_MS
            decode(String(SecretBox.open(file.readText()))).mapValues { it.value.olderDropped(cutoff) }
        } catch (e: SecretBox.LostKeyException) {
            // Sealed on another phone or with a lost key: the phone still has the history, so start again from it.
            Log.w(TAG, "chat history can't be opened here; fetching it again", e)
            file.delete()
            emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "chat load: $e")
            emptyMap()
        }
    }

    private fun encode(all: Map<String, ChatThread>): String {
        val o = JSONObject()
        all.forEach { (dev, t) ->
            val a = JSONArray()
            t.messages.forEach { m ->
                a.put(
                    JSONObject().put("cid", m.cid).put("seq", m.seq).put("from", m.from).put("text", m.text).put("at", m.at)
                        .put("mine", m.mine).put("shown", m.shown).put("sentAt", m.sentAt),
                )
            }
            o.put(dev, JSONObject().put("read", t.read).put("synced", t.synced).put("caughtUp", t.caughtUp).put("msgs", a))
        }
        return o.toString()
    }

    private fun decode(json: String): Map<String, ChatThread> {
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { dev ->
            val t = o.getJSONObject(dev)
            val a = t.optJSONArray("msgs") ?: JSONArray()
            val msgs = (0 until a.length()).mapNotNull { i ->
                val m = a.optJSONObject(i) ?: return@mapNotNull null
                val cid = m.optString("cid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ChatMessage(
                    cid,
                    m.optLong("seq"),
                    m.optString("from"),
                    m.optString("text"),
                    m.optLong("at"),
                    m.optBoolean("mine"),
                    m.optBoolean("shown"),
                    m.optLong("sentAt"),
                )
            }
            val synced = t.optLong("synced")
            ChatThread(ChatThread.ordered(msgs), t.optLong("read"), synced, t.optBoolean("caughtUp", synced > 0))
        }
    }
}
