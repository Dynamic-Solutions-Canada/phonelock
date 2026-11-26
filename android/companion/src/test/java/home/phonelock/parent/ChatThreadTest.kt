package home.phonelock.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The family chat's thread rules: merging by cid, order by seq, the sync mark, resending and unread counts. */
class ChatThreadTest {
    private fun msg(cid: String, seq: Long = 0, at: Long = 0, from: String = "phone", mine: Boolean = false, sentAt: Long = 0) = ChatMessage(cid, seq, from, "hi $cid", at, mine = mine, sentAt = sentAt)

    @Test
    fun storedMessagesGoBySeqAndPendingOnesAfterByTime() {
        val t = ChatThread().merge(listOf(msg("late", at = 50), msg("b", seq = 2, at = 900), msg("early", at = 10), msg("a", seq = 1, at = 999)))
        assertEquals(listOf("a", "b", "early", "late"), t.messages.map { it.cid })
    }

    @Test
    fun aMessageIsHeldOnceByCidAndTakesItsSeqWhenStored() {
        val mine = msg("x", at = 5, from = "Mom", mine = true, sentAt = 100)
        var t = ChatThread().merge(listOf(mine, msg("a", seq = 1)))
        assertEquals(listOf("a", "x"), t.messages.map { it.cid })

        // Another copy of the same cid (a resend's echo, or the phone's history) doesn't add a second message.
        t = t.merge(listOf(mine.copy(sentAt = 200)))
        assertEquals(2, t.messages.size)

        val (stored, known) = t.delivered("x", 2)
        assertTrue(known)
        val x = stored.messages.single { it.cid == "x" }
        assertEquals(2L, x.seq)
        assertTrue(x.mine)
        assertEquals(200L, x.sentAt)
    }

    @Test
    fun aChatinForAnUnknownMessageSaysSo() {
        val (same, known) = ChatThread().delivered("nope", 3)
        assertFalse(known)
        assertTrue(same.messages.isEmpty())
    }

