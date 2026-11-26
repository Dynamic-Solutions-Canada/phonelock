package home.phonelock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.text.format.DateUtils;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The family chat on the child's phone: the stored thread (the record every parent app catches up from), the
 * chat messages of the protocol ({@code chat}, {@code chatsync}, {@code chatlimit} in; {@code chat},
 * {@code chatin}, {@code chatread}, {@code chatlog} out), the child's sending with the parents' limits, and the
 * notification for each new parent message. The thread's rules are in {@link ChatHistory}; the screen is
 * {@link ChatActivity}. See docs/chat.md and docs/protocol.md.
 *
 * <p>The thread lives in {@code chat.json} in the app's private storage, written through a temporary file like
 * the location log. Everything read from the topic or the file is parsed defensively: a bad message is logged
 * and dropped, and a corrupt file is set aside, never allowed to stop the background service.
 */
final class ChatLog {
    /** Sent within this app whenever the thread or the limits change, so an open chat redraws. */
    static final String ACTION_CHANGED = "home.phonelock.CHAT_CHANGED";

    /** What {@link #send} did. */
    static final int SENT = 0, EMPTY = 1, WAIT = 2, NOT_ALLOWED = 3, NOT_SET_UP = 4;

    private static final String FILE = "chat.json";
    private static final String CHANNEL = "chat";
    /** Every chat notification carries this tag (and its message's {@code seq} as id), so opening clears them. */
    private static final String NOTICE_TAG = "chat";
    /** A {@code cid} longer than this isn't one a parent app made. */
    private static final int MAX_CID = 64;

    // Preferences: the next number survives a lost or pruned file; the rest are the limits and send marks.
    private static final String KEY_SEQ = "chat_seq";
    private static final String KEY_EVERY = "chat_every";
    private static final String KEY_UNTIL = "chat_until";
    private static final String KEY_LAST_SENT = "chat_last_sent";
    private static final String KEY_READ_SENT = "chat_read_sent";
    private static final String KEY_READ_SENT_AT = "chat_read_sent_at";

    /** The thread, loaded once per process; guarded by the class lock. */
    private static ChatHistory cached;
    /** Whether the chat screen is in front, so new messages don't also post a notification. */
    static volatile boolean visible;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ChatLog() {}

    // ---------- from parents ----------

