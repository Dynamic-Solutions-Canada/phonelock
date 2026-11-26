package home.phonelock.parent

/*
 * The family chat's thread for one child's phone, as plain data with the rules for merging, ordering, resending
 * and counting unread messages (docs/chat.md). Nothing here touches Android or JSON, so the JVM unit tests cover
 * it directly; Chat.kt reads and writes the messages and owns when these run.
 */

/**
 * One chat message. [cid] is the sender's id for it, the same on every resend. [seq] is its place in the phone's
 * history (0 until the phone has stored it). [from] is "phone" for the child, otherwise a parent's name. [at] is
 * when the sender wrote it, by the sender's clock, and is used only to place messages that have no [seq] yet.
 *
 * [mine] marks this parent's own messages; [shown] that this app has shown it, for the unread count; [sentAt] is
 * when this app last sent one of its own (this phone's clock), so a resend goes out only after a sync that began
 * later than that.
 */
data class ChatMessage(
    val cid: String,
    val seq: Long = 0,
    val from: String,
    val text: String,
    val at: Long,
    val mine: Boolean = false,
    val shown: Boolean = false,
    val sentAt: Long = 0,
)

/** What a parent's own message shows under it. */
enum class ChatStatus { SENDING, DELIVERED, SEEN }

/**
 * One phone's thread: [messages] in display order (see [ChatThread.ordered]), [read] the highest seq the child has
 * seen (the phone's `chatread`), and [synced] the seq up to which this app holds the phone's whole history, which
 * is what `chatsync` asks after. [synced] only moves over seqs with no gap below them, so a live message that
 * arrives after some were missed doesn't make the next sync skip the missing ones.
 */