    @Test
    fun syncedMovesOnlyOverSeqsWithNoGapBelow() {
        var t = ChatThread().merge(listOf(msg("a", seq = 1), msg("b", seq = 2)))
        assertEquals(2L, t.synced)

        // Seq 3 and 4 were missed while offline; a live 5 must not make the next sync skip them.
        t = t.merge(listOf(msg("e", seq = 5)))
        assertEquals(2L, t.synced)

        t = t.withLog(listOf(msg("c", seq = 3), msg("d", seq = 4), msg("e", seq = 5)), after = 2, read = 0, ownRequest = true, baseline = false)
        assertEquals(5L, t.synced)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), t.messages.map { it.seq })
    }

    @Test
    fun aPageOfOwnHistoryCoversFromItsAfterEvenWhenOlderSeqsWerePruned() {
        val t = ChatThread().withLog(listOf(msg("x", seq = 500), msg("y", seq = 501)), after = 0, read = 0, ownRequest = true, baseline = true)
        assertEquals(501L, t.synced)
    }

    @Test
    fun anotherParentsHistoryPageIsMergedButDoesNotMoveSynced() {
        val t = ChatThread().merge(listOf(msg("a", seq = 1)))
            .withLog(listOf(msg("j", seq = 10), msg("k", seq = 11)), after = 9, read = 0, ownRequest = false, baseline = false)
        assertEquals(1L, t.synced)
        assertEquals(3, t.messages.size)
    }

    @Test
    fun theSeenMarkOnlyMovesForward() {
        var t = ChatThread().seenUpTo(5)
        t = t.seenUpTo(3)
        assertEquals(5L, t.read)
        t = t.withLog(emptyList(), after = 0, read = 2, ownRequest = true, baseline = false)
        assertEquals(5L, t.read)
    }

    @Test
    fun ownMessagesGoFromSendingToDeliveredToSeen() {
        val t = ChatThread(read = 3)
        assertEquals(ChatStatus.SENDING, t.status(msg("p", mine = true)))
        assertEquals(ChatStatus.SEEN, t.status(msg("s", seq = 3, mine = true)))
        assertEquals(ChatStatus.DELIVERED, t.status(msg("d", seq = 4, mine = true)))
    }

    @Test
    fun onlyPendingMessagesSentBeforeTheSyncAreResent() {
        val t = ChatThread().merge(
            listOf(
                msg("old", from = "Mom", mine = true, sentAt = 100),
                msg("new", from = "Mom", mine = true, sentAt = 300),
                msg("stored", seq = 1, from = "Mom", mine = true, sentAt = 50),
                msg("theirs", from = "Dad", sentAt = 0),
            ),
        )
        assertEquals(listOf("old"), t.toResend(syncSentAt = 200).map { it.cid })

        val again = t.resent(listOf("old"), now = 400)
        assertTrue(again.toResend(syncSentAt = 200).isEmpty())
    }

    @Test
    fun unreadCountsOthersMessagesNotShownAndSurvivesASeqArriving() {
        var t = ChatThread().merge(listOf(msg("a", seq = 1), msg("mine", from = "Mom", mine = true), msg("dad", from = "Dad")))
        assertEquals(2, t.unread)

        t = t.allShown()
        assertEquals(0, t.unread)

        // Dad's message is stored by the phone later and moves into place: still shown.
        t = t.delivered("dad", 2).first
        assertEquals(0, t.unread)
        assertEquals(listOf("a", "dad", "mine"), t.messages.map { it.cid })

        t = t.merge(listOf(msg("b", seq = 3)))
        assertEquals(1, t.unread)
    }

    @Test
    fun aFirstSyncFromNothingIsHistoryNotNews() {
        val t = ChatThread().withLog(listOf(msg("a", seq = 1), msg("b", seq = 2)), after = 0, read = 0, ownRequest = true, baseline = true)
        assertEquals(0, t.unread)
    }

    @Test
    fun aFirstSyncLeavesAMessageAlreadyHeldUnread() {
        // Arrived live (and was notified) before the first sync brought the history around it.
        val t = ChatThread().merge(listOf(msg("live", seq = 3)))
            .withLog(listOf(msg("a", seq = 1), msg("b", seq = 2), msg("live", seq = 3)), after = 0, read = 0, ownRequest = true, baseline = true)
        assertEquals(1, t.unread)
        assertEquals(3L, t.synced)
    }

    @Test
    fun newFromOthersLeavesOutHeldAndOwnMessages() {
        val t = ChatThread().merge(listOf(msg("a", seq = 1)))
        val incoming = listOf(msg("a", seq = 1), msg("b", seq = 2), msg("c", from = "Mom", mine = true))
        assertEquals(listOf("b"), t.newFromOthers(incoming).map { it.cid })
    }

    @Test
    fun theThreadKeepsTheNewestMessagesUpToTheCap() {
        val many = (1..ChatThread.MAX_MESSAGES + 5).map { msg("m$it", seq = it.toLong()) }
        val t = ChatThread().merge(many)
        assertEquals(ChatThread.MAX_MESSAGES, t.messages.size)
        assertEquals(6L, t.messages.first().seq)
        assertEquals(ChatThread.MAX_MESSAGES + 5L, t.synced)
    }

    @Test
    fun oldMessagesAreDropped() {
        val t = ChatThread().merge(listOf(msg("old", seq = 1, at = 10), msg("new", seq = 2, at = 100))).olderDropped(50)
        assertEquals(listOf("new"), t.messages.map { it.cid })
    }

    @Test
    fun sendableTextIsTrimmedAndLimited() {
        assertEquals("hello", ChatThread.sendable("  hello \n"))
        assertNull(ChatThread.sendable("   "))
        assertNull(ChatThread.sendable("x".repeat(ChatThread.MAX_TEXT + 1)))
        assertEquals(ChatThread.MAX_TEXT, ChatThread.sendable("x".repeat(ChatThread.MAX_TEXT))!!.length)
    }

    @Test
    fun aCompletedSyncOfAnEmptyThreadStillCountsAsCaughtUp() {
        // The first sync finds nothing; the child's first message later arrives only in a chatlog, and is news.
        val empty = ChatThread().withLog(emptyList(), after = 0, read = 0, ownRequest = true, baseline = true)
        assertEquals(0L, empty.synced)
        assertTrue(empty.caughtUp)
        val later = empty.withLog(listOf(msg("a", seq = 1)), after = 0, read = 0, ownRequest = false, baseline = !empty.caughtUp)
        assertEquals(1, later.unread)
    }

    @Test
    fun anotherParentsPageDoesntCountAsThisAppCatchingUp() {
        val t = ChatThread().withLog(listOf(msg("a", seq = 1)), after = 0, read = 0, ownRequest = false, baseline = true)
        assertFalse(t.caughtUp)
    }

    @Test
    fun aDeliveredMessageTakesThePhonesTime() {
        val t = ChatThread().merge(listOf(msg("x", at = 1_000, mine = true)))
        val (stored, _) = t.delivered("x", 1, at = 5_000)
        assertEquals(5_000L, stored.messages.single().at)
        // An older phone that sends no time keeps the one held.
        assertEquals(1_000L, t.delivered("x", 1).first.messages.single().at)
    }
}