    /**
     * A parent's {@code chat}: stored once per {@code cid} and answered with {@code chatin}, also for a resend
     * (the parent app may have missed the first answer). Only a new message notifies the child.
     *
     * @param from the parent's name as the message carried it
     * @param serverTime when the hub received it (ms; 0 if unknown): the message's time, the same for everyone
     */
    static void onParentMessage(Context c, JSONObject m, String from, long serverTime) {
        try {
            String cid = m.optString("cid");
            String text = ChatHistory.cleanText(m.optString("text", null));
            if (cid.isEmpty() || cid.length() > MAX_CID || text == null) {
                Log.w(Policy.TAG, "chat: dropped a malformed message");
                return;
            }
            // Times in the chat are the hub's (ServerClock), never a sender's own clock, which may be wrong.
            long now = ServerClock.now(c);
            long at = serverTime > 0 ? serverTime : now;
            ChatHistory.Added added;
            synchronized (ChatLog.class) {
                ChatHistory h = history(c);
                added = h.add(cid, from.isEmpty() ? "Parent" : from, text, at, now, null);
                if (added.isNew) {
                    h.prune(now);
                    save(c, h);
                }
            }

            JSONObject in = Remote.msg("chatin");
            in.put("cid", added.msg.cid).put("seq", added.msg.seq).put("at", added.msg.at);
            Remote.publish(c, in, null);

            if (added.isNew) {
                changed(c);
                notifyChild(c, added.msg);
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "chat: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Deletes the whole chat: its messages, unsent ones included, its numbering, the parents' limits and the read
     * mark. Called when the pairing changes (see {@link Pairing}).
     */
    static synchronized void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
        new File(c.getFilesDir(), FILE + ".bad").delete();
        Policy.prefs(c)
                .edit()
                .remove(KEY_SEQ)
                .remove(KEY_EVERY)
                .remove(KEY_UNTIL)
                .remove(KEY_LAST_SENT)
                .remove(KEY_READ_SENT)
                .remove(KEY_READ_SENT_AT)
                .commit();
        cached = null;
        cancelNotices(c);
    }

    /** How late a {@code chatsync} may arrive and still be answered (a parent asks again when it opens the chat). */
    private static final long SYNC_MAX_LATE_MS = 5 * 60_000L;

    /** A parent's {@code chatsync}: answers with one {@code chatlog} page after the parent's newest {@code seq}. */
    static void onSync(Context c, JSONObject m) {
        // A phone back from hours offline receives every sync sent meanwhile; answering each would spend the
        // household's message allowance (see ChatHistory.paceWait) on stale requests.
        if (System.currentTimeMillis() - m.optLong("ts") > SYNC_MAX_LATE_MS) return;
        try {
            long after = Math.max(0, m.optLong("after", 0));
            List<ChatHistory.Msg> page;
            boolean more;
            long read;
            synchronized (ChatLog.class) {
                ChatHistory h = history(c);
                page = h.page(after);
                more = !page.isEmpty() && h.hasMoreAfter(page.get(page.size() - 1).seq);
                read = h.read;
            }
            JSONArray msgs = new JSONArray();
            for (ChatHistory.Msg x : page) {
                msgs.put(new JSONObject()
                        .put("seq", x.seq)
                        .put("cid", x.cid)
                        .put("from", x.from)
                        .put("text", x.text)
                        .put("at", x.at));
            }
            JSONObject o = Remote.msg("chatlog");
            o.put("req", m.optString("id")).put("msgs", msgs).put("read", read);
            if (more) o.put("more", true);
            // Usually too large for one ntfy message: Remote sends it as "big".
            Remote.publish(c, o, null);
        } catch (Exception e) {
            Log.w(Policy.TAG, "chat sync: " + e.getClass().getSimpleName());
        }
    }

    /**
     * A parent's {@code chatlimit}: replaces slow mode and the timeout, tells the child and the parents, and
     * sends a fresh {@code state}. A slow mode other than 0, 1, 5 or 15 minutes is dropped.
     *
     * @param who the parent's name, already worded for this phone ("A parent" when it had none)
     */
    static void onLimit(Context c, JSONObject m, String who) {
        int every = m.optInt("every", -1);
        if (!ChatHistory.validEvery(every)) {
            Log.w(Policy.TAG, "chat limit: dropped slow mode " + m.opt("every"));
            return;
        }
        long now = ServerClock.now(c);
        long until = m.optLong("until", 0);
        if (until <= now) until = 0;
        Policy.prefs(c)
                .edit()
                .putInt(KEY_EVERY, every)
                .putLong(KEY_UNTIL, until)
                .commit();
        changed(c);

        String child;
        String parents;
        if (until > 0) {
            String end = when(c, until);
            child = c.getString(R.string.chat_limit_timeout_child, end, who);
            parents = every > 0
                    ? c.getResources().getQuantityString(R.plurals.chat_event_timeout_slow, every, who, end, every)
                    : c.getString(R.string.chat_event_timeout, who, end);
        } else if (every > 0) {
            child = c.getResources().getQuantityString(R.plurals.chat_limit_slow_child, every, every, who);
            parents = c.getResources().getQuantityString(R.plurals.chat_event_slow, every, who, every);
        } else {
            child = c.getString(R.string.chat_limit_off_child, who);
            parents = c.getString(R.string.chat_event_off, who);
        }
        Remote.notifyKid(c, child);
        Remote.event(c, parents, who, false);
        Remote.sendState(c, false);
    }

    /** Adds {@code chat} {every, until} (the limits in force) to a {@code state}. */
    static void putState(Context c, JSONObject state) throws Exception {
        SharedPreferences p = Policy.prefs(c);
        long until = p.getLong(KEY_UNTIL, 0);
        state.put(
                "chat",
                new JSONObject()
                        .put("every", p.getInt(KEY_EVERY, 0))
                        .put("until", until > ServerClock.now(c) ? until : 0));
    }

    // ---------- the child's side ----------

    /** The thread, oldest first, for the chat screen (a copy; the messages themselves are shared). */
    static List<ChatHistory.Msg> messages(Context c) {
        synchronized (ChatLog.class) {
            return new ArrayList<>(history(c).msgs);
        }
    }

    /** Why the child can't send right now, and until when; null when the child can. */
    static ChatHistory.Wait sendWait(Context c) {
        SharedPreferences p = Policy.prefs(c);
        return ChatHistory.sendWait(
                p.getLong(KEY_LAST_SENT, 0), p.getInt(KEY_EVERY, 0), p.getLong(KEY_UNTIL, 0), ServerClock.now(c));
    }

    /**
     * The child sends {@code text}: checked against whether Chat is allowed now and the parents' limits, stored
     * with the next {@code seq}, then handed to the outbox, which keeps it until the hub accepts it. Every send
     * of the child's goes through here, so a further limit belongs here too.
     *
     * @return {@link #SENT}, or why not: {@link #EMPTY}, {@link #WAIT}, {@link #NOT_ALLOWED}, {@link #NOT_SET_UP}
     */
    static int send(Context c, String text) {
        if (!Remote.paired(c)) return NOT_SET_UP;
        if (!BuiltInApps.allowedNow(c, BuiltInApps.CHAT)) return NOT_ALLOWED;
        String clean = ChatHistory.cleanText(text);
        if (clean == null) return EMPTY;
        synchronized (ChatLog.class) {
            if (sendWait(c) != null) return WAIT;
            long now = ServerClock.now(c);
            ChatHistory h = history(c);
            h.add(Crypto.randomId(), ChatHistory.PHONE, clean, now, now, Crypto.randomId());
            h.prune(now);
            save(c, h);
            Policy.prefs(c).edit().putLong(KEY_LAST_SENT, now).commit();
        }
        changed(c);
        Outbox.flushChat(c);
        return SENT;
    }

    /**
     * Sends the child's messages the hub hasn't accepted yet, oldest first, stopping at the first that doesn't
     * go through. Each attempt reuses the message's envelope id, so if a send that timed out did arrive, the
     * parents drop the retry. Sends are spaced by the flood guard ({@link ChatHistory#paceWait}); a message that
     * has to wait is sent by a later flush. Blocks; call from the outbox worker.
     */
    static void flush(Context c, long timeoutMs) {
        while (true) {
            ChatHistory.Msg next;
            long wait;
            synchronized (ChatLog.class) {
                ChatHistory h = history(c);
                List<ChatHistory.Msg> waiting = h.unsent();
                next = waiting.isEmpty() ? null : waiting.get(0);
                // Message times are the hub's; the spacing between sends is measured on the phone's own clock.
                int sentToday = h.childSentSince(ServerClock.now(c) - 86_400_000L);
                wait = ChatHistory.paceWait(lastSend, sentToday, System.currentTimeMillis());
            }
            if (next == null) {
                // Nothing of the child's waiting: a read mark that couldn't go out before goes now.
                sendReadNow(c, timeoutMs);
                return;
            }
            if (wait > 0) {
                // The flood guard: come back for it later, rather than holding up the outbox's other sends.
                paceLater(c, wait);
                return;
            }
            try {
                JSONObject o = Remote.msg("chat");
                o.put("id", next.envelopeId)
                        .put("cid", next.cid)
                        .put("seq", next.seq)
                        .put("text", next.text)
                        .put("at", next.at);
                if (!Remote.publishNow(c, o, timeoutMs)) return;
            } catch (Exception e) {
                Log.w(Policy.TAG, "chat send: " + e);
                return;
            }
            lastSend = System.currentTimeMillis();
            synchronized (ChatLog.class) {
                next.sent = true;
                save(c, history(c));
            }
            changed(c);
        }
    }

    /**
     * Whether this phone offers the family chat: not on the public ntfy.sh server, which allows only 250
     * messages a day per network, so a busy chat there could crowd out SOS alerts and location. Phones on the
     * PhoneLock hub, or another self-hosted server, have it.
     */
    static boolean available(Context c) {
        String host = android.net.Uri.parse(RemoteService.server(c)).getHost();
        return host != null && !host.equalsIgnoreCase("ntfy.sh");
    }

    /** When the last of the child's messages went out (ms), for the flood guard; 0 until one has since start. */
    private static volatile long lastSend;

    private static final java.util.concurrent.ScheduledExecutorService PACER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private static volatile boolean pacing;

    /**
     * Flushes the chat again in {@code delayMs}; one pending at a time. A short wait (the usual spacing) keeps the
     * phone awake for it, so a message sent just before the screen goes off still leaves within seconds; the long
     * waits of a very busy day don't, and go out at the phone's next wake-up instead.
     */
    private static void paceLater(Context c, long delayMs) {
        if (pacing) return;
        pacing = true;
        Context app = c.getApplicationContext();
        if (delayMs <= ChatHistory.PACE_MS) {
            app.getSystemService(android.os.PowerManager.class)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "phonelock:chat")
                    .acquire(delayMs + 30_000L);
        }
        PACER.schedule(
                () -> {
                    pacing = false;
                    // The send runs on the outbox's worker; the wake lock's own timeout covers it.
                    Outbox.flushChat(app);
                },
                delayMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** How many of the child's messages are waiting to send (for diagnostics). */
    static int pending(Context c) {
        synchronized (ChatLog.class) {
            return history(c).unsent().size();
        }
    }

    /**
     * The child has seen the thread up to {@code seq}: kept as the child's read mark, their chat notifications are
     * cleared, and the mark goes to the parents as {@code chatread}, at most once a minute (a mark that moves
     * on within the minute is sent when the minute is up).
     */
    static void seen(Context c, long seq) {
        cancelNotices(c);
        synchronized (ChatLog.class) {
            ChatHistory h = history(c);
            if (seq <= h.read) return;
            h.read = seq;
            save(c, h);
        }
        Context app = c.getApplicationContext();
        long delay = ChatHistory.readDelay(Policy.prefs(app).getLong(KEY_READ_SENT_AT, 0), System.currentTimeMillis());
        MAIN.removeCallbacksAndMessages(READ_TOKEN);
        MAIN.postAtTime(() -> sendRead(app), READ_TOKEN, android.os.SystemClock.uptimeMillis() + delay);
    }

    private static final Object READ_TOKEN = new Object();

    /** Publishes the read mark if it moved since the last one sent, off the main thread (it waits for the hub). */
    private static void sendRead(Context c) {
        new Thread(() -> sendReadNow(c, SEND_READ_TIMEOUT_MS), "chatread").start();
    }

    private static final long SEND_READ_TIMEOUT_MS = 45_000L;

    /**
     * Publishes the read mark if it moved since the last one the hub accepted, and records it only then, so one
     * that couldn't be sent (no connection) goes out with the next {@link #flush}. Blocks.
     */
    private static void sendReadNow(Context c, long timeoutMs) {
        long read;
        synchronized (ChatLog.class) {
            read = history(c).read;
        }
        SharedPreferences p = Policy.prefs(c);
        if (read <= p.getLong(KEY_READ_SENT, 0)) return;
        try {
            JSONObject o = Remote.msg("chatread");
            o.put("seq", read);
            if (!Remote.publishNow(c, o, timeoutMs)) return;
            p.edit()
                    .putLong(KEY_READ_SENT, read)
                    .putLong(KEY_READ_SENT_AT, System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            Log.w(Policy.TAG, "chat read: " + e.getClass().getSimpleName());
        }
    }

    // ---------- wording ----------

    /** Who sent a message, as the child reads it: "You", the parent's name, or "A parent" for an unnamed one. */
    static String sender(Context c, String from) {
        if (ChatHistory.PHONE.equals(from)) return c.getString(R.string.chat_you);
        if (from.isEmpty() || "Parent".equals(from)) return c.getString(R.string.events_a_parent);
        return from;
    }

    /** A time, with the weekday when it isn't today ("8:00 PM", "Tue 8:00 PM"). */
    static String when(Context c, long t) {
        if (sameDay(t, ServerClock.now(c))) return Remote.time(c, t);
        return DateUtils.formatDateTime(
                c, t, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_SHOW_TIME);
    }

    /** A message's time in the thread: the time today, else the date and time. */
    static String stamp(Context c, long t) {
        if (sameDay(t, ServerClock.now(c))) return Remote.time(c, t);
        return DateUtils.formatDateTime(
                c, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME);
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance();
        x.setTimeInMillis(a);
        Calendar y = Calendar.getInstance();
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR)
                && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    // ---------- notifications ----------

    /**
     * A notification for a new parent message, opening the chat. It shows the message even when Chat isn't
     * allowed right now; the screen then says so and closes. None while the chat is open in front.
     */
    private static void notifyChild(Context c, ChatHistory.Msg m) {
        if (visible) return;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, c.getString(R.string.chat_channel), NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(c, ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(
                    c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(sender(c, m.from))
                    .setContentText(m.text)
                    .setStyle(new Notification.BigTextStyle().bigText(m.text))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setWhen(m.stored)
                    .setShowWhen(true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTICE_TAG, (int) m.seq, n);
        } catch (Exception e) {
            Log.w(Policy.TAG, "chat notice: " + e);
        }
    }

    /** Clears every chat notification (the child has the chat open). */
    static void cancelNotices(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (NOTICE_TAG.equals(s.getTag())) nm.cancel(NOTICE_TAG, s.getId());
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "chat notices: " + e);
        }
    }

    private static void changed(Context c) {
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
    }

    // ---------- storage ----------

    /** The thread, loaded on first use. Call with the class lock held. */
    private static ChatHistory history(Context c) {
        if (cached == null) cached = load(c);
        return cached;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /**
     * Reads the thread from disk. Entries that don't parse are skipped; a file that doesn't parse at all is
     * renamed to {@code chat.json.bad} (kept for diagnosis, never overwritten by the next save) and the thread
     * starts empty. Numbering always continues from the highest {@code seq} known.
     */
    private static ChatHistory load(Context c) {
        ChatHistory h = new ChatHistory();
        h.lastSeq = Policy.prefs(c).getLong(KEY_SEQ, 0);
        File f = file(c);
        if (!f.isFile()) return h;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            h.lastSeq = Math.max(h.lastSeq, o.optLong("seq", 0));
            h.read = o.optLong("read", 0);
            JSONArray a = o.optJSONArray("msgs");
            for (int i = 0; a != null && i < a.length(); i++) {
                ChatHistory.Msg m = parse(a.optJSONObject(i));
                if (m == null) continue;
                h.msgs.add(m);
                h.lastSeq = Math.max(h.lastSeq, m.seq);
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "chat load: " + e);
            if (!f.renameTo(new File(c.getFilesDir(), FILE + ".bad"))) Log.w(Policy.TAG, "chat load: rename failed");
        }
        return h;
    }

    /** One stored message, or null if it is missing what a message needs. */
    private static ChatHistory.Msg parse(JSONObject e) {
        if (e == null) return null;
        long seq = e.optLong("seq", 0);
        String cid = e.optString("cid");
        String text = e.optString("text", null);
        if (seq <= 0 || cid.isEmpty() || text == null) return null;
        String envelope = e.optString("id", null);
        return new ChatHistory.Msg(
                seq,
                cid,
                e.optString("from", "Parent"),
                text,
                e.optLong("at", 0),
                e.optLong("got", 0),
                envelope,
                envelope == null || e.optBoolean("sent", false));
    }

    /** Writes the thread through a temp file, so a crash mid-write can't lose it. Call with the lock held. */
    private static void save(Context c, ChatHistory h) {
        try {
            Policy.prefs(c).edit().putLong(KEY_SEQ, h.lastSeq).commit();
            JSONArray a = new JSONArray();
            for (ChatHistory.Msg m : h.msgs) {
                JSONObject e = new JSONObject()
                        .put("seq", m.seq)
                        .put("cid", m.cid)
                        .put("from", m.from)
                        .put("text", m.text)
                        .put("at", m.at)
                        .put("got", m.stored);
                if (m.envelopeId != null) e.put("id", m.envelopeId).put("sent", m.sent);
                a.put(e);
            }
            JSONObject o =
                    new JSONObject().put("seq", h.lastSeq).put("read", h.read).put("msgs", a);
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file(c))) Log.w(Policy.TAG, "chat save: rename failed");
        } catch (Exception e) {
            Log.w(Policy.TAG, "chat save: " + e);
        }
    }
}