data class ChatThread(
    val messages: List<ChatMessage> = emptyList(),
    val read: Long = 0,
    val synced: Long = 0,
    /** Whether one of this app's own syncs has completed: until then, what the phone sends is old history, not news. */
    val caughtUp: Boolean = false,
) {
    /** Messages from the child or other parents this app hasn't shown yet. */
    val unread: Int get() = messages.count { !it.mine && !it.shown }

    /** Whether this thread already holds a message with [cid]. */
    fun has(cid: String): Boolean = messages.any { it.cid == cid }

    /** What [m] (one of this parent's own) shows: sending until the phone stores it, then delivered, then seen. */
    fun status(m: ChatMessage): ChatStatus = when {
        m.seq <= 0 -> ChatStatus.SENDING
        m.seq <= read -> ChatStatus.SEEN
        else -> ChatStatus.DELIVERED
    }

    /** The messages in [incoming] that are new to this thread and not this parent's own: the ones worth a notification. */
    fun newFromOthers(incoming: List<ChatMessage>): List<ChatMessage> = incoming.filter { m -> !m.mine && !has(m.cid) }
        .distinctBy { it.cid }

    /**
     * This thread with [incoming] merged in by cid (live messages, `chatin` marks, or a page of history), in order,
     * with [synced] moved over any seqs that now follow it without a gap. Capped at [MAX_MESSAGES].
     */
    fun merge(incoming: List<ChatMessage>): ChatThread {
        if (incoming.isEmpty()) return this
        val byCid = LinkedHashMap<String, ChatMessage>()
        messages.forEach { byCid[it.cid] = it }
        incoming.forEach { m -> byCid[m.cid] = byCid[m.cid]?.let { combine(it, m) } ?: m }
        val merged = ordered(byCid.values.toList())
        return copy(messages = capped(merged), synced = contiguous(synced, merged))
    }

    /**
     * A page of the phone's history ([chatlog]) merged in. [after] is the seq the sync asked after, [ownRequest] true
     * when this app asked for it (only then does the page say the history is complete from [after] up to its last
     * message, so only then may [synced] jump), and [baseline] true for a first sync from nothing, whose messages
     * are the existing history rather than news, so they don't count as unread. [read] is the child's seen mark.
     */
    fun withLog(page: List<ChatMessage>, after: Long, read: Long, ownRequest: Boolean, baseline: Boolean): ChatThread {
        // Only messages new to this thread are history; one already held (and perhaps notified) keeps its mark.
        val incoming = if (baseline) page.map { if (has(it.cid)) it else it.copy(shown = true) } else page
        val covered = if (ownRequest && after <= synced) maxOf(synced, page.maxOfOrNull { it.seq } ?: after) else synced
        return copy(synced = covered, caughtUp = caughtUp || ownRequest).merge(incoming).seenUpTo(read)
    }

    /** The child has seen the thread up to [seq]; the mark never moves back. */
    fun seenUpTo(seq: Long): ChatThread = if (seq > read) copy(read = seq) else this

    /** The phone stored this parent's (or another parent's) message [cid] as [seq]; false in the pair when [cid] isn't held here. */
    fun delivered(cid: String, seq: Long): Pair<ChatThread, Boolean> {
        val m = messages.firstOrNull { it.cid == cid } ?: return this to false
        return merge(listOf(m.copy(seq = seq))) to true
    }

    /** Every message marked as shown, after the chat was on screen. */
    fun allShown(): ChatThread = if (messages.all { it.shown }) this else copy(messages = messages.map { if (it.shown) it else it.copy(shown = true) })

    /**
     * This parent's messages still missing from the phone's history after a sync sent at [syncSentAt] finished:
     * the ones last sent before that sync. Anything sent after it may simply not have reached the phone yet.
     */
    fun toResend(syncSentAt: Long): List<ChatMessage> = messages.filter { it.mine && it.seq <= 0 && it.sentAt <= syncSentAt }

    /** [cids] marked as sent again at [now]. */
    fun resent(cids: Collection<String>, now: Long): ChatThread = copy(messages = messages.map { if (it.cid in cids) it.copy(sentAt = now) else it })

    /** This thread without messages written before [cutoff], as the phone keeps 90 days. */
    fun olderDropped(cutoff: Long): ChatThread = copy(messages = messages.filter { it.at >= cutoff })

    companion object {
        /** The most messages kept per phone, the same as the phone keeps. */
        const val MAX_MESSAGES = 2000

        /** How long messages are kept, the same as the phone keeps them. */
        const val KEEP_MS = 90 * 24 * 3600_000L

        /** The longest message a parent can send, in characters. */
        const val MAX_TEXT = 1000

        /**
         * [text] as it would be sent: trimmed, or null when it is empty or longer than [MAX_TEXT]. Counted in UTF-16
         * characters, never fewer than the phone's count (it counts code points), so the phone never cuts one.
         */
        fun sendable(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }

        /**
         * The thread's order: messages the phone has stored by their seq (never by senders' clocks, which disagree),
         * then those it hasn't yet (this parent's pending ones, another parent's not confirmed) by when they were
         * written.
         */
        fun ordered(list: List<ChatMessage>): List<ChatMessage> = list.sortedWith(
            compareBy<ChatMessage>({ if (it.seq > 0) 0 else 1 }, { if (it.seq > 0) it.seq else it.at }, { it.cid }),
        )

        /**
         * Two copies of one message ([old] held, [new] arriving) as one. The phone's record wins for what it says
         * (the copy with a seq), and this app's own marks (mine, shown, last sent) are kept from either.
         */
        private fun combine(old: ChatMessage, new: ChatMessage): ChatMessage {
            val record = if (new.seq > 0 || old.seq <= 0) new else old
            return record.copy(
                seq = maxOf(old.seq, new.seq),
                mine = old.mine || new.mine,
                shown = old.shown || new.shown,
                sentAt = maxOf(old.sentAt, new.sentAt),
            )
        }

        /** [from] moved over each seq in [list] that follows it without a gap. */
        private fun contiguous(from: Long, list: List<ChatMessage>): Long {
            val held = list.mapTo(HashSet()) { it.seq }
            var n = from
            while (n + 1 in held) n++
            return n
        }

        /**
         * [list] with its oldest messages dropped past [MAX_MESSAGES]. Pending messages come last in the order, so
         * the oldest stored ones go first.
         */
        private fun capped(list: List<ChatMessage>): List<ChatMessage> = if (list.size <= MAX_MESSAGES) list else list.drop(list.size - MAX_MESSAGES)
    }
}
